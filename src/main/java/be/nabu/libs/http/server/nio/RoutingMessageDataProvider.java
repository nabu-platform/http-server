package be.nabu.libs.http.server.nio;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import be.nabu.libs.http.api.server.EnrichingMessageDataProvider;
import be.nabu.libs.http.api.server.MessageDataProvider;
import be.nabu.libs.resources.URIUtils;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.WritableResource;
import be.nabu.libs.resources.memory.MemoryItem;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.api.ModifiablePart;

public class RoutingMessageDataProvider implements EnrichingMessageDataProvider {

	private Map<String, MessageDataProvider> providers = new HashMap<String, MessageDataProvider>();
	
	private long maxSize;
	
	public RoutingMessageDataProvider() {
		this(0);
	}
	
	public RoutingMessageDataProvider(long maxSize) {
		this.maxSize = maxSize;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T extends WritableResource & ReadableResource> T newResource(String method, String target, double version, Header...headers) throws IOException {
		MessageDataProvider provider = getProvider(target);
		return provider == null 
			? (T) new MemoryItem("tmp", maxSize) 
			: (T) provider.newResource(method, target, version, headers);
	}

	@Override
	public void enrich(ModifiablePart part, String method, String target, double version, Header...headers) {
		MessageDataProvider provider = getProvider(target);
		if (provider instanceof EnrichingMessageDataProvider) {
			((EnrichingMessageDataProvider) provider).enrich(part, method, target, version, headers);
		}
	}

	private MessageDataProvider getProvider(String target) {
		String path;
		if (target.startsWith("http://") || target.startsWith("https://")) {
			try {
				path = new URI(URIUtils.encodeURI(target)).getPath();
			}
			catch (URISyntaxException e) {
				throw new RuntimeException(e);
			}
		}
		else {
			path = target;
		}
		String longestMatch = null;
		for (String potential : providers.keySet()) {
			if (path.matches(potential)) {
				if (longestMatch == null || potential.length() > longestMatch.length()) {
					longestMatch = potential;
				}
			}
		}
		return longestMatch == null ? null : providers.get(longestMatch);
	}
	
	public synchronized void route(String path, MessageDataProvider provider) {
		Map<String, MessageDataProvider> providers = new HashMap<String, MessageDataProvider>(this.providers);
		providers.put(path, provider);
		this.providers = providers;
	}

	public synchronized void unroute(String path) {
		Map<String, MessageDataProvider> providers = new HashMap<String, MessageDataProvider>(this.providers);
		providers.remove(path);
		this.providers = providers;
	}
}
