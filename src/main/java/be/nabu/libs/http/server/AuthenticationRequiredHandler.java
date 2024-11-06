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
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class AuthenticationRequiredHandler implements EventHandler<HTTPRequest, HTTPResponse> {

	private String challenge;

	public AuthenticationRequiredHandler(String challenge) {
		this.challenge = challenge;
	}
	
	@Override
	public HTTPResponse handle(HTTPRequest event) {
		Header header = MimeUtils.getHeader(ServerHeader.REMOTE_USER.getName(), event.getContent().getHeaders());
		if (header == null) {
			PlainMimeEmptyPart content = new PlainMimeEmptyPart(null, 
				new MimeHeader("Content-Length", "0")
			);
			if (challenge != null) {
				content.setHeader(new MimeHeader("WWW-Authenticate", challenge));
			}
			return new DefaultHTTPResponse(event, 401, HTTPCodes.getMessage(401), content);
		}
		return null;
	}

}
