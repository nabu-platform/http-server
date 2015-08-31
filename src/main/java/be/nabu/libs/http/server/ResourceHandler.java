package be.nabu.libs.http.server;

import java.io.IOException;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.resources.DynamicResource;
import be.nabu.libs.resources.ResourceReadableContainer;
import be.nabu.libs.resources.ResourceUtils;
import be.nabu.libs.resources.URIUtils;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.libs.resources.api.TimestampedResource;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class ResourceHandler implements EventHandler<HTTPRequest, HTTPResponse> {

	private String serverPath;
	private ResourceContainer<?> root;
	private boolean useCache;
	private Map<String, ReadableResource> cache = new HashMap<String, ReadableResource>();
	
	public ResourceHandler(ResourceContainer<?> root, String serverPath, boolean useCache) {
		this.root = root;
		this.serverPath = serverPath;
		this.useCache = useCache;
	}
	
	@Override
	public HTTPResponse handle(HTTPRequest request) {
		try {
			String path = URIUtils.normalize(HTTPUtils.getURI(request, false).getPath());
			if (!path.startsWith(serverPath)) {
				return null;
			}
			else {
				path = path.substring(serverPath.length());
			}
			if (path.startsWith("/")) {
				path = path.substring(1);
			}
			Resource resource = getResource(path);
			if (resource == null) {
				return null;
			}
			if (resource instanceof TimestampedResource) {
				Date ifModifiedSince = HTTPUtils.getIfModifiedSince(request.getContent().getHeaders());
				MimeHeader lastModifiedHeader = new MimeHeader("Last-Modified", HTTPUtils.formatDate(((TimestampedResource) resource).getLastModified()));
				MimeHeader cacheHeader = new MimeHeader("Cache-Control", "public");
				// if it has not been modified, send back a 304
				if (ifModifiedSince != null && !ifModifiedSince.after(((TimestampedResource) resource).getLastModified())) {
					return new DefaultHTTPResponse(304, HTTPCodes.getMessage(304), new PlainMimeEmptyPart(null, 
						new MimeHeader("Content-Length", "0"), 
						cacheHeader,
						lastModifiedHeader
					));
				}
				else {
					return HTTPUtils.newResponse((ReadableResource) resource, cacheHeader, lastModifiedHeader);
				}
			}
			return HTTPUtils.newResponse((ReadableResource) resource);
		}
		catch (IOException e) {
			throw new HTTPException(500, e);
		}
		catch (FormatException e) {
			throw new HTTPException(500, e);
		}
		catch (ParseException e) {
			throw new HTTPException(400, e);
		}
	}
	
	public ReadableResource getResource(String path) throws IOException {
		Resource resource;
		if (!cache.containsKey(path)) { 
			resource = ResourceUtils.resolve(root, path);
			if (resource == null) {
				return null;
			}
			if (!(resource instanceof ReadableResource)) {
				throw new HTTPException(500, "Invalid resource: " + path);
			}
			if (useCache) {
				synchronized(cache) {
					ReadableResource cachedResource = new DynamicResource(new ResourceReadableContainer((ReadableResource) resource), resource.getName(), resource.getContentType(), true);
					cache.put(path, cachedResource);
					resource = cachedResource;
				}
			}
		}
		else {
			resource = cache.get(path);
		}
		return (ReadableResource) resource;
	}
}
