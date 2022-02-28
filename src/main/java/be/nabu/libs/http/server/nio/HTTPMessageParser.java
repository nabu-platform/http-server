package be.nabu.libs.http.server.nio;

import java.io.Closeable;
import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventTarget;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.server.EnrichingMessageDataProvider;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.libs.nio.PipelineUtils;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.api.StreamingMessageParser;
import be.nabu.libs.nio.impl.MessagePipelineImpl;
import be.nabu.libs.resources.api.LocatableResource;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.WritableResource;
import be.nabu.utils.cep.api.EventSeverity;
import be.nabu.utils.cep.impl.CEPUtils;
import be.nabu.utils.cep.impl.NetworkedComplexEventImpl;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.CharBuffer;
import be.nabu.utils.io.api.DelimitedCharContainer;
import be.nabu.utils.io.api.EventfulSubscriber;
import be.nabu.utils.io.api.EventfulSubscription;
import be.nabu.utils.io.api.PushbackContainer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.io.api.WritableContainer;
import be.nabu.utils.io.buffers.bytes.ByteBufferFactory;
import be.nabu.utils.io.buffers.bytes.CyclicByteBuffer;
import be.nabu.utils.io.buffers.bytes.DynamicByteBuffer;
import be.nabu.utils.io.containers.EventfulContainerImpl;
import be.nabu.utils.io.containers.SynchronizedReadableContainer;
import be.nabu.utils.io.containers.SynchronizedWritableContainer;
import be.nabu.utils.io.containers.chars.ReadableStraightByteToCharContainer;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.api.ModifiableContentPart;
import be.nabu.utils.mime.api.ModifiablePart;
import be.nabu.utils.mime.impl.MimeContentTransferTranscoder;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeParser;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeContentPart;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;
import be.nabu.utils.mime.util.ChunkedReadableByteContainer;
import be.nabu.utils.mime.util.ChunkedWritableByteContainer;

/**
 * TODO Optimization: search straight in the bytes of the dynamic for the combination "\r\n\r\n" (or \n\n) before parsing the request+headers
 * Unlikely to do much though as standard packets are up to 64kb in size and standard headers are 0.5-2kb in size in general so they should almost always arrive in a single block
 */
public class HTTPMessageParser implements StreamingMessageParser<ModifiablePart> {

	public static final int COPY_SIZE = 8192;
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	private DynamicByteBuffer initialBuffer;
	private Header [] headers;
	private MessageDataProvider dataProvider;
	private Resource resource;
	private WritableContainer<ByteBuffer> writable, chunkedWritable;
	private ChunkedReadableByteContainer chunked;
	private ModifiablePart part;
	private byte [] pair = new byte[2];
	private ByteBuffer copyBuffer = new CyclicByteBuffer(COPY_SIZE);
	private boolean allowNoContent = Boolean.parseBoolean(System.getProperty("http.allowNoContent", "true"));
	
	// this refers to rfc2616 article 4.4 Message Length option 5
	// i have only ever seen this in the wild in a single situation
	// the behavior (if you disable this) is that the parser assumes there is no content and you get an empty response
	private boolean allowNoMessageSizeForClosedConnections;
	private boolean streamingMode = false;
	private volatile boolean streamingDone = false;
	
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
	
	private ByteBuffer streamingBuffer;
	
	/**
	 * When writing to the backend, do we want to include the headers?
	 * In most cases yes because they tell you something about the payload
	 * But in some cases you may simply want to store the payload
	 */
	private boolean includeHeaders = true;

	private EventTarget eventTarget;
	
	// set when we encounter a response (this can not be the case in requests!) that has no content length or chunked transfer encoding but the connection is marked as close
	private boolean isUnlimitedResponse;
	
	public HTTPMessageParser(MessageDataProvider dataProvider, EventTarget target) {
		this(dataProvider, false, target);
	}
	
	public HTTPMessageParser(MessageDataProvider dataProvider, boolean isResponse, EventTarget eventTarget) {
		this(dataProvider, isResponse, eventTarget, false);
	}
	
	public HTTPMessageParser(MessageDataProvider dataProvider, boolean isResponse, EventTarget eventTarget, boolean streamingMode) {
		this.dataProvider = dataProvider;
		this.isResponse = isResponse;
		this.eventTarget = eventTarget;
		this.streamingMode = streamingMode;
		initialBuffer = new DynamicByteBuffer();
		initialBuffer.mark();
		
		// no content length _and_ no chunked is only allowed if you set connection close (otherwise you don't know when it ends)
		// this in turn is only possible as a response, because if the request was immediately closed, the server could never send back a response
		allowNoMessageSizeForClosedConnections = isResponse;
	}
	
	private void report(EventSeverity severity, String eventName, String code, String message, Exception e, String reason) {
		if (eventTarget != null) {
			try {
				Pipeline pipeline = PipelineUtils.getPipeline();
				NetworkedComplexEventImpl event = CEPUtils.newServerNetworkEvent(getClass(), eventName, pipeline == null ? null : pipeline.getSourceContext().getSocketAddress(), message, e);
				event.setCode(code);
				event.setSeverity(severity);
				event.setApplicationProtocol("HTTP");
				event.setReason(reason);
				eventTarget.fire(event, this);
			}
			catch (Exception f) {
				logger.warn("Could not register event", f);
			}
		}
	}
	
	@SuppressWarnings("resource")
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
					report(EventSeverity.WARNING, "http-parse", "NO-FIRST-LINE", "Could not find initial line in first " + maxInitialLineLength + " bytes", null, "Received: " + request);
					throw new HTTPException(414);
				}
				request = null;
			}
			else {
				// it really should end with a \r at this point
				if (request.endsWith("\r")) {
					request = request.substring(0, request.length() - 1);
				}
				if (request.contains("%00")) {
					report(EventSeverity.WARNING, "http-parse", "NUL", "Request/response line contains encoded NUL character", null, "Received: " + request);
					throw new ParseException("Request line contains encoded NUL character, this is not allowed", 0);
				}
				initialBuffer.remark();
				if (isResponse) {
					if (!request.startsWith("HTTP/")) {
						report(EventSeverity.WARNING, "http-parse", "RESPONSE-LINE", "Could not parse response line: " + request, null, "Received: " + request);
						throw new ParseException("Could not parse response line: " + request, 0);
					}
					int firstSpaceIndex = request.indexOf(' ');
					int secondSpaceIndex = request.indexOf(' ', firstSpaceIndex + 1);
					if (firstSpaceIndex < 0) {
						report(EventSeverity.WARNING, "http-parse", "RESPONSE-LINE", "Could not parse response line: " + request, null, "Received: " + request);
						throw new ParseException("Could not parse response line: " + request, 0);
					}
					version = new Double(request.substring(0, firstSpaceIndex).replaceFirst("HTTP/", "").trim());
					code = new Integer(secondSpaceIndex < 0 ? request.substring(firstSpaceIndex + 1) : request.substring(firstSpaceIndex + 1, secondSpaceIndex));
					// according to the spec, the "reason phrase" is actually optional
					if (secondSpaceIndex >= 0) {
						message = request.substring(secondSpaceIndex + 1).trim();
					}
					else {
						message = HTTPCodes.getMessage(code);
					}
				}
				else {
					int firstSpaceIndex = request.indexOf(' ');
					int httpIndex = request.lastIndexOf("HTTP/");
					
					if (firstSpaceIndex < 0 || httpIndex < 0) {
						report(EventSeverity.WARNING, "http-parse", "REQUEST-LINE", "Could not parse request line: " + request, null, "Received: " + request);
						throw new ParseException("Could not parse request line: " + request, 0);
					}
					method = request.substring(0, firstSpaceIndex);
					// @2017-05-14: not a clue why the replace is there
//					target = request.substring(firstSpaceIndex + 1, httpIndex).trim().replaceFirst("[/]{2,}", "/");
					target = request.substring(firstSpaceIndex + 1, httpIndex).trim();
					version = new Double(request.substring(httpIndex).replaceFirst("HTTP/", "").trim());
				}
				if (logger.isDebugEnabled()) {
					logger.debug("[INBOUND] " + (isResponse ? "Response" : "Request") + " (" + hashCode() + ") first line: {}", request);
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
					report(EventSeverity.WARNING, "http-parse", "NO-HEADERS", "No headers found for method: " + method, null, null);
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
						report(EventSeverity.WARNING, "http-parse", "INCORRECT-HEADERS", "Could not parse the headers", e, null);
						throw e;
					}
				}
				// if we did not find the headers in the allotted space, throw an exception
				if (headers == null && limitedBuffer.remainingSpace() == 0) {
					report(EventSeverity.WARNING, "http-parse", "LONG-HEADERS", "No headers found within the size limit of " + maxHeaderSize + " bytes", null, null);
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
		if (!streamingMode) {
			parse: if (headers != null && !isDone()) {
				if (resource == null) {
					contentLength = MimeUtils.getContentLength(headers);
					if (contentLength == null) {
						String transferEncoding = MimeUtils.getTransferEncoding(headers);
						Header connection = MimeUtils.getHeader("Connection", headers);
						
						if ("chunked".equals(transferEncoding)) {
							chunked = new ChunkedReadableByteContainer(initialBuffer);
							chunked.setMaxChunkSize(maxChunkSize);
						}
						// if we allow no message size for closed connections, we need to double check that the connection will be closed
						else if (allowNoMessageSizeForClosedConnections && connection != null && connection.getValue() != null && connection.getValue().equalsIgnoreCase("close")) {
							isUnlimitedResponse = true;
						}
						// if no content length or encoding, we don't have an (allowed) content, check if this is ok for the method
						else if (transferEncoding == null && allowWithoutContent(method)) {
							isDone = true;
							part = new PlainMimeEmptyPart(null, headers);
							content.pushback(initialBuffer);
							break parse;
						}
						else {
							report(EventSeverity.WARNING, "http-parse", "NO-CONTENT-LENGTH", "No content-length provided and not using chunked", null, null);
							// throw the exception code for length required
							throw new HTTPException(411, "No content-length provided and not using chunked");
						}
					}
					else if (contentLength == 0) {
						isDone = true;
						part = new PlainMimeEmptyPart(null, headers);
						content.pushback(initialBuffer);
						break parse;
					}
					resource = getDataProvider().newResource(method, target, version, headers);
					if (resource == null) {
						report(EventSeverity.WARNING, "http-parse", "NO-DATA-PROVIDER", "No data provider available for: " + request + ", headers: " + Arrays.asList(headers), null, null);
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
				// @2020-07-30: added an exception for messages without length or chunked but that are closed. if they are already closed at this point, we have read the entire message, pushback the data
				if ((contentLength != null && initialBuffer.remainingData() > contentLength) || (isUnlimitedResponse && isClosed)) {
					content.pushback(initialBuffer);
				}
				long read = 0;
				while (!isDone() && (initialBuffer.remainingData() > 0 || (read = content.read(ByteBufferFactory.getInstance().limit(initialBuffer, null, Math.min(COPY_SIZE, contentLength == null && (chunked != null || isUnlimitedResponse) ? Long.MAX_VALUE : contentLength - totalRead)))) > 0)) {
					totalRead += initialBuffer.remainingData();
					if (chunked != null) {
						long chunkRead = IOUtils.copy(chunked, writable, copyBuffer);
						// if the chunk is done, stop
						if (chunked.isFinished()) {
							if (chunkRead > 0) {
								totalChunkRead += chunkRead;
							}
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
							// the resource "should" be backed and re-readable, we are not in streaming mode
							if (part instanceof ModifiableContentPart) {
								((ModifiableContentPart) part).setReopenable(true);
							}
						}
						else {
							totalChunkRead += chunkRead;
						}
					}
					else {
						if (initialBuffer.remainingData() != writable.write(initialBuffer)) {
							report(EventSeverity.WARNING, "http-parse", "TOO-BIG", "The backing resource does not have enough space for: " + contentLength, null, null);
							throw new HTTPException(413, "The backing resource does not have enough space");
						}
						// we have reached the end
						if ((contentLength != null && totalRead == contentLength) || (isUnlimitedResponse && (isClosed || read == -1))) {
							if (logger.isDebugEnabled()) {
								logger.debug("[INBOUND] " + (isResponse ? "Response" : "Request") + " (" + hashCode() + ") finished reading {} bytes", totalRead - initialBuffer.remainingData());
							}
							writable.close();
							// whether or not we send the headers along to the parser depends on whether or not they are stored in the resource already
							part = includeHeaders ? new MimeParser().parse((ReadableResource) resource) : new MimeParser().parse((ReadableResource) resource, headers);
							isDone = true;
							// the resource "should" be backed and re-readable, we are not in streaming mode
							if (part instanceof ModifiableContentPart) {
								((ModifiableContentPart) part).setReopenable(true);
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
			
			// always remove these headers, they should not be coming from the client, not even in a proxy situation
			if (part != null) {
				part.removeHeader(ServerHeader.RESOURCE_URI.getName());
				part.removeHeader(ServerHeader.REQUEST_RECEIVED.getName());
			}
			
			if (part != null && resource instanceof LocatableResource) {
				HTTPUtils.setHeader(part, ServerHeader.RESOURCE_URI, ((LocatableResource) resource).getUri().toString());
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
		else {
			parse: if (headers != null && !isDone) {
				contentLength = MimeUtils.getContentLength(headers);
				if (contentLength == null) {
					String transferEncoding = MimeUtils.getTransferEncoding(headers);
					// if no content length or encoding, we don't have an (allowed) content, check if this is ok for the method
					if (transferEncoding == null && allowWithoutContent(method)) {
						isDone = true;
						part = new PlainMimeEmptyPart(null, headers);
						content.pushback(initialBuffer);
						streamingDone = true;
						break parse;
					}
					else if (!"chunked".equals(transferEncoding)) {
						report(EventSeverity.WARNING, "http-parse", "NO-CONTENT-LENGTH", "No content-length provided and not using chunked", null, null);
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
					streamingDone = true;
					break parse;
				}
				// quick fix, see above
				if (contentLength != null && initialBuffer.remainingData() > contentLength) {
					content.pushback(initialBuffer);
				}
				// we make the buffer a lot bigger than we actually ship back and forth so we have some room left for overreads (especially for chunked), we also need some wiggle room for gzip finalization...
				streamingBuffer = IOUtils.newByteBuffer(COPY_SIZE * 6, true);
				Lock lock = new ReentrantLock();
				isDone = true;
				// if we get here, we have a content part, either regular or chunked
//				part = new RetrievedContentPart(new SynchronizedReadableContainer<ByteBuffer>(streamingBuffer, lock), headers);
				writable = streamingBuffer;
//				if (chunked != null) {
//					writable = new ChunkedWritableByteContainer(writable, false);
//					chunkedWritable = writable;
//				}
				String contentEncoding = MimeUtils.getContentEncoding(headers);
				MimeContentTransferTranscoder transcoder = new MimeContentTransferTranscoder();
				// we reverse the order because we are doing it on write now instead of read
				// decode any content encoding (e.g. gzip)
//				writable = transcoder.decodeContent(contentEncoding, writable);
//				// decode any transfer encoding (e.g. base64)
//				writable = transcoder.decodeTransfer(MimeUtils.getContentTransferEncoding(headers), writable);
//				writable = transcoder.decodeContent(MimeUtils.getTransferEncoding(headers), writable);
				writable = new SynchronizedWritableContainer<ByteBuffer>(writable, lock);

				ReadableContainer<ByteBuffer> readable = streamingBuffer;
				readable = transcoder.decodeContent(contentEncoding, readable);
				readable = new SynchronizedReadableContainer<ByteBuffer>(readable, lock);
				
				// we wrap events around them
				EventfulContainerImpl<ByteBuffer> eventfulContainer = new EventfulContainerImpl<ByteBuffer>(IOUtils.wrap(readable, writable));
				writable = eventfulContainer;
				
				// if new space opens up on the container (by act of reading it), pump new data to it
				Pipeline pipeline = PipelineUtils.getPipeline();
				eventfulContainer.availableSpace(new EventfulSubscriber() {
					@Override
					public void on(EventfulSubscription subscription) {
//						System.out.println("--------------------------------> [" + pipeline.hashCode() + "] triggering available data " + streamingBuffer.remainingData() + " / " + initialBuffer.remainingData() + " / " + isDone + " && " + streamingDone);
//						if (streamingBuffer.remainingData() == 0 && !streamingDone) {
//							pipeline.getServer().setReadInterest(pipeline.getSelectionKey(), true);
//							pipeline.read();
//						}
						// if the streaming is not done and we have some room left, we want more information
						if (streamingBuffer.remainingSpace() > COPY_SIZE * 5 && !streamingDone) {
							if (pipeline instanceof MessagePipelineImpl) {
								((MessagePipelineImpl<?, ?>) pipeline).registerReadInterest();
							}
							pipeline.read();
						}
					}
				});
				
				part = new PlainMimeContentPart(null, eventfulContainer, headers);
				// if we have some form of content encoding, we will undo it and redo it, ending up with a potentially different size
				// set it from fixed contentlength (if any) to chunked (if not set)
				if (contentEncoding != null && contentLength != null) {
					part.removeHeader("Content-Length");
					if (chunked == null) {
						part.setHeader(new MimeHeader("Transfer-Encoding", "chunked"));
					}
				}
			}
			// if we are done but the streaming isn't, we need to copy more data into the streamingbuffer (if we can)
			if (isDone && !streamingDone) {
				long read = 0;
				// we want to keep some space open in the streamingbuffer at all times (for the chunked finalization bits)
				while (!streamingDone && streamingBuffer.remainingSpace() > COPY_SIZE * 5 && 
						(initialBuffer.remainingData() > 0 || (read = content.read(ByteBufferFactory.getInstance().limit(initialBuffer, null, Math.min(streamingBuffer.remainingSpace() - (COPY_SIZE * 5), contentLength == null && chunked != null ? Long.MAX_VALUE : contentLength - totalRead)))) > 0)) {
					totalRead += initialBuffer.remainingData();

					if (chunked != null) {
						long chunkRead = IOUtils.copy(chunked, writable, copyBuffer);
						// if the chunk is done, stop
						if (chunked.isFinished()) {
							if (chunkRead > 0) {
								totalChunkRead += chunkRead;
							}
							if (copyBuffer.remainingData() > 0) {
								totalChunkRead += writable.write(copyBuffer);
							}
							if (copyBuffer.remainingData() == 0) {
//								System.out.println("-----------------------------------> [" + PipelineUtils.getPipeline().hashCode() + "] Remaining space for finalization: " + streamingBuffer.remainingSpace());
//								System.out.println("-----------------------------------> [" + PipelineUtils.getPipeline().hashCode() + "] Read from source: " + (totalRead - initialBuffer.remainingData()));
//								System.out.println("-----------------------------------> [" + PipelineUtils.getPipeline().hashCode() + "] Chunk read: " + totalChunkRead + " => " + chunked.read(copyBuffer));
								Header[] additionalHeaders = chunked.getAdditionalHeaders();
								// add a content length header for information
								if (additionalHeaders == null || MimeUtils.getContentLength(additionalHeaders) == null) {
									List<Header> finalHeaders = new ArrayList<Header>();
									finalHeaders.addAll(Arrays.asList(additionalHeaders));
									finalHeaders.add(new MimeHeader("Content-Length", "" + totalChunkRead));
									additionalHeaders = finalHeaders.toArray(new Header[finalHeaders.size()]);
								}
								// flush the writable so we can add headers at a lower level
	//							writable.flush();
	//							((ChunkedWritableByteContainer) chunkedWritable).finish(additionalHeaders);
//								writable.close();
								streamingBuffer.close();
								streamingDone = true;
							}
						}
						else {
							totalChunkRead += chunkRead;
						}
					}
					else {
						// this might not write all the data in the buffer
						writable.write(initialBuffer);
						// we have reached the end
						if ((totalRead - initialBuffer.remainingData()) == contentLength) {
							if (logger.isDebugEnabled()) {
								logger.debug("[INBOUND] " + (isResponse ? "Response" : "Request") + " (" + hashCode() + ") finished reading {} bytes", totalRead - initialBuffer.remainingData());
							}
							writable.close();
							streamingDone = true;
						}
					}
					// don't take anything into account that is not processed
					totalRead -= initialBuffer.remainingData();
				}
				// push back whatever remains after streaming
				if (streamingDone && initialBuffer.remainingData() > 0) {
					content.pushback(initialBuffer);
				}
				// if we got to the end, signal the closure and the streaming that is done
				if (read <= -1) {
					isClosed = true;
					streamingDone = true;
				}
			}
		}
	}
	
	private boolean allowWithoutContent(String method) {
		if (allowNoContent) {
			return true;
		}
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
		if (streamingMode) {
			// unset on read, we don't want to keep retriggering, bit of a dirty hack...
			ModifiablePart part = this.part;
			this.part = null;
			return part;
		}
		else {
			return part;
		}
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

	@Override
	public boolean isStreamed() {
		// if we do not operate in streaming mode, it is always done streaming
		if (!streamingMode) {
			return true;
		}
		return streamingDone;
	}

	@Override
	public boolean isStreaming() {
		return streamingMode;
	}
	
}
