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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.WritableResource;
import be.nabu.libs.resources.memory.MemoryItem;
import be.nabu.utils.mime.api.Header;

public class MultipleMessageDataProvider implements MessageDataProvider {

	private List<MessageDataProvider> providers = new ArrayList<MessageDataProvider>();
	private long maxSize;
	
	public MultipleMessageDataProvider(long maxSize) {
		this.maxSize = maxSize;
	}
	
	public MultipleMessageDataProvider(Collection<MessageDataProvider> providers) {
		this.providers.addAll(providers);
	}
	
	public MultipleMessageDataProvider(MessageDataProvider...providers) {
		if (providers != null && providers.length > 0) {
			this.providers.addAll(Arrays.asList(providers));
		}
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T extends WritableResource & ReadableResource> T newResource(String method, String target, double version, Header... headers) throws IOException {
		for (MessageDataProvider provider : providers) {
			T newResource = provider.newResource(method, target, version, headers);
			if (newResource != null) {
				return newResource;
			}
		}
		return (T) new MemoryItem("tmp", maxSize);
	}

	public void addProvider(MessageDataProvider...providers) {
		// we create a new list to prevent concurrency issues
		if (providers != null && providers.length > 0) {
			List<MessageDataProvider> newList = new ArrayList<MessageDataProvider>(this.providers);
			newList.addAll(Arrays.asList(providers));
			this.providers = newList;
		}
	}
	
	public void removeProvider(MessageDataProvider...providers) {
		if (providers != null && providers.length > 0) {
			List<MessageDataProvider> newList = new ArrayList<MessageDataProvider>(this.providers);
			newList.removeAll(Arrays.asList(providers));
			this.providers = newList;
		}
	}
}
