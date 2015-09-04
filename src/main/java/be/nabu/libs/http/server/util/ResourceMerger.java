package be.nabu.libs.http.server.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import be.nabu.libs.http.api.ContentRewriter;
import be.nabu.libs.http.server.ResourceHandler;
import be.nabu.libs.resources.ResourceReadableContainer;
import be.nabu.libs.resources.URIUtils;
import be.nabu.libs.resources.api.FiniteResource;
import be.nabu.libs.resources.api.ReadableResource;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.ResourceContainer;
import be.nabu.libs.resources.api.TimestampedResource;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.ReadableContainer;

abstract public class ResourceMerger extends ResourceHandler implements ContentRewriter {

	private Pattern pattern;
	private String serverPath;
	private String mimeType;
	private String name;
	
	public ResourceMerger(ResourceContainer<?> root, String serverPath, Pattern pattern, String mimeType) {
		super(root, serverPath + (serverPath.endsWith("/") ? "" : "/") + mimeType.replaceFirst("^.*/", ""), true);
		this.serverPath = serverPath;
		this.mimeType = mimeType;
		if (!this.serverPath.endsWith("/")) {
			this.serverPath += "/";
		}
		this.pattern = pattern;
		this.name = mimeType.replaceFirst("^.*/", "");
	}
	
	@Override
	public String rewrite(String content, String mimeType) {
		Set<String> resources = new LinkedHashSet<String>();
		// only rewrite html response
		if (mimeType.equalsIgnoreCase("text/html")) {
			Matcher matcher = pattern.matcher(content);
			while(matcher.find()) {
				String resource = matcher.group(1);
				if (resource.startsWith(serverPath)) {
					resource = resource.substring(serverPath.length());
				}
				resources.add(resource);
				// remove it from the content
				content = content.replace(matcher.group(), "");
			}
			StringBuilder builder = new StringBuilder();
			for (String resource : resources) {
				if (builder.length() > 0) {
					builder.append(";");
				}
				builder.append(URIUtils.encodeURIComponent(resource));
			}
			content = injectMerged(content, serverPath + name + "/" + builder.toString());
		}
		return content;
	}

	abstract protected String injectMerged(String content, String mergedURL);
	
	@Override
	protected Resource resolveResource(String path) throws IOException {
		List<ReadableContainer<ByteBuffer>> containers = new ArrayList<ReadableContainer<ByteBuffer>>();
		long totalSize = 0;
		for (String part : path.split(";")) {
			String fullPath = URIUtils.decodeURIComponent(part);
			Resource resolvedResource = super.resolveResource(fullPath);
			if (resolvedResource instanceof ReadableResource) {
				// include a linefeed to make sure they are separated
				if (!containers.isEmpty()) {
					containers.add(IOUtils.wrap("\n".getBytes(), true));
					if (totalSize >= 0) {
						totalSize += 1;
					}
				}
				if (resolvedResource instanceof FiniteResource) {
					totalSize += ((FiniteResource) resolvedResource).getSize();
				}
				else {
					totalSize = -1;
				}
				containers.add(new ResourceReadableContainer((ReadableResource) resolvedResource));
			}
		}
		if (totalSize >= 0) {
			return new FiniteCombinedResource(containers, totalSize);
		}
		else {
			return new CombinedResource(containers);
		}
	}
	
	private class CombinedResource implements ReadableResource, TimestampedResource {

		private List<ReadableContainer<ByteBuffer>> containers;
		private Date lastModified = new Date();

		public CombinedResource(List<ReadableContainer<ByteBuffer>> containers) {
			this.containers = containers;
		}
		
		@Override
		public String getContentType() {
			return mimeType;
		}

		@Override
		public String getName() {
			return "automerged";
		}

		@Override
		public ResourceContainer<?> getParent() {
			return null;
		}

		@SuppressWarnings("unchecked")
		@Override
		public ReadableContainer<ByteBuffer> getReadable() throws IOException {
			return IOUtils.chain(true, containers.toArray(new ReadableContainer[containers.size()]));
		}

		@Override
		public Date getLastModified() {
			return lastModified;
		}
		
	}
	
	private class FiniteCombinedResource extends CombinedResource implements FiniteResource {

		private long totalSize;

		public FiniteCombinedResource(List<ReadableContainer<ByteBuffer>> containers, long totalSize) {
			super(containers);
			this.totalSize = totalSize;
		}

		@Override
		public long getSize() {
			return totalSize;
		}
		
	}
}
