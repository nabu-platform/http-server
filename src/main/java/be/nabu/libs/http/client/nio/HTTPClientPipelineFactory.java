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
import be.nabu.libs.nio.api.KeepAliveDecider;
import be.nabu.libs.nio.api.NIOServer;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.api.PipelineFactory;
import be.nabu.libs.nio.impl.MessagePipelineImpl;

public class HTTPClientPipelineFactory implements PipelineFactory {
	
	private Map<Pipeline, Deque<HTTPRequest>> pending = Collections.synchronizedMap(new WeakHashMap<Pipeline, Deque<HTTPRequest>>());
	private MessageDataProvider messageDataProvider;
	private EventDispatcher dispatcher;
	private CookieHandler cookieHandler;
	private Map<HTTPRequest, HTTPResponseFuture> futures;
	private NIOHTTPClientImpl client;
	private boolean streamingMode;
	private Integer maxChunkSize;

	public HTTPClientPipelineFactory(NIOHTTPClientImpl client, CookieHandler cookieHandler, Map<HTTPRequest, HTTPResponseFuture> futures, EventDispatcher dispatcher, MessageDataProvider messageDataProvider, boolean streamingMode) {
		this.client = client;
		this.cookieHandler = cookieHandler;
		this.futures = futures;
		this.dispatcher = dispatcher;
		this.messageDataProvider = messageDataProvider;
		this.streamingMode = streamingMode;
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
		HTTPResponseParserFactory responseParserFactory = new HTTPResponseParserFactory(messageDataProvider, queue, server, streamingMode);
		responseParserFactory.setMaxChunkSize(maxChunkSize);
		MessagePipelineImpl<HTTPResponse, HTTPRequest> pipeline = new MessagePipelineImpl<HTTPResponse, HTTPRequest>(
			server,
			key,
			responseParserFactory,
			new HTTPRequestFormatterFactory(queue),
			new HTTPResponseProcessorFactory(client, cookieHandler, secure, dispatcher, exceptionFormatter, futures),
			new KeepAliveDecider<HTTPRequest>() {
				@Override
				public boolean keepConnectionAlive(HTTPRequest request) {
					// we must first emit the request, then wait for the response
					// the ResponseWriter however will write the request, then see the connection closed and close the pipeline without waiting for a response
					// in short: connection closed has to be respected by the server, not the client
					return true;
//					boolean keepAlive = HTTPUtils.keepAlive(request);
//					if (keepAlive && request.getContent() != null) {
//						Header header = MimeUtils.getHeader("Proxy-Connection", request.getContent().getHeaders());
//						if (header != null && header.getValue().equalsIgnoreCase("close")) {
//							return false;
//						}
//					}
//					return keepAlive;
				}
			},
			exceptionFormatter,
			true,
			secure,
			// tcp max is 65535, ethernet is 1500, allow space for ssl, read estimates of 40kb per packet, let's give it some more
			1400
		);
		pending.put(pipeline, queue);
		return pipeline;
	}
	
	public Deque<HTTPRequest> getPending(Pipeline pipeline) {
		return pending.get(pipeline);
	}

	public Integer getMaxChunkSize() {
		return maxChunkSize;
	}

	public void setMaxChunkSize(Integer maxChunkSize) {
		this.maxChunkSize = maxChunkSize;
	}
}
