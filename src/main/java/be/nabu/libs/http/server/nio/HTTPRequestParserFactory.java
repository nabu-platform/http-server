package be.nabu.libs.http.server.nio;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.nio.api.MessageParser;
import be.nabu.libs.nio.api.MessageParserFactory;

public class HTTPRequestParserFactory implements MessageParserFactory<HTTPRequest> {

	private MessageDataProvider provider;

	public HTTPRequestParserFactory(MessageDataProvider provider) {
		this.provider = provider;
	}
	
	@Override
	public MessageParser<HTTPRequest> newMessageParser() {
		return new HTTPRequestParser(provider);
	}

}
