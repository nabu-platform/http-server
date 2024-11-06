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
 * Not sure if we want this, marking as deprecated
 */
@Deprecated
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
