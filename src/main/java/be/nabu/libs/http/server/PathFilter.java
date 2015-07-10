package be.nabu.libs.http.server;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.impl.FormatException;

public class PathFilter implements EventHandler<HTTPRequest, Boolean> {

	private String path;
	private boolean isRegex;

	public PathFilter(String path) {
		this(path, false);
	}
	
	public PathFilter(String path, boolean isRegex) {
		this.path = path;
		this.isRegex = isRegex;
		// make sure it is absolute
		if (!isRegex && !this.path.startsWith("/")) {
			this.path = "/" + this.path;
		}
	}
	
	@Override
	public Boolean handle(HTTPRequest request) {
		try {
			return isRegex
				? !HTTPUtils.getURI(request, false).getPath().matches(path)
				: !HTTPUtils.getURI(request, false).getPath().startsWith(path);
		}
		catch (FormatException e) {
			return false;
		}
	}

}
