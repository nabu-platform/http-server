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

package be.nabu.libs.http.client.nio.handlers;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;

public class RequestFilter implements EventHandler<HTTPResponse, Boolean> {

	private HTTPRequest request;

	public RequestFilter(HTTPRequest request) {
		this.request = request;
	}
	
	@Override
	public Boolean handle(HTTPResponse event) {
		HTTPRequest request = event instanceof LinkableHTTPResponse ? ((LinkableHTTPResponse) event).getRequest() : null;
		// if we can't find the original request or it doesn't match the expected request, filter it
		if (request == null || !request.equals(this.request)) {
			return true;
		}
		return false;
	}

}
