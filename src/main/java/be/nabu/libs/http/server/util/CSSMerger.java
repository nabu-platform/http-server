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

package be.nabu.libs.http.server.util;

import java.util.regex.Pattern;

import be.nabu.libs.resources.api.ResourceContainer;

public class CSSMerger extends ResourceMerger {

	public CSSMerger(ResourceContainer<?> root, String serverPath) {
		super(root, serverPath, Pattern.compile("<link[^>]+href=(?:'|\")([^'\"]+\\.css)[^>]+>(?:[\\s]*</script>|)"), "text/css");
	}

	@Override
	protected String injectMerged(String content, String mergedURL) {
		return content.replaceAll("(<head[^>]*>)", "$1\n<link type='text/css' rel='stylesheet' href='" + mergedURL + "'/>");
	}

}
