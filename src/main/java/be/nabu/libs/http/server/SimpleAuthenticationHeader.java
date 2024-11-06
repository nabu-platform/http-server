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
import be.nabu.libs.http.api.server.AuthenticationHeader;
import be.nabu.libs.http.core.ServerHeader;

public class SimpleAuthenticationHeader implements AuthenticationHeader {

	private Token token;

	public SimpleAuthenticationHeader(Token token) {
		this.token = token;
	}
	
	@Override
	public String getName() {
		return ServerHeader.REMOTE_USER.getName();
	}

	@Override
	public String getValue() {
		return token.getName();
	}

	@Override
	public String[] getComments() {
		return new String[0];
	}
	
	@Override
	public Token getToken() {
		return token;
	}
}
