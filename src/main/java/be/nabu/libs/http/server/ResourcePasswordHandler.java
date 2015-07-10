package be.nabu.libs.http.server;

import java.io.IOException;
import java.io.InputStream;

import be.nabu.libs.resources.ResourceReadableContainer;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.utils.io.IOUtils;

public class ResourcePasswordHandler extends BasePasswordHandler {

	private ResourceContainer<?> container;
	
	public ResourcePasswordHandler(ResourceContainer<?> container, boolean forceDefaultRealm) {
		super(forceDefaultRealm);
		this.container = container;
	}

	@Override
	protected InputStream getInput(String fileName) throws IOException {
		Resource child = container.getChild(fileName);
		return child == null ? null : IOUtils.toInputStream(new ResourceReadableContainer((ReadableResource) child));
	}
	
}
