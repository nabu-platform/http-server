package be.nabu.libs.http.server.nio;

import java.io.Closeable;
import java.io.IOException;
import java.text.ParseException;

import be.nabu.libs.http.UnknownFrameException;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.api.server.MessageFramer;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.WritableResource;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.CharBuffer;
import be.nabu.utils.io.api.DelimitedCharContainer;
import be.nabu.utils.io.api.PushbackContainer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.io.api.WritableContainer;
import be.nabu.utils.io.buffers.bytes.ByteBufferFactory;
import be.nabu.utils.io.buffers.bytes.DynamicByteBuffer;
import be.nabu.utils.io.containers.chars.ReadableStraightByteToCharContainer;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.api.ModifiablePart;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeParser;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;
import be.nabu.utils.mime.util.ChunkedReadableByteContainer;

public class HTTPMessageFramer implements MessageFramer<ModifiablePart> {

	private DynamicByteBuffer initialBuffer, chunkBuffer;
	private Header [] headers;
	private MessageDataProvider dataProvider;
	private Resource resource;
	private WritableContainer<ByteBuffer> writable;
	private ChunkedReadableByteContainer chunked;
	private ModifiablePart part;
	private byte [] single = new byte[1];
	
	private String request;
	private long totalRead;
	private int maxInitialLineLength = 4096;
	private int maxHeaderSize = 8192;
	private int maxChunkSize = 8192;
	private Long contentLength;
	private String method;
	private String target;
	private double version;
	
	private boolean isClosed;
	private boolean isDone;
	
	public HTTPMessageFramer(MessageDataProvider dataProvider) {
		this.dataProvider = dataProvider;
		initialBuffer = new DynamicByteBuffer();
		initialBuffer.mark();
	}
	
	@Override
	public void push(PushbackContainer<ByteBuffer> content) throws IOException, UnknownFrameException {
		if (request == null) {
			initialBuffer.reset();
			ByteBuffer limitedBuffer = ByteBufferFactory.getInstance().limit(initialBuffer, null, maxInitialLineLength - initialBuffer.remainingData());
			long read = content.read(limitedBuffer);
			isClosed |= read == -1;
			ReadableContainer<CharBuffer> data = new ReadableStraightByteToCharContainer(initialBuffer);
			DelimitedCharContainer delimit = IOUtils.delimit(data, "\n");
			request = IOUtils.toString(delimit);
			if (!delimit.isDelimiterFound()) {
				request = null;
			}
			else {
				initialBuffer.remark();
				int firstSpaceIndex = request.indexOf(' ');
				int httpIndex = request.lastIndexOf("HTTP/");
				
				if (firstSpaceIndex < 0 || httpIndex < 0) {
					throw new UnknownFrameException("Could not parse request line: " + request);
				}
				method = request.substring(0, firstSpaceIndex);
				target = request.substring(firstSpaceIndex + 1, httpIndex).trim().replaceFirst("[/]{2,}", "/");
				version = new Double(request.substring(httpIndex).replaceFirst("HTTP/", "").trim());
			}
		}
		if (request != null && headers == null && !isDone()) {
			// reset so we see all the data
			try {
				initialBuffer.reset();
				ByteBuffer limitedBuffer = ByteBufferFactory.getInstance().limit(initialBuffer, null, maxHeaderSize - initialBuffer.remainingData());
				isClosed |= content.read(limitedBuffer) == -1;
				
				// it is possible to have a request without headers
				if (allowWithoutHeaders(method) && initialBuffer.peek(IOUtils.wrap(single, false)) == 1 && single[0] == '\n') {
					isDone = true;
					// skip past the linefeed
					initialBuffer.skip(1);
					// push everything else back
					content.pushback(initialBuffer);
				}
				else {
					ReadableContainer<CharBuffer> data = new ReadableStraightByteToCharContainer(initialBuffer);
					headers = MimeUtils.readHeaders(data, false);
					// if we did not find the headers in the allotted space, throw an exception
					if (headers == null && limitedBuffer.remainingSpace() == 0) {
						throw new UnknownFrameException("No headers found within the size limit: " + maxHeaderSize + " bytes");
					}
					else if (headers != null) {
						initialBuffer.unmark();
					}
				}
			}
			catch (ParseException e) {
				throw new UnknownFrameException(e);
			}
		}
		if (headers != null && !isDone()) {
			if (resource == null) {
				contentLength = MimeUtils.getContentLength(headers);
				if (contentLength == null) {
					String transferEncoding = MimeUtils.getTransferEncoding(headers);
					// if no content length or encoding, we don't have an (allowed) content, check if this is ok for the method
					if (transferEncoding == null && allowWithoutContent(method)) {
						isDone = true;
						part = new PlainMimeEmptyPart(null, headers);
						content.pushback(initialBuffer);
						return;
					}
					else if (!"chunked".equals(transferEncoding)) {
						throw new UnknownFrameException("The HTTP frame does not contain a content length nor is it chunked");
					}
					chunked = new ChunkedReadableByteContainer(initialBuffer);
					chunked.setMaxChunkSize(maxChunkSize);
					chunkBuffer = new DynamicByteBuffer();
				}
				else if (contentLength == 0) {
					isDone = true;
					part = new PlainMimeEmptyPart(null, headers);
					content.pushback(initialBuffer);
					return;
				}
				resource = getDataProvider().newResource(request, headers);
				if (resource == null) {
					throw new UnknownFrameException("No data provider available for contentLength: " + contentLength);
				}
			}
			if (writable == null) {
				writable = ((WritableResource) resource).getWritable();
			}
			long read = content.read(contentLength == null ? initialBuffer : ByteBufferFactory.getInstance().limit(initialBuffer, null, contentLength - initialBuffer.remainingData()));
			if (read == -1) {
				isClosed = true;
			}
			if (contentLength == null || totalRead < contentLength) {
				if (chunked != null) {
					// if the chunk is done, stop
					long chunkRead = chunked.read(chunkBuffer);
					if (chunkRead == -1) {
						// switch the transfer encoding with a length header
						for (int i = 0; i < headers.length; i++) {
							if (headers[i].getName().equalsIgnoreCase("Transfer-Encoding")) {
								headers[i] = new MimeHeader("Content-Length", "" + totalRead);
								break;
							}
						}
						writable.close();
						try {
							part = new MimeParser().parse((ReadableResource) resource, headers);
							isDone = true;
						}
						catch (ParseException e) {
							throw new UnknownFrameException(e);
						}
					}
					else {
						totalRead += chunkBuffer.remainingData();
						if (chunkBuffer.remainingData() != writable.write(chunkBuffer)) {
							throw new IOException("The backing resource does not have enough space for the chunked stream");	
						}
					}
				}
				else {
					totalRead += initialBuffer.remainingData();
					if (initialBuffer.remainingData() != writable.write(initialBuffer)) {
						throw new IOException("The backing resource does not have enough space");
					}
					// we have reached the end
					if (totalRead == contentLength) {
						writable.close();
						try {
							part = new MimeParser().parse((ReadableResource) resource, headers);
							isDone = true;
						}
						catch (ParseException e) {
							throw new UnknownFrameException(e);
						}
					}
				}
			}
		}
	}
	
	private boolean allowWithoutContent(String method) {
		return allowWithoutHeaders(method);
	}
	
	private boolean allowWithoutHeaders(String method) {
		return "GET".equalsIgnoreCase(method)
			|| "HEAD".equalsIgnoreCase(method)
			|| "DELETE".equalsIgnoreCase(method);
	}

	@Override
	public boolean isIdentified() {
		return headers != null;
	}

	@Override
	public boolean isDone() {
		return isDone;
	}

	@Override
	public ModifiablePart getMessage() {
		return part;
	}

	@Override
	public void close() throws IOException {
		writable.close();
		if (resource instanceof Closeable) {
			((Closeable) resource).close();
		}
	}

	public String getMethod() {
		return method;
	}

	public String getTarget() {
		return target;
	}

	public double getVersion() {
		return version;
	}

	public boolean isClosed() {
		return isClosed;
	}

	public MessageDataProvider getDataProvider() {
		if (dataProvider == null) {
			dataProvider = new MemoryMessageDataProvider();
		}
		return dataProvider;
	}
}
