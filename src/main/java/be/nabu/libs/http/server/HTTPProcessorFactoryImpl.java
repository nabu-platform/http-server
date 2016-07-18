package be.nabu.libs.http.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPProcessorFactory;
import be.nabu.libs.nio.api.ExceptionFormatter;
import be.nabu.libs.nio.api.MessageProcessor;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeUtils;

public class HTTPProcessorFactoryImpl implements HTTPProcessorFactory {

	private Map<String, EventDispatcher> dispatchers = new HashMap<String, EventDispatcher>();
	private List<String> hostsCache;
	private ExceptionFormatter<HTTPRequest, HTTPResponse> exceptionFormatter;
	private boolean isProxied;

	public HTTPProcessorFactoryImpl(ExceptionFormatter<HTTPRequest, HTTPResponse> exceptionFormatter, boolean isProxied, EventDispatcher coreDispatcher) {
		this.exceptionFormatter = exceptionFormatter;
		this.isProxied = isProxied;
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
			throw new HTTPException(400, "Expecting host header for HTTP version " + request.getVersion());
		}
		EventDispatcher dispatcher = getDispatcher(host);
		if (dispatcher == null) {
			return null;
		}
		else {
			return new HTTPProcessor(dispatcher, exceptionFormatter, isProxied);
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
