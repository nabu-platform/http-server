package be.nabu.libs.http.client.nio.handlers;

import java.net.URI;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.client.nio.NIOHTTPClient;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.nio.PipelineUtils;
import be.nabu.libs.nio.impl.MessagePipelineImpl;

public class HandlerFilter implements EventHandler<HTTPResponse, Boolean> {

	private String host;
	private int port;
	
	public HandlerFilter(String host, int port) {
		this.host = host;
		this.port = port;
	}
	
	@Override
	public Boolean handle(HTTPResponse event) {
		HTTPRequest request = event instanceof LinkableHTTPResponse ? ((LinkableHTTPResponse) event).getRequest() : null;
		if (request == null) {
			return true;
		}
		try {
			boolean secure = ((MessagePipelineImpl<?, ?>) PipelineUtils.getPipeline()).isUseSsl();
			URI uri = HTTPUtils.getURI(request, secure);
			String host = NIOHTTPClient.getHost(uri);
			int port = NIOHTTPClient.getPort(uri);
			return !host.equals(this.host) || port != this.port;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
