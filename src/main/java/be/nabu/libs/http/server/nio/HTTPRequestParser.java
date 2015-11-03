package be.nabu.libs.http.server.nio;

import java.io.IOException;
import java.text.ParseException;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.nio.api.MessageParser;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.PushbackContainer;

public class HTTPRequestParser implements MessageParser<HTTPRequest> {

	private HTTPMessageParser messageFramer;
	
	public HTTPRequestParser(MessageDataProvider dataProvider) {
		this.messageFramer = new HTTPMessageParser(dataProvider);
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
	public HTTPRequest getMessage() {
		return new DefaultHTTPRequest(messageFramer.getMethod(), messageFramer.getTarget(), messageFramer.getMessage(), messageFramer.getVersion());
	}

	@Override
	public boolean isClosed() {
		return messageFramer.isClosed();
	}

	protected HTTPMessageParser getMessageFramer() {
		return messageFramer;
	}
}