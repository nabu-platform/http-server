package be.nabu.libs.http.server.nio;

import java.io.Closeable;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.server.EnrichingMessageDataProvider;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.libs.nio.api.MessageParser;
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
import be.nabu.utils.mime.util.ChunkedWritableByteContainer;

/**
 * TODO Optimization: search straight in the bytes of the dynamic for the combination "\r\n\r\n" (or \n\n) before parsing the request+headers
 * Unlikely to do much though as standard packets are up to 64kb in size and standard headers are 0.5-2kb in size in general so they should almost always arrive in a single block
 */
public class HTTPMessageParser implements MessageParser<ModifiablePart> {

	public static final int COPY_SIZE = 8192;
	
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
	private int maxChunkSize = 81920;
	private Long contentLength;
	private String method;
	private String target;
	private Integer code;
	private String message;
	private double version;
	
	private boolean isClosed;
	private boolean isDone;
	/**
	 * Whether we are parsing a response or a request, default is a request
	 */
	private boolean isResponse;
	
	/**
	 * When writing to the backend, do we want to include the headers?
	 * In most cases yes because they tell you something about the payload
	 * But in some cases you may simply want to store the payload
	 */
	private boolean includeHeaders = true;
	
	public HTTPMessageParser(MessageDataProvider dataProvider) {
		this(dataProvider, false);
	}
	
	public HTTPMessageParser(MessageDataProvider dataProvider, boolean isResponse) {
		this.dataProvider = dataProvider;
		this.isResponse = isResponse;
		initialBuffer = new DynamicByteBuffer();
		initialBuffer.mark();
	}
	
	@Override
	public void push(PushbackContainer<ByteBuffer> content) throws IOException, ParseException {
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
				if (request.contains("%00")) {
					throw new ParseException("Request line contains encoded NUL character, this is not allowed", 0);
				}
				initialBuffer.remark();
				if (isResponse) {
					if (!request.startsWith("HTTP/")) {
						throw new ParseException("Could not parse response line: " + request, 0);
					}
					int firstSpaceIndex = request.indexOf(' ');
					int secondSpaceIndex = request.indexOf(' ', firstSpaceIndex + 1);
					if (firstSpaceIndex < 0 || secondSpaceIndex < 0) {
						throw new ParseException("Could not parse response line: " + request, 0);
					}
					version = new Double(request.substring(0, firstSpaceIndex).replaceFirst("HTTP/", "").trim());
					code = new Integer(request.substring(firstSpaceIndex + 1, secondSpaceIndex));
					message = request.substring(secondSpaceIndex + 1);
				}
				else {
					int firstSpaceIndex = request.indexOf(' ');
					int httpIndex = request.lastIndexOf("HTTP/");
					
					if (firstSpaceIndex < 0 || httpIndex < 0) {
						throw new ParseException("Could not parse request line: " + request, 0);
					}
					method = request.substring(0, firstSpaceIndex);
					// @2017-05-14: not a clue why the replace is there
//					target = request.substring(firstSpaceIndex + 1, httpIndex).trim().replaceFirst("[/]{2,}", "/");
					target = request.substring(firstSpaceIndex + 1, httpIndex).trim();
					version = new Double(request.substring(httpIndex).replaceFirst("HTTP/", "").trim());
					if (logger.isDebugEnabled()) {
						logger.debug("[INBOUND] " + (isResponse ? "Response" : "Request") + " (" + hashCode() + ") first line: {}", request);
					}
				}
			}
		}
		if (request != null && headers == null && !isDone()) {
			// reset so we see all the data
			initialBuffer.reset();
			ByteBuffer limitedBuffer = ByteBufferFactory.getInstance().limit(initialBuffer, null, maxHeaderSize - initialBuffer.remainingData());
			isClosed |= content.read(limitedBuffer) == -1;
			
			// it is possible to have a request without headers
			long peek = initialBuffer.peek(IOUtils.wrap(pair, false));
			if ((peek == 2 && pair[0] == '\r' && pair[1] == '\n') || (peek >= 1 && pair[0] == '\n')) {
				if (allowWithoutHeaders(method)) {
					isDone = true;
					// skip past the linefeed
					initialBuffer.skip(pair[0] == '\r' ? 2 : 1);
					// push everything else back
					content.pushback(initialBuffer);
					// set an empty part nonetheless for additional metadata later on
					part = new PlainMimeEmptyPart(null);
				}
				else {
					throw new ParseException("No headers found for the method '" + method + "'", 3);
				}
			}
			else {
				ReadableContainer<CharBuffer> data = new ReadableStraightByteToCharContainer(initialBuffer);
				try {
					headers = MimeUtils.readHeaders(data, true);
				}
				catch (ParseException e) {
					// it is possible that we received partial headers because they were cut in two by the transport layer
					// this problem occurred at 2017-09-25 where the proxy injected many "new" headers which presumably went over the packet size of the transport layer
					// partial headers bumped into a parse exception for MimeHeader.parseHeader()
					// this bubbled up, causing the message framer to close the connection and a retry on the proxy side
					// we set the error offset to 1 for that particular error to be able to recognize it
					// other parse exceptions should bubble up
					if (e.getErrorOffset() != 1) {
						throw e;
					}
				}
				// if we did not find the headers in the allotted space, throw an exception
				if (headers == null && limitedBuffer.remainingSpace() == 0) {
					throw new HTTPException(431, "No headers found within the size limit: " + maxHeaderSize + " bytes");
				}
				else if (headers != null) {
					initialBuffer.unmark();
					if (logger.isDebugEnabled()) {
						logger.debug("[INBOUND] " + (isResponse ? "Response" : "Request") + " (" + hashCode() + ") headers: {}", Arrays.asList(headers));
					}
				}
			}
		}
		parse: if (headers != null && !isDone()) {
			if (resource == null) {
				contentLength = MimeUtils.getContentLength(headers);
				if (contentLength == null) {
					String transferEncoding = MimeUtils.getTransferEncoding(headers);
					// if no content length or encoding, we don't have an (allowed) content, check if this is ok for the method
					if (transferEncoding == null && allowWithoutContent(method)) {
						isDone = true;
						part = new PlainMimeEmptyPart(null, headers);
						content.pushback(initialBuffer);
						break parse;
					}
					else if (!"chunked".equals(transferEncoding)) {
						// throw the exception code for length required
						throw new HTTPException(411, "No content-length provided and not using chunked");
					}
					chunked = new ChunkedReadableByteContainer(initialBuffer);
					chunked.setMaxChunkSize(maxChunkSize);
				}
				else if (contentLength == 0) {
					isDone = true;
					part = new PlainMimeEmptyPart(null, headers);
					content.pushback(initialBuffer);
					break parse;
				}
				resource = getDataProvider().newResource(method, target, version, headers);
				if (resource == null) {
					throw new ParseException("No data provider available for '" + request + "', headers: " + Arrays.asList(headers), 2);
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
				if (chunked != null) {
					writable = new ChunkedWritableByteContainer(writable, false);
				}
			}
			// @2017-03-22: the loop that follows assumes that the initial buffer contains at most contentLength amount of data, this is true if the headers are sent separately which is almost always the case (99.999% of the time)
			// if however you send it as one tcp packet and there is more data, the loop breaks due to bad design at line 231 (should use local var) and 255 (don't write the entire buffer but a limited version)
			// this almost never occurs but if it does, the message never gets finished and the selection key will trigger indefinitely for the EOS to be read!! quick fix is the following if
			// proper refactor needed at some point but it is complex code so needs proper testing
			if (contentLength != null && initialBuffer.remainingData() > contentLength) {
				content.pushback(initialBuffer);
			}
			long read = 0;
			while (!isDone() && (initialBuffer.remainingData() > 0 || (read = content.read(ByteBufferFactory.getInstance().limit(initialBuffer, null, Math.min(COPY_SIZE, contentLength == null && chunked != null ? Long.MAX_VALUE : contentLength - totalRead)))) > 0)) {
				totalRead += initialBuffer.remainingData();
				if (chunked != null) {
					long chunkRead = IOUtils.copy(chunked, writable, copyBuffer);
					// if the chunk is done, stop
					if (chunked.isFinished()) {
						Header[] additionalHeaders = chunked.getAdditionalHeaders();
						// add a content length header for information
						if (additionalHeaders == null || MimeUtils.getContentLength(additionalHeaders) == null) {
							List<Header> finalHeaders = new ArrayList<Header>();
							finalHeaders.addAll(Arrays.asList(additionalHeaders));
							finalHeaders.add(new MimeHeader("Content-Length", "" + totalChunkRead));
							additionalHeaders = finalHeaders.toArray(new Header[finalHeaders.size()]);
						}
						((ChunkedWritableByteContainer) writable).finish(additionalHeaders);
						writable.close();
						// whether or not we send the headers along to the parser depends on whether or not they are stored in the resource already
						part = includeHeaders ? new MimeParser().parse((ReadableResource) resource) : new MimeParser().parse((ReadableResource) resource, headers);
						isDone = true;
					}
					else {
						totalChunkRead += chunkRead;
					}
				}
				else {
					if (initialBuffer.remainingData() != writable.write(initialBuffer)) {
						throw new HTTPException(413, "The backing resource does not have enough space");
					}
					// we have reached the end
					if (totalRead == contentLength) {
						if (logger.isDebugEnabled()) {
							logger.debug("[INBOUND] " + (isResponse ? "Response" : "Request") + " (" + hashCode() + ") finished reading {} bytes", totalRead - initialBuffer.remainingData());
						}
						writable.close();
						// whether or not we send the headers along to the parser depends on whether or not they are stored in the resource already
						part = includeHeaders ? new MimeParser().parse((ReadableResource) resource) : new MimeParser().parse((ReadableResource) resource, headers);
						isDone = true;
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
			HTTPUtils.setHeader(part, ServerHeader.RESOURCE_URI, ((LocatableResource) resource).getUri().toString());
		}
		else if (part != null) {
			part.removeHeader(ServerHeader.RESOURCE_URI.getName());
		}
		
		// set the timestamp that it was received
		if (part != null) {
			part.setHeader(new MimeHeader(ServerHeader.REQUEST_RECEIVED.getName(), HTTPUtils.formatDate(new Date())));
		}
		
		// it is possible that the message provider did something to the resource it managed that altered the part that came back from the parsing
		// in this can we can enrich it again to be the original part
		// for example the broker message provider will stream directly to the filesystem but will strip the authorization header
		if (part != null && getDataProvider() instanceof EnrichingMessageDataProvider) {
			((EnrichingMessageDataProvider) getDataProvider()).enrich(part, method, target, version, headers);
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
	
	public String getMessageText() {
		return message;
	}
	
	public Integer getCode() {
		return code;
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

	public int getMaxInitialLineLength() {
		return maxInitialLineLength;
	}

	public void setMaxInitialLineLength(int maxInitialLineLength) {
		this.maxInitialLineLength = maxInitialLineLength;
	}

	public int getMaxHeaderSize() {
		return maxHeaderSize;
	}

	public void setMaxHeaderSize(int maxHeaderSize) {
		this.maxHeaderSize = maxHeaderSize;
	}

	public int getMaxChunkSize() {
		return maxChunkSize;
	}

	public void setMaxChunkSize(int maxChunkSize) {
		this.maxChunkSize = maxChunkSize;
	}
	
}
