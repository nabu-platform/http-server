package be.nabu.libs.http.server;

import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.authentication.api.TokenValidator;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.AuthenticationHeader;
import be.nabu.libs.http.api.server.TokenResolver;
import be.nabu.libs.http.core.HTTPUtils;

/**
 * This sets the authentication header if a valid token is found
 * Alternatively it unsets the authentication header if an invalid token is found
 * 
 * Not sure if we want this, marking as deprecated
 */
@Deprecated
public class AuthenticationHeaderHandler implements EventHandler<HTTPRequest, HTTPResponse> {
	
	private TokenValidator tokenValidator;
	private TokenResolver tokenResolver;

	public AuthenticationHeaderHandler(TokenValidator tokenValidator, TokenResolver tokenResolver) {
		this.tokenValidator = tokenValidator;
		this.tokenResolver = tokenResolver;
	}

	@Override
	public HTTPResponse handle(HTTPRequest request) {
		if (request.getContent() != null) {
			// an auth header indicates a validated (or newly created) token
			AuthenticationHeader authenticationHeader = HTTPUtils.getAuthenticationHeader(request.getContent().getHeaders());
			if (authenticationHeader == null) {
				Token token = tokenResolver.getToken(request.getContent().getHeaders());
				if (token != null) {
					if (tokenValidator == null || tokenValidator.isValid(token)) {
						request.getContent().setHeader(new SimpleAuthenticationHeader(token));
					}
				}
			}
			else if (tokenValidator != null && !tokenValidator.isValid(authenticationHeader.getToken())) {
				request.getContent().removeHeader(authenticationHeader.getName());
			}
		}
		return null;
	}
}
