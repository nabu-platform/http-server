package be.nabu.libs.http.client.nio;

import java.util.Deque;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.nio.api.MessageFormatter;
import be.nabu.libs.nio.api.MessageFormatterFactory;

public class HTTPRequestFormatterFactory implements MessageFormatterFactory<HTTPRequest> {

	private Deque<HTTPRequest> queue;

	public HTTPRequestFormatterFactory(Deque<HTTPRequest> queue) {
		this.queue = queue;
	}
	
	@Override
	public MessageFormatter<HTTPRequest> newMessageFormatter() {
		return new HTTPRequestFormatter(queue);
	}

}
