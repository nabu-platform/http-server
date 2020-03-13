package be.nabu.libs.http.client.nio;

import java.io.IOException;
import java.text.ParseException;
import java.util.Deque;

import be.nabu.libs.events.api.EventTarget;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.server.nio.HTTPMessageParser;
import be.nabu.libs.nio.api.StreamingMessageParser;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.PushbackContainer;

public class HTTPResponseParser implements StreamingMessageParser<HTTPResponse> {

	private HTTPMessageParser messageFramer;
	private Deque<HTTPRequest> queue;
	private boolean streamingMode;
	
	public HTTPResponseParser(MessageDataProvider dataProvider, Deque<HTTPRequest> queue, EventTarget target, boolean streamingMode) {
		this.queue = queue;
		this.streamingMode = streamingMode;
		this.messageFramer = new HTTPMessageParser(dataProvider, true, target, streamingMode);
	}

	@Override
	public void close() throws IOException {
		messageFramer.close();
	}

	@Override
	public void push(PushbackContainer<ByteBuffer> content) throws IOException, ParseException {
		messageFramer.push(content);
	}

	@Override
	public boolean isIdentified() {
		return messageFramer.isIdentified();
	}

	@Override
	public boolean isDone() {
		return messageFramer.isDone();
	}

	@Override
	public HTTPResponse getMessage() {
		HTTPRequest originalRequest = null;
		if (!queue.isEmpty()) {
			synchronized(queue) {
				if (!queue.isEmpty()) {
					originalRequest = queue.poll();
				}
			}
		}
		return new DefaultHTTPResponse(originalRequest, messageFramer.getCode(), messageFramer.getMessageText(), messageFramer.getMessage(), messageFramer.getVersion());
	}

	@Override
	public boolean isClosed() {
		return messageFramer.isClosed();
	}

	protected HTTPMessageParser getMessageFramer() {
		return messageFramer;
	}

	@Override
	public boolean isStreamed() {
		return messageFramer.isStreamed();
	}

	@Override
	public boolean isStreaming() {
		return streamingMode;
	}
}
