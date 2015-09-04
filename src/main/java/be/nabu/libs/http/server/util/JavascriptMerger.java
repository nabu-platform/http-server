package be.nabu.libs.http.server.util;

import java.util.regex.Pattern;

import be.nabu.libs.resources.api.ResourceContainer;

public class JavascriptMerger extends ResourceMerger {

	public JavascriptMerger(ResourceContainer<?> root, String serverPath) {
		super(root, serverPath, Pattern.compile("<script[^>]+src=(?:'|\")([^'\"]+)[^>]+>(?:[\\s]*</script>|)"), "application/javascript");
	}

	@Override
	protected String injectMerged(String content, String mergedURL) {
		return content.replaceAll("(<head[^>]*>)", "$1\n<script src='" + mergedURL + "'></script>");
	}

}
