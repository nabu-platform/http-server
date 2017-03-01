package be.nabu.libs.http.server.nio;

import java.io.IOException;
import java.nio.charset.Charset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.nio.api.MessageFormatter;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.mime.impl.PullableMimeFormatter;

public class HTTPResponseFormatter implements MessageFormatter<HTTPResponse> {
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	@SuppressWarnings("unchecked")
	@Override
	public ReadableContainer<ByteBuffer> format(HTTPResponse message) {
		byte [] firstLine = ("HTTP/" + message.getVersion() + " " + message.getCode() + " " + message.getMessage() + "\r\n").getBytes(Charset.forName("ASCII"));

		// no content, just write the ending
		if (message.getContent() == null) {
			return IOUtils.chain(true, IOUtils.wrap(firstLine, true), IOUtils.wrap("\r\n".getBytes(Charset.forName("ASCII")), true));
		}
		
		PullableMimeFormatter formatter = new PullableMimeFormatter();
		// internet explorer does not support header folding
		formatter.setFoldHeader(false);
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
		return IOUtils.chain(true, IOUtils.wrap(firstLine, true), formatter);
	}
}
