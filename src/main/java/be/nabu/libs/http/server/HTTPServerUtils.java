package be.nabu.libs.http.server;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.net.ssl.SSLContext;

import be.nabu.libs.authentication.api.Authenticator;
import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.events.api.EventSubscription;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.HTTPServer;
import be.nabu.libs.http.api.server.RealmHandler;
import be.nabu.libs.http.server.nio.NIOHTTPServer;
import be.nabu.libs.resources.ResourceFactory;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.utils.io.SSLServerMode;

public class HTTPServerUtils {

	public static HTTPServer newServer(int port, int processPoolSize, EventDispatcher dispatcher) {
		return newServer(port, processPoolSize, dispatcher, Executors.defaultThreadFactory());
	}
	
	public static HTTPServer newServer(int port, int processPoolSize, EventDispatcher dispatcher, ThreadFactory threadFactory) {
		return newServer(null, null, port, 10, processPoolSize, dispatcher, threadFactory);
	}
	
	public static HTTPServer newServer(SSLContext sslContext, SSLServerMode sslServerMode, int port, int ioPoolSize, int processPoolSize, EventDispatcher dispatcher) {
		return newServer(sslContext, sslServerMode, port, ioPoolSize, processPoolSize, dispatcher, Executors.defaultThreadFactory());
	}
	
	public static HTTPServer newServer(SSLContext sslContext, SSLServerMode sslServerMode, int port, int ioPoolSize, int processPoolSize, EventDispatcher dispatcher, ThreadFactory threadFactory) {
		return new NIOHTTPServer(sslContext, sslServerMode, port, ioPoolSize, processPoolSize, dispatcher, threadFactory);
	}
	
	public static EventHandler<HTTPRequest, Boolean> filterPath(String path) {
		return new PathFilter(path, false, false);
	}
	
	public static EventHandler<HTTPRequest, Boolean> filterPath(String path, boolean isRegex) {
		return new PathFilter(path, isRegex, false);
	}

	public static EventHandler<HTTPRequest, Boolean> limitToPath(String path) {
		return new PathFilter(path, false, true);
	}
	
	public static EventHandler<HTTPResponse, Boolean> limitToRequestPath(String path) {
		return new RequestPathFilter(path, false, true);
	}
	
	public static EventHandler<HTTPResponse, Boolean> limitToRequestPath(String path, boolean isRegex) {
		return new RequestPathFilter(path, isRegex, true);
	}
	
	public static EventHandler<HTTPRequest, Boolean> limitToPath(String path, boolean isRegex) {
		return new PathFilter(path, isRegex, true);
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
	
	public static EventSubscription<HTTPRequest, HTTPRequest> handleDefaultPage(HTTPServer server, String defaultPage) {
		return server.getDispatcher(null).subscribe(HTTPRequest.class, defaultPageHandler(defaultPage));
	}
	
	public static EventSubscription<HTTPRequest, HTTPRequest> rewrite(HTTPServer server, String regex, String replacement) {
		EventSubscription<HTTPRequest, HTTPRequest> subscription = server.getDispatcher(null).subscribe(HTTPRequest.class, rewriteHandler(regex, replacement));
		subscription.filter(limitToPath(regex, true));
		return subscription;
	}
	
	public static EventSubscription<HTTPRequest, HTTPResponse> handleResources(HTTPServer server, ResourceContainer<?> root, String serverPath) {
		EventSubscription<HTTPRequest, HTTPResponse> subscription = server.getDispatcher(null).subscribe(HTTPRequest.class, resourceHandler(root, serverPath));
		subscription.filter(limitToPath(serverPath, false));
		return subscription;
	}
	
	public static EventSubscription<HTTPRequest, HTTPResponse> handleResources(HTTPServer server, URI uri, String serverPath) throws IOException {
		return handleResources(server, (ResourceContainer<?>) ResourceFactory.getInstance().resolve(uri, null), serverPath);
	}
	
	public static EventSubscription<HTTPRequest, HTTPResponse> verifyAbsenceOfHeaders(HTTPServer server, String...headers) {
		return server.getDispatcher(null).subscribe(HTTPRequest.class, new AbsenceOfHeadersValidator(false, headers));
	}
	
	public static EventSubscription<HTTPRequest, HTTPResponse> requireAuthentication(HTTPServer server) {
		return server.getDispatcher(null).subscribe(HTTPRequest.class, new AuthenticationRequiredHandler(null));
	}
	
	public static EventSubscription<HTTPRequest, HTTPResponse> requireBasicAuthentication(HTTPServer server, Authenticator handler, RealmHandler realmHandler) {
		return server.getDispatcher(null).subscribe(HTTPRequest.class, new BasicAuthenticationHandler(handler, realmHandler));
	}
	
	public static void addKeepAlive(HTTPServer server) {
		server.getDispatcher(null).subscribe(HTTPResponse.class, new KeepAliveRewriter());
	}
	
	public static void addDate(HTTPServer server) {
		server.getDispatcher(null).subscribe(HTTPResponse.class, new DateRewriter());
	}
	
	public static RealmHandler newFixedRealmHandler(String realm) {
		return new FixedRealmHandler(realm);
	}
	
	public static EventHandler<HTTPResponse, HTTPResponse> ensureAuthenticateHeader(String realm) {
		return new EnsureAuthenticateHeader(realm);
	}
	
	public static EventHandler<HTTPResponse, HTTPResponse> ensureContentEncoding() {
		return new EnsureContentEncodingHandler();
	}
}
