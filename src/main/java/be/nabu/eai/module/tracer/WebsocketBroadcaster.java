package be.nabu.eai.module.tracer;

import java.io.ByteArrayOutputStream;
import java.util.Stack;

import be.nabu.eai.module.tracer.api.TracingBroadcaster;
import be.nabu.libs.http.api.server.HTTPServer;
import be.nabu.libs.http.server.websockets.WebSocketUtils;
import be.nabu.libs.http.server.websockets.api.OpCode;
import be.nabu.libs.http.server.websockets.api.WebSocketMessage;
import be.nabu.libs.http.server.websockets.api.WebSocketRequest;
import be.nabu.libs.nio.api.NIOServer;
import be.nabu.libs.nio.api.StandardizedMessagePipeline;
import be.nabu.libs.services.ServiceRuntime;
import be.nabu.libs.services.api.DefinedService;
import be.nabu.libs.services.api.Service;
import be.nabu.utils.io.IOUtils;

public class WebsocketBroadcaster implements TracingBroadcaster {

	private HTTPServer httpServer;
	private String originalServiceId;
	
	public WebsocketBroadcaster(HTTPServer httpServer, String originalServiceId) {
		this.httpServer = httpServer;
		this.originalServiceId = originalServiceId;
	}

	@Override
	public void broadcast(Stack<String> serviceStack, TraceMessage message) {
		// we are only interested if our original service is somewhere in the parent or the current message is about that
		if (serviceStack.contains(originalServiceId) || originalServiceId.equals(message.getServiceId())) {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			TracerUtils.marshal(message, output);
			byte[] byteArray = output.toByteArray();
			broadcast(WebSocketUtils.newMessage(OpCode.TEXT, true, byteArray.length, IOUtils.wrap(byteArray, true)));
		}
	}
	
	public void broadcast(WebSocketMessage message) {
		ServiceRuntime runtime = ServiceRuntime.getRuntime();
		// we are currently not running a service, only check the original service id this was created for
		if (runtime == null) {
			for (StandardizedMessagePipeline<WebSocketRequest, WebSocketMessage> pipeline : WebSocketUtils.getWebsocketPipelines((NIOServer) httpServer, "/trace/" + originalServiceId)) {
				pipeline.getResponseQueue().add(message);
			}
		}
		else {
			// make sure we send the message to anyone listening to any of the services in the callstack
			while (runtime != null) {
				Service service = TracerListener.getService(runtime);
				if (service instanceof DefinedService) {
					for (StandardizedMessagePipeline<WebSocketRequest, WebSocketMessage> pipeline : WebSocketUtils.getWebsocketPipelines((NIOServer) httpServer, "/trace/" + ((DefinedService) service).getId().split(":")[0])) {
						pipeline.getResponseQueue().add(message);
					}
				}
				runtime = runtime.getParent();
			}
		}
	}

	@Override
	public int hashCode() {
		return originalServiceId.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof WebsocketBroadcaster
			&& ((WebsocketBroadcaster) obj).httpServer.equals(httpServer)
			&& ((WebsocketBroadcaster) obj).originalServiceId.equals(originalServiceId);
	}

}
