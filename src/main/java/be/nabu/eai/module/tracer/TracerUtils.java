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

import java.io.InputStream;
import java.io.OutputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;

public class TracerUtils {
	
	private static JAXBContext context; static {
		try {
			context = JAXBContext.newInstance(TraceMessage.class);
		}
		catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}
	
	public static void marshal(TraceMessage message, OutputStream output) {
		try {
			context.createMarshaller().marshal(message, output);
		}
		catch (JAXBException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static TraceMessage unmarshal(InputStream input) {
		try {
			return (TraceMessage) context.createUnmarshaller().unmarshal(input);
		}
		catch (JAXBException e) {
			throw new RuntimeException(e);
		}
	}
}
