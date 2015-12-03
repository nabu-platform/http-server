package be.nabu.libs.http.server;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.impl.FormatException;

public class RequestPathFilter implements EventHandler<HTTPResponse, Boolean> {

	private String path;
	private boolean isRegex, whitelist;

	public RequestPathFilter(String path) {
		this(path, false, true);
	}
	
	public RequestPathFilter(String path, boolean isRegex, boolean whitelist) {
		this.path = path;
		this.isRegex = isRegex;
		this.whitelist = whitelist;
		// make sure it is absolute
		if (!isRegex && !this.path.startsWith("/")) {
			this.path = "/" + this.path;
		}
	}
	
	@Override
	public Boolean handle(HTTPResponse response) {
		HTTPRequest request = response instanceof LinkableHTTPResponse ? ((LinkableHTTPResponse) response).getRequest() : null;
		if (request == null) {
			return false;
		}
		try {
			if (whitelist) {
				return isRegex
					? !HTTPUtils.getURI(request, false).getPath().matches(path)
					: !HTTPUtils.getURI(request, false).getPath().startsWith(path);
			}
			else {
				return isRegex
					? HTTPUtils.getURI(request, false).getPath().matches(path)
					: HTTPUtils.getURI(request, false).getPath().startsWith(path);
			}
		}
		catch (FormatException e) {
			return false;
		}
	}

}
