package be.nabu.libs.http.server;

import java.io.IOException;
import java.net.URI;

import javax.net.ssl.SSLContext;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPServer;
import be.nabu.libs.http.server.io.DefaultHTTPServer;
import be.nabu.libs.http.server.nio.NonBlockingHTTPServer;
import be.nabu.libs.resources.ResourceFactory;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.utils.io.SSLServerMode;

public class HTTPServerUtils {
	
	public static HTTPServer newBlocking(SSLContext sslContext, int port, int poolSize, EventDispatcher dispatcher) throws IOException {
		return new DefaultHTTPServer(sslContext, port, poolSize, dispatcher);
	}
	
	public static HTTPServer newNonBlocking(SSLContext sslContext, SSLServerMode serverMode, int port, int poolSize, EventDispatcher dispatcher) {
		return new NonBlockingHTTPServer(sslContext, serverMode, port, poolSize, dispatcher);
	}
	
	public static HTTPServer newNonBlocking(int port, int poolSize, EventDispatcher dispatcher) {
		return new NonBlockingHTTPServer(null, null, port, poolSize, dispatcher);
	}
	
	public static EventHandler<HTTPRequest, Boolean> filterPath(String path) {
		return new PathFilter(path);
	}
	
	public static EventHandler<HTTPRequest, Boolean> filterPath(String path, boolean isRegex) {
		return new PathFilter(path, isRegex);
	}
	
	public static EventHandler<HTTPRequest, HTTPRequest> defaultPageHandler(String defaultPage) {
		return rewriteHandler("/", defaultPage.startsWith("/") ? defaultPage : "/" + defaultPage);
	}
	
	public static EventHandler<HTTPRequest, HTTPResponse> resourceHandler(ResourceContainer<?> root, String serverPath) {
		return resourceHandler(root, serverPath, true);
	}
	public static EventHandler<HTTPRequest, HTTPResponse> resourceHandler(ResourceContainer<?> root, String serverPath, boolean useCache) {
		return new ResourceHandler(root, serverPath, useCache);
	}

	public static EventHandler<HTTPRequest, HTTPRequest> rewriteHandler(String regex, String replacement) {
		return new RequestPathRewriter(regex, replacement);
	}
	
	public static void handleDefaultPage(HTTPServer server, String defaultPage) {
		server.getEventDispatcher().subscribe(HTTPRequest.class, defaultPageHandler(defaultPage));
	}
	
	public static void rewrite(HTTPServer server, String regex, String replacement) {
		server.getEventDispatcher().subscribe(HTTPRequest.class, rewriteHandler(regex, replacement))
			.filter(filterPath(regex, true));
	}
	
	public static void handleResources(HTTPServer server, ResourceContainer<?> root, String serverPath) {
		server.getEventDispatcher().subscribe(HTTPRequest.class, resourceHandler(root, serverPath))
			.filter(filterPath(serverPath, false));
	}
	
	public static void handleResources(HTTPServer server, URI uri, String serverPath) throws IOException {
		handleResources(server, (ResourceContainer<?>) ResourceFactory.getInstance().resolve(uri, null), serverPath);
	}
	
}
