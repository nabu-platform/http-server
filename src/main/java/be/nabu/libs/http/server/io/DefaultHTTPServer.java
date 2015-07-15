package be.nabu.libs.http.server.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.ParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPServer;
import be.nabu.libs.http.core.DefaultDynamicResourceProvider;
import be.nabu.libs.http.core.HTTPFormatter;
import be.nabu.libs.http.core.HTTPParser;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.server.HTTPProcessor;
import be.nabu.libs.resources.api.DynamicResourceProvider;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;
import be.nabu.utils.io.api.WritableContainer;
import be.nabu.utils.io.containers.EOFReadableContainer;
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.api.ExpectContinueHandler;
import be.nabu.utils.mime.impl.AlwaysContinue;
import be.nabu.utils.mime.impl.FormatException;

/**
 * A basic HTTP server using synchronous I/O
 */
public class DefaultHTTPServer implements HTTPServer {
	
	private ExecutorService threadPool;
	private ServerSocket socket;
	
	private EventDispatcher eventDispatcher;
	
	private ExpectContinueHandler expectContinueHandler;

	private SSLContext sslContext;
	private DynamicResourceProvider dynamicResourceProvider;
	private Integer socketTimeout;
	
	private HTTPProcessor processor;
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	private int poolSize;
	
	public DefaultHTTPServer(int port, int poolSize, EventDispatcher eventDispatcher) throws IOException {
		this(new ServerSocket(port), poolSize, eventDispatcher);
	}
	
	public DefaultHTTPServer(SSLContext sslContext, int port, int poolSize, EventDispatcher eventDispatcher) throws IOException {
		this(sslContext.getServerSocketFactory().createServerSocket(port), poolSize, eventDispatcher);
		this.sslContext = sslContext;
	}
	
	public DefaultHTTPServer(ServerSocket socket, int poolSize, EventDispatcher eventDispatcher) {
		this.socket = socket;
		this.poolSize = poolSize;
		this.eventDispatcher = eventDispatcher;
		this.processor = new HTTPProcessor(eventDispatcher);
	}
	
	@Override
	public void start() throws IOException {
		threadPool = Executors.newFixedThreadPool(poolSize);
		while (!Thread.interrupted() && !threadPool.isShutdown()) {
			Socket client = socket.accept();
			if (socketTimeout != null) {
				client.setSoTimeout(socketTimeout);
			}
			threadPool.execute(new ClientHandler(client));
		}
	}
	
	@Override
	public void stop() {
		threadPool.shutdown();
	}
	
	public ExpectContinueHandler getExpectContinueHandler() {
		if (expectContinueHandler == null) {
			expectContinueHandler = new AlwaysContinue();
		}
		return expectContinueHandler;
	}

	public void setExpectContinueHandler(ExpectContinueHandler expectContinueHandler) {
		this.expectContinueHandler = expectContinueHandler;
	}

	private class ClientHandler implements Runnable {
		
		private Socket socket;
		private HTTPParser parser;
		private HTTPFormatter formatter;
		
		public ClientHandler(Socket socket) {
			this.socket = socket;
			this.parser = new HTTPParser(getDynamicResourceProvider(), true);
			this.formatter = new HTTPFormatter();
		}
		
		@Override
		public void run() {
			try {
				EOFReadableContainer<ByteBuffer> readable = new EOFReadableContainer<ByteBuffer>(
					IOUtils.wrap(new BufferedInputStream(socket.getInputStream()))
				);
				WritableContainer<ByteBuffer> writable = IOUtils.wrap(new BufferedOutputStream(socket.getOutputStream()));
				try {
					while(!Thread.currentThread().isInterrupted() && socket.isConnected() && !socket.isClosed() && !socket.isInputShutdown() && socket.isBound() && !readable.isEOF()) {
						boolean keepAlive = true;
						try {
							HTTPRequest request = parser.parseRequest(readable, expectContinueHandler);
							if (request != null) {
								logger.debug("< socket:" + socket.hashCode() + " [request:" + request.hashCode() + "] " + request.getMethod() + ": " + request.getTarget());
								if (request.getContent() != null) {
									keepAlive = HTTPUtils.keepAlive(request);
								}
								HTTPResponse response = processor.process(sslContext, null, request, socket.getInetAddress().getHostName(), socket.getPort());
								if (keepAlive && response.getContent() != null) {
									keepAlive = HTTPUtils.keepAlive(response);
								}
								else if (keepAlive && response.getCode() >= 400) {
									keepAlive = false;
								}
								try {
									formatter.formatResponse(response, writable);
								}
								finally {
									if (response.getContent() instanceof ContentPart) {
										ReadableContainer<ByteBuffer> contentReadable = ((ContentPart) response.getContent()).getReadable();
										if (contentReadable != null) {
											contentReadable.close();
										}
									}
								}
							}
							writable.flush();
							if (!keepAlive) {
								break;
							}
						}
						catch (ParseException e) {
							throw new HTTPException(400, e);
						}
						catch (FormatException e) {
							throw new HTTPException(500, e);
						}
						catch (HTTPException e) {
							throw e;
						}
						catch (Exception e) {
							throw new HTTPException(500, e);
						}
					}
				}
				catch (HTTPException e) {
					logger.error("Could not execute request", e);
					// if the client is still connected, report the exception to him
					if (socket.isConnected() && !socket.isClosed() && !socket.isOutputShutdown() && socket.isBound()) {
						formatter.formatResponse(processor.createError(e), writable);
						writable.flush();
					}
				}
			}
			catch (IOException e) {
				// it happens...
				logger.error("Could not send back response", e);
			}
			catch (FormatException e) {
				// at this point it might be wise to panic
				logger.error("Could not send back response", e);
			}
			finally {
				try {
					socket.close();
				}
				catch (IOException e) {
					// suppress
				}
			}
		}
	}
	
	
	@Override
	public EventDispatcher getEventDispatcher() {
		return eventDispatcher;
	}

	public DynamicResourceProvider getDynamicResourceProvider() {
		if (dynamicResourceProvider == null) {
			dynamicResourceProvider = new DefaultDynamicResourceProvider();
		}
		return dynamicResourceProvider;
	}

	public void setDynamicResourceProvider(DynamicResourceProvider dynamicResourceProvider) {
		this.dynamicResourceProvider = dynamicResourceProvider;
	}
}
