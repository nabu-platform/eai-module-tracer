package nabu.misc.tracer;

import java.util.List;

import javax.jws.WebParam;
import javax.jws.WebResult;
import javax.jws.WebService;

import be.nabu.eai.module.tracer.TraceProcessor;
import be.nabu.eai.module.tracer.TraceProfile;

@WebService
public class Services {
	public void startTrace(@WebParam(name = "serviceToTrace") String serviceToTrace, @WebParam(name = "serviceToCall") String serviceToCall) {
		TraceProcessor.startTraceProcessor(serviceToTrace, serviceToCall);
	}
	public void stopTrace(@WebParam(name = "serviceToTrace") String serviceToTrace, @WebParam(name = "serviceToCall") String serviceToCall) {
		TraceProcessor.stopTraceProcessor(serviceToTrace, serviceToCall);
	}
	@WebResult(name = "traceProfiles")
	public List<TraceProfile> listTraces(@WebParam(name = "serviceToCall") String serviceToCall) {
		return TraceProcessor.getWatchedServices(serviceToCall);
	}
}
