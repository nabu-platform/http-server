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

import java.net.CookieHandler;
import java.util.Map;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.client.nio.NIOHTTPClientImpl.HTTPResponseFuture;
import be.nabu.libs.nio.api.ExceptionFormatter;
import be.nabu.libs.nio.api.MessageProcessor;
import be.nabu.libs.nio.api.MessageProcessorFactory;

public class HTTPResponseProcessorFactory implements MessageProcessorFactory<HTTPResponse, HTTPRequest> {

	private EventDispatcher dispatcher;
	private ExceptionFormatter<HTTPResponse, HTTPRequest> exceptionFormatter;
	private CookieHandler cookieHandler;
	private boolean secure;
	private Map<HTTPRequest, HTTPResponseFuture> futures;
	private NIOHTTPClientImpl client;
	
	public HTTPResponseProcessorFactory(NIOHTTPClientImpl client, CookieHandler cookieHandler, boolean secure, EventDispatcher dispatcher, ExceptionFormatter<HTTPResponse, HTTPRequest> exceptionFormatter, Map<HTTPRequest, HTTPResponseFuture> futures) {
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
