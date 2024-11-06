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
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.utils.mime.impl.MimeUtils;

public class AbsenceOfHeadersValidator implements EventHandler<HTTPRequest, HTTPResponse> {
	
	private String[] headersToCheck;
	private boolean fail;

	public AbsenceOfHeadersValidator(boolean fail, String...headersToCheck) {
		this.fail = fail;
		if (headersToCheck == null || headersToCheck.length == 0) {
			headersToCheck = new String[ServerHeader.values().length];
			for (int i = 0; i < ServerHeader.values().length; i++) {
				headersToCheck[i] = ServerHeader.values()[i].getName();
			}
		}
		this.headersToCheck = headersToCheck;
	}
	
	@Override
	public HTTPResponse handle(HTTPRequest request) {
		if (request.getContent() != null) {
			if (fail) {
				for (String header : headersToCheck) {
					if (MimeUtils.getHeader(header, request.getContent().getHeaders()) != null) {
						throw new HTTPException(400, "Header not allowed: " + header);
					}
				}
			}
			else {
				request.getContent().removeHeader(headersToCheck);
			}
		}
		return null;
	}

}
