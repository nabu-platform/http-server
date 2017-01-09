package be.nabu.libs.http.client.nio;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.security.Principal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.EventSubscription;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.NonBlockingHTTPClient;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.http.client.nio.handlers.HandlerFilter;
import be.nabu.libs.http.client.nio.handlers.RedirectFollower;
import be.nabu.libs.http.client.nio.handlers.ServerAuthenticator;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.nio.api.MessagePipeline;
import be.nabu.libs.nio.api.NIOClient;
import be.nabu.libs.nio.api.Pipeline;
import be.nabu.libs.nio.api.PipelineWithMetaData;
import be.nabu.libs.nio.impl.NIOClientImpl;
import be.nabu.utils.mime.impl.FormatException;

public class NIOHTTPClient implements NonBlockingHTTPClient {

	private NIOClient client;
	private int maxConnectionsPerServer;
	private Map<HTTPRequest, HTTPResponseFuture> futures = Collections.synchronizedMap(new WeakHashMap<HTTPRequest, HTTPResponseFuture>());
	private HTTPClientPipelineFactory pipelineFactory;
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	private Map<String, Boolean> secure = Collections.synchronizedMap(new HashMap<String, Boolean>());
	private EventDispatcher dispatcher;
	
	public NIOHTTPClient(SSLContext sslContext, int ioPoolSize, int processPoolSize, int maxConnectionsPerServer, EventDispatcher dispatcher, MessageDataProvider messageDataProvider, CookieHandler cookieHandler, ThreadFactory threadFactory) {
		this.maxConnectionsPerServer = maxConnectionsPerServer;
		this.dispatcher = dispatcher;
		pipelineFactory = new HTTPClientPipelineFactory(this, cookieHandler, futures, dispatcher, messageDataProvider);
		this.client = new NIOClientImpl(sslContext, ioPoolSize, processPoolSize, pipelineFactory, dispatcher, threadFactory);
		new Thread(new Runnable() {
			public void run() {
				try {
					client.start();
				}
				catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}).start();
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public Future<HTTPResponse> call(final HTTPRequest request, boolean secure) throws IOException, FormatException, ParseException {
		URI uri = HTTPUtils.getURI(request, secure);
		int port = uri.getPort();
		if (port < 0) {
			port = secure ? 443 : 80;
		}
		String host = uri.getAuthority();
		int indexOfColon = host.indexOf(':');
		if (indexOfColon >= 0) {
			port = new Integer(host.substring(indexOfColon + 1));
			host = host.substring(0, indexOfColon);
		}
		setSecure(host, port, secure);
		// find the pipeline with the least requests pending
		Pipeline pipeline = null;
		int currentQueueSize = 0;
		int openConnections = 0;
		HTTPResponseFuture future = futures.get(request);
		if (future == null) {
			future = new HTTPResponseFuture();
			futures.put(request, future);
		}
		HTTPClientPipelineFactory factory = ((HTTPClientPipelineFactory) client.getPipelineFactory());
		for (Pipeline possible : new ArrayList<Pipeline>(client.getPipelines())) {
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
			final Future<Pipeline> pipelineFuture = client.connect(host, port);
			client.submitIOTask(new Runnable() {
				public void run() {
					try {
						Pipeline pipeline = pipelineFuture.get();
						((MessagePipeline<HTTPResponse, HTTPRequest>) pipeline).getResponseQueue().add(request);
					}
					catch (Exception e) {
						logger.error("Could not add to queue", e);
						throw new RuntimeException(e);
					}
				}
			});
		}
		else {
			logger.debug("Reusing existing pipeline to {}:{}", new Object[] { host, port });
			((MessagePipeline<HTTPResponse, HTTPRequest>) pipeline).getResponseQueue().add(request);
		}
		return future;
	}

	public static class HTTPResponseFuture implements Future<HTTPResponse> {

		private HTTPResponse response;
		private CountDownLatch latch = new CountDownLatch(1);
		
		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			return false;
		}

		@Override
		public boolean isCancelled() {
			return false;
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
			this.response = response;
			latch.countDown();
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
			redirectSubscription.filter(new HandlerFilter(host, port));
		}
		// if we have a principal, set a handler
		EventSubscription<HTTPResponse, HTTPRequest> authenticateSubscription = null;
		if (principal != null) {
			authenticateSubscription = getDispatcher().subscribe(HTTPResponse.class, new ServerAuthenticator(principal, new SPIAuthenticationHandler()));
			authenticateSubscription.filter(new HandlerFilter(host, port));
		}
		Future<HTTPResponse> call = call(request, secure);
		try {
			return call.get();
		}
		catch (Exception e) {
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

	@Override
	public EventDispatcher getDispatcher() {
		return dispatcher;
	}
}
