package be.nabu.libs.http.client.nio;

import java.net.CookieHandler;
import java.util.Map;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.client.nio.NIOHTTPClient.HTTPResponseFuture;
import be.nabu.libs.nio.api.ExceptionFormatter;
import be.nabu.libs.nio.api.MessageProcessor;
import be.nabu.libs.nio.api.MessageProcessorFactory;

public class HTTPResponseProcessorFactory implements MessageProcessorFactory<HTTPResponse, HTTPRequest> {

	private EventDispatcher dispatcher;
	private ExceptionFormatter<HTTPResponse, HTTPRequest> exceptionFormatter;
	private CookieHandler cookieHandler;
	private boolean secure;
	private Map<HTTPRequest, HTTPResponseFuture> futures;
	private NIOHTTPClient client;
	
	public HTTPResponseProcessorFactory(NIOHTTPClient client, CookieHandler cookieHandler, boolean secure, EventDispatcher dispatcher, ExceptionFormatter<HTTPResponse, HTTPRequest> exceptionFormatter, Map<HTTPRequest, HTTPResponseFuture> futures) {
		this.client = client;
		this.cookieHandler = cookieHandler;
		this.secure = secure;
		this.dispatcher = dispatcher;
		this.exceptionFormatter = exceptionFormatter;
		this.futures = futures;
	}
	
	@Override
	public MessageProcessor<HTTPResponse, HTTPRequest> newProcessor(HTTPResponse response) {
		return new HTTPResponseProcessor(client, cookieHandler, secure, dispatcher, exceptionFormatter, futures);
	}

}
