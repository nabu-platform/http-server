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

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

/**
 * According to the spec, when sending back a 401, we should ensure that a WWW-Authenticate is set enabling the user to remedy the situation
 */
public class EnsureAuthenticateHeader implements EventHandler<HTTPResponse, HTTPResponse> {

	private String realm;

	public EnsureAuthenticateHeader(String realm) {
		this.realm = realm;
	}
	
	@Override
	public HTTPResponse handle(HTTPResponse event) {
		if (event.getCode() == 401 && event.getContent() != null && MimeUtils.getHeader("WWW-Authenticate", event.getContent().getHeaders()) == null) {
			event.getContent().setHeader(new MimeHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\""));
		}
		return null;
	}

}
