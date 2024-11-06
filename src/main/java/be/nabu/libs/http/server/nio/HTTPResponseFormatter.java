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

package be.nabu.libs.http.server.nio;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.nio.api.MessageFormatter;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.EventfulReadableContainer;
import be.nabu.utils.io.api.EventfulSubscriber;
import be.nabu.utils.io.api.EventfulSubscription;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.io.containers.ReadableContainerChainer;
import be.nabu.utils.mime.impl.HeaderEncoding;
import be.nabu.utils.mime.impl.PullableMimeFormatter;

public class HTTPResponseFormatter implements MessageFormatter<HTTPResponse> {
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	public static boolean STREAMING_MODE = Boolean.parseBoolean(System.getProperty("http.streamingMode", "true"));
	
	@SuppressWarnings({ "unchecked", "resource" })
	@Override
	public ReadableContainer<ByteBuffer> format(HTTPResponse message) {
		if (logger.isDebugEnabled()) {
			HTTPRequest request = null;
			if (message instanceof LinkableHTTPResponse) {
				request = ((LinkableHTTPResponse) message).getRequest();
			}
			if (request == null) {
				logger.debug("[OUTBOUND] Anonymous response (" + hashCode() + ") code: " + message.getCode() + " " + message.getMessage());
			}
			else {
				logger.debug("[OUTBOUND] Response to " + request.getMethod() + " " + request.getTarget() + " (" + hashCode() + ") code: " + message.getCode() + " " + message.getMessage());
			}
			if (message.getContent() != null) {
				logger.debug("[OUTBOUND] Response (" + hashCode() + ") headers: " + Arrays.asList(message.getContent().getHeaders()));
			}
		}
		byte [] firstLine = ("HTTP/" + message.getVersion() + " " + message.getCode() + " " + message.getMessage() + "\r\n").getBytes(Charset.forName("ASCII"));

		// no content, just write the ending
		if (message.getContent() == null) {
			return IOUtils.chain(true, IOUtils.wrap(firstLine, true), IOUtils.wrap("\r\n".getBytes(Charset.forName("ASCII")), true));
		}
		
		PullableMimeFormatter formatter = STREAMING_MODE ? new StreamableMimeFormatter() : new PullableMimeFormatter();
		// internet explorer does not support header folding
		formatter.setFoldHeader(false);
		formatter.setOptimizeCompression(true);
		formatter.setIncludeMainContentTrailingLineFeeds(false);
		formatter.setAllowBinary(true);
		// chrome does not support the default encoding in for example the content-disposition header if the filename contains weird stuff
		formatter.setHeaderEncoding(HeaderEncoding.RFC2231);
		try {
			formatter.format(message.getContent());
		}
		catch (Exception e) {
			try {
				formatter.close();
			}
			catch (IOException e1) {
				logger.error("Could not close formatter", e1);
			}
			throw new RuntimeException(e);
		}
		ReadableContainerChainer<ByteBuffer> chained = new ReadableContainerChainer<ByteBuffer>(true, IOUtils.wrap(firstLine, true), formatter);
		if (STREAMING_MODE) {
				chained.setAllowEmptyReads(STREAMING_MODE);
				return new EventfulReadableContainer<ByteBuffer>() {
					@Override
					public long read(ByteBuffer buffer) throws IOException {
						return chained.read(buffer);
					}
					@Override
					public void close() throws IOException {
						chained.close();
					}
					@Override
					public EventfulSubscription availableData(EventfulSubscriber subscriber) {
						return ((StreamableMimeFormatter) formatter).availableData(subscriber);
					}
				};
		}
		else {
			return chained;
		}
//		return IOUtils.chain(true, IOUtils.wrap(firstLine, true), formatter);
	}
}
