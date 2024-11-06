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

import java.net.URI;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.client.nio.NIOHTTPClientImpl;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.nio.PipelineUtils;
import be.nabu.libs.nio.impl.MessagePipelineImpl;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeUtils;

public class RedirectFollower implements EventHandler<HTTPResponse, HTTPRequest> {

	private NIOHTTPClientImpl client;
	private int redirectCount;

	public RedirectFollower(NIOHTTPClientImpl client, int redirectCount) {
		this.client = client;
		this.redirectCount = redirectCount;
	}
	
	@Override
	public HTTPRequest handle(HTTPResponse response) {
		if (response.getCode() == 301 || response.getCode() == 302 || response.getCode() == 307) {
			Header locationHeader = MimeUtils.getHeader("Location", response.getContent().getHeaders());
			if (locationHeader != null) {
				HTTPRequest request = ((LinkableHTTPResponse) response).getRequest();
				if (request == null) {
					return null;
				}
				else {
					try {
						int tryCount = ServerAuthenticator.getTryCount(request);
						if (tryCount < redirectCount) {
							URI newTarget = new URI(locationHeader.getValue());
							Boolean secure = newTarget.getScheme() == null ? null : newTarget.getScheme().equals("https");
							// if we don't have a new security policy, take the one from the current connection
							if (secure == null) {
								secure = ((MessagePipelineImpl<?, ?>) PipelineUtils.getPipeline()).isUseSsl();
							}
							// you can also have relative redirect locations although the standard (currently, this will change) states absolute
							if (newTarget.getAuthority() == null) {
								newTarget = HTTPUtils.getURI(request, secure).resolve(newTarget);
							}
							String host = NIOHTTPClientImpl.getHost(newTarget);
							int port = NIOHTTPClientImpl.getPort(newTarget);
							client.setSecure(host, port, secure);
							
							HTTPRequest newRequest = HTTPUtils.redirect(request, newTarget, true);
							ServerAuthenticator.setTryCount(newRequest, tryCount + 1);
							return newRequest;
						}
						else {
							throw new RuntimeException("The amount of redirects is greater than: " + redirectCount);
						}
					}
					catch (Exception e) {
						throw new RuntimeException("Can not redirect to: " + locationHeader.getValue() + ": " + e.getMessage());
					}
				}
			}
		}
		return null;
	}

}
