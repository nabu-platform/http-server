package be.nabu.libs.http.server.nio;

import java.io.IOException;
import java.nio.channels.SelectionKey;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPPipelineFactory;
import be.nabu.libs.http.api.server.HTTPProcessorFactory;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.nio.api.KeepAliveDecider;
import be.nabu.libs.nio.api.MessageFormatter;
import be.nabu.libs.nio.api.MessageFormatterFactory;
import be.nabu.libs.nio.api.NIOServer;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.impl.MessagePipelineImpl;

public class HTTPPipelineFactoryImpl implements HTTPPipelineFactory {

	private MessageDataProvider messageDataProvider;
	private HTTPProcessorFactory processorFactory;
	private long readTimeout, writeTimeout;
	private int requestLimit, responseLimit;

	public HTTPPipelineFactoryImpl(HTTPProcessorFactory processorFactory, MessageDataProvider messageDataProvider) {
		this.processorFactory = processorFactory;
		this.messageDataProvider = messageDataProvider;
	}
	
	@Override
	public Pipeline newPipeline(NIOServer server, SelectionKey key) throws IOException {
		MessagePipelineImpl<HTTPRequest, HTTPResponse> pipeline = new MessagePipelineImpl<HTTPRequest, HTTPResponse>(
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
		pipeline.setReadTimeout(readTimeout);
		pipeline.setWriteTimeout(writeTimeout);
		pipeline.setRequestLimit(requestLimit);
		pipeline.setResponseLimit(responseLimit);
		return pipeline;
	}

	public HTTPProcessorFactory getProcessorFactory() {
		return processorFactory;
	}

	public MessageDataProvider getMessageDataProvider() {
		return messageDataProvider;
	}
	
	public void setMessageDataProvider(MessageDataProvider messageDataProvider) {
		this.messageDataProvider = messageDataProvider;
	}

	@Override
	public long getReadTimeout() {
		return readTimeout;
	}
	@Override
	public void setReadTimeout(long readTimeout) {
		this.readTimeout = readTimeout;
	}

	@Override
	public long getWriteTimeout() {
		return writeTimeout;
	}
	@Override
	public void setWriteTimeout(long writeTimeout) {
		this.writeTimeout = writeTimeout;
	}

	@Override
	public int getRequestLimit() {
		return requestLimit;
	}
	@Override
	public void setRequestLimit(int requestLimit) {
		this.requestLimit = requestLimit;
	}

	@Override
	public int getResponseLimit() {
		return responseLimit;
	}
	@Override
	public void setResponseLimit(int responseLimit) {
		this.responseLimit = responseLimit;
	}
}
