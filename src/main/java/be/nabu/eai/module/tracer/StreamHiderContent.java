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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Iterator;

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
		return process(object);
	}

	private Object process(Object object) {
		if (object instanceof ComplexContent) {
			return new StreamHiderContent((ComplexContent) object);
		}
		else if (object instanceof Iterable) {
			// @2024-05-28: this could SHOULD be ok but has not been tested yet
			return new Iterable<Object>() {
				@Override
				public Iterator<Object> iterator() {
					Iterator<?> iterator = ((Iterable<?>) object).iterator();
					return new Iterator<Object>() {
						@Override
						public boolean hasNext() {
							return iterator.hasNext();
						}
						@Override
						public Object next() {
							Object next = iterator.next();
							return process(next);
						}
					};
				}
			};
		}
		else if (object instanceof InputStream) {
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
