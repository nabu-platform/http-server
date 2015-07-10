package be.nabu.libs.http.server.nio;

import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.WritableResource;
import be.nabu.libs.resources.memory.MemoryItem;

public class MemoryMessageDataProvider implements MessageDataProvider {

	@SuppressWarnings("unchecked")
	@Override
	public <T extends WritableResource & ReadableResource> T newResource(Long size) {
		return (T) new MemoryItem("tmp");
	}

}
