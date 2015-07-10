# Description

This package contains both a classic IO server and a NIO based server.

It also contains some default handlers like resource handlers, path filters, rewriting,...

For example, suppose you want a new nio server instance with some html/js/css stuff:

```java
// create a new server instance
HTTPServer server = HTTPServerUtils.newNonBlocking(port, 20, new EventDispatcherImpl());
// make sure there is a default page
HTTPServerUtils.handleDefaultPage(server, "index.html");
// handle the resources like css, javascript,...
HTTPServerUtils.handleResources(server, resourceContainer, "/resources");
// rewrite any path matching that to the given path
HTTPServerUtils.rewrite(server, "/view/.*", "index.html");
// handle the pages themselves
HTTPServerUtils.handleResources(server, pageContainer, "/");
// start the server
server.start();
```

There is currently no standalone mode yet, the HTTP server is currently always embedded.

## SSL

Note that the nio server also supports SSL connections by giving it an SSLContext and a server mode to operate in:

```java
SSLContext context = ...;
HTTPServer server = HTTPServerUtils.newNonBlocking(context, SSLServerMode.NEED_CLIENT_CERTIFICATES, port, 20, new EventDispatcherImpl());
```

Note that the `utils-security` package contains the necessary utilities to create custom contexts, generate self-signed certificates etc...