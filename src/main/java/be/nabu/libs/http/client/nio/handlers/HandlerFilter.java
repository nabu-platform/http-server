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

package be.nabu.libs.http.client.nio.handlers;

import java.net.URI;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.client.nio.NIOHTTPClientImpl;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.nio.PipelineUtils;
import be.nabu.libs.nio.impl.MessagePipelineImpl;

public class HandlerFilter implements EventHandler<HTTPResponse, Boolean> {

	private String host;
	private int port;
	
	public HandlerFilter(String host, int port) {
		this.host = host;
		this.port = port;
	}
	
	@Override
	public Boolean handle(HTTPResponse event) {
		HTTPRequest request = event instanceof LinkableHTTPResponse ? ((LinkableHTTPResponse) event).getRequest() : null;
		if (request == null) {
			return true;
		}
		try {
			boolean secure = ((MessagePipelineImpl<?, ?>) PipelineUtils.getPipeline()).isUseSsl();
			URI uri = HTTPUtils.getURI(request, secure);
			String host = NIOHTTPClientImpl.getHost(uri);
			int port = NIOHTTPClientImpl.getPort(uri);
			return !host.equals(this.host) || port != this.port;
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
