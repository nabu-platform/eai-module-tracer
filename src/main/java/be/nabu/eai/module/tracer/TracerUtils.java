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
