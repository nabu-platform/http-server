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

import be.nabu.libs.authentication.api.RoleHandler;
import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.AuthenticationHeader;
import be.nabu.libs.http.core.HTTPUtils;

public class RoleValidator implements EventHandler<HTTPRequest, HTTPResponse> {

	private RoleHandler roleHandler;
	private String[] roles;

	public RoleValidator(RoleHandler roleHandler, String...roles) {
		this.roleHandler = roleHandler;
		this.roles = roles;
	}
	
	@Override
	public HTTPResponse handle(HTTPRequest request) {
		AuthenticationHeader authenticationHeader = HTTPUtils.getAuthenticationHeader(request);
		Token token = authenticationHeader == null ? null : authenticationHeader.getToken();
		if (token == null) {
			throw new HTTPException(401);
		}
		boolean allowed = false;
		for (String role : roles) {
			if (roleHandler.hasRole(token, role)) {
				allowed = true;
				break;
			}
		}
		if (!allowed) {
			throw new HTTPException(403);
		}
		return null;
	}

}
