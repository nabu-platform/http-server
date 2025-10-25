/*
* Copyright (C) 2015 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.libs.http.server;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPRequest;
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
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class ResourceHandler implements EventHandler<HTTPRequest, HTTPResponse> {

	private String serverPath;
	private List<ResourceContainer<?>> roots = new ArrayList<ResourceContainer<?>>();
	private boolean useCache;
	private Map<String, ReadableResource> cache = new HashMap<String, ReadableResource>();
	private boolean allowEncoding;
	private Map<ResourceContainer<?>, Set<String>> resources = new HashMap<ResourceContainer<?>, Set<String>>();
	private String defaultResource = null;
	
	public ResourceHandler(ResourceContainer<?> root, String serverPath, boolean useCache) {
		if (root != null) {
			this.roots.add(root);
		}
		this.serverPath = serverPath;
		this.useCache = useCache;
		this.allowEncoding = useCache;
	}
	
	public EventHandler<HTTPResponse, HTTPResponse> get404Rewriter() {
		// if we are about to send back a 404 and you were not doin
		return new EventHandler<HTTPResponse, HTTPResponse>() {
			@Override
			public HTTPResponse handle(HTTPResponse response) {
				if (response.getCode() == 404 && response instanceof LinkableHTTPResponse && ((LinkableHTTPResponse) response).getRequest() != null && ((LinkableHTTPResponse) response).getRequest().getMethod().equalsIgnoreCase("get")) {
					// check if the Accept contains html
					Header header = MimeUtils.getHeader("Accept", ((LinkableHTTPResponse) response).getRequest().getContent().getHeaders());
					if (header != null && header.getValue().contains("html")) {
						return ResourceHandler.this.handle(new DefaultHTTPRequest("GET", "/", ((LinkableHTTPResponse) response).getRequest().getContent()));
					}
				}
				return null;
			}
		};
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
			MimeHeader contentTypeHeader = new MimeHeader("Content-Type", resource.getContentType() == null ? "application/octet-stream" : resource.getContentType());
			if (resource instanceof TimestampedResource) {
				Date ifModifiedSince = HTTPUtils.getIfModifiedSince(request.getContent().getHeaders());
				MimeHeader lastModifiedHeader = new MimeHeader("Last-Modified", HTTPUtils.formatDate(((TimestampedResource) resource).getLastModified()));
				MimeHeader cacheHeader = new MimeHeader("Cache-Control", "public");
				// if it has not been modified, send back a 304
				if (ifModifiedSince != null && !ifModifiedSince.after(((TimestampedResource) resource).getLastModified())) {
					return new DefaultHTTPResponse(request, 304, HTTPCodes.getMessage(304), new PlainMimeEmptyPart(null, 
						new MimeHeader("Content-Length", "0"), 
						cacheHeader,
						lastModifiedHeader,
						contentTypeHeader
					));
				}
				else {
					HTTPResponse newResponse = HTTPUtils.newResponse(request, (ReadableResource) resource, cacheHeader, lastModifiedHeader, contentTypeHeader);
					if (allowEncoding && newResponse.getContent() instanceof ContentPart) {
						HTTPUtils.setContentEncoding(newResponse.getContent(), request.getContent().getHeaders());
					}
					return newResponse;
				}
			}
			return HTTPUtils.newResponse(request, (ReadableResource) resource, contentTypeHeader);
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
		if (path == null || path.isBlank()) {
			path = defaultResource;
		}
		if (path == null) {
			return null;
		}
		if (!cache.containsKey(path)) { 
			resource = resolveResource(path);
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

	protected Resource resolveResource(String path) throws IOException {
		for (ResourceContainer<?> root : roots) {
			Resource resource = ResourceUtils.resolve(root, path);
			if (resource != null) {
				// if we are using the cache, remember where the resource came from so we can remove it from cache if we unload the resource
				if (useCache) {
					synchronized(resources) {
						if (!resources.containsKey(root)) {
							resources.put(root, new HashSet<String>());
						}
						resources.get(root).add(path);
					}
				}
				return resource;
			}
		}
		return null;
	}

	public boolean isUseCache() {
		return useCache;
	}

	public void setUseCache(boolean useCache) {
		this.useCache = useCache;
	}

	public void addRoot(ResourceContainer<?> container) {
		if (!roots.contains(container)) {
			synchronized(roots) {
				if (!roots.contains(container)) {
					roots.add(container);
				}
			}
		}
	}
	
	public void removeRoot(ResourceContainer<?> container) {
		// first remove from roots so we don't accidently resolve from there
		if (roots.contains(container)) {
			synchronized(roots) {
				if (roots.contains(container)) {
					roots.remove(container);
				}
			}
		}
		// then remove from the cache if it is in there so we don't serve up stale data
		if (resources.containsKey(container)) {
			synchronized(cache) {
				for (String path : resources.get(container)) {
					cache.remove(path);
				}
			}
			synchronized(resources) {
				resources.remove(container);
			}
		}
	}

	public List<ResourceContainer<?>> getRoots() {
		return roots;
	}

	public String getDefaultResource() {
		return defaultResource;
	}

	public void setDefaultResource(String defaultResource) {
		this.defaultResource = defaultResource;
	}
	
}
