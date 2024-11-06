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
