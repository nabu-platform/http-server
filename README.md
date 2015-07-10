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
```

There is currently no standalone mode yet, the HTTP server is currently always embedded.