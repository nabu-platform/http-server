package be.nabu.libs.http.client.nio;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.WeakHashMap;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.client.nio.NIOHTTPClientImpl.HTTPResponseFuture;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.nio.api.KeepAliveDecider;
import be.nabu.libs.nio.api.NIOServer;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.api.PipelineFactory;
import be.nabu.libs.nio.impl.MessagePipelineImpl;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeUtils;

public class HTTPClientPipelineFactory implements PipelineFactory {
	
	private Map<Pipeline, Deque<HTTPRequest>> pending = Collections.synchronizedMap(new WeakHashMap<Pipeline, Deque<HTTPRequest>>());
	private MessageDataProvider messageDataProvider;
	private EventDispatcher dispatcher;
	private CookieHandler cookieHandler;
	private Map<HTTPRequest, HTTPResponseFuture> futures;
	private NIOHTTPClientImpl client;

	public HTTPClientPipelineFactory(NIOHTTPClientImpl client, CookieHandler cookieHandler, Map<HTTPRequest, HTTPResponseFuture> futures, EventDispatcher dispatcher, MessageDataProvider messageDataProvider) {
		this.client = client;
		this.cookieHandler = cookieHandler;
		this.futures = futures;
		this.dispatcher = dispatcher;
		this.messageDataProvider = messageDataProvider;
	}
	
	@Override
	public Pipeline newPipeline(NIOServer server, SelectionKey key) throws IOException {
		Deque<HTTPRequest> queue = new ArrayDeque<HTTPRequest>();
		
		SocketAddress remoteAddress = ((SocketChannel) key.channel()).getRemoteAddress();
		String host = ((InetSocketAddress) remoteAddress).getHostString();
		int port = ((InetSocketAddress) remoteAddress).getPort();
		Boolean secure = client.getSecure(host, port);
		if (secure == null) {
			secure = server.getSSLContext() != null;
		}
		HTTPClientExceptionFormatter exceptionFormatter = new HTTPClientExceptionFormatter();
		MessagePipelineImpl<HTTPResponse, HTTPRequest> pipeline = new MessagePipelineImpl<HTTPResponse, HTTPRequest>(
			server,
			key,
			new HTTPResponseParserFactory(messageDataProvider, queue),
			new HTTPRequestFormatterFactory(queue),
			new HTTPResponseProcessorFactory(client, cookieHandler, secure, dispatcher, exceptionFormatter, futures),
			new KeepAliveDecider<HTTPRequest>() {
				@Override
				public boolean keepConnectionAlive(HTTPRequest request) {
					boolean keepAlive = HTTPUtils.keepAlive(request);
					if (keepAlive && request.getContent() != null) {
						Header header = MimeUtils.getHeader("Proxy-Connection", request.getContent().getHeaders());
						if (header != null && header.getValue().equalsIgnoreCase("close")) {
							return false;
						}
					}
					return keepAlive;
				}
			},
			exceptionFormatter,
			true,
			secure
		);
		pending.put(pipeline, queue);
		return pipeline;
	}
	
	public Deque<HTTPRequest> getPending(Pipeline pipeline) {
		return pending.get(pipeline);
	}

}
