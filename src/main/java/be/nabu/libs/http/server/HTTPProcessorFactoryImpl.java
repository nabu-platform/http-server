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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.EventTarget;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.HeaderMappingProvider;
import be.nabu.libs.http.api.server.HTTPProcessorFactory;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.nio.PipelineUtils;
import be.nabu.libs.nio.api.ExceptionFormatter;
import be.nabu.libs.nio.api.MessageProcessor;
import be.nabu.utils.cep.api.EventSeverity;
import be.nabu.utils.cep.impl.CEPUtils;
import be.nabu.utils.cep.impl.HTTPComplexEventImpl;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeUtils;

public class HTTPProcessorFactoryImpl implements HTTPProcessorFactory {

	private Map<String, EventDispatcher> dispatchers = new HashMap<String, EventDispatcher>();
	private List<String> hostsCache;
	private ExceptionFormatter<HTTPRequest, HTTPResponse> exceptionFormatter;
	private boolean isProxied;
	private HeaderMappingProvider mapping;
	private EventTarget eventTarget;

	public HTTPProcessorFactoryImpl(ExceptionFormatter<HTTPRequest, HTTPResponse> exceptionFormatter, boolean isProxied, EventDispatcher coreDispatcher) {
		this(exceptionFormatter, isProxied, coreDispatcher, null, null);
	}
	
	public HTTPProcessorFactoryImpl(ExceptionFormatter<HTTPRequest, HTTPResponse> exceptionFormatter, boolean isProxied, EventDispatcher coreDispatcher, HeaderMappingProvider mapping, EventTarget eventTarget) {
		this.exceptionFormatter = exceptionFormatter;
		this.isProxied = isProxied;
		this.mapping = mapping;
		this.eventTarget = eventTarget;
		this.route(null, coreDispatcher);
	}
	
	public synchronized void route(String hostMatch, EventDispatcher eventDispatcher) {
		Map<String, EventDispatcher> dispatchers = new HashMap<String, EventDispatcher>(this.dispatchers);
		dispatchers.put(hostMatch, eventDispatcher);
		this.dispatchers = dispatchers;
		this.hostsCache = new ArrayList<String>(this.dispatchers.keySet());
	}
	
	@Override
	public MessageProcessor<HTTPRequest, HTTPResponse> newProcessor(HTTPRequest request) {
		String host = null;
		if (request.getContent() != null) {
			Header header = MimeUtils.getHeader("Host", request.getContent().getHeaders());
			if (header != null) {
				host = header.getValue();
			}
		}
		if (host == null && request.getVersion() >= 1.1) {
			if (eventTarget != null) {
				HTTPComplexEventImpl event = new HTTPComplexEventImpl();
				CEPUtils.enrich(event, getClass(), "missing-host-header", PipelineUtils.getPipeline().getSourceContext().getSocketAddress(), "No host header found", null);
				event.setSeverity(EventSeverity.WARNING);
				event.setMethod(request.getMethod());
				try {
					event.setRequestUri(HTTPUtils.getURI(request, false));
				}
				catch (FormatException e) {
					// ignore
				}
				eventTarget.fire(event, this);
			}
			throw new HTTPException(400, "Expecting host header for HTTP version " + request.getVersion());
		}
		EventDispatcher dispatcher = getDispatcher(host);
		if (dispatcher == null) {
			return null;
		}
		else {
			return new HTTPProcessor(dispatcher, exceptionFormatter, isProxied, mapping, eventTarget);
		}
	}

	@Override
	public EventDispatcher getDispatcher(String host) {
		String closestRoute = null;
		if (host != null) {
			// strip the port (if any), we are already after a connection
			int index = host.indexOf(':');
			if (index > 0) {
				host = host.substring(0, index);
			}
			for (String key : hostsCache) {
				if (key != null) {
					if (host.matches(key)) {
						if (closestRoute == null || key.length() > closestRoute.length()) {
							closestRoute = key;
						}
					}
				}
			}
		}
		return dispatchers.get(closestRoute);
	}

	@Override
	public ExceptionFormatter<HTTPRequest, HTTPResponse> getExceptionFormatter() {
		return exceptionFormatter;
	}
	@Override
	public void setExceptionFormatter(ExceptionFormatter<HTTPRequest, HTTPResponse> exceptionFormatter) {
		this.exceptionFormatter = exceptionFormatter;
	}
	
	@Override
	public boolean isProxied() {
		return isProxied;
	}

	@Override
	public void setProxied(boolean isProxied) {
		this.isProxied = isProxied;
	}

	@Override
	public synchronized void unroute(String hostMatch) {
		Map<String, EventDispatcher> dispatchers = new HashMap<String, EventDispatcher>(this.dispatchers);
		dispatchers.remove(hostMatch);
		this.dispatchers = dispatchers;
	}

}
