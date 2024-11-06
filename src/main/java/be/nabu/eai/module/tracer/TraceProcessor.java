/*
* Copyright (C) 2015 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.eai.module.tracer;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.eai.module.tracer.api.TracingBroadcaster;
import be.nabu.eai.repository.EAIResourceRepository;
import be.nabu.eai.repository.api.Repository;
import be.nabu.eai.repository.util.SystemPrincipal;
import be.nabu.libs.events.api.EventTarget;
import be.nabu.libs.metrics.api.MetricInstance;
import be.nabu.libs.services.ServiceRuntime;
import be.nabu.libs.services.api.DefinedService;
import be.nabu.libs.services.api.ExecutionContext;
import be.nabu.libs.services.api.SecurityContext;
import be.nabu.libs.services.api.ServiceContext;
import be.nabu.libs.services.api.TransactionContext;
import be.nabu.libs.types.api.ComplexContent;

public class TraceProcessor implements TracingBroadcaster {

	private static final int POLL_INTERVAL = 5000;
	private static final int INTERRUPT_INTERVAL = 1000;
	
	// a list of all the active processors
	// the key is the combination of the service-to-watch and the service-to-call
	// e.g. service.to.watch:service.to.call
	private static Map<String, TraceProcessor> processors = new HashMap<String, TraceProcessor>();
	
	// we want to buffer up to 512kb by default
	private long bufferSize = 512 * 1024;
	
	private String traceService;
	private DefinedService service;
	private Thread thread;
	private List<TraceMessage> messages = new ArrayList<TraceMessage>();
	private long currentSize = 0;
	private static Logger logger = LoggerFactory.getLogger(TraceProcessor.class);
	private boolean stopped;
	private boolean skipOnError = true;		// if the handler can't deal, it is generally better to toss the events (they are best effort anyway) rather than keep buffering them, as they are presumed to be high volume
	// we want to keep track of the last time we interrupted
	// if we are getting a lot of pushes and stay over 500, perhaps we can't process at all (e.g. websocket down)
	// if we keep interrupting the thread, we are wasting a lot of CPU cycles 
	private Date lastInterrupted;
	private Repository repository;
	private TraceProfile profile;
	private Runnable unsubscribe;
	// in best effort mode we start throwing away messages if we can't process them fast enough rather than buffering them
	private boolean bestEffort = false;
	private Date lastWarned = null;
	
	public TraceProcessor(Repository repository, String traceService) {
		this.repository = repository;
		this.traceService = traceService;
	}
	
	public static void startTraceProcessor(String serviceToWatch, String serviceToCall) {
		String key = serviceToWatch + ":" + serviceToCall;
		TraceProcessor processor = null;
		if (!processors.containsKey(key)) {
			processor = new TraceProcessor(EAIResourceRepository.getInstance(), serviceToCall);
			processor.profile = new TraceProfile();
			processor.profile.setHello(true);
			processor.profile.setServiceId(serviceToWatch);
			processor.profile.setRecursive(true);
			processor.profile.setBroadcaster(processor);
			TracerListener tracerListener = TracerListener.getInstance();
			if (tracerListener == null) {
				logger.error("Can not register trace mode for service " + serviceToWatch + " because tracer listener is not available");
			}
			else {
				processors.put(key, processor);
				processor.start();
				processor.unsubscribe = tracerListener.registerProfile(processor.profile);
			}
		}
		// TODO: we may want to reconfigure the trace profile for this processor
	}
	
	// list all the services that are currently being watched by a particular handler
	public static List<TraceProfile> getWatchedServices(String serviceToCall) {
		List<TraceProfile> traceProfiles = new ArrayList<TraceProfile>();
		for (Map.Entry<String, TraceProcessor> entry : processors.entrySet()) {
			if (entry.getKey().endsWith(":" + serviceToCall)) {
				traceProfiles.add(entry.getValue().profile);
			}
		}
		return traceProfiles;
	}
	
	public static void stopTraceProcessor(String serviceToWatch, String serviceToCall) {
		String key = serviceToWatch + ":" + serviceToCall;
		TraceProcessor traceProcessor = processors.get(key);
		if (traceProcessor != null) {
			traceProcessor.unsubscribe.run();
			processors.remove(key);
			traceProcessor.stop();
		}
	}
	
	public void stop() {
		stopped = true;
		synchronized(messages) {
			messages.clear();
		}
	}

	public void start() {
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (!stopped) {
					try {
						if (messages.size() > 0) {
							ArrayList<Object> messagesToProcess;
							DefinedService service = getService();
							long originalSize = 0;
							synchronized(messages) {
								messagesToProcess = new ArrayList<Object>(messages);
								originalSize = currentSize;
							}
							if (service != null) {
								ComplexContent newInstance = service.getServiceInterface().getInputDefinition().newInstance();
								newInstance.set("messages", messagesToProcess);
								ExecutionContext ec = repository.newExecutionContext(SystemPrincipal.ROOT);
								ServiceRuntime serviceRuntime = new ServiceRuntime(service, new ExecutionContext() {
									@Override
									public MetricInstance getMetricInstance(String id) {
										return ec.getMetricInstance(id);
									}
									@Override
									public ServiceContext getServiceContext() {
										return ec.getServiceContext();
									}
									@Override
									public TransactionContext getTransactionContext() {
										return ec.getTransactionContext();
									}
									@Override
									public SecurityContext getSecurityContext() {
										return ec.getSecurityContext();
									}
									@Override
									public boolean isDebug() {
										return false;
									}
									@Override
									public EventTarget getEventTarget() {
										return null;
									}
								});
								boolean handled = true;
								try {
									serviceRuntime.run(newInstance);
								}
								catch (Exception e) {
									if (!skipOnError) {
										throw e;
									}
								}
								finally {
									if (handled) {
										synchronized(messages) {
											messages.removeAll(messagesToProcess);
											currentSize -= originalSize;
										}
									}
								}
							}
							else {
								logger.warn("Could not find trace handling service: " + traceService);
								break;
							}
						}
					}
					catch (Exception e) {
						logger.error("Could not process trace messages", e);
					}
					try {
						Thread.sleep(POLL_INTERVAL);
					}
					catch (InterruptedException e) {
						// ignore
					}
				}
				stopped = true;
			}
		});
		thread.setContextClassLoader(repository.getClassLoader());
		thread.setDaemon(true);
		thread.setName("trace-processor:" + traceService);
		thread.start();
		this.thread = thread;
	}

	private DefinedService getService() {
		if (service == null) {
			synchronized(this) {
				if (service == null) {
					service = (DefinedService) repository.resolve(traceService);
					// if the service can't be resolved, stop
					if (service == null) {
						stop();
					}
				}
			}
		}
		return service;
	}
	
	@Override
	public void broadcast(Stack<String> serviceStack, TraceMessage message) {
		if (message != null && !stopped) {
			synchronized(messages) {
				messages.add(message);
				currentSize += message.getEstimatedSize();
			}
			Date now = new Date();
			// if it is getting too much, interrupt the thread for processing (if applicable)
			if ((currentSize > bufferSize || messages.size() > 500) && thread != null) {
				if (lastInterrupted == null || lastInterrupted.getTime() < now.getTime() - INTERRUPT_INTERVAL) {
					lastInterrupted = now;
					thread.interrupt();
				}
			}
			if (bestEffort) {
				// we have too many and apparently we can't dump 'em
				if (messages.size() > 5000 || currentSize > bufferSize * 3) {
					Object remove;
					synchronized(messages) {	
						remove = messages.remove(0);
					}
					logger.info("Not enough capacity for '" + traceService + "' to store trace message: " + remove);
				}
			}
			else if (currentSize > bufferSize * 10 && (lastWarned == null || now.getTime() > lastWarned.getTime() + 1000*30)) {
				logger.warn("Trace processor is buffering large amounts of data: " + messages.size() + " messages with an estimated size of " + currentSize);
				lastWarned = now;
			}
		}
	}

	public String getServiceId() {
		return traceService;
	}

	public boolean isStopped() {
		return stopped;
	}
	
}
