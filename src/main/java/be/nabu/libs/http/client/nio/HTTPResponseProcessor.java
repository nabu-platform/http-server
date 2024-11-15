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

package be.nabu.libs.http.client.nio;

import java.net.CookieHandler;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.ResponseHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.client.nio.NIOHTTPClientImpl.HTTPResponseFuture;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.nio.PipelineUtils;
import be.nabu.libs.nio.api.ExceptionFormatter;
import be.nabu.libs.nio.api.SecurityContext;
import be.nabu.libs.nio.api.SourceContext;
import be.nabu.libs.nio.impl.EventDrivenMessageProcessor;
import be.nabu.utils.mime.api.Header;

public class HTTPResponseProcessor extends EventDrivenMessageProcessor<HTTPResponse, HTTPRequest> {

	private Logger logger = LoggerFactory.getLogger(getClass());
	private CookieHandler cookieHandler;
	private boolean secure;
	private Map<HTTPRequest, HTTPResponseFuture> futures;
	private NIOHTTPClientImpl client;

	public HTTPResponseProcessor(NIOHTTPClientImpl client, CookieHandler cookieHandler, boolean secure, EventDispatcher dispatcher, ExceptionFormatter<HTTPResponse, HTTPRequest> exceptionFormatter, Map<HTTPRequest, HTTPResponseFuture> futures) {
		super(HTTPResponse.class, HTTPRequest.class, dispatcher, exceptionFormatter, false);
		this.client = client;
		this.cookieHandler = cookieHandler;
		this.secure = secure;
		this.futures = futures;
	}

	@Override
	public HTTPRequest process(SecurityContext securityContext, SourceContext sourceContext, HTTPResponse response) {
		HTTPRequest request = response instanceof LinkableHTTPResponse ? ((LinkableHTTPResponse) response).getRequest() : null;
		logger.debug("> socket [" + sourceContext.getSocketAddress() + "] " + response.getCode() + ": " + response.getMessage());
		if (logger.isTraceEnabled() && response.getContent() != null) {
			for (Header header : response.getContent().getHeaders()) {
				logger.trace("	> " + header.getName() + ": " + header.getValue());
			}
		}
		try {
			URI originalUri = request == null ? null : HTTPUtils.getURI(request, secure);
			// push the response into the cookiestore
			// we need the original request to calculate the path
			if (originalUri != null && cookieHandler != null && response.getContent() != null) {
				cookieHandler.put(originalUri, getHeadersAsMap(response.getContent().getHeaders()));
			}
			HTTPResponseFuture httpResponseFuture = request == null ? null : futures.get(request);
			
			// in some cases the response might not be successful (additional authentication required, need to follow redirects etc)
			// if that is the case, we don't want to resolve the futures just yet, instead we want to redo the request with the necessary parameters
			// note that we already send it up the dispatcher pipeline, so we no longer need to send it to the super to send it again down the chain
			// this actually means we might not need to extend the parent class?
			
			// if the response is not successful, see if we have a handler for it
			HTTPRequest retryRequest = client.getDispatcher().fire(response, PipelineUtils.getPipeline(), new ResponseHandler<HTTPResponse, HTTPRequest>() {
				@Override
				public HTTPRequest handle(HTTPResponse event, Object response, boolean isLast) {
					return response instanceof HTTPRequest ? (HTTPRequest) response : null;
				}
			});
			// note that sometimes the retryrequest is the exact same object (but altered) as the original request
			if (retryRequest != null) {
				// we are gonna do the new request, update the future to point to the new request
				if (httpResponseFuture != null) {
					futures.remove(request);
					futures.put(retryRequest, httpResponseFuture);
				}
				URI newUri = HTTPUtils.getURI(retryRequest, secure);
				String newHost = NIOHTTPClientImpl.getHost(newUri);
				int newPort = NIOHTTPClientImpl.getPort(newUri);
				if (originalUri != null) {
					String originalHost = NIOHTTPClientImpl.getHost(originalUri);
					int originalPort = NIOHTTPClientImpl.getPort(originalUri);
					if (originalHost.equals(newHost) && originalPort == newPort) {
						return retryRequest;
					}
				}
//				client.call(retryRequest, client.getSecure(newHost, newPort));
				// need it to be run on the same pipeline
				return retryRequest;
			}

			// resolve the future
			if (httpResponseFuture != null) {
				httpResponseFuture.setResponse(response);
				futures.remove(request);
			}
			// questionable code? the response writer is supposed to check & enforce this
			// at this point we might be closing the connection before we had a chance to stream back the response
//			if (!HTTPUtils.keepAlive(response)) {
//				PipelineUtils.getPipeline().close();
//			}
			
//			return super.process(securityContext, sourceContext, response);
			return null;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private Map<String, List<String>> getHeadersAsMap(Header...headers) {
		Map<String, List<String>> map = new HashMap<String, List<String>>();
		for (Header header : headers) {
			if (!map.containsKey(header.getName().toLowerCase()))
				map.put(header.getName().toLowerCase(), new ArrayList<String>());
			if (header.getValue() != null)
				map.get(header.getName().toLowerCase()).add(header.getValue());
		}
		return map;
	}

}
