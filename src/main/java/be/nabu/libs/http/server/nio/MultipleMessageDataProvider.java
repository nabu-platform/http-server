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
		return (T) new MemoryItem("tmp");
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
