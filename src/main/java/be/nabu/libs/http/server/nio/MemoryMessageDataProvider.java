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

package be.nabu.libs.http.server.nio;

import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.WritableResource;
import be.nabu.libs.resources.memory.MemoryItem;
import be.nabu.utils.mime.api.Header;

public class MemoryMessageDataProvider implements MessageDataProvider {

	private long maxSize;
	
	public MemoryMessageDataProvider(long maxSize) {
		this.maxSize = maxSize;
	}
	
	public MemoryMessageDataProvider() {
		this(0);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T extends WritableResource & ReadableResource> T newResource(String method, String target, double version, Header...headers) {
		return (T) new MemoryItem("tmp", maxSize);
	}
	
}
