package be.nabu.eai.module.tracer.api;

import java.util.Stack;

import be.nabu.eai.module.tracer.TraceMessage;

public interface TracingBroadcaster {
	// The service stack let's you decide whether you are interested or not
	public void broadcast(Stack<String> serviceStack, TraceMessage message);
}