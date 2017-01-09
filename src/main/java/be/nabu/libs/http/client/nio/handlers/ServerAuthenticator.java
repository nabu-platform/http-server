package be.nabu.libs.http.client.nio.handlers;

import java.security.Principal;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.api.client.ClientAuthenticationHandler;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

public class ServerAuthenticator implements EventHandler<HTTPResponse, HTTPRequest> {

	private Principal principal;
	private ClientAuthenticationHandler authenticationHandler;
	
	public ServerAuthenticator(Principal principal, ClientAuthenticationHandler authenticationHandler) {
		this.principal = principal;
		this.authenticationHandler = authenticationHandler;
	}
	
	@Override
	public HTTPRequest handle(HTTPResponse response) {
		if (response.getCode() == 401) {
			Header connectionHeader = MimeUtils.getHeader("Connection", response.getContent().getHeaders());
			if (connectionHeader != null && connectionHeader.getValue().equalsIgnoreCase("close")) {
				return null;
			}
			else {
				HTTPRequest request = ((LinkableHTTPResponse) response).getRequest();
				if (request == null) {
					return null;
				}
				else {
					int tryCount = getTryCount(request);
					// arbitrary limit
					if (tryCount <= 3) {
						Header authenticationHeader = HTTPUtils.authenticateServer(response, principal, authenticationHandler);
						if (authenticationHeader != null) {
							request.getContent().removeHeader(authenticationHeader.getName());
							request.getContent().setHeader(authenticationHeader);
							setTryCount(request, tryCount + 1);
							return request;
						}
					}
					return null;
				}
			}
		}
		return null;
	}

	public static int getTryCount(HTTPRequest request) {
		Header tryHeader = MimeUtils.getHeader("X-Try", request.getContent().getHeaders());
		return tryHeader == null ? 0 : Integer.parseInt(tryHeader.getValue());
	}
	
	public static void setTryCount(HTTPRequest request, int tryCount) {
		request.getContent().removeHeader("X-Try");
		request.getContent().setHeader(new MimeHeader("X-Try", Integer.toString(tryCount + 1)));
	}
}
