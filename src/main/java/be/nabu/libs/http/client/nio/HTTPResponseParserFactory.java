package be.nabu.libs.http.client.nio;

import java.util.Deque;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.nio.api.MessageParser;
import be.nabu.libs.nio.api.MessageParserFactory;

public class HTTPResponseParserFactory implements MessageParserFactory<HTTPResponse> {

	private MessageDataProvider provider;
	private Deque<HTTPRequest> queue;

	public HTTPResponseParserFactory(MessageDataProvider provider, Deque<HTTPRequest> queue) {
		this.provider = provider;
		this.queue = queue;
	}
	
	@Override
	public MessageParser<HTTPResponse> newMessageParser() {
		return new HTTPResponseParser(provider, queue);
	}

}
