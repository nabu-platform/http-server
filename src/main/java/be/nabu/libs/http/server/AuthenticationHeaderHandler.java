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
