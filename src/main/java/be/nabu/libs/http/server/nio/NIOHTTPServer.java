package be.nabu.libs.http.server.nio;

import javax.net.ssl.SSLContext;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPPipelineFactory;
import be.nabu.libs.http.api.server.HTTPServer;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.server.DefaultHTTPExceptionFormatter;
import be.nabu.libs.http.server.HTTPProcessorFactoryImpl;
import be.nabu.libs.nio.api.ExceptionFormatter;
import be.nabu.libs.nio.impl.NIOServerImpl;
import be.nabu.utils.io.SSLServerMode;

public class NIOHTTPServer extends NIOServerImpl implements HTTPServer {

	public NIOHTTPServer(SSLContext sslContext, SSLServerMode sslServerMode, int port, int ioPoolSize, int processPoolSize, HTTPPipelineFactory pipelineFactory) {
		super(sslContext, sslServerMode, port, ioPoolSize, processPoolSize, pipelineFactory);
	}
	
	public NIOHTTPServer(SSLContext sslContext, SSLServerMode sslServerMode, int port, int ioPoolSize, int processPoolSize) {
		this(sslContext, sslServerMode, port, ioPoolSize, processPoolSize, new HTTPPipelineFactoryImpl(
			new HTTPProcessorFactoryImpl(new DefaultHTTPExceptionFormatter(), false), 
			new MemoryMessageDataProvider())
		);
	}

	@Override
	public MessageDataProvider getMessageDataProvider() {
		return getPipelineFactory().getMessageDataProvider();
	}

	@Override
	public void setMessageDataProvider(MessageDataProvider messageDataProvider) {
		getPipelineFactory().setMessageDataProvider(messageDataProvider);
	}

	@Override
	public void route(String hostMatch, EventDispatcher eventDispatcher) {
		getPipelineFactory().getProcessorFactory().route(hostMatch, eventDispatcher);
	}

	@Override
	public ExceptionFormatter<HTTPRequest, HTTPResponse> getExceptionFormatter() {
		return getPipelineFactory().getProcessorFactory().getExceptionFormatter();
	}

	@Override
	public void setExceptionFormatter(ExceptionFormatter<HTTPRequest, HTTPResponse> exceptionFormatter) {
		getPipelineFactory().getProcessorFactory().setExceptionFormatter(exceptionFormatter);
	}

	@Override
	public boolean isProxied() {
		return getPipelineFactory().getProcessorFactory().isProxied();
	}

	@Override
	public void setProxied(boolean isProxied) {
		getPipelineFactory().getProcessorFactory().setProxied(isProxied);
	}

	@Override
	public HTTPPipelineFactory getPipelineFactory() {
		return (HTTPPipelineFactory) super.getPipelineFactory();
	}

	@Override
	public void unroute(String hostMatch) {
		getPipelineFactory().getProcessorFactory().unroute(hostMatch);
	}

	@Override
	public EventDispatcher getDispatcher(String hostMatch) {
		return getPipelineFactory().getProcessorFactory().getDispatcher(hostMatch);
	}

}
