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

import java.io.IOException;
import java.nio.channels.SocketChannel;

import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.nio.api.MessagePipeline;
import be.nabu.libs.nio.api.NIOClient;
import be.nabu.libs.nio.api.NIOConnector;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.impl.NIODirectConnector;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class NIOHTTPProxy implements NIOConnector {

	private String host;
	private int port;

	public NIOHTTPProxy(String host, int port) {
		this.host = host;
		this.port = port;
	}
	
	@Override
	public SocketChannel connect(NIOClient client, String host, Integer port) throws IOException {
		return new NIODirectConnector().connect(client, this.host, this.port);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void tunnel(NIOClient client, String host, Integer port, Pipeline pipeline) throws IOException {
		// if we are setting up a secure connection, we need to request a tunnel
		if (client.getSSLContext() != null) {
			((MessagePipeline<HTTPResponse, HTTPRequest>) pipeline).getResponseQueue().add(
				new DefaultHTTPRequest("CONNECT", host + ":" + port,
					new PlainMimeEmptyPart(null,
						new MimeHeader("Host", host),
						new MimeHeader("Proxy-Connection", "Keep-Alive"),
						new MimeHeader("Connection", "Keep-Alive"),
						new MimeHeader("User-Agent", "nio-http-client")
					)
				)
			);
		}
	}

}
