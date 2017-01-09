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
