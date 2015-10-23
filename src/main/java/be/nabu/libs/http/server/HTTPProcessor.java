package be.nabu.libs.http.server;

import java.net.URI;
import java.security.cert.Certificate;
import java.util.Date;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.ResponseHandler;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPExceptionFormatter;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeUtils;

public class HTTPProcessor {
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	private HTTPExceptionFormatter exceptionFormatter;
	private EventDispatcher eventDispatcher;

	public HTTPProcessor(EventDispatcher eventDispatcher) {
		this.eventDispatcher = eventDispatcher;
	}
	
	public HTTPResponse process(SSLContext context, Certificate[] peerCertificates, final HTTPRequest request, String remoteHost, int remotePort) throws FormatException {
		if (request.getContent() != null) {
			if (MimeUtils.getHeader(ServerHeader.REQUEST_SECURITY.getName(), request.getContent().getHeaders()) != null) {
				throw new HTTPException(400, "Header not allowed: " + ServerHeader.REQUEST_SECURITY.getName());
			}
			request.getContent().setHeader(new SimpleSecurityHeader(context, peerCertificates));
			HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_HOST, remoteHost);
			HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_PORT, new Integer(remotePort).toString());
		}
		Date timestamp = new Date();
		// fire the request to get a response
		HTTPResponse response = eventDispatcher.fire(request, this, new ResponseHandler<HTTPRequest, HTTPResponse>() {
			@Override
			public HTTPResponse handle(HTTPRequest request, Object response, boolean isLast) {
				if (response instanceof HTTPException) {
					return getExceptionFormatter().format(request, (HTTPException) response);
				}
				else if (response instanceof Throwable) {
					return getExceptionFormatter().format(request, new HTTPException(500, (Throwable) response));
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
			logger.warn("Requested path not found: " + requestURI);
			response = new DefaultHTTPResponse(404, HTTPCodes.getMessage(404), null);
		}
		logger.debug("> request:" + request.hashCode() + " (" + (new Date().getTime() - timestamp.getTime()) + "ms) " + response.getCode() + ": " + response.getMessage());
		// fire the response to allow others to alter it
		HTTPResponse alteredResponse = eventDispatcher.fire(response, this, new ResponseHandler<HTTPResponse, HTTPResponse>() {
			@Override
			public HTTPResponse handle(HTTPResponse original, Object proposed, boolean isLast) {
				if (proposed instanceof HTTPException) {
					return getExceptionFormatter().format(request, (HTTPException) proposed);
				}
				else if (proposed instanceof Throwable) {
					return getExceptionFormatter().format(request, new HTTPException(500, (Throwable) proposed));
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

	public HTTPExceptionFormatter getExceptionFormatter() {
		if (exceptionFormatter == null) {
			exceptionFormatter = new DefaultHTTPExceptionFormatter();
		}
		return exceptionFormatter;
	}

	public void setExceptionFormatter(HTTPExceptionFormatter exceptionFormatter) {
		this.exceptionFormatter = exceptionFormatter;
	}
}
