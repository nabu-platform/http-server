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
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.impl.FormatException;

public class PathFilter implements EventHandler<HTTPRequest, Boolean> {

	private String path;
	private boolean isRegex, whitelist;

	public PathFilter(String path) {
		this(path, false, true);
	}
	
	public PathFilter(String path, boolean isRegex, boolean whitelist) {
		this.path = path;
		this.isRegex = isRegex;
		this.whitelist = whitelist;
		// make sure it is absolute
		if (!isRegex && !this.path.startsWith("/")) {
			this.path = "/" + this.path;
		}
	}
	
	@Override
	public Boolean handle(HTTPRequest request) {
		try {
			boolean result = false;
			if (whitelist) {
				result = isRegex
					? !HTTPUtils.getURI(request, false).getPath().matches(path)
					: !HTTPUtils.getURI(request, false).getPath().startsWith(path);
			}
			else {
				result = isRegex
					? HTTPUtils.getURI(request, false).getPath().matches(path)
					: HTTPUtils.getURI(request, false).getPath().startsWith(path);
			}
			return result;
		}
		catch (FormatException e) {
			return false;
		}
	}

}
