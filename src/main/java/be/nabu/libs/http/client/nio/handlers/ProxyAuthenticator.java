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

package be.nabu.libs.http.client.nio.handlers;

import java.security.Principal;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.api.client.ClientAuthenticationHandler;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeUtils;

public class ProxyAuthenticator implements EventHandler<HTTPResponse, HTTPRequest> {

	private Principal principal;
	private ClientAuthenticationHandler authenticationHandler;
	
	public ProxyAuthenticator(Principal principal, ClientAuthenticationHandler authenticationHandler) {
		this.principal = principal;
		this.authenticationHandler = authenticationHandler;
	}
	
	@Override
	public HTTPRequest handle(HTTPResponse response) {
		if (response.getCode() == 407) {
			Header proxyConnectionHeader = MimeUtils.getHeader("Proxy-Connection", response.getContent().getHeaders());
			if (proxyConnectionHeader != null && proxyConnectionHeader.getValue().equalsIgnoreCase("close")) {
				return null;
			}
			else {
				HTTPRequest request = ((LinkableHTTPResponse) response).getRequest();
				if (request == null) {
					return null;
				}
				else {
					int tryCount = ServerAuthenticator.getTryCount(request);
					// arbitrary limit
					if (tryCount <= 3) {
						Header authenticationHeader = HTTPUtils.authenticateProxy(response, principal, authenticationHandler);
						if (authenticationHeader != null) {
							request.getContent().removeHeader(authenticationHeader.getName());
							request.getContent().setHeader(authenticationHeader);
							ServerAuthenticator.setTryCount(request, tryCount + 1);
							return request;
						}
					}
					return null;
				}
			}
		}
		return null;
	}

}
