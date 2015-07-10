package be.nabu.libs.http.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.ResponseHandler;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeContentPart;

public class HTTPProcessor {
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	private Map<Integer, String> errorTemplates = new HashMap<Integer, String>();
	private String defaultErrorTemplate = "<html><head><title>$code: $message</title></head><body><h1>$code: $message</h1><p>An error occurred: </p><pre>$stacktrace</pre></body></html>";

	private EventDispatcher eventDispatcher;

	public HTTPProcessor(EventDispatcher eventDispatcher) {
		this.eventDispatcher = eventDispatcher;
	}
	
	public HTTPResponse process(SSLContext context, HTTPRequest request, String remoteHost, int remotePort) throws FormatException {
		if (request.getContent() != null) {
			if (MimeUtils.getHeader(ServerHeader.REQUEST_SECURITY.getName(), request.getContent().getHeaders()) != null) {
				throw new HTTPException(400, "Header not allowed: " + ServerHeader.REQUEST_SECURITY.getName());
			}
			request.getContent().setHeader(new SimpleSecurityHeader(context));
			HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_HOST, remoteHost);
			HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_PORT, new Integer(remotePort).toString());
		}
		Date timestamp = new Date();
		// fire the request to get a response
		HTTPResponse response = eventDispatcher.fire(request, this, new ResponseHandler<HTTPRequest, HTTPResponse>() {
			@Override
			public HTTPResponse handle(HTTPRequest request, Object response, boolean isLast) {
				if (response instanceof HTTPException) {
					return createError((HTTPException) response);
				}
				else if (response instanceof Throwable) {
					return createError(new HTTPException(500, (Throwable) response));
				}
				else if (response instanceof HTTPResponse) {
					return (HTTPResponse) response;
				}
				return null;
			}
		}, new ResponseHandler<HTTPRequest, HTTPRequest>()  {
			@Override
			public HTTPRequest handle(HTTPRequest request, Object rewritten, boolean isLast) {
				if (rewritten instanceof HTTPRequest) {
					return (HTTPRequest) rewritten;
				}
				return null;
			}
		});
		if (response == null) {
			URI requestURI = HTTPUtils.getURI(request, false);
			response = new DefaultHTTPResponse(404, requestURI.getPath(), null);
		}
		logger.debug("> request:" + request.hashCode() + " (" + (new Date().getTime() - timestamp.getTime()) + "ms) " + response.getCode() + ": " + response.getMessage());
		// fire the response to allow others to alter it
		HTTPResponse alteredResponse = eventDispatcher.fire(response, this, new ResponseHandler<HTTPResponse, HTTPResponse>() {
			@Override
			public HTTPResponse handle(HTTPResponse original, Object proposed, boolean isLast) {
				if (proposed instanceof HTTPException) {
					return createError((HTTPException) proposed);
				}
				else if (proposed instanceof Throwable) {
					return createError(new HTTPException(500, (Throwable) proposed));
				}
				else if (proposed instanceof HTTPResponse) {
					return (HTTPResponse) proposed;
				}
				else {
					return null;
				}
			}
		});
		if (alteredResponse != null) {
			logger.debug("> request:" + request.hashCode() + " (" + (new Date().getTime() - timestamp.getTime()) + "ms) ALTERED: " + response.getCode() + ": " + response.getMessage());
			response = alteredResponse;
		}
		return response;
	}
	
	public HTTPResponse createError(HTTPException e) {
		logger.error("Request execution triggered exception", e);
		StringWriter stringWriter = new StringWriter();
		PrintWriter printer = new PrintWriter(stringWriter);
		e.printStackTrace(printer);
		printer.flush();
		String errorMessage = errorTemplates.containsKey(e.getCode()) ? errorTemplates.get(e.getCode()) : defaultErrorTemplate;
		errorMessage = errorMessage.replace("$code", "" + e.getCode())
				.replace("$message", e.getMessage() == null ? getDefaultMessage(e.getCode()) : e.getMessage())
				.replace("$stacktrace", stringWriter.toString());
		byte [] bytes = errorMessage.getBytes(Charset.forName("UTF-8"));
		return new DefaultHTTPResponse(e.getCode(), e.getMessage(), new PlainMimeContentPart(null, IOUtils.wrap(bytes, true), 
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
	
	public static String getDefaultMessage(int code) {
		switch(code) {
			case 404: return "Not Found";
			default: return "No Message";
		}
	}
}
