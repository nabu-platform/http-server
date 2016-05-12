package be.nabu.libs.http.server;

import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.authentication.api.TokenValidator;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.AuthenticationHeader;
import be.nabu.libs.http.api.server.Session;
import be.nabu.libs.http.api.server.SessionResolver;
import be.nabu.libs.http.core.HTTPUtils;

/**
 * This will either copy the token to the session (if valid)
 */
public class SessionTokenHandler implements EventHandler<HTTPRequest, HTTPResponse> {
	
	private SessionResolver sessionResolver;
	private String tokenKey;
	private TokenValidator tokenValidator;

	public SessionTokenHandler(TokenValidator tokenValidator, SessionResolver sessionResolver, String tokenKey) {
		this.tokenValidator = tokenValidator;
		this.sessionResolver = sessionResolver;
		this.tokenKey = tokenKey;
	}

	@Override
	public HTTPResponse handle(HTTPRequest request) {
		if (request.getContent() != null) {
			Session session = sessionResolver.getSession(request.getContent().getHeaders());
			AuthenticationHeader authenticationHeader = HTTPUtils.getAuthenticationHeader(request.getContent().getHeaders());
			if (authenticationHeader == null && (session == null || session.get(tokenKey) != null)) {
				Token token = (Token) session.get(tokenKey);
				// if we have a token in the session and it is no longer valid, destroy the session
				if (tokenValidator != null && !tokenValidator.isValid(token)) {
					session.destroy();
				}
				else {
					request.getContent().setHeader(new SimpleAuthenticationHeader(token));
				}
			}
			else if (authenticationHeader != null && !authenticationHeader.getToken().equals(session.get(tokenKey))) {
				session.set(tokenKey, authenticationHeader.getToken());
			}
		}
		return null;
	}
}
