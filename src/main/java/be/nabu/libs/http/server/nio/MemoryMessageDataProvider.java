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
