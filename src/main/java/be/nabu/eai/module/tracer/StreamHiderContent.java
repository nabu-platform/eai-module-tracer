package be.nabu.eai.module.tracer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;

public class StreamHiderContent implements ComplexContent {

	private ComplexContent content;

	public StreamHiderContent(ComplexContent content) {
		this.content = content;
	}
	
	@Override
	public ComplexType getType() {
		return content.getType();
	}

	@Override
	public void set(String path, Object value) {
		content.set(path, value);
	}

	@Override
	public Object get(String path) {
		Object object = content.get(path);
		if (object instanceof InputStream) {
			return new ByteArrayInputStream(("Instance: " + object.getClass().getName()).getBytes());
		}
		return object;
	}

}
