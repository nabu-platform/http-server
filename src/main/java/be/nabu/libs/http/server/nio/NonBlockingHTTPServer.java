package be.nabu.libs.http.server.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.UnknownFrameException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPServer;
import be.nabu.libs.http.api.server.MessageFramer;
import be.nabu.libs.http.api.server.MessageFramerFactory;
import be.nabu.libs.http.core.HTTPFormatter;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.server.HTTPProcessor;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.SSLServerMode;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.Container;
import be.nabu.utils.io.api.PushbackContainer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.io.api.WritableContainer;
import be.nabu.utils.io.buffers.bytes.ByteBufferFactory;
import be.nabu.utils.io.containers.BufferedWritableContainer;
import be.nabu.utils.io.containers.bytes.SSLSocketByteContainer;
import be.nabu.utils.io.containers.bytes.SocketByteContainer;
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.impl.FormatException;

public class NonBlockingHTTPServer implements HTTPServer {

	private EventDispatcher eventDispatcher;
	private int port;
	private SSLContext sslContext;
	private Logger logger = LoggerFactory.getLogger(getClass());
	private ExecutorService executors;

	private static final int BUFFER_SIZE = 512000;
	private static String CHANNEL_TYPE_CLIENT = "client";
    private static String CHANNEL_TYPE_SERVER = "server";
    private static String CHANNEL_TYPE = "channelType";
    private ServerSocketChannel channel;
    private HTTPProcessor processor;
    private MessageFramerFactory<HTTPRequest> framerFactory;

	private Map<SocketChannel, RequestProcessor> requestProcessors = new HashMap<SocketChannel, RequestProcessor>();
	private Map<RequestProcessor, Boolean> requestWriteInterest = new HashMap<RequestProcessor, Boolean>();
	private Map<RequestProcessor, SelectionKey> requestSelectionKeys = new HashMap<RequestProcessor, SelectionKey>();
	private SSLServerMode serverMode;
	private Selector selector;
	
	public NonBlockingHTTPServer(int port, int poolSize, EventDispatcher eventDispatcher) {
		this.port = port;
		this.eventDispatcher = eventDispatcher;
		this.executors = Executors.newFixedThreadPool(poolSize);
		this.processor = new HTTPProcessor(eventDispatcher);
	}
	
	public NonBlockingHTTPServer(SSLContext sslContext, SSLServerMode serverMode, int port, int poolSize, EventDispatcher eventDispatcher) {
		this(port, poolSize, eventDispatcher);
		this.sslContext = sslContext;
		this.serverMode = serverMode;
	}
	
	@Override
	public EventDispatcher getEventDispatcher() {
		return eventDispatcher;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void start() throws IOException {
		channel = ServerSocketChannel.open();
		channel.bind(new InetSocketAddress(port));		// new InetSocketAddress("localhost", port)
		channel.configureBlocking(false);
		
		selector = Selector.open();
		SelectionKey socketServerSelectionKey = channel.register(selector, SelectionKey.OP_ACCEPT);
		
		Map<String, String> properties = new HashMap<String, String>();
        properties.put(CHANNEL_TYPE, CHANNEL_TYPE_SERVER);
        socketServerSelectionKey.attach(properties);
        
        while(true) {
        	// note that we _don't_ subscribe to OP_WRITE continuously as the channels are nearly always ready to write (unless the buffer is full) so it would continuously trigger events
        	// so we need to intelligently update to subscribe to OP_WRITE if we have application-level buffered data and unsubscribe if the application level buffer is empty
        	// however this really should be done from the thread that is running the selector otherwise naive implementations might hang indefinitely while updating the actual interestops
        	// for this reason we have the boolean map containing the requests to turn it on and off
        	if (!requestWriteInterest.isEmpty()) {
        		synchronized(requestWriteInterest) {
        			Iterator<RequestProcessor> iterator = requestWriteInterest.keySet().iterator();
        			while (iterator.hasNext()) {
        				RequestProcessor processor = iterator.next();
        				// if we want a new interest, add it
        				if (requestSelectionKeys.containsKey(processor)) {
        					if (requestWriteInterest.get(processor)) {
        						requestSelectionKeys.get(processor).interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        					}
        					else {
        						requestSelectionKeys.get(processor).interestOps(SelectionKey.OP_READ);
        					}
        				}
        				iterator.remove();
        			}
        		}
        	}
        	// the selectNow() does NOT block whereas the select() does
        	// we want to make sure we update the interestOps() as soon as possible, we can't do a fully blocking wait here, unregistered write ops would simply be ignored
        	// the selectNow() however ends up in a permanent while loop and takes up 100% of at least one thread
        	// luckily the selector provides a wakeup() which unblocks the select() from another thread, this combines low overhead with quick interestops() updates
        	if (selector.select() == 0) {
        		continue;
        	}
        	Set<SelectionKey> selectedKeys = selector.selectedKeys();
        	Iterator<SelectionKey> iterator = selectedKeys.iterator();
        	while(iterator.hasNext()) {
        		SelectionKey key = iterator.next();
        		try {
	        		try {
		        		if (((Map<String, String>) key.attachment()).get(CHANNEL_TYPE).equals(CHANNEL_TYPE_SERVER)) {
		        			ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
		        			SocketChannel clientSocketChannel = serverSocketChannel.accept();
		        			if (clientSocketChannel != null) {
		        				clientSocketChannel.configureBlocking(false);
		        				SelectionKey clientKey = clientSocketChannel.register(selector, SelectionKey.OP_READ);
		        				Map<String, String> clientproperties = new HashMap<String, String>();
		                        clientproperties.put(CHANNEL_TYPE, CHANNEL_TYPE_CLIENT);
		                        clientKey.attach(clientproperties);
		                        if (!requestProcessors.containsKey(clientSocketChannel)) {
			                        synchronized(requestProcessors) {
			                        	if (!requestProcessors.containsKey(clientSocketChannel)) {
				                        	try {
				                        		requestProcessors.put(clientSocketChannel, new RequestProcessor(clientSocketChannel));
				                        		requestSelectionKeys.put(requestProcessors.get(clientSocketChannel), clientKey);
				                        	}
				                        	catch (SSLException e) {
				                        		logger.error("Failed SSL connection", e);
				                        		clientSocketChannel.close();
				                        	}
			                        	}
			                        }
		                        }
		        			}
		        		}
		        		else {
		        			SocketChannel clientChannel = (SocketChannel) key.channel();
		        			if (!requestProcessors.containsKey(clientChannel)) {
		        				key.cancel();
		        			}
		        			else if (!clientChannel.isConnected() || !clientChannel.isOpen() || clientChannel.socket().isInputShutdown()) {
		        				key.cancel();
		        				if (requestProcessors.containsKey(clientChannel)) {
			        				synchronized(this) {
			        					requestSelectionKeys.remove(requestProcessors.get(clientChannel));
			        					requestProcessors.remove(clientChannel);
			        				}
		        				}
		        			}
		        			else {
		        				if (key.isReadable() && requestProcessors.containsKey(clientChannel)) {
			        				RequestProcessor requestProcessor = requestProcessors.get(clientChannel);
			        				if (requestProcessor != null) {
			        					requestProcessor.schedule();
			        				}
		        				}
			        			if (key.isWritable() && requestProcessors.containsKey(clientChannel)) {
			        				requestProcessors.get(clientChannel).responseProcessor.schedule();
			        			}
		        			}
		        		}
	        		}
	        		catch(CancelledKeyException e) {
	        			if (requestProcessors.containsKey(key.channel())) {
		        			synchronized(requestProcessors) {
		        				requestSelectionKeys.remove(requestProcessors.get(key.channel()));
		        				requestProcessors.remove(key.channel());
		        			}
	        			}
	        			key.channel().close();
	        		}
        		}
        		catch (Exception e) {
        			e.printStackTrace();
        		}
        		finally {
        			iterator.remove();
        		}
        	}
        }
	}

	@Override
	public void stop() {
		try {
			channel.close();
		}
		catch (IOException e) {
			logger.error("Failed to close server", e);
		}
	}
	
	public abstract class SocketChannelProcessor implements Closeable, Runnable {
		
		private SocketChannel channel;

		public SocketChannelProcessor(SocketChannel channel) {
			this.channel = channel;
		}
		
		public SocketChannel getChannel() {
			return channel;
		}

		@Override
		public void close() {
			synchronized(requestProcessors) {
				requestSelectionKeys.remove(requestProcessors.get(channel));
				requestProcessors.remove(channel);
			}
			try {
				channel.close();
			}
			catch (IOException e) {
				// suppress, a failed close can not be "fixed"
			}
		}
	}
	
	public class ResponseProcessor extends SocketChannelProcessor {

		private BufferedWritableContainer<ByteBuffer> writable;
		private Queue<HTTPRequest> queue = new ArrayDeque<HTTPRequest>();
		private boolean isScheduled = false;
		private boolean isClosed;
		private RequestProcessor requestProcessor;

		public ResponseProcessor(RequestProcessor requestProcessor, SocketChannel clientChannel, WritableContainer<ByteBuffer> writable) {
			super(clientChannel);
			this.requestProcessor = requestProcessor;
			this.writable = new BufferedWritableContainer<ByteBuffer>(writable, ByteBufferFactory.getInstance().newInstance(BUFFER_SIZE, true));
			this.writable.setAllowPartialFlush(true);
		}
		
		@Override
		public void run() {
			if (getChannel().isConnected()) {
				if (writable.getActualBufferSize() > 0) {
					try {
						flush();
						// still needs to be flushed, stop
						if (writable.getActualBufferSize() > 0) {
							synchronized(this) {
								isScheduled = false;
							}
							return;
						}
					}
					catch (IOException e) {
						logger.error("Could not flush buffer to target", e);
						synchronized(this) {
							isScheduled = false;
						}
						return;
					}
				}
				boolean closeConnection = false;
				while(true) {
					HTTPRequest request;
					synchronized(this) {
						request = queue.poll();
						if (request == null) {
							isScheduled = false;
							break;
						}
					}
					try {
						try {
							boolean keepAlive = true;
							if (request.getContent() != null) {
								keepAlive = HTTPUtils.keepAlive(request);
							}
							HTTPResponse response = processor.process(
								sslContext, 
								requestProcessor.sslContainer == null ? null : requestProcessor.sslContainer.getPeerCertificates(), 
								request, 
								getChannel().socket().getInetAddress().getHostName(),
								getChannel().socket().getPort()
							);
							if (keepAlive && response.getContent() != null) {
								keepAlive = HTTPUtils.keepAlive(response);
							}
							else if (keepAlive && response.getCode() >= 300) {
								keepAlive = false;
							}
							try {
								write(response);
							}
							finally {
								if (response.getContent() instanceof ContentPart) {
									ReadableContainer<ByteBuffer> contentReadable = ((ContentPart) response.getContent()).getReadable();
									if (contentReadable != null) {
										contentReadable.close();
									}
								}
							}
							if (!keepAlive) {
								closeConnection = true;
							}
						}
						catch (FormatException e) {
							throw new HTTPException(400, e);
						}
						// capture unexpected exceptions
						catch (Exception e) {
							throw new HTTPException(500, e);
						}
						catch (Throwable e) {
							logger.error("Error occurred", e);
							throw new HTTPException(500, e);
						}
					}
					catch (HTTPException e) {
						closeConnection = true;
						if (getChannel().socket().isConnected() && !getChannel().socket().isClosed() && !getChannel().socket().isOutputShutdown() && getChannel().socket().isBound()) {
							try {
								write(processor.createError(e));
							}
							catch (FormatException fe) {
								// could not send back response...
							}
							catch (IOException ie) {
								// can't recover
							}
						}
					}
				}
				if (closeConnection) {
					close();
				}
			}
		}

		private void write(HTTPResponse response) throws IOException, FormatException {
			if (!isClosed() && getChannel().isConnected() && !getChannel().socket().isOutputShutdown()) {
				new HTTPFormatter().formatResponse(response, writable);
				flush();
			}
		}

		public void push(HTTPRequest request) {
			synchronized(this) {
				queue.add(request);
				if (!isScheduled) {
					isScheduled = true;
					executors.submit(this);
				}
			}
		}
		
		public void schedule() {
			synchronized(this) {
				if (!isScheduled) {
					isScheduled = true;
					executors.submit(this);
				}
			}
		}
		
		public void flush() throws IOException {
			writable.flush();
			// still data in the buffer, add an interest in write ops so we can complete this write
			if (writable.getActualBufferSize() > 0) {
				synchronized(requestWriteInterest) {
					requestWriteInterest.put(requestProcessor, true);
					selector.wakeup();
				}
			}
			// deregister interest in write ops otherwise it will cycle endlessly (it is almost always writable)
			else {
				synchronized(requestWriteInterest) {
					requestWriteInterest.put(requestProcessor, false);
				}
			}
		}

		public boolean isClosed() {
			return isClosed;
		}

		public void setClosed(boolean isClosed) {
			this.isClosed = isClosed;
		}
	}
	
	public class RequestProcessor extends SocketChannelProcessor {
		
		private PushbackContainer<ByteBuffer> readable;
		private MessageFramer<HTTPRequest> framer;
		private ResponseProcessor responseProcessor;
		private boolean hasRead = true;
		private SSLSocketByteContainer sslContainer;

		public RequestProcessor(SocketChannel clientChannel) throws SSLException {
			super(clientChannel);
			SocketByteContainer socketByteContainer = new SocketByteContainer(clientChannel);
			socketByteContainer.setBufferOutput(true);
			Container<ByteBuffer> wrap = socketByteContainer;
			if (sslContext != null) {
				sslContainer = new SSLSocketByteContainer(wrap, sslContext, serverMode);
				wrap = sslContainer;
			}
			this.readable = IOUtils.pushback(IOUtils.bufferReadable(wrap, IOUtils.newByteBuffer(BUFFER_SIZE, true)));
			this.responseProcessor = new ResponseProcessor(this, clientChannel, wrap);
		}

		public void schedule() {
			if (hasRead) {
				synchronized(this) {
					if (hasRead) {
						hasRead = false;
						executors.submit(this);
					}
				}
			}
		}
		
		@Override
		public void run() {
			if (getChannel().isConnected()) {
				HTTPRequest request = null;
				boolean closeConnection = false;
				try {
					synchronized(this) {
						if (framer == null) {
							framer = getFramerFactory().newFramer();
						}
						try {
							framer.push(readable);
						}
						catch (SSLException e) {
							logger.error("SSL Exception", e);
							close();
							return;
						}
						catch (IOException e) {
							throw new HTTPException(500, e);
						}
						catch (UnknownFrameException e) {
							throw new HTTPException(400, e);
						}
						hasRead = true;
						if (framer.isDone()) {
							request = framer.getMessage();
							framer = null;
						}
						else if (framer.isClosed()) {
							closeConnection = true;
						}
					}
					if (request != null) {
						logger.trace("Parsed request {}", request);
						responseProcessor.push(request);
					}
				}
				catch (HTTPException e) {
					closeConnection = true;
					if (getChannel().socket().isConnected() && !getChannel().socket().isClosed() && !getChannel().socket().isOutputShutdown() && getChannel().socket().isBound()) {
						try {
							responseProcessor.write(processor.createError(e));
						}
						catch (FormatException fe) {
							// could not send back response...
						}
						catch (IOException ie) {
							// can't recover
						}
					}
				}
				catch (Exception e) {
					closeConnection = true;
					logger.error("Could not recover from error", e);
				}
				finally {
					if (closeConnection) {
						close();
						responseProcessor.setClosed(true);
					}
				}
			}
		}
		
		@Override
		public int hashCode() {
			return getChannel().hashCode();
		}
		
		@Override
		public boolean equals(Object object) {
			return object instanceof RequestProcessor && ((RequestProcessor) object).getChannel().equals(getChannel());
		}
	}

	public MessageFramerFactory<HTTPRequest> getFramerFactory() {
		if (framerFactory == null) {
			framerFactory = new HTTPRequestFramerFactory(new MemoryMessageDataProvider());
		}
		return framerFactory;
	}

	public void setFramerFactory(MessageFramerFactory<HTTPRequest> framerFactory) {
		this.framerFactory = framerFactory;
	}
}
