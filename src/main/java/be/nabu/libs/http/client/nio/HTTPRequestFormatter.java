package be.nabu.libs.http.client.nio;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Deque;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.nio.api.MessageFormatter;
import be.nabu.libs.resources.URIUtils;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.mime.impl.PullableMimeFormatter;

public class HTTPRequestFormatter implements MessageFormatter<HTTPRequest> {
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	private Deque<HTTPRequest> queue;
	
	public HTTPRequestFormatter(Deque<HTTPRequest> queue) {
		this.queue = queue;
	}

	@SuppressWarnings("unchecked")
	@Override
	public ReadableContainer<ByteBuffer> format(HTTPRequest message) {
		logger.debug("< " + message.getMethod() + " " + message.getTarget());
		byte [] firstLine = (message.getMethod() + " " + URIUtils.encodeURI(message.getTarget()) + " HTTP/" + message.getVersion() + "\r\n").getBytes(Charset.forName("ASCII"));

		// no content, just write the ending
		if (message.getContent() == null) {
			return IOUtils.chain(true, IOUtils.wrap(firstLine, true), IOUtils.wrap("\r\n".getBytes(Charset.forName("ASCII")), true));
		}
		
		PullableMimeFormatter formatter = new PullableMimeFormatter();
		formatter.setOptimizeCompression(true);
		formatter.setIncludeMainContentTrailingLineFeeds(false);
		formatter.setAllowBinary(true);
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
		return IOUtils.chain(true, IOUtils.wrap(firstLine, true), formatter);
	}
}
