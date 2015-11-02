package be.nabu.libs.http.server.nio;

import java.io.IOException;
import java.nio.channels.SelectionKey;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPProcessorFactory;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.server.HTTPProcessorFactoryImpl;
import be.nabu.libs.nio.api.KeepAliveDecider;
import be.nabu.libs.nio.api.MessageFormatter;
import be.nabu.libs.nio.api.MessageFormatterFactory;
import be.nabu.libs.nio.api.NIOServer;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.api.PipelineFactory;
import be.nabu.libs.nio.impl.MessagePipelineImpl;

public class HTTPPipelineFactory implements PipelineFactory {

	private MessageDataProvider messageDataProvider;
	private HTTPProcessorFactoryImpl processorFactory;

	public HTTPPipelineFactory(HTTPProcessorFactoryImpl processorFactory, MessageDataProvider messageDataProvider) {
		this.processorFactory = processorFactory;
		this.messageDataProvider = messageDataProvider;
	}
	
	@Override
	public Pipeline newPipeline(NIOServer server, SelectionKey key) throws IOException {
		return new MessagePipelineImpl<HTTPRequest, HTTPResponse>(
			server,
			key,
			new HTTPRequestParserFactory(messageDataProvider),
			new MessageFormatterFactory<HTTPResponse>() {
				@Override
				public MessageFormatter<HTTPResponse> newMessageFormatter() {
					return new HTTPResponseFormatter();
				}
			},
			processorFactory,
			new KeepAliveDecider<HTTPResponse>() {
				@Override
				public boolean keepConnectionAlive(HTTPResponse response) {
					return HTTPUtils.keepAlive(response);
				}
			},
			processorFactory.getExceptionFormatter()
		);
	}

	public MessageDataProvider getMessageDataProvider() {
		return messageDataProvider;
	}

	public HTTPProcessorFactory getProcessorFactory() {
		return processorFactory;
	}

	public void setMessageDataProvider(MessageDataProvider messageDataProvider) {
		this.messageDataProvider = messageDataProvider;
	}
}
