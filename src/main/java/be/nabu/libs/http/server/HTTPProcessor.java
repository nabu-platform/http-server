package be.nabu.libs.http.server;

import java.net.InetSocketAddress;

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
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class HTTPProcessor extends EventDrivenMessageProcessor<HTTPRequest, HTTPResponse> {
	
	private boolean isProxied;

	public HTTPProcessor(EventDispatcher dispatcher, ExceptionFormatter<HTTPRequest, HTTPResponse> exceptionFormatter, boolean isProxied) {
		super(HTTPRequest.class, HTTPResponse.class, dispatcher, exceptionFormatter, true);
		this.isProxied = isProxied;
	}

	private Logger logger = LoggerFactory.getLogger(getClass());
	
	@Override
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
			if (!isProxied) {
				for (ServerHeader header : ServerHeader.values()) {
					if (!header.isUserValueAllowed()) {
						request.getContent().removeHeader(header.getName());
					}
				}
				InetSocketAddress remoteSocketAddress = ((InetSocketAddress) sourceContext.getSocketAddress());
				if (remoteSocketAddress != null) {
					HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_IS_LOCAL, Boolean.toString(remoteSocketAddress.getAddress().isLoopbackAddress() || remoteSocketAddress.getAddress().isLinkLocalAddress()));
					HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_ADDRESS, remoteSocketAddress.getAddress().getHostAddress());
					HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_HOST, remoteSocketAddress.getHostName());
					HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_PORT, new Integer(remoteSocketAddress.getPort()).toString());
				}
				else {
					request.getContent().removeHeader(ServerHeader.REMOTE_IS_LOCAL.name());
					request.getContent().removeHeader(ServerHeader.REMOTE_ADDRESS.name());
					request.getContent().removeHeader(ServerHeader.REMOTE_HOST.name());
				}
				HTTPUtils.setHeader(request.getContent(), ServerHeader.LOCAL_PORT, new Integer(sourceContext.getLocalPort()).toString());
			}
		}
		HTTPResponse response = super.process(securityContext, sourceContext, request);
		
		// make sure any requested connection closing is also enforced in the server
		if (!HTTPUtils.keepAlive(request) && response.getContent() != null) {
			response.getContent().setHeader(new MimeHeader("Connection", "close"));
		}
		return response;
	}

	@Override
	protected HTTPResponse getDefaultResponse(HTTPRequest request) {
		// if no response, generate a 404
		logger.warn("Could not find requested target: " + request.getTarget());
		return new DefaultHTTPResponse(request, 404, HTTPCodes.getMessage(404), new PlainMimeEmptyPart(null, new MimeHeader("Content-Length", "0")));
	}
}
