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
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.core.DefaultHTTPRequest;
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

	private static final int BUFFER_SIZE = 40960;
	private static String CHANNEL_TYPE_CLIENT = "client";
    private static String CHANNEL_TYPE_SERVER = "server";
    private static String CHANNEL_TYPE = "channelType";
    private ServerSocketChannel channel;
    private HTTPProcessor processor;
    private MessageDataProvider dataProvider;

	private Map<SocketChannel, RequestProcessor> requestProcessors = new HashMap<SocketChannel, RequestProcessor>();
	private SSLServerMode serverMode;
	
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
		
		Selector selector = Selector.open();
		SelectionKey socketServerSelectionKey = channel.register(selector, SelectionKey.OP_ACCEPT);
		
		Map<String, String> properties = new HashMap<String, String>();
        properties.put(CHANNEL_TYPE, CHANNEL_TYPE_SERVER);
        socketServerSelectionKey.attach(properties);
        
        while(true) {
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
			        					requestProcessors.remove(clientChannel);
			        				}
		        				}
		        			}
		        			else if (key.isReadable() && requestProcessors.containsKey(clientChannel)) {
		        				RequestProcessor requestProcessor = requestProcessors.get(clientChannel);
		        				if (requestProcessor != null) {
		        					requestProcessor.schedule();
		        				}
		        			}
		        		}
	        		}
	        		catch(CancelledKeyException e) {
	        			if (requestProcessors.containsKey(key.channel())) {
		        			synchronized(requestProcessors) {
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

		private WritableContainer<ByteBuffer> writable;
		private Queue<HTTPRequest> queue = new ArrayDeque<HTTPRequest>();
		private boolean isScheduled = false;
		private boolean isClosed;
		private RequestProcessor requestProcessor;

		public ResponseProcessor(RequestProcessor requestProcessor, SocketChannel clientChannel, WritableContainer<ByteBuffer> writable) {
			super(clientChannel);
			this.requestProcessor = requestProcessor;
			this.writable = writable;
		}
		
		@Override
		public void run() {
			if (getChannel().isConnected()) {
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
				writable.flush();
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

		public boolean isClosed() {
			return isClosed;
		}

		public void setClosed(boolean isClosed) {
			this.isClosed = isClosed;
		}
	}
	
	public class RequestProcessor extends SocketChannelProcessor {
		
		private PushbackContainer<ByteBuffer> readable;
		private HTTPMessageFramer framer;
		private ResponseProcessor responseProcessor;
		private boolean hasRead = true;
		private SSLSocketByteContainer sslContainer;

		public RequestProcessor(SocketChannel clientChannel) throws SSLException {
			super(clientChannel);
			Container<ByteBuffer> wrap = new SocketByteContainer(clientChannel);
			if (sslContext != null) {
				sslContainer = new SSLSocketByteContainer(wrap, sslContext, serverMode);
				wrap = sslContainer;
			}
			this.readable = IOUtils.pushback(IOUtils.bufferReadable(wrap, IOUtils.newByteBuffer(BUFFER_SIZE, true)));
			this.responseProcessor = new ResponseProcessor(this, clientChannel, IOUtils.bufferWritable(wrap, IOUtils.newByteBuffer(BUFFER_SIZE, true)));
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
							framer = new HTTPMessageFramer(dataProvider);
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
							request = new DefaultHTTPRequest(framer.getMethod(), framer.getTarget(), framer.getMessage());
							framer = null;
						}
						else if (framer.isClosed()) {
							closeConnection = true;
						}
					}
					if (request != null) {
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

	public MessageDataProvider getDataProvider() {
		return dataProvider;
	}

	public void setDataProvider(MessageDataProvider dataProvider) {
		this.dataProvider = dataProvider;
	}
	
}
