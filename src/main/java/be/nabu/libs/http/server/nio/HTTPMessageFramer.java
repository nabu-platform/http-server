package be.nabu.libs.http.server.nio;

import java.io.Closeable;
import java.io.IOException;
import java.text.ParseException;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.UnknownFrameException;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.api.server.MessageFramer;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.libs.resources.api.LocatableResource;
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
import be.nabu.utils.io.buffers.bytes.CyclicByteBuffer;
import be.nabu.utils.io.buffers.bytes.DynamicByteBuffer;
import be.nabu.utils.io.containers.chars.ReadableStraightByteToCharContainer;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.api.ModifiablePart;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeParser;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;
import be.nabu.utils.mime.util.ChunkedReadableByteContainer;

/**
 * TODO Optimization: search straight in the bytes of the dynamic for the combination "\r\n\r\n" (or \n\n) before parsing the request+headers
 * Unlikely to do much though as standard packets are up to 64kb in size and standard headers are 0.5-2kb in size in general so they should almost always arrive in a single block
 */
public class HTTPMessageFramer implements MessageFramer<ModifiablePart> {

	public static final int COPY_SIZE = 4096;
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	private DynamicByteBuffer initialBuffer;
	private Header [] headers;
	private MessageDataProvider dataProvider;
	private Resource resource;
	private WritableContainer<ByteBuffer> writable;
	private ChunkedReadableByteContainer chunked;
	private ModifiablePart part;
	private byte [] pair = new byte[2];
	private ByteBuffer copyBuffer = new CyclicByteBuffer(COPY_SIZE);
	
	private String request;
	private long totalRead, totalChunkRead;
	private int maxInitialLineLength = 4096;
	private int maxHeaderSize = 8192;
	private int maxChunkSize = 8192;
	private Long contentLength;
	private String method;
	private String target;
	private double version;
	
	private boolean isClosed;
	private boolean isDone;
	
	/**
	 * When writing to the backend, do we want to include the headers?
	 * In most cases yes because they tell you something about the payload
	 * But in some cases you may simply want to store the payload
	 */
	private boolean includeHeaders = true;
	
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
				// if we have reached the maximum size for the request and not found one, throw an exception
				if (request.length() >= maxInitialLineLength) {
					throw new HTTPException(414);
				}
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
				logger.debug("Request: {}", request);
			}
		}
		if (request != null && headers == null && !isDone()) {
			// reset so we see all the data
			try {
				initialBuffer.reset();
				ByteBuffer limitedBuffer = ByteBufferFactory.getInstance().limit(initialBuffer, null, maxHeaderSize - initialBuffer.remainingData());
				isClosed |= content.read(limitedBuffer) == -1;
				
				// it is possible to have a request without headers
				long peek = initialBuffer.peek(IOUtils.wrap(pair, false));
				if (allowWithoutHeaders(method) && ((peek == 2 && pair[0] == '\r' && pair[1] == '\n') || (peek >= 1 && pair[0] == '\n'))) {
					isDone = true;
					// skip past the linefeed
					initialBuffer.skip(pair[0] == '\r' ? 2 : 1);
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
						logger.trace("Headers: {}", Arrays.asList(headers));
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
						// throw the exception code for length required
						throw new HTTPException(411);
					}
					chunked = new ChunkedReadableByteContainer(initialBuffer);
					chunked.setMaxChunkSize(maxChunkSize);
				}
				else if (contentLength == 0) {
					isDone = true;
					part = new PlainMimeEmptyPart(null, headers);
					content.pushback(initialBuffer);
					return;
				}
				resource = getDataProvider().newResource(method, target, version, headers);
				if (resource == null) {
					throw new UnknownFrameException("No data provider available for '" + request + "', headers: " + Arrays.asList(headers));
				}
			}
			if (writable == null) {
				writable = ((WritableResource) resource).getWritable();
				if (includeHeaders) {
					for (Header header : headers) {
						writable.write(IOUtils.wrap(header.toString().getBytes("ASCII"), true));
						writable.write(IOUtils.wrap("\r\n".getBytes("ASCII"), true));
					}
					writable.write(IOUtils.wrap("\r\n".getBytes("ASCII"), true));
				}
			}
			long read = 0;
			while (!isDone() && (initialBuffer.remainingData() > 0 || (read = content.read(ByteBufferFactory.getInstance().limit(initialBuffer, null, Math.min(COPY_SIZE, contentLength == null && chunked != null ? Long.MAX_VALUE : contentLength - totalRead)))) > 0)) {
				totalRead += initialBuffer.remainingData();
				if (chunked != null) {
					long chunkRead = IOUtils.copy(chunked, writable, copyBuffer);
					// if the chunk is done, stop
					if (chunked.isFinished()) {
						// switch the transfer encoding with a length header
						for (int i = 0; i < headers.length; i++) {
							if (headers[i].getName().equalsIgnoreCase("Transfer-Encoding")) {
								headers[i] = new MimeHeader("Content-Length", "" + totalChunkRead);
								break;
							}
						}
						writable.close();
						try {
							// whether or not we send the headers along to the parser depends on whether or not they are stored in the resource already
							part = includeHeaders ? new MimeParser().parse((ReadableResource) resource) : new MimeParser().parse((ReadableResource) resource, headers);
							isDone = true;
						}
						catch (ParseException e) {
							throw new UnknownFrameException(e);
						}
					}
					else {
						totalChunkRead += chunkRead;
					}
				}
				else {
					if (initialBuffer.remainingData() != writable.write(initialBuffer)) {
						throw new IOException("The backing resource does not have enough space");
					}
					// we have reached the end
					if (totalRead == contentLength) {
						logger.trace("Finished reading {} bytes", totalRead);
						writable.close();
						try {
							// whether or not we send the headers along to the parser depends on whether or not they are stored in the resource already
							part = includeHeaders ? new MimeParser().parse((ReadableResource) resource) : new MimeParser().parse((ReadableResource) resource, headers);
							isDone = true;
						}
						catch (ParseException e) {
							throw new UnknownFrameException(e);
						}
					}
				}
				// don't take anything into account that is not processed
				totalRead -= initialBuffer.remainingData();
			}
			if (isDone() && initialBuffer.remainingData() > 0) {
				content.pushback(initialBuffer);
			}
			if (read == -1) {
				isClosed = true;
			}
		}
		if (part != null && resource instanceof LocatableResource) {
			HTTPUtils.setHeader(part, ServerHeader.RESOURCE_URI, ((LocatableResource) resource).getURI().toString());
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

	@Override
	public boolean isClosed() {
		return isClosed;
	}

	public MessageDataProvider getDataProvider() {
		if (dataProvider == null) {
			dataProvider = new MemoryMessageDataProvider();
		}
		return dataProvider;
	}

	public Header[] getOriginalHeaders() {
		return headers;
	}
}
