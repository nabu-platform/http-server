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
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Deque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.nio.api.MessageFormatter;
import be.nabu.libs.resources.URIUtils;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.io.api.WritableContainer;
import be.nabu.utils.io.containers.ReadableContainerDuplicator;
import be.nabu.utils.mime.impl.HeaderEncoding;
import be.nabu.utils.mime.impl.PullableMimeFormatter;

public class HTTPRequestFormatter implements MessageFormatter<HTTPRequest> {
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	private Deque<HTTPRequest> queue;
	
	private static Boolean DUMPING = Boolean.parseBoolean(System.getProperty("http.dump.requests", "false"));
	
	public HTTPRequestFormatter(Deque<HTTPRequest> queue) {
		this.queue = queue;
	}

	@SuppressWarnings("unchecked")
	@Override
	public ReadableContainer<ByteBuffer> format(HTTPRequest message) {
		if (logger.isDebugEnabled()) {
			logger.debug("[OUTBOUND] Request (" + hashCode() + "): " + message.getMethod() + " " + message.getTarget());
			if (message.getContent() != null) {
				logger.debug("[OUTBOUND] Request (" + hashCode() + ") headers: " + Arrays.asList(message.getContent().getHeaders()));
			}
		}
		
		byte [] firstLine = (message.getMethod() + " " + URIUtils.encodeURI(message.getTarget(), false) + " HTTP/" + message.getVersion() + "\r\n").getBytes(Charset.forName("ASCII"));

		// no content, just write the ending
		if (message.getContent() == null) {
			return IOUtils.chain(true, IOUtils.wrap(firstLine, true), IOUtils.wrap("\r\n".getBytes(Charset.forName("ASCII")), true));
		}
		
		PullableMimeFormatter formatter = new PullableMimeFormatter();
		formatter.setOptimizeCompression(true);
		formatter.setIncludeMainContentTrailingLineFeeds(false);
		formatter.setAllowBinary(true);
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
		synchronized(queue) {
			queue.offer(message);
		}
		if (DUMPING) {
			ReadableContainer<ByteBuffer> chain = IOUtils.chain(true, IOUtils.wrap(firstLine, true), formatter);
			return new ReadableContainerDuplicator<ByteBuffer>(chain, new WritableContainer<ByteBuffer>() {
				@Override
				public void close() throws IOException {
					
				}
				@Override
				public long write(ByteBuffer buffer) throws IOException {
					byte[] bytes = IOUtils.toBytes(buffer);
					System.out.print(new String(bytes));
					return bytes.length;
				}
				@Override
				public void flush() throws IOException {
					
				}
			});
		}
		else {
			return IOUtils.chain(true, IOUtils.wrap(firstLine, true), formatter);
		}
	}
}
