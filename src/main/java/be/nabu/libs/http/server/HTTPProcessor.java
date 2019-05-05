package be.nabu.libs.http.server;

import java.net.InetSocketAddress;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.EventTarget;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.HeaderMappingProvider;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.libs.nio.PipelineUtils;
import be.nabu.libs.nio.api.ExceptionFormatter;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.api.SecurityContext;
import be.nabu.libs.nio.api.SourceContext;
import be.nabu.libs.nio.impl.EventDrivenMessageProcessor;
import be.nabu.utils.cep.impl.CEPUtils;
import be.nabu.utils.cep.impl.HTTPComplexEventImpl;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.api.ModifiablePart;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class HTTPProcessor extends EventDrivenMessageProcessor<HTTPRequest, HTTPResponse> {
	
	private boolean isProxied;
	private HeaderMappingProvider mapping;
	private EventTarget eventTarget;

	public HTTPProcessor(EventDispatcher dispatcher, ExceptionFormatter<HTTPRequest, HTTPResponse> exceptionFormatter, boolean isProxied, HeaderMappingProvider mapping, EventTarget eventTarget) {
		super(HTTPRequest.class, HTTPResponse.class, dispatcher, exceptionFormatter, true);
		this.isProxied = isProxied;
		this.mapping = mapping;
		this.eventTarget = eventTarget;
	}

	private Logger logger = LoggerFactory.getLogger(getClass());
	
	private void cleanRequestHeaders(ModifiablePart part, HeaderMappingProvider mapping) {
		Map<String, Header[]> remap = new HashMap<String, Header[]>();
		Map<String, String> mappings = mapping == null ? null : mapping.getMappings();
		if (mappings == null) {
			mappings = new HashMap<String, String>();
		}
		// take the headers we want to remap
		for (Map.Entry<String, String> entry : mappings.entrySet()) {
			if (entry.getKey() != null && entry.getValue() != null && !entry.getKey().trim().isEmpty() && !entry.getValue().trim().isEmpty()) {
				Header[] headers = MimeUtils.getHeaders(entry.getValue(), part.getHeaders());
				if (headers != null && headers.length > 0) {
					remap.put(entry.getKey(), headers);
				}
			}
		}
		
		// remove all the headers that are not allowed
		for (ServerHeader header : ServerHeader.values()) {
			if (!header.isUserValueAllowed()) {
				part.removeHeader(header.getName());
			}
		}
		// reinjected the explicitly mapped headers
		for (Map.Entry<String, Header[]> entry : remap.entrySet()) {
			for (Header original : entry.getValue()) {
				part.setHeader(new MimeHeader(entry.getKey(), original.getValue(), original.getComments()));
			}
		}
	}
	
	@Override
	public HTTPResponse process(SecurityContext securityContext, SourceContext sourceContext, final HTTPRequest request) {
		HTTPComplexEventImpl event = null;
		if (eventTarget != null) {
			event = new HTTPComplexEventImpl();
			Pipeline pipeline = PipelineUtils.getPipeline();
			CEPUtils.enrich(event, getClass(), "http-request", pipeline.getSourceContext().getSocketAddress(), null, null);
			event.setStarted(new Date());
			event.setMethod(request.getMethod());
			try {
				event.setRequestUri(HTTPUtils.getURI(request, securityContext.getSSLContext() != null));
			}
			catch (FormatException e) {
				// too bad
			}
			event.setApplicationProtocol(securityContext.getSSLContext() != null ? "HTTPS" : "HTTP");
		}
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
			cleanRequestHeaders(request.getContent(), mapping);
			// if not proxied, inject the remote data
			if (!isProxied) {
				InetSocketAddress remoteSocketAddress = ((InetSocketAddress) sourceContext.getSocketAddress());
				if (remoteSocketAddress != null) {
					HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_IS_LOCAL, Boolean.toString(remoteSocketAddress.getAddress().isLoopbackAddress() || remoteSocketAddress.getAddress().isLinkLocalAddress()));
					HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_ADDRESS, remoteSocketAddress.getAddress().getHostAddress());
					HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_HOST, remoteSocketAddress.getHostName());
					HTTPUtils.setHeader(request.getContent(), ServerHeader.REMOTE_PORT, new Integer(remoteSocketAddress.getPort()).toString());
				}
				HTTPUtils.setHeader(request.getContent(), ServerHeader.LOCAL_PORT, new Integer(sourceContext.getLocalPort()).toString());
			}
		}
		HTTPResponse response = super.process(securityContext, sourceContext, request);
		
		// make sure any requested connection closing is also enforced in the server
		if (!HTTPUtils.keepAlive(request) && response.getContent() != null) {
			response.getContent().setHeader(new MimeHeader("Connection", "close"));
		}
		
		if (event != null) {
			event.setStopped(new Date());
			event.setSizeIn(MimeUtils.getContentLength(request.getContent().getHeaders()));
			if (response != null) {
				event.setSizeOut(MimeUtils.getContentLength(response.getContent().getHeaders()));
				event.setResponseCode(response.getCode());
			}
			eventTarget.fire(event, this);
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
