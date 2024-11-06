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
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class RedirectHandler implements EventHandler<HTTPRequest, HTTPResponse> {

	private String redirect;
	private String path;
	private boolean permanent;

	public RedirectHandler(String path, String redirect, boolean permanent) {
		this.path = path;
		this.redirect = redirect;
		this.permanent = permanent;
	}
	
	@Override
	public HTTPResponse handle(HTTPRequest request) {
		try {
			if (HTTPUtils.getURI(request, false).getPath().startsWith(path)) {
				return new DefaultHTTPResponse(request,
						permanent ? 301 : 307, 
						permanent ? HTTPCodes.getMessage(301) : HTTPCodes.getMessage(307), new PlainMimeEmptyPart(null, 
					new MimeHeader("Location", redirect),
					new MimeHeader("Content-Length", "0")
				));
			}
		}
		catch (FormatException e) {
			throw new HTTPException(500, e);
		}
		return null;
	}
}
