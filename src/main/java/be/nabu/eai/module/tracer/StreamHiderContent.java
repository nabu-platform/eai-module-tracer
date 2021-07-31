package be.nabu.eai.module.tracer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import be.nabu.libs.types.MarshalRuleFactory;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.api.MarshalRuleProvider.MarshalRule;
import be.nabu.libs.types.resultset.ResultSetWithType;

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
		else if (object instanceof ResultSetWithType) {
			return null;
		}
		// we check the marshalling rules for this object
		else if (object != null) {
			MarshalRule marshalRule = MarshalRuleFactory.getInstance().getMarshalRule(object.getClass());
			if (marshalRule == null || marshalRule == MarshalRule.ALWAYS) {
				return object;
			}
		}
		return null;
	}

}
