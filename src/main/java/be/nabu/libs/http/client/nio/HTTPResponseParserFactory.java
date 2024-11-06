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

import java.util.Deque;

import be.nabu.libs.events.api.EventTarget;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.nio.api.MessageParser;
import be.nabu.libs.nio.api.MessageParserFactory;

public class HTTPResponseParserFactory implements MessageParserFactory<HTTPResponse> {

	private MessageDataProvider provider;
	private Deque<HTTPRequest> queue;
	
	private Integer maxInitialLineLength;
	private Integer maxHeaderSize;
	private Integer maxChunkSize;
	private EventTarget target;
	private boolean streamingMode;
	
	public HTTPResponseParserFactory(MessageDataProvider provider, Deque<HTTPRequest> queue, EventTarget target, boolean streamingMode) {
		this.provider = provider;
		this.queue = queue;
		this.target = target;
		this.streamingMode = streamingMode;
	}
	
	@Override
	public MessageParser<HTTPResponse> newMessageParser() {
		HTTPResponseParser parser = new HTTPResponseParser(provider, queue, target, streamingMode);
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
