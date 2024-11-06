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

import java.util.Date;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

@XmlRootElement(name = "trace")
public class TraceMessage {

	// the root service that you are tracing and that leads to all these trace messages
	private String rootServiceId;
	
	private String traceId, serviceId, stepId, exception, report, reportType, reportTarget;
	private String stepType, comment;
	private Date started, stopped;
	private TraceType type;
	private String input, output;
	private String correlationId, authenticationId, alias, realm, impersonatorId, impersonator, authenticator;
	private long messageIndex;
	
	private String from, to, condition, feature;
	private Boolean fixed, masked;
	private String code;

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
	
	// the estimate is probably on the low end
	@XmlTransient
	public long getEstimatedSize() {
		// let's assume a base size of for basic stuff like object wrappers, started date, end date, ids etc
		long size = 100;
		// we are assuming UTF-8 in a latin-like alphabet so most of it will be 1 byte in size
		// we could actually convert with a charset to get an exact estimate
		if (input != null) {
			size += input.length();
		}
		if (output != null) {
			size += output.length();
		}
		if (exception != null) {
			size += exception.length();
		}
		if (report != null) {
			size += report.length();
		}
		return size;
	}
	
	public String getRootServiceId() {
		return rootServiceId;
	}
	public void setRootServiceId(String rootServiceId) {
		this.rootServiceId = rootServiceId;
	}

	public TraceMessage cloneFor(TraceProfile profile) {
		TraceMessage message = new TraceMessage();
		message.setTraceId(traceId);
		message.setInput(input);
		message.setOutput(output);
		message.setStarted(started);
		message.setStopped(stopped);
		message.setStepId(stepId);
		message.setException(exception);
		message.setReport(report);
		message.setReportType(reportType);
		message.setReportTarget(reportTarget);
		message.setServiceId(serviceId);
		message.setType(type);
		message.setMessageIndex(messageIndex);
		message.setCorrelationId(correlationId);
		message.setAlias(alias);
		message.setRealm(realm);
		message.setAuthenticationId(authenticationId);
		message.setImpersonator(impersonator);
		message.setImpersonatorId(impersonatorId);
		message.setAuthenticator(authenticator);
		message.setStepType(stepType);
		message.setFrom(from);
		message.setTo(to);
		message.setCondition(condition);
		message.setFeature(feature);
		message.setCode(code);
		message.setFixed(fixed);
		message.setMasked(masked);
		message.setComment(comment);
		return message.configureFor(profile);
	}
	public TraceMessage configureFor(TraceProfile profile) {
		setRootServiceId(profile.getServiceId());
		return this;
	}
	public String getStepType() {
		return stepType;
	}
	public void setStepType(String stepType) {
		this.stepType = stepType;
	}
	public String getComment() {
		return comment;
	}
	public void setComment(String comment) {
		this.comment = comment;
	}
	public String getReportTarget() {
		return reportTarget;
	}
	public void setReportTarget(String reportTarget) {
		this.reportTarget = reportTarget;
	}
	public long getMessageIndex() {
		return messageIndex;
	}
	public void setMessageIndex(long messageIndex) {
		this.messageIndex = messageIndex;
	}
	public String getCorrelationId() {
		return correlationId;
	}
	public void setCorrelationId(String correlationId) {
		this.correlationId = correlationId;
	}
	public String getAuthenticationId() {
		return authenticationId;
	}
	public void setAuthenticationId(String authenticationId) {
		this.authenticationId = authenticationId;
	}
	public String getAlias() {
		return alias;
	}
	public void setAlias(String alias) {
		this.alias = alias;
	}
	public String getRealm() {
		return realm;
	}
	public void setRealm(String realm) {
		this.realm = realm;
	}
	public String getImpersonatorId() {
		return impersonatorId;
	}
	public void setImpersonatorId(String impersonatorId) {
		this.impersonatorId = impersonatorId;
	}
	public String getImpersonator() {
		return impersonator;
	}
	public void setImpersonator(String impersonator) {
		this.impersonator = impersonator;
	}
	public String getAuthenticator() {
		return authenticator;
	}
	public void setAuthenticator(String authenticator) {
		this.authenticator = authenticator;
	}
	public String getFrom() {
		return from;
	}
	public void setFrom(String from) {
		this.from = from;
	}
	public String getTo() {
		return to;
	}
	public void setTo(String to) {
		this.to = to;
	}
	public String getCondition() {
		return condition;
	}
	public void setCondition(String condition) {
		this.condition = condition;
	}
	public String getFeature() {
		return feature;
	}
	public void setFeature(String feature) {
		this.feature = feature;
	}
	public String getCode() {
		return code;
	}
	public void setCode(String code) {
		this.code = code;
	}
	public Boolean getFixed() {
		return fixed;
	}
	public void setFixed(Boolean fixed) {
		this.fixed = fixed;
	}
	public Boolean getMasked() {
		return masked;
	}
	public void setMasked(Boolean masked) {
		this.masked = masked;
	}
	
}