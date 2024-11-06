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

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeUtils;

public class RequestPathFilter implements EventHandler<HTTPResponse, Boolean> {

	private String path;
	private boolean isRegex, whitelist;
	private boolean useProxyPath;

	public RequestPathFilter(String path) {
		this(path, false, true);
	}
	
	public RequestPathFilter(String path, boolean isRegex, boolean whitelist) {
		this(path, isRegex, whitelist, false);
	}
	
	public RequestPathFilter(String path, boolean isRegex, boolean whitelist, boolean useProxyPath) {
		this.path = path;
		this.isRegex = isRegex;
		this.whitelist = whitelist;
		this.useProxyPath = useProxyPath;
		// make sure it is absolute
		if (!isRegex && !this.path.startsWith("/")) {
			this.path = "/" + this.path;
		}
	}
	
	@Override
	public Boolean handle(HTTPResponse response) {
		HTTPRequest request = response instanceof LinkableHTTPResponse ? ((LinkableHTTPResponse) response).getRequest() : null;
		if (request == null) {
			return false;
		}
		try {
			String requestPath = HTTPUtils.getURI(request, false).getPath();
			if (useProxyPath && request.getContent() != null) {
				Header header = MimeUtils.getHeader(ServerHeader.PROXY_PATH.getName(), request.getContent().getHeaders());
				if (header != null && header.getValue() != null) {
					requestPath = header.getValue().replaceFirst("[/]+$", "") + "/" + requestPath.replaceFirst("^[/]+", "");
					if (!requestPath.startsWith("/")) {
						requestPath = "/" + requestPath;
					}
				}
			}
			if (whitelist) {
				return isRegex
					? !requestPath.matches(path)
					: !requestPath.startsWith(path);
			}
			else {
				return isRegex
					? requestPath.matches(path)
					: requestPath.startsWith(path);
			}
		}
		catch (FormatException e) {
			return false;
		}
	}

}
