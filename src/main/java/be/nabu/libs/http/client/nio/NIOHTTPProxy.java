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
