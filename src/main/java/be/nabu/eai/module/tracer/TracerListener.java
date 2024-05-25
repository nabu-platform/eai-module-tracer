package be.nabu.eai.module.tracer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.UUID;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.eai.repository.EAIResourceRepository;
import be.nabu.eai.server.Server;
import be.nabu.eai.server.api.ServerListener;
import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.converter.ConverterFactory;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.events.api.EventSubscription;
import be.nabu.libs.http.HTTPInterceptorManager;
import be.nabu.libs.http.api.HTTPEntity;
import be.nabu.libs.http.api.HTTPInterceptor;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPServer;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.server.HTTPServerUtils;
import be.nabu.libs.http.server.nio.MemoryMessageDataProvider;
import be.nabu.libs.http.server.websockets.WebSocketHandshakeHandler;
import be.nabu.libs.http.server.websockets.WebSocketUtils;
import be.nabu.libs.http.server.websockets.api.WebSocketMessage;
import be.nabu.libs.http.server.websockets.api.WebSocketRequest;
import be.nabu.libs.http.server.websockets.impl.WebSocketRequestParserFactory;
import be.nabu.libs.nio.PipelineUtils;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.api.StandardizedMessagePipeline;
import be.nabu.libs.nio.api.events.ConnectionEvent;
import be.nabu.libs.nio.impl.MessagePipelineImpl;
import be.nabu.libs.services.ServiceRuntime;
import be.nabu.libs.services.TransactionCloseable;
import be.nabu.libs.services.api.DefinedService;
import be.nabu.libs.services.api.Service;
import be.nabu.libs.services.api.ServiceException;
import be.nabu.libs.services.api.ServiceRuntimeTracker;
import be.nabu.libs.services.api.ServiceRuntimeTrackerProvider;
import be.nabu.libs.services.api.ServiceWrapper;
import be.nabu.libs.services.vm.api.Step;
import be.nabu.libs.services.vm.step.Link;
import be.nabu.libs.services.vm.step.Throw;
import be.nabu.libs.types.ComplexContentWrapperFactory;
import be.nabu.libs.types.SimpleTypeWrapperFactory;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.DefinedSimpleType;
import be.nabu.libs.types.api.DefinedType;
import be.nabu.libs.types.binding.json.JSONBinding;
import be.nabu.libs.types.binding.xml.XMLBinding;

public class TracerListener implements ServerListener {

	private Map<String, Runnable> websocketSubscriptions = new HashMap<String, Runnable>();
	
	private HTTPServer httpServer;
	private boolean includePipeline = true;
	
	// all the active tracing profiles, grouped by the service they are aimed at
	private Map<String, List<TraceProfile>> tracingProfiles = new HashMap<String, List<TraceProfile>>();
	
	private static TracerListener instance;
	
	public static TracerListener getInstance() {
		return instance;
	}
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	public Runnable registerProfile(TraceProfile profile) {
		List<TraceProfile> list = tracingProfiles.get(profile.getServiceId());
		if (list == null) {
			list = new ArrayList<TraceProfile>();
			synchronized(tracingProfiles) {
				tracingProfiles.put(profile.getServiceId(), list);
			}
		}
		synchronized(list) {
			list.add(profile);
		}
		return new Runnable() {
			@Override
			public void run() {
				List<TraceProfile> list = tracingProfiles.get(profile.getServiceId());
				if (list != null) {
					synchronized(list) {
						list.remove(profile);
					}
					// clear the list from the map
					if (list.isEmpty()) {
						synchronized(tracingProfiles) {
							tracingProfiles.remove(profile.getServiceId());
						}
					}
				}
			}
		};
	}
	
	@Override
	public void listen(Server server, HTTPServer httpServer) {
		this.httpServer = httpServer;
		// there should only be one active instance
		instance = this;
		// set up a web socket listener where we can send the data for a service call
		WebSocketHandshakeHandler websocketHandshakeHandler = new WebSocketHandshakeHandler(httpServer.getDispatcher(), new MemoryMessageDataProvider(1024*1024*10), false);
		websocketHandshakeHandler.setRequireUpgrade(true);
		EventSubscription<HTTPRequest, HTTPResponse> httpSubscription = httpServer.getDispatcher().subscribe(HTTPRequest.class, websocketHandshakeHandler);
		httpSubscription.filter(HTTPServerUtils.limitToPath("/trace"));
		
		// listen to the connection events to start/stop tracing a service
		httpServer.getDispatcher().subscribe(ConnectionEvent.class, new EventHandler<ConnectionEvent, Void>() {
			@Override
			public Void handle(ConnectionEvent event) {
				WebSocketRequestParserFactory parserFactory = WebSocketUtils.getParserFactory(event.getPipeline());
				if (parserFactory != null) {
					if (parserFactory.getPath().startsWith("/trace/")) {
						boolean summaryOnly = false;
						String service = parserFactory.getPath().substring("/trace/".length());
						if (service.contains("/")) {
							String[] split = service.split("/");
							service = split[0];
							if (split[1].equals("summary")) {
								summaryOnly = true;
							}
						}
						if (!service.isEmpty()) {
							// we have a new websocket connection to the path, add the service if not in the list yet
							if (ConnectionEvent.ConnectionState.UPGRADED.equals(event.getState())) {
								if (!websocketSubscriptions.containsKey(service)) {
									TraceProfile profile = new TraceProfile();
									profile.setBroadcaster(new WebsocketBroadcaster(httpServer, service, summaryOnly));
									profile.setRecursive(true);
									profile.setServiceId(service);
									profile.setSummaryOnly(summaryOnly);
									websocketSubscriptions.put(service, registerProfile(profile));
									logger.info("Added websocket tracer for: " + service);
								}
								// we set an unlimited lifetime value for the websocket connection so it can remain open
								// however, we leave the idle timeout, the client is sending heartbeats to keep it alive, if the client stops sending them, the connection can be cleaned up correctly
								Pipeline pipeline = PipelineUtils.getPipeline();
								if (pipeline instanceof MessagePipelineImpl) {
									((MessagePipelineImpl<?,?>) pipeline).setMaxLifeTime(0l);
									// the hearts are every minute, let's kill the connection after 5 idle minutes, regardless of server settings
									((MessagePipelineImpl<?,?>) pipeline).setMaxIdleTime(5l*60*1000);
								}
							}
							// we have a close connection, check if someone is still listening to the path
							else if (ConnectionEvent.ConnectionState.CLOSED.equals(event.getState())) {
								List<StandardizedMessagePipeline<WebSocketRequest, WebSocketMessage>> pipelinesOnPath = WebSocketUtils.getWebsocketPipelines(event.getServer(), parserFactory.getPath());
								// remove self
								pipelinesOnPath.remove(event.getPipeline());
								// if noone else is left, remove the service
								if (pipelinesOnPath.isEmpty()) {
									Runnable removedSubscription = websocketSubscriptions.remove(service);
									if (removedSubscription != null) {
										removedSubscription.run();
										logger.info("Removing websocket subscription for trace on " + service);
									}
								}
							}
						}
					}
				}
				return null;
			}
		});
		
		// set up a new service track provider
		((EAIResourceRepository) server.getRepository()).getDynamicRuntimeTrackers().add(new TracingTrackerProvider());
	}

	public class TracingTrackerProvider implements ServiceRuntimeTrackerProvider {
		@Override
		public ServiceRuntimeTracker getTracker(ServiceRuntime runtime) {
			// let's see if we have a tracker already 
			TracingTracker tracker = (TracingTracker) runtime.getContext().get(getClass().getName());
			
			// we need to check if there are any active trace profiles
			List<TraceProfile> activeProfiles = new ArrayList<TraceProfile>();
			
			ServiceRuntime runtimeToScan = runtime;
			while (runtimeToScan != null) {
				// while scanning the runtimes, also check if we have a tracker at that level because (comment taken from another bit of code that was refactored but still applies)
				// @2022-11-09: we had tracker explosion on the new v2 login rest call because it called web application authenticate which reinitializes global context stuff
				// due to this, the tracker could not be found at the correct level and we have trackers at every level
				// in such a case, by walking up the service runtime tree, we should find the active tracker
				// at the moment we believe this is what we want. the reinitializing of the context seems faulty as well
				if (tracker == null) {
					tracker = (TracingTracker) runtimeToScan.getContext().get(getClass().getName());
				}
				Service service = getService(runtimeToScan);
				String originalServiceId = ((DefinedService) service).getId().split(":")[0];
				if (tracingProfiles.containsKey(originalServiceId)) {
					activeProfiles.addAll(tracingProfiles.get(originalServiceId));
				}
				runtimeToScan = runtimeToScan.getParent();
			}
			// if we don't have a tracker, but we do have active profiles, start one
			if (tracker == null && !activeProfiles.isEmpty()) {
				logger.info("Creating new tracer for profiles: " + activeProfiles);
				tracker = new TracingTracker();
				tracker.setCorrelationId(runtime.getCorrelationId());
				tracker.setToken(runtime.getExecutionContext().getSecurityContext().getToken());
				HTTPInterceptorImpl interceptor = new HTTPInterceptorImpl((TracingTracker) tracker);
				runtime.getContext().put(getClass().getName(), tracker);
				// make sure we unregister the interceptor when we are done
				runtime.getExecutionContext().getTransactionContext().add(null, new TransactionCloseable(new AutoCloseable() {
					@Override
					public void close() throws Exception {
						HTTPInterceptorManager.unregister();
						// make sure we also destroy the tracker
						// if we reuse the serviceruntime global context within this thread, we want a new tracer to start up
						// e.g. the rest services use the same global context for cache checks and actual runtime
						ServiceRuntime.getRuntime().getContext().put(getClass().getName(), null);
					}
				}));
				HTTPInterceptorManager.register(interceptor);
			}
			// if we do have a tracker but no active profiles, stop the tracker
			// in most cases an active tracker does not impact much because the vast majority of services are shortlived (as are their attached trackers)
			// but sometimes we do track daemons for a period or large batch services and we want to make sure the trackers are destroyed if we are no longer interested
			else if (tracker != null && activeProfiles.isEmpty()) {
				logger.info("Disabling active tracer because no active profiles remain");
				HTTPInterceptorManager.unregister();
				runtime.getContext().put(getClass().getName(), null);
				tracker = null;
			}
			// if we have a tracker at this point, let's update its active profile list
			if (tracker != null) {
				// update correlation id in case it changes (unlikely)
				tracker.setCorrelationId(runtime.getCorrelationId());
				tracker.setActiveProfiles(activeProfiles);
			}
			
			// make sure we only have one tracer per runtime context
//			if (tracker == null) {
//				// check if service is in stack somewhere
//				while (runtime != null && tracker == null) {
//					tracker = (ServiceRuntimeTracker) runtime.getContext().get(getClass().getName());
//					
//					Service service = getService(runtime);
//					String originalServiceId = ((DefinedService) service).getId().split(":")[0];
//					// the ":" is to support container artifacts
//					if (service instanceof DefinedService && tracingProfiles.containsKey(originalServiceId) && tracker == null) {
//						logger.info("Starting new tracer on " + originalServiceId);
//						tracker = new TracingTracker();
//						
////						((TracingTracker) tracker).addBroadcaster(new WebsocketBroadcaster(httpServer, originalServiceId));
//						
//						HTTPInterceptorImpl interceptor = new HTTPInterceptorImpl((TracingTracker) tracker);
////						System.out.println("Registering http interceptor " + interceptor.hashCode() + " for service: " + ((DefinedService) service).getId());
//						runtime.getContext().put(getClass().getName(), tracker);
//						
//						// make sure we unregister the interceptor when we are done
//						runtime.getExecutionContext().getTransactionContext().add(null, new TransactionCloseable(new AutoCloseable() {
//							@Override
//							public void close() throws Exception {
////								System.out.println("Unregistering http interceptor " + interceptor.hashCode());
//								HTTPInterceptorManager.unregister(interceptor);
//								// make sure we also destroy the tracker
//								// if we reuse the serviceruntime global context within this thread, we want a new tracer to start up
//								// e.g. the rest services use the same global context for cache checks and actual runtime
//								ServiceRuntime.getRuntime().getContext().put(getClass().getName(), null);
//							}
//						}));
//						
//						// register it now
//						HTTPInterceptorManager.register(interceptor);
//						break;
//					}
//					runtime = runtime.getParent();
//				}
//			}
			return tracker;
		}
	}
	
	public static class HTTPInterceptorImpl implements HTTPInterceptor {
		private TracingTracker tracker;
		
		public HTTPInterceptorImpl(TracingTracker tracker) {
			this.tracker = tracker;
		}
		
		@Override
		public HTTPEntity intercept(HTTPEntity entity) {
			if (entity.getContent() != null) {
				tracker.report(HTTPUtils.toMessage(entity));
			}
			return null;
		}
	}
	
	public static Service getService(ServiceRuntime runtime) {
		return resolveService(runtime.getService());
	}

	private static Service resolveService(Service service) {
		while (service instanceof ServiceWrapper) {
			service = ((ServiceWrapper) service).getOriginal();
		}
		return service;
	}
	
	public static class TraceSummary {
		private String serviceId;
		private long amount, exceptions, total, maximum, minimum = Long.MAX_VALUE;
		private double average;
		public String getServiceId() {
			return serviceId;
		}
		public void setServiceId(String serviceId) {
			this.serviceId = serviceId;
		}
		public long getAmount() {
			return amount;
		}
		public void setAmount(long amount) {
			this.amount = amount;
		}
		public long getExceptions() {
			return exceptions;
		}
		public void setExceptions(long exceptions) {
			this.exceptions = exceptions;
		}
		public double getAverage() {
			return average;
		}
		public void setAverage(double average) {
			this.average = average;
		}
		public long getTotal() {
			return total;
		}
		public void setTotal(long total) {
			this.total = total;
		}
		public long getMaximum() {
			return maximum;
		}
		public void setMaximum(long maximum) {
			this.maximum = maximum;
		}
		public long getMinimum() {
			return minimum;
		}
		public void setMinimum(long minimum) {
			this.minimum = minimum;
		}
		@Override
		public String toString() {
			return serviceId + " (#" + amount + " * [" + minimum + ", " + maximum + "] = " + total + ")";
		}
	}
	
	public class TracingTracker implements ServiceRuntimeTracker {
		// for sequential listing of messages without having to rely on dates
		private long messageCounter;
		private String id = UUID.randomUUID().toString().replace("-", "");
		private Stack<String> serviceStack = new Stack<String>();
		private Stack<String> stepStack = new Stack<String>();
		private Stack<Date> timestamps = new Stack<Date>();
		private List<TraceProfile> activeProfiles;
		private Charset charset = Charset.forName("UTF-8");
		private String correlationId;
		private Token token;
		private Map<String, TraceSummary> summaries = new HashMap<String, TraceSummary>();
		private String rootService;
		
		// for this particular instance of the tracker, we want to know which trace profiles have already received a hello message
		private List<TraceProfile> alreadyHello = new ArrayList<TraceProfile>();
		
		public void broadcast(TraceMessage message) {
			if (activeProfiles != null && !activeProfiles.isEmpty()) {
				for (TraceProfile profile : activeProfiles) {
					// todo: add decent support for type summary?
					if (message.getType() == TraceType.SUMMARY) {
						message.setType(TraceType.REPORT);
					}
					else if (profile.isSummaryOnly()) {
						continue;
					}
					// this should not be multithreaded because a tracer is limited to a single thread
					if (profile.isHello() && !alreadyHello.contains(profile)) {
						TraceMessage hello = new TraceMessage();
						hello.configureFor(profile);
						hello.setStarted(new Date());
						hello.setTraceId(id);
						hello.setServiceId(profile.getServiceId());
						hello.setType(TraceType.START);
						hello.setCorrelationId(correlationId);
						if (token != null) {
							hello.setAlias(token.getName());
							hello.setRealm(token.getRealm());
							hello.setAuthenticationId(token.getAuthenticationId());
							hello.setImpersonator(token.getImpersonator());
							hello.setImpersonatorId(token.getImpersonatorId());
							hello.setAuthenticator(token.getAuthenticator());
						}
						profile.getBroadcaster().broadcast(serviceStack, hello);
						alreadyHello.add(profile);
					}
					profile.getBroadcaster().broadcast(serviceStack, message.cloneFor(profile));
				}
			}
//			ByteArrayOutputStream output = new ByteArrayOutputStream();
//			TracerUtils.marshal(message, output);
//			byte[] byteArray = output.toByteArray();
//			broadcast(WebSocketUtils.newMessage(OpCode.TEXT, true, byteArray.length, IOUtils.wrap(byteArray, true)));
		}
		
		public void broadcastSummaries() {
			List<TraceSummary> values = new ArrayList<TraceSummary>(summaries.values());
			// we want the "longest" first
			Collections.sort(values, new Comparator<TraceSummary>() {
				@Override
				public int compare(TraceSummary o1, TraceSummary o2) {
					return (int) (o2.getTotal() - o1.getTotal());
				}
			});
			for (TraceSummary summary : values) {
				// we don't care too much about the fast stuff
				if (summary.getTotal() > 50) {
					report(summary, "technical", TraceType.SUMMARY);
				}
			}
		}
		
//		public void broadcast(WebSocketMessage message) {
//			ServiceRuntime runtime = ServiceRuntime.getRuntime();
//			// we are currently not running a service, only check the original service id this was created for
//			if (runtime == null) {
//				for (StandardizedMessagePipeline<WebSocketRequest, WebSocketMessage> pipeline : WebSocketUtils.getWebsocketPipelines((NIOServer) httpServer, "/trace/" + originalServiceId)) {
//					pipeline.getResponseQueue().add(message);
//				}
//			}
//			else {
//				// make sure we send the message to anyone listening to any of the services in the callstack
//				while (runtime != null) {
//					Service service = getService(runtime);
//					if (service instanceof DefinedService) {
//						for (StandardizedMessagePipeline<WebSocketRequest, WebSocketMessage> pipeline : WebSocketUtils.getWebsocketPipelines((NIOServer) httpServer, "/trace/" + ((DefinedService) service).getId().split(":")[0])) {
//							pipeline.getResponseQueue().add(message);
//						}
//					}
//					runtime = runtime.getParent();
//				}
//			}
//		}
		
		public TraceMessage newMessage(TraceType type, Exception exception) {
			TraceMessage newMessage = newMessage(type);
			StringWriter writer = new StringWriter();
			PrintWriter printer = new PrintWriter(writer);
			exception.printStackTrace(printer);
			printer.flush();
			newMessage.setException(writer.toString());
			ServiceException serviceException = getServiceException(exception);
			if (serviceException != null) {
				newMessage.setCode(serviceException.getCode());
				newMessage.setReportType("exception-description");
				newMessage.setReport(serviceException.getDescription());
				if (newMessage.getReport() == null) {
					newMessage.setReport(serviceException.getMessage());
				}
			}
			return newMessage;
		}
		
		public TraceMessage newMessage(TraceType type) {
			TraceMessage message = new TraceMessage();
			message.setMessageIndex(messageCounter++);
			message.setTraceId(id);
			message.setType(type);
			message.setCorrelationId(correlationId);
			if (token != null) {
				message.setAlias(token.getName());
				message.setRealm(token.getRealm());
				message.setAuthenticationId(token.getAuthenticationId());
				message.setImpersonator(token.getImpersonator());
				message.setImpersonatorId(token.getImpersonatorId());
				message.setAuthenticator(token.getAuthenticator());
			}
			if (!serviceStack.isEmpty()) {
				message.setServiceId(serviceStack.peek());
			}
			if (!stepStack.isEmpty()) {
				message.setStepId(stepStack.peek());
			}
			return message;
		}
		
		@Override
		public void report(Object object) {
			report(object, "technical");
		}

		private void report(Object object, String audience) {
			report(object, audience, TraceType.REPORT);
		}
		
		private void report(Object object, String audience, TraceType traceType) {
			if (object != null) {
				ComplexContent content = wrapAsComplex(object);
				// disadvantage is that we don't get xsi:type, but advantage (important at this point) is that we can easily unmarshal it in the frontend
				// before it was using JAXB anyway instead of xml binding, so we didn't have xsi type in the past either
				// the reports are usually dedicated java objects and the descriptions structures et al
				try {
					JSONBinding binding = new JSONBinding(content.getType(), charset);
					ByteArrayOutputStream output = new ByteArrayOutputStream();
					binding.marshal(output, content);
					TraceMessage message = newMessage(traceType);
					message.setReport(new String(output.toByteArray()));
					message.setReportType(content.getType() instanceof DefinedType ? ((DefinedType) content.getType()).getId() : "java.lang.Object");
					message.setReportTarget(audience);
					message.setServiceId(rootService);
					broadcast(message);
				}
				catch (IOException e) {
					logger.error("Could not report object", e);
				}
			}
		}

		@Override
		public void start(Service service) {
			service = resolveService(service);
			if (service instanceof DefinedService) {
				Date timestamp = new Date();
				timestamps.push(timestamp);
				if (serviceStack.isEmpty()) {
					rootService = ((DefinedService) service).getId();
				}
				serviceStack.push(((DefinedService) service).getId());
				TraceMessage message = newMessage(TraceType.SERVICE);
				message.setStarted(timestamp);
				if (includePipeline && service.getServiceInterface() != null) {
					ComplexContent input = ServiceRuntime.getRuntime().getInput();
					message.setInput(marshal(service.getServiceInterface().getInputDefinition(), input));
				}
				broadcast(message);
			}
		}

		private String marshal(ComplexType type, ComplexContent content) {
			if (content != null) {
				content = new StreamHiderContent(content);
//				XMLBinding binding = new XMLBinding(type, charset);
				JSONBinding binding = new JSONBinding(type, charset);
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				try {
					binding.marshal(output, content);
					return new String(output.toByteArray(), "UTF-8");
				}
				catch (Exception e) {
					logger.error("Could not log pipeline", e);
				}
			}
			return null;
		}

		private void addSummary(String serviceId, long runtime, boolean exception) {
			TraceSummary summary = summaries.get(serviceId);
			// TODO
			if (!summaries.containsKey(serviceId)) {
				summary = new TraceSummary();
				summary.setServiceId(serviceId);
				summaries.put(serviceId, summary);
			}
			double newAverage = ((summary.getAverage() * summary.getAmount()) + runtime) / (summary.getAmount() + 1);
			summary.setTotal(summary.getTotal() + runtime);
			summary.setAverage(newAverage);
			summary.setAmount(summary.getAmount() + 1);
			if (runtime < summary.getMinimum()) {
				summary.setMinimum(runtime);
			}
			if (runtime > summary.getMaximum()) {
				summary.setMaximum(runtime);
			}
			if (exception) {
				summary.setExceptions(summary.getExceptions() + 1);
			}
		}
		
		@Override
		public void stop(Service service) {
			service = resolveService(service);
			if (service instanceof DefinedService) {
				TraceMessage message = newMessage(TraceType.SERVICE);
				serviceStack.pop();
				message.setStarted(timestamps.pop());
				message.setStopped(new Date());
				if (includePipeline && service.getServiceInterface() != null) {
					ComplexContent output = ServiceRuntime.getRuntime().getOutput();
					message.setOutput(marshal(service.getServiceInterface().getOutputDefinition(), output));
				}
				broadcast(message);
				addSummary(((DefinedService) service).getId(), message.getStopped().getTime() - message.getStarted().getTime(), false);
				if (serviceStack.isEmpty()) {
					broadcastSummaries();
				}
			}
		}

		@Override
		public void error(Service service, Exception exception) {
			service = resolveService(service);
			if (service instanceof DefinedService) {
				TraceMessage message = newMessage(TraceType.SERVICE, exception);
				serviceStack.pop();
				message.setStarted(timestamps.pop());
				message.setStopped(new Date());
				// a service message never has a comment at this point, so we use it to summarize the exception
				message.setComment(exception.getMessage());
				broadcast(message);
				addSummary(((DefinedService) service).getId(), message.getStopped().getTime() - message.getStarted().getTime(), false);
				if (serviceStack.isEmpty()) {
					broadcastSummaries();
				}
			}
		}

		@Override
		public void before(Object step) {
			if (step instanceof Step) {
				Date timestamp = new Date();
				stepStack.push(((Step) step).getId());
				timestamps.push(timestamp);
				TraceMessage message = newMessage(TraceType.STEP);
				message.setStepType(step.getClass().getName());
				message.setCondition(((Step) step).getLabel());
				message.setFeature(((Step) step).getFeatures());
				message.setComment(((Step) step).getComment());
				if (step instanceof Link) {
					message.setFrom(((Link) step).getFrom());
					message.setTo(((Link) step).getTo());
					message.setFixed(((Link) step).isFixedValue());
					message.setMasked(((Link) step).isMask());
				}
				else if (step instanceof Throw) {
					message.setCode(((Throw) step).getCode());
				}
				message.setStarted(timestamp);
				broadcast(message);
			}
		}

		@Override
		public void after(Object step) {
			if (step instanceof Step) {
				TraceMessage message = newMessage(TraceType.STEP);
				stepStack.pop();
				message.setStarted(timestamps.pop());
				message.setStepType(step.getClass().getName());
				message.setComment(((Step) step).getComment());
				message.setStopped(new Date());
				broadcast(message);
			}
		}

		@Override
		public void error(Object step, Exception exception) {
			if (step instanceof Step) {
				TraceMessage message = newMessage(TraceType.STEP, exception);
				stepStack.pop();
				message.setStarted(timestamps.pop());
				message.setStepType(step.getClass().getName());
				message.setComment(((Step) step).getComment());
				message.setStopped(new Date());
				broadcast(message);
			}			
		}
		
		private ServiceException getServiceException(Exception exception) {
			ServiceException last = null;
			while (exception != null && exception.getCause() instanceof Exception) {
				if (exception instanceof ServiceException) {
					last = (ServiceException) exception;
				}
				exception = (Exception) exception.getCause();
			}
			return last;
		}

		public List<TraceProfile> getActiveProfiles() {
			return activeProfiles;
		}

		public void setActiveProfiles(List<TraceProfile> activeProfiles) {
			this.activeProfiles = activeProfiles;
		}

		@Override
		public void describe(Object object) {
			report(object, "business");
		}

		@SuppressWarnings("unchecked")
		private ComplexContent wrapAsComplex(Object object) {
			if (object != null) {
				DefinedSimpleType<? extends Object> simpleType = SimpleTypeWrapperFactory.getInstance().getWrapper().wrap(object.getClass());
				if (simpleType != null) {
					TraceReportString reportString = new TraceReportString();
					reportString.setReport(object instanceof String ? (String) object : ConverterFactory.getInstance().getConverter().convert(object, String.class));
					object = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(reportString);
				}
				else if (!(object instanceof ComplexContent)) {
					Object wrapped = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(object);
					// we can't wrap it, so it's not a valid complex content
					if (wrapped == null) {
						TraceReportString reportString = new TraceReportString();
						reportString.setReport(object.toString());
						object = ComplexContentWrapperFactory.getInstance().getWrapper().wrap(reportString);
					}
					else {
						object = wrapped;
					}
				}
			}
			return (ComplexContent) object;
		}

		public String getCorrelationId() {
			return correlationId;
		}

		public void setCorrelationId(String correlationId) {
			this.correlationId = correlationId;
		}

		public Token getToken() {
			return token;
		}

		public void setToken(Token token) {
			this.token = token;
		}
	}
}
