package be.nabu.eai.module.tracer;

import javax.xml.bind.annotation.XmlTransient;

import be.nabu.eai.module.tracer.api.TracingBroadcaster;

public class TraceProfile {
	private String serviceId;
	// a query that is run against the serviceruntime to determine whether you don't want this service instance
	private String blacklist;
	// a query that is run against the serviceruntime to determine whether you do want this service instance
	private String whitelist;
	// whether you want to recurse into deeper services
	private boolean recursive;
	// do we want a hello when a new trace is started? this allows for example a database listener to create a new master record that it can use to link all subsequent records on
	private boolean hello;
	private TracingBroadcaster broadcaster;
	
	public String getServiceId() {
		return serviceId;
	}
	public void setServiceId(String serviceId) {
		this.serviceId = serviceId;
	}
	public String getBlacklist() {
		return blacklist;
	}
	public void setBlacklist(String blacklist) {
		this.blacklist = blacklist;
	}
	public String getWhitelist() {
		return whitelist;
	}
	public void setWhitelist(String whitelist) {
		this.whitelist = whitelist;
	}
	public boolean isRecursive() {
		return recursive;
	}
	public void setRecursive(boolean recursive) {
		this.recursive = recursive;
	}
	
	@XmlTransient
	public TracingBroadcaster getBroadcaster() {
		return broadcaster;
	}
	public void setBroadcaster(TracingBroadcaster broadcaster) {
		this.broadcaster = broadcaster;
	}
	
	@Override
	public String toString() {
		return serviceId + "[" + broadcaster + "]";
	}
	public boolean isHello() {
		return hello;
	}
	public void setHello(boolean hello) {
		this.hello = hello;
	}
}
