package be.nabu.libs.http.server.nio;

import java.io.IOException;

import be.nabu.libs.http.UnknownFrameException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.api.server.MessageFramer;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.PushbackContainer;

public class HTTPRequestFramer implements MessageFramer<HTTPRequest> {

	private HTTPMessageFramer messageFramer;
	
	public HTTPRequestFramer(MessageDataProvider dataProvider) {
		this.messageFramer = new HTTPMessageFramer(dataProvider);
	}

	@Override
	public void close() throws IOException {
		messageFramer.close();
	}

	@Override
	public void push(PushbackContainer<ByteBuffer> content) throws UnknownFrameException, IOException {
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
	public HTTPRequest getMessage() {
		return new DefaultHTTPRequest(messageFramer.getMethod(), messageFramer.getTarget(), messageFramer.getMessage());
	}

	@Override
	public boolean isClosed() {
		return messageFramer.isClosed();
	}

	protected HTTPMessageFramer getMessageFramer() {
		return messageFramer;
	}
}