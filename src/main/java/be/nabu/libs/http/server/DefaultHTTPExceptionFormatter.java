package be.nabu.libs.http.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.nio.api.ExceptionFormatter;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeContentPart;

public class DefaultHTTPExceptionFormatter implements ExceptionFormatter<HTTPRequest, HTTPResponse> {

	private Logger logger = LoggerFactory.getLogger(getClass());
	private Map<Integer, String> errorTemplates = new HashMap<Integer, String>();
	private String defaultErrorTemplate = "<html><head><title>$code: $message</title></head><body><h1>$code: $message</h1><p>An error occurred: </p><pre>$stacktrace</pre></body></html>";
	
	@Override
	public HTTPResponse format(HTTPRequest request, Exception originalException) {
		HTTPException exception = originalException instanceof HTTPException ? (HTTPException) originalException : new HTTPException(500, originalException);
		logger.error("HTTP Exception " + exception.getCode(), exception);
		StringWriter stringWriter = new StringWriter();
		PrintWriter printer = new PrintWriter(stringWriter);
		exception.printStackTrace(printer);
		printer.flush();
		String errorMessage = errorTemplates.containsKey(exception.getCode()) ? errorTemplates.get(exception.getCode()) : defaultErrorTemplate;
		errorMessage = errorMessage.replace("$code", "" + exception.getCode())
			.replace("$message", exception.getMessage() == null ? HTTPCodes.getMessage(exception.getCode()) : exception.getMessage())
			.replace("$stacktrace", stringWriter.toString());
		byte [] bytes = errorMessage.getBytes(Charset.forName("UTF-8"));
		return new DefaultHTTPResponse(exception.getCode(), HTTPCodes.getMessage(exception.getCode()), new PlainMimeContentPart(null, IOUtils.wrap(bytes, true), 
			new MimeHeader("Connection", "close"),
			new MimeHeader("Content-Length", "" + bytes.length),
			new MimeHeader("Content-Type", "text/html; charset=UTF-8")
		));
	}

	public String getErrorTemplate(int code) {
		return errorTemplates.get(code);
	}
	
	public void setErrorTemplate(int code, String template) {
		errorTemplates.put(code, template);
	}
	
	public void setDefaultErrorTemplate(String template) {
		this.defaultErrorTemplate = template;
	}
		
	public String getDefaultErrorTemplate() {
		return defaultErrorTemplate;
	}
}
