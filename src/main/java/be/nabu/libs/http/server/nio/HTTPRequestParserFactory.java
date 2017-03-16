package be.nabu.libs.http.server.nio;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.nio.api.MessageParser;
import be.nabu.libs.nio.api.MessageParserFactory;

public class HTTPRequestParserFactory implements MessageParserFactory<HTTPRequest> {

	private MessageDataProvider provider;
	private Integer maxInitialLineLength;
	private Integer maxHeaderSize;
	private Integer maxChunkSize;

	public HTTPRequestParserFactory(MessageDataProvider provider) {
		this.provider = provider;
	}
	
	@Override
	public MessageParser<HTTPRequest> newMessageParser() {
		HTTPRequestParser parser = new HTTPRequestParser(provider);
		if (maxInitialLineLength != null) {
			parser.getMessageFramer().setMaxInitialLineLength(maxInitialLineLength);
		}
		if (maxHeaderSize != null) {
			parser.getMessageFramer().setMaxHeaderSize(maxHeaderSize);
		}
		if (maxChunkSize != null) {
			parser.getMessageFramer().setMaxChunkSize(maxChunkSize);
		}
		return parser;
	}

	public Integer getMaxInitialLineLength() {
		return maxInitialLineLength;
	}

	public void setMaxInitialLineLength(Integer maxInitialLineLength) {
		this.maxInitialLineLength = maxInitialLineLength;
	}

	public Integer getMaxHeaderSize() {
		return maxHeaderSize;
	}

	public void setMaxHeaderSize(Integer maxHeaderSize) {
		this.maxHeaderSize = maxHeaderSize;
	}

	public Integer getMaxChunkSize() {
		return maxChunkSize;
	}

	public void setMaxChunkSize(Integer maxChunkSize) {
		this.maxChunkSize = maxChunkSize;
	}

}
