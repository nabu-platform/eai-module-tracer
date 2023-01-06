package be.nabu.eai.module.tracer.api;

import java.util.List;

import javax.jws.WebParam;
import javax.jws.WebResult;

import be.nabu.eai.module.tracer.TraceMessage;

public interface TraceListener {
	@WebResult(name = "handled")
	public Boolean handle(@WebParam(name = "messages") List<TraceMessage> messages);
}
