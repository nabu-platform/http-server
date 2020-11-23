package be.nabu.libs.http.client.nio;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.security.Principal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.events.api.EventSubscription;
import be.nabu.libs.http.HTTPInterceptorManager;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.NIOHTTPClient;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.client.nio.handlers.RedirectFollower;
import be.nabu.libs.http.client.nio.handlers.RequestFilter;
import be.nabu.libs.http.client.nio.handlers.ServerAuthenticator;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.nio.api.MessagePipeline;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.api.PipelineWithMetaData;
import be.nabu.libs.nio.api.StandardizedMessagePipeline;
import be.nabu.libs.nio.api.events.ConnectionEvent;
import be.nabu.libs.nio.api.events.ConnectionEvent.ConnectionState;
import be.nabu.libs.nio.impl.MessagePipelineImpl;
import be.nabu.libs.nio.impl.NIOClientImpl;
import be.nabu.utils.mime.api.ModifiableContentPart;
import be.nabu.utils.mime.impl.FormatException;

public class NIOHTTPClientImpl implements NIOHTTPClient {

	private NIOClientImpl client;
	private int maxConnectionsPerServer;
	private Map<HTTPRequest, HTTPResponseFuture> futures = Collections.synchronizedMap(new WeakHashMap<HTTPRequest, HTTPResponseFuture>());
	private HTTPClientPipelineFactory pipelineFactory;
	private Logger logger = LoggerFactory.getLogger(getClass());
	// default request timeout is 2 minutes
	private long requestTimeout = 1000l*60*2;
	// connection timeout after 30 seconds
	private long connectionTimeout = 1000l*30;
	// amount of retries to do if a request failed
	// failure in this case means we get an actual connection error (e.g. timeout etc) not a 500
	private int amountOfRetries = 0;
	
	private Map<String, Boolean> secure = Collections.synchronizedMap(new HashMap<String, Boolean>());
	private EventDispatcher dispatcher;
	private Thread thread;
	
	public NIOHTTPClientImpl(SSLContext sslContext, ExecutorService ioExecutors, ExecutorService processExecutors, int maxConnectionsPerServer, EventDispatcher dispatcher, MessageDataProvider messageDataProvider, CookieHandler cookieHandler, boolean streamingMode) {
		this.maxConnectionsPerServer = maxConnectionsPerServer;
		this.dispatcher = dispatcher;
		pipelineFactory = new HTTPClientPipelineFactory(this, cookieHandler, futures, dispatcher, messageDataProvider, streamingMode);
		this.client = new NIOClientImpl(sslContext, ioExecutors, processExecutors, pipelineFactory, dispatcher);
		startClient();
	}

	public NIOHTTPClientImpl(SSLContext sslContext, int ioPoolSize, int processPoolSize, int maxConnectionsPerServer, EventDispatcher dispatcher, MessageDataProvider messageDataProvider, CookieHandler cookieHandler, final ThreadFactory threadFactory) {
		this(sslContext, ioPoolSize, processPoolSize, maxConnectionsPerServer, dispatcher, messageDataProvider, cookieHandler, threadFactory, false);
	}
	
	public NIOHTTPClientImpl(SSLContext sslContext, int ioPoolSize, int processPoolSize, int maxConnectionsPerServer, EventDispatcher dispatcher, MessageDataProvider messageDataProvider, CookieHandler cookieHandler, final ThreadFactory threadFactory, boolean streamingMode) {
		this.maxConnectionsPerServer = maxConnectionsPerServer;
		this.dispatcher = dispatcher;
		pipelineFactory = new HTTPClientPipelineFactory(this, cookieHandler, futures, dispatcher, messageDataProvider, streamingMode);
		this.client = new NIOClientImpl(sslContext, ioPoolSize, processPoolSize, pipelineFactory, dispatcher, new ThreadFactory() {
			@Override
			public Thread newThread(Runnable r) {
				Thread thread = threadFactory.newThread(r);
				thread.setDaemon(true);
				return thread;
			}
		});
		startClient();
	}

	private void startClient() {
		thread = new Thread(new Runnable() {
			public void run() {
				try {
					client.start();
				}
				catch (Exception e) {
					logger.warn("Could not run http client", e);
				}
			}
		});
		thread.setDaemon(true);
		thread.start();
		Date date = new Date();
		try {
			// we clear the interrupted flag _before_ we start this loop, otherwise we can go straight into failure mode
			Thread.interrupted();
			while (!client.isStarted()) {
				if (new Date().getTime() - date.getTime() > 60000) {
					throw new RuntimeException("Could not start nio client in time");
				}
				try {
					Thread.sleep(50);
				}
				catch (InterruptedException e) {
					throw new RuntimeException("Interrupted while waiting for client to start");
				}
			}
		}
		catch (RuntimeException e) {
			client.stop();
			throw e;
		}
	}
	
	public NIOClientImpl getNIOClient() {
		return client;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public Future<HTTPResponse> call(HTTPRequest originalRequest, boolean secure) throws IOException, FormatException, ParseException {
		// allow interception of requests, we don't know whether or not it is reopeneable, only the one who created the request knows
		final HTTPRequest request = HTTPInterceptorManager.intercept(originalRequest);
		
		URI uri = HTTPUtils.getURI(request, secure);
		int port = uri.getPort();
		if (port < 0) {
			port = secure ? 443 : 80;
		}
		String host = uri.getAuthority();
		int indexOfColon = host.indexOf(':');
		if (indexOfColon >= 0) {
			String substring = host.substring(indexOfColon + 1);
			if (!substring.isEmpty()) {
				port = new Integer(substring);
			}
			host = host.substring(0, indexOfColon);
		}
		setSecure(host, port, secure);
		// find the pipeline with the least requests pending
		Pipeline pipeline = null;
		int currentQueueSize = 0;
		int openConnections = 0;
		HTTPResponseFuture future = futures.get(request);
		if (future == null) {
			future = new HTTPResponseFuture(request, secure);
			futures.put(request, future);
		}
		// make sure we get rid of stale connections first
		client.pruneConnections();
		
		HTTPClientPipelineFactory factory = ((HTTPClientPipelineFactory) client.getPipelineFactory());
		for (Pipeline possible : new ArrayList<Pipeline>(client.getPipelines())) {
			// we can only use pipelines that can process http requests
			if (!(possible instanceof StandardizedMessagePipeline) || !(((StandardizedMessagePipeline<?, ?>) possible).getResponseFormatterFactory() instanceof HTTPRequestFormatterFactory)) {
				continue;
			}
			Map<String, Object> metaData = ((PipelineWithMetaData) possible).getMetaData();
			String possibleHost = (String) metaData.get("host");
			Integer possiblePort = (Integer) metaData.get("port");
			// only pipelines to the given server are considered
			if (possibleHost.equals(host) && possiblePort.equals(port)) {
				openConnections++;
				Deque<HTTPRequest> pending = factory.getPending(possible);
				int queueSize = pending == null ? 0 : pending.size();
				if (pipeline == null || queueSize < currentQueueSize) {
					pipeline = possible;
					currentQueueSize = queueSize;
				}
				if (queueSize == 0) {
					break;
				}
			}
		}
		// if we don't have a pipeline yet or all pipelines are busy and we still have room for another connection, open a new one
		if (pipeline == null || (currentQueueSize > 0 && openConnections < maxConnectionsPerServer)) {
			logger.debug("Creating new pipeline [" + openConnections + "/" + maxConnectionsPerServer + "] to {}:{}", new Object[] { host, port });
			final HTTPResponseFuture finalFuture = future;
			final Future<Pipeline> pipelineFuture = client.connect(host, port);
			client.submitIOTask(new Runnable() {
				public void run() {
					try {
						Pipeline pipeline = pipelineFuture.get(connectionTimeout, TimeUnit.MILLISECONDS);
						finalFuture.setPipeline(pipeline);
						((MessagePipeline<HTTPResponse, HTTPRequest>) pipeline).getResponseQueue().add(request);
					}
					catch (Exception e) {
						logger.error("Could not add to queue", e);
						pipelineFuture.cancel(true);
						finalFuture.fail(e);
						throw new RuntimeException(e);
					}
				}
			});
		}
		else {
			logger.debug("Reusing existing pipeline to {}:{}", new Object[] { host, port });
			future.setPipeline(pipeline);
			((MessagePipeline<HTTPResponse, HTTPRequest>) pipeline).getResponseQueue().add(request);
		}
		return future;
	}

	
	public class HTTPResponseFuture implements Future<HTTPResponse> {

		private HTTPRequest request;
		private boolean secure;
		private volatile HTTPResponse response;
		private CountDownLatch latch = new CountDownLatch(1);
		private Pipeline pipeline;
		private boolean cancelled;
		private Throwable e;
		private EventSubscription<ConnectionEvent, Void> subscription;
		private volatile int retries;
		
		public HTTPResponseFuture(HTTPRequest request, boolean secure) {
			this.request = request;
			this.secure = secure;
		}
		
		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			cancelled = true;
			latch.countDown();
			if (subscription != null) {
				subscription.unsubscribe();
			}
			return cancelled;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean isDone() {
			return response != null;
		}

		@Override
		public HTTPResponse get() throws InterruptedException, ExecutionException {
			try {
				return get(365, TimeUnit.DAYS);
			}
			catch (TimeoutException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public HTTPResponse get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
			if (latch.await(timeout, unit)) {
				if (subscription != null) {
					subscription.unsubscribe();
				}
				if (response == null) {
					throw new RuntimeException("No response found", e);
				}
                return response;
            }
			else {
                throw new TimeoutException();
        	}
		}

		public HTTPResponse getResponse() {
			return response;
		}

		public void setResponse(HTTPResponse response) {
			if (this.response != null) {
				throw new IllegalStateException("A response has already been set");
			}
			// we should be backing the response with proper reopenable resources
			if (response.getContent() instanceof ModifiableContentPart) {
				((ModifiableContentPart) response.getContent()).setReopenable(true);
			}
			response = HTTPInterceptorManager.intercept(response);
			
			this.response = response;
			if (subscription != null) {
				subscription.unsubscribe();
				subscription = null;
			}
			latch.countDown();
		}

		public Pipeline getPipeline() {
			return pipeline;
		}

		public void setPipeline(final Pipeline pipeline) {
			this.pipeline = pipeline;
			if (pipeline != null) {
				subscription = pipeline.getServer().getDispatcher().subscribe(ConnectionEvent.class, new EventHandler<ConnectionEvent, Void>() {
					@Override
					public Void handle(ConnectionEvent event) {
						if (event.getState() == ConnectionState.CLOSED && pipeline.equals(event.getPipeline())) {
							((MessagePipelineImpl<?, ?>) pipeline).drainInput();
						}
						if (event.getState() == ConnectionState.EMPTY && pipeline.equals(event.getPipeline())) {
							subscription.unsubscribe();
							subscription = null;
							// if no response was returned, retry or count down the latch for fast fail.
							// the problem is as follows: the server sends back an error (e.g. a 404) with a close connection header in it
							// the server sends out all the bytes and promptly closes the connection
							// this gets triggered immediately on close, and the bytes have most likely already arrived but not been processed yet
							// that means we preemptively decide the server is not gonna send back a response and we get a ton of "No response found" exceptions because we close the latch before the response is processed
							// the retry itself has been generally experienced as evil anyway and should be handled (if relevant) by an outside party
//							if (response == null && !cancelled) {
//								retry(null);
//							}
							// if we didn't get a response _and_ the pipeline is empty (so nothing left to process), do an early cancel
							if (response == null && ((MessagePipeline<?, ?>) pipeline).getRequestQueue().isEmpty()) {
								// there is a chance that it has already left the request queue but it is still being processed, in fact it seems to be the case in +- 70% of our tests
								// so let's check the processing future
								Future<?> processFuture = ((MessagePipelineImpl<?, ?>) pipeline).getProcessFuture();
								if (processFuture == null || processFuture.isDone()) {
									logger.warn("We have no response and nothing is left to process, we are prematurely cancelling the http response future");
									cancel(true);
								}
								else {
									try {
										processFuture.get(30, TimeUnit.SECONDS);
									}
									catch (Exception e) {
										logger.warn("Timed out waiting for process future, we are cancelling the http response future", e);
										// do nothing
									}
									if (response == null) {
										cancel(true);
									}
								}
							}
						}
						return null;
					}

				});
			}
		}
		
		public void retry(Exception e) {
			if (retries < amountOfRetries) {
				retries++;
				client.submitIOTask(new Runnable() {
					@Override
					public void run() {
						try {
							call(request, secure);
						}
						catch (Exception e) {
							fail(e);
						}
					}
				});
			}
			else if (e != null) {
				fail(e);
			}
			else {
				cancel(true);
			}
		}
		
		public void fail(Throwable e) {
			this.e = e;
			this.cancel(true);
		}
	}
	
	public void setSecure(String host, int port, boolean secure) {
		this.secure.put(host + ":" + port, secure);
	}
	
	public Boolean getSecure(String host, int port) {
		return this.secure.get(host + ":" + port);
	}
	
	public static String getHost(URI uri) {
		String host = uri.getAuthority();
		int indexOfColon = host.indexOf(':');
		if (indexOfColon >= 0) {
			host = host.substring(0, indexOfColon);
		}
		return host;
	}
	
	public static int getPort(URI uri) {
		int port = uri.getPort();
		if (port < 0) {
			port = uri.getScheme().equals("http") ? 80 : 443;
		}
		String host = uri.getAuthority();
		int indexOfColon = host.indexOf(':');
		if (indexOfColon >= 0) {
			port = new Integer(host.substring(indexOfColon + 1));
		}
		return port;
	}
	
	public static boolean matches(HTTPRequest request, String host, int port) {
		try {
			return matches(HTTPUtils.getURI(request, false), host, port);
		}
		catch (FormatException e) {
			throw new RuntimeException(e);
		}
	}
	
	public static boolean matches(URI uri, String host, int port) {
		return getHost(uri).equals(host) && getPort(uri) == port;
	}

	@Override
	public HTTPResponse execute(HTTPRequest request, Principal principal, boolean secure, boolean followRedirects) throws IOException, FormatException, ParseException {
		URI uri = HTTPUtils.getURI(request, secure);
		String host = getHost(uri);
		int port = getPort(uri);
		setSecure(host, port, secure);
		// if we want to redirect, set a handler
		EventSubscription<HTTPResponse, HTTPRequest> redirectSubscription = null;
		if (followRedirects) {
			redirectSubscription = getDispatcher().subscribe(HTTPResponse.class, new RedirectFollower(this, 50));
//			redirectSubscription.filter(new HandlerFilter(host, port));
			// this works splendidly as long as the request itself is rewritten, not a new request is made
			// at that point we would need linkable requests or something
			// the alternative is the filter we used above but that can be too broad
			redirectSubscription.filter(new RequestFilter(request));
		}
		// if we have a principal, set a handler
		EventSubscription<HTTPResponse, HTTPRequest> authenticateSubscription = null;
		if (principal != null) {
			authenticateSubscription = getDispatcher().subscribe(HTTPResponse.class, new ServerAuthenticator(principal, new SPIAuthenticationHandler()));
//			authenticateSubscription.filter(new HandlerFilter(host, port));
			authenticateSubscription.filter(new RequestFilter(request));
		}
		// there is no real use in doing the intercept in the call() as it uses different threads to perform the actual execution
		Future<HTTPResponse> call = call(request, secure);
		try {
			return requestTimeout <= 0 ? call.get() : call.get(requestTimeout, TimeUnit.MILLISECONDS);
		}
		catch (Exception e) {
			// if the request failed for whatever reason (timeout for example), close the pipeline
			try {
				logger.warn("Closing pipeline for failed call: " + ((HTTPResponseFuture) call).getPipeline(), e);
				if (((HTTPResponseFuture) call).getPipeline() != null) {
					((HTTPResponseFuture) call).getPipeline().close();
				}
			}
			catch (Exception f) {
				logger.debug("Could not close failed connection", f);
			}
			throw new RuntimeException(e);
		}
		finally {
			if (redirectSubscription != null) {
				redirectSubscription.unsubscribe();
			}
			if (authenticateSubscription != null) {
				authenticateSubscription.unsubscribe();
			}
		}
	}

	public EventDispatcher getDispatcher() {
		return dispatcher;
	}

	@Override
	public void close() throws IOException {
		if (client.isStarted()) {
			client.stop();
		}
	}

	public long getRequestTimeout() {
		return requestTimeout;
	}

	public void setRequestTimeout(long requestTimeout) {
		this.requestTimeout = requestTimeout;
	}
	@Deprecated
	public int getAmountOfRetries() {
		return amountOfRetries;
	}
	@Deprecated
	public void setAmountOfRetries(int amountOfRetries) {
		this.amountOfRetries = amountOfRetries;
	}
	
}
