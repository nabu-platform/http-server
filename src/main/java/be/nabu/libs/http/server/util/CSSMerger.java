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
