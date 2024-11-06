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
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.impl.MimeUtils;

public class EnsureContentEncodingHandler implements EventHandler<HTTPResponse, HTTPResponse> {

	@Override
	public HTTPResponse handle(HTTPResponse response) {
		if (response.getContent() != null) {
			HTTPRequest request = response instanceof LinkableHTTPResponse ? ((LinkableHTTPResponse) response).getRequest() : null;
			if (request != null && request.getContent() != null && MimeUtils.getHeader("Content-Encoding", response.getContent().getHeaders()) == null) {
				HTTPUtils.setContentEncoding(response.getContent(), request.getContent().getHeaders());
//				List<String> acceptedEncodings = MimeUtils.getAcceptedEncodings(request.getContent().getHeaders());
//				if (acceptedEncodings.contains("gzip")) {
//					response.getContent().setHeader(new MimeHeader("Content-Encoding", "gzip"));
//				}
//				else if (acceptedEncodings.contains("deflate")) {
//					response.getContent().setHeader(new MimeHeader("Content-Encoding", "deflate"));
//				}
			}
		}
		return null;
	}

}
