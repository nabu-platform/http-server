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

package be.nabu.libs.http.server;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
import be.nabu.utils.cep.api.EventSeverity;
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
	
	// if enabled, we make sure a correlation id header is available so it can be accessed statically everywhere the http request passes
	private boolean ensureCorrelationId = true;

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
		// calculate the conversation id
		boolean hasConversationId = false;
		if (mapping != null && mapping.getConversationIdMapping() != null) {
			String[] split = mapping.getConversationIdMapping().split("[\\s]*:[\\s]*");
			Header header = MimeUtils.getHeader(split[0], part.getHeaders());
			if (header != null && header.getValue() != null && !header.getValue().trim().isEmpty()) {
				String value = MimeUtils.getFullHeaderValue(header);
				if (split.length >= 2) {
					value = value.replaceAll(split[1], "$1");
				}
				// remove any existing header, otherwise it will get picked up before the other one!
				part.removeHeader(ServerHeader.NAME_CONVERSATION_ID);
				part.setHeader(new MimeHeader(ServerHeader.NAME_CONVERSATION_ID, value));
				hasConversationId = true;
			}
		}
		// we currently want this to be explicitly opt in, so remove it if you didn't specifically choose for it
		if (!hasConversationId) {
			part.removeHeader(ServerHeader.NAME_CONVERSATION_ID);
		}
	}
	
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
		
		// it was getting tiresome to have to resolve the proxied host everywhere
		// and then we got to places that don't know anything about http and can't even resolve it
		// so instead, I opted to do the resolving here and update the pipeline so the reporting is correct
		// it is _very_ rarely interesting to see proxy stuff here
		if (isProxied) {
			String address = HTTPUtils.getRemoteAddress(true, request.getContent().getHeaders());
			if (address != null) {
				SocketAddress currentAddress = sourceContext.getSocketAddress();
				if (!(currentAddress instanceof InetSocketAddress) || !address.equals(((InetSocketAddress) currentAddress).getHostString())) {
					Integer port = HTTPUtils.getRemotePort(true, request.getContent().getHeaders());

					// if we are being proxied, get the "actual" data
					Pipeline pipeline = PipelineUtils.getPipeline();
					pipeline.setRemoteAddress(new InetSocketAddress(address, port == null ? 1 : port));
					
					// we update the source context, because it will now reflect the remote address, we want the below code to work properly as well
					sourceContext = pipeline.getSourceContext();
				}
			}
		}
		
		// if we want to ensure the correlation id, we inject one if necessary
		if (ensureCorrelationId && request.getContent() != null) {
			Header header = MimeUtils.getHeader(ServerHeader.NAME_CORRELATION_ID, request.getContent().getHeaders());
			if (header == null) {
				// check if we have a conversation id
				Header conversationIdHeader = MimeUtils.getHeader(ServerHeader.NAME_CONVERSATION_ID, request.getContent().getHeaders());
				String correlationId = UUID.randomUUID().toString().replace("-", "");
				if (conversationIdHeader != null && conversationIdHeader.getValue() != null && !conversationIdHeader.getValue().trim().isEmpty()) {
					correlationId = conversationIdHeader.getValue().trim() + ":" + correlationId;
				}
				request.getContent().setHeader(new MimeHeader(ServerHeader.NAME_CORRELATION_ID, correlationId));
			}
		}
		
		HTTPComplexEventImpl event = null;
		if (eventTarget != null) {
			event = new HTTPComplexEventImpl();
			Pipeline pipeline = PipelineUtils.getPipeline();
			CEPUtils.enrich(event, getClass(), "http-request", pipeline.getSourceContext().getSocketAddress(), null, null);
			// all http traffic is captured in this particular category
			event.setEventCategory("http-message");
			// set the correct source ip & host
			event.setSourceIp(HTTPUtils.getRemoteAddress(isProxied, request.getContent().getHeaders()));
			event.setSourceHost(HTTPUtils.getRemoteHost(isProxied, request.getContent().getHeaders()));
			event.setStarted(new Date());
			event.setMethod(request.getMethod());
			try {
				event.setRequestUri(HTTPUtils.getURI(request, securityContext.getSSLContext() != null));
			}
			catch (FormatException e) {
				// too bad
			}
			event.setApplicationProtocol(securityContext.getSSLContext() != null ? "HTTPS" : "HTTP");
			Header header = MimeUtils.getHeader("User-Agent", request.getContent().getHeaders());
			if (header != null) {
				event.setUserAgent(MimeUtils.getFullHeaderValue(header));
			}
		}
		HTTPResponse response = super.process(securityContext, sourceContext, request);
		
		// make sure any requested connection closing is also enforced in the server
		if (!HTTPUtils.keepAlive(request) && response.getContent() != null) {
			response.getContent().removeHeader("Connection");
			response.getContent().setHeader(new MimeHeader("Connection", "close"));
		}
		
		if (event != null) {
			event.setStopped(new Date());
			event.setSizeIn(MimeUtils.getContentLength(request.getContent().getHeaders()));
			if (response != null) {
				event.setSizeOut(MimeUtils.getContentLength(response.getContent().getHeaders()));
				event.setResponseCode(response.getCode());
				if (response.getCode() < 400) {
					// hardcoded exception for heartbeats, otherwise they generate way too many events
					if (event.getRequestUri() != null && event.getRequestUri().getPath().equals("/heartbeat")) {
						event.setSeverity(EventSeverity.DEBUG);
					}
					else {
						event.setSeverity(EventSeverity.INFO);
					}
				}
				// in general this is "expected", a 403 is fishy though
				else if (response.getCode() == 401) {
					event.setSeverity(EventSeverity.INFO);
				}
				else {
					event.setSeverity(EventSeverity.WARNING);
				}
				if (event.getCode() == null) {
					event.setCode("HTTP-" + response.getCode());
				}
			}
			else {
				event.setSeverity(EventSeverity.ERROR);
				event.setCode("MISSING-RESPONSE");
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
