package be.nabu.libs.http.server;

import java.net.InetSocketAddress;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.libs.nio.api.ExceptionFormatter;
import be.nabu.libs.nio.api.SecurityContext;
import be.nabu.libs.nio.api.SourceContext;
import be.nabu.libs.nio.impl.EventDrivenMessageProcessor;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class HTTPProcessor extends EventDrivenMessageProcessor<HTTPRequest, HTTPResponse> {
	
	private boolean injectRemoteInformation;

	public HTTPProcessor(EventDispatcher dispatcher, ExceptionFormatter<HTTPRequest, HTTPResponse> exceptionFormatter, boolean injectRemoteInformation) {
		super(HTTPRequest.class, HTTPResponse.class, dispatcher, exceptionFormatter);
		this.injectRemoteInformation = injectRemoteInformation;
	}

	private Logger logger = LoggerFactory.getLogger(getClass());
	
	public HTTPResponse process(SecurityContext securityContext, SourceContext sourceContext, final HTTPRequest request) {
		if (request.getVersion() >= 1.1) {
			if (MimeUtils.getHeader("Host", request.getContent().getHeaders()) == null) {
				throw new HTTPException(400, "Missing host header");
			}
		}
		if (request.getContent() != null) {
			// remove any existing security header
			request.getContent().removeHeader(ServerHeader.REQUEST_SECURITY.name());
			// add a new one
			request.getContent().setHeader(new SimpleSecurityHeader(securityContext.getSSLContext(), securityContext.getPeerCertificates()));
			if (injectRemoteInformation) {
				HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_HOST, ((InetSocketAddress) sourceContext.getSocket().getRemoteSocketAddress()).getHostName());
				HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_PORT, new Integer(sourceContext.getSocket().getPort()).toString());
			}
		}
		HTTPResponse response = super.process(securityContext, sourceContext, request);

		// if no response, generate a 404
		if (response == null) {
			URI requestURI;
			try {
				requestURI = HTTPUtils.getURI(request, false);
			}
			catch (FormatException e) {
				throw new RuntimeException(e);
			}
			logger.warn("Requested path not found: " + requestURI);
			response = new DefaultHTTPResponse(404, HTTPCodes.getMessage(404), new PlainMimeEmptyPart(null, new MimeHeader("Content-Length", "0")));
		}
		// make sure any requested connection closing is also enforced in the server
		if (!HTTPUtils.keepAlive(request) && response.getContent() != null) {
			response.getContent().setHeader(new MimeHeader("Connection", "close"));
		}
		return response;
	}

}
