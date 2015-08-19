package be.nabu.libs.http.server.nio;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.api.server.MessageFramer;
import be.nabu.libs.http.api.server.MessageFramerFactory;

public class HTTPRequestFramerFactory implements MessageFramerFactory<HTTPRequest> {

	private MessageDataProvider provider;

	public HTTPRequestFramerFactory(MessageDataProvider provider) {
		this.provider = provider;
	}
	
	@Override
	public MessageFramer<HTTPRequest> newFramer() {
		return new HTTPRequestFramer(provider);
	}

}
