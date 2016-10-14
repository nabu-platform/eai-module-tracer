package be.nabu.eai.module.tracer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Stack;
import java.util.UUID;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.annotation.XmlRootElement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.eai.module.tracer.TracerListener.TraceMessage.TraceType;
import be.nabu.eai.repository.EAIResourceRepository;
import be.nabu.eai.server.Server;
import be.nabu.eai.server.api.ServerListener;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.events.api.EventSubscription;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPServer;
import be.nabu.libs.http.server.HTTPServerUtils;
import be.nabu.libs.http.server.nio.MemoryMessageDataProvider;
import be.nabu.libs.http.server.websockets.WebSocketUtils;
import be.nabu.libs.http.server.websockets.WebSocketHandshakeHandler;
import be.nabu.libs.http.server.websockets.api.OpCode;
import be.nabu.libs.http.server.websockets.api.WebSocketMessage;
import be.nabu.libs.http.server.websockets.api.WebSocketRequest;
import be.nabu.libs.http.server.websockets.impl.WebSocketRequestParserFactory;
import be.nabu.libs.nio.api.NIOServer;
import be.nabu.libs.nio.api.StandardizedMessagePipeline;
import be.nabu.libs.nio.api.events.ConnectionEvent;
import be.nabu.libs.services.ServiceRuntime;
import be.nabu.libs.services.api.DefinedService;
import be.nabu.libs.services.api.Service;
import be.nabu.libs.services.api.ServiceRuntimeTracker;
import be.nabu.libs.services.api.ServiceRuntimeTrackerProvider;
import be.nabu.libs.services.api.ServiceWrapper;
import be.nabu.libs.services.vm.api.Step;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.binding.xml.XMLBinding;
import be.nabu.utils.io.IOUtils;

public class TracerListener implements ServerListener {

	private List<String> servicesToTrace = new ArrayList<String>();
	private HTTPServer httpServer;
	private boolean includePipeline = true;
	
	private static JAXBContext context; static {
		try {
			context = JAXBContext.newInstance(TraceMessage.class);
		}
		catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	@Override
	public void listen(Server server, HTTPServer httpServer) {
		this.httpServer = httpServer;
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
						String service = parserFactory.getPath().substring("/trace/".length());
						if (!service.isEmpty()) {
							// we have a new websocket connection to the path, add the service if not in the list yet
							if (ConnectionEvent.ConnectionState.UPGRADED.equals(event.getState())) {
								if (!servicesToTrace.contains(service)) {
									servicesToTrace.add(service);
								}
							}
							// we have a close connection, check if someone is still listening to the path
							else if (ConnectionEvent.ConnectionState.CLOSED.equals(event.getState())) {
								List<StandardizedMessagePipeline<WebSocketRequest, WebSocketMessage>> pipelinesOnPath = WebSocketUtils.getWebsocketPipelines(event.getServer(), parserFactory.getPath());
								// remove self
								pipelinesOnPath.remove(event.getPipeline());
								// if noone else is left, remove the service
								if (pipelinesOnPath.isEmpty()) {
									servicesToTrace.remove(service);
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
			ServiceRuntimeTracker tracker = (ServiceRuntimeTracker) runtime.getContext().get(getClass().getName());
			// make sure we only have one tracer per runtime context
			if (tracker == null) {
				// check if service is in stack somewhere
				while (runtime != null) {
					Service service = getService(runtime);
					// the ":" is to support container artifacts
					if (service instanceof DefinedService && servicesToTrace.contains(((DefinedService) service).getId().split(":")[0])) {
						tracker = new TracingTracker();
						runtime.getContext().put(getClass().getName(), tracker);
						break;
					}
					runtime = runtime.getParent();
				}
			}
			return tracker;
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
	
	public class TracingTracker implements ServiceRuntimeTracker {

		private String id = UUID.randomUUID().toString().replace("-", "");
		private Stack<String> serviceStack = new Stack<String>();
		private Stack<String> stepStack = new Stack<String>();
		private Stack<Date> timestamps = new Stack<Date>();
		
		public void broadcast(TraceMessage message) {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			message.serialize(output);
			byte[] byteArray = output.toByteArray();
			broadcast(WebSocketUtils.newMessage(OpCode.TEXT, true, byteArray.length, IOUtils.wrap(byteArray, true)));
		}
		
		public void broadcast(WebSocketMessage message) {
			ServiceRuntime runtime = ServiceRuntime.getRuntime();
			// make sure we send the message to anyone listening to any of the services in the callstack
			while (runtime != null) {
				Service service = getService(runtime);
				if (service instanceof DefinedService) {
					for (StandardizedMessagePipeline<WebSocketRequest, WebSocketMessage> pipeline : WebSocketUtils.getWebsocketPipelines((NIOServer) httpServer, "/trace/" + ((DefinedService) service).getId())) {
						pipeline.getResponseQueue().add(message);
					}
				}
				runtime = runtime.getParent();
			}
		}
		
		public TraceMessage newMessage(TraceType type, Exception exception) {
			TraceMessage newMessage = newMessage(type);
			StringWriter writer = new StringWriter();
			PrintWriter printer = new PrintWriter(writer);
			exception.printStackTrace(printer);
			printer.flush();
			newMessage.setException(writer.toString());
			return newMessage;
		}
		
		public TraceMessage newMessage(TraceType type) {
			TraceMessage message = new TraceMessage();
			message.setTraceId(id);
			message.setType(type);
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
			if (object != null) {
				try {
					JAXBContext context = JAXBContext.newInstance(object.getClass());
					ByteArrayOutputStream output = new ByteArrayOutputStream();
					context.createMarshaller().marshal(object, output);
					TraceMessage message = newMessage(TraceType.REPORT);
					message.setReport(new String(output.toByteArray()));
					message.setReportType(object.getClass().getName());
					broadcast(message);
				}
				catch (JAXBException e) {
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
				serviceStack.push(((DefinedService) service).getId());
				TraceMessage message = newMessage(TraceType.SERVICE);
				message.setStarted(timestamp);
				if (includePipeline) {
					ComplexContent input = ServiceRuntime.getRuntime().getInput();
					message.setInput(marshal(service.getServiceInterface().getInputDefinition(), input));
				}
				broadcast(message);
			}
		}

		private String marshal(ComplexType type, ComplexContent content) {
			if (content != null) {
				content = new StreamHiderContent(content);
				XMLBinding binding = new XMLBinding(type, Charset.forName("UTF-8"));
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				try {
					binding.marshal(output, content);
					return new String(output.toByteArray(), "UTF-8");
				}
				catch (IOException e) {
					logger.error("Could not log pipeline", e);
				}
			}
			return null;
		}

		@Override
		public void stop(Service service) {
			service = resolveService(service);
			if (service instanceof DefinedService) {
				TraceMessage message = newMessage(TraceType.SERVICE);
				serviceStack.pop();
				message.setStarted(timestamps.pop());
				message.setStopped(new Date());
				if (includePipeline) {
					ComplexContent output = ServiceRuntime.getRuntime().getOutput();
					message.setOutput(marshal(service.getServiceInterface().getOutputDefinition(), output));
				}
				broadcast(message);
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
				broadcast(message);
			}
		}

		@Override
		public void before(Object step) {
			if (step instanceof Step) {
				Date timestamp = new Date();
				stepStack.push(((Step) step).getId());
				timestamps.push(timestamp);
				TraceMessage message = newMessage(TraceType.STEP);
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
				message.setStopped(new Date());
				broadcast(message);
			}			
		}
		
	}
	
	@XmlRootElement(name = "trace")
	public static class TraceMessage {
		
		public enum TraceType {
			REPORT,
			SERVICE,
			STEP
		}
		
		private String traceId, serviceId, stepId, exception, report, reportType;
		private Date started, stopped;
		private TraceType type;
		private String input, output;

		public String getTraceId() {
			return traceId;
		}
		public void setTraceId(String traceId) {
			this.traceId = traceId;
		}

		public String getServiceId() {
			return serviceId;
		}
		public void setServiceId(String serviceId) {
			this.serviceId = serviceId;
		}

		public String getStepId() {
			return stepId;
		}
		public void setStepId(String stepId) {
			this.stepId = stepId;
		}

		public String getException() {
			return exception;
		}
		public void setException(String exception) {
			this.exception = exception;
		}
		
		public String getReport() {
			return report;
		}
		public void setReport(String report) {
			this.report = report;
		}
		
		public String getReportType() {
			return reportType;
		}
		public void setReportType(String reportType) {
			this.reportType = reportType;
		}
		
		public TraceType getType() {
			return type;
		}
		public void setType(TraceType type) {
			this.type = type;
		}
		
		public Date getStarted() {
			return started;
		}
		public void setStarted(Date started) {
			this.started = started;
		}
		
		public Date getStopped() {
			return stopped;
		}
		public void setStopped(Date stopped) {
			this.stopped = stopped;
		}
		
		public String getInput() {
			return input;
		}
		public void setInput(String input) {
			this.input = input;
		}
		public String getOutput() {
			return output;
		}
		public void setOutput(String output) {
			this.output = output;
		}
		
		public void serialize(OutputStream output) {
			try {
				context.createMarshaller().marshal(this, output);
			}
			catch (JAXBException e) {
				throw new RuntimeException(e);
			}
		}
		
		public static TraceMessage unmarshal(InputStream input) {
			try {
				return (TraceMessage) context.createUnmarshaller().unmarshal(input);
			}
			catch (JAXBException e) {
				throw new RuntimeException(e);
			}
		}
	}
}
