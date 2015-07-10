package be.nabu.libs.http.server;

import java.net.URI;
import java.net.URISyntaxException;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.resources.URIUtils;
import be.nabu.utils.mime.impl.FormatException;

public class RequestPathRewriter implements EventHandler<HTTPRequest, HTTPRequest> {

	private String regexToMatch;
	private String replacement;

	public RequestPathRewriter(String regexToMatch, String replacement) {
		this.regexToMatch = regexToMatch;
		this.replacement = replacement;
	}
	
	@Override
	public HTTPRequest handle(HTTPRequest request) {
		try {
			URI original = HTTPUtils.getURI(request, false);
			if (original.getPath().matches(regexToMatch)) {
				URI uri = new URI(URIUtils.encodeURI(request.getTarget()));
				String newPath = original.getPath().replaceAll(regexToMatch, replacement);
				if (!newPath.startsWith("/")) {
					newPath = "/" + newPath;
				}
				uri = new URI(uri.getScheme(), uri.getAuthority(), newPath, uri.getQuery(), uri.getFragment());
				return new DefaultHTTPRequest(
					request.getMethod(),
					uri.toString(),
					request.getContent()
				);
			}
		}
		catch (FormatException e) {
			throw new HTTPException(500, e);
		}
		catch (URISyntaxException e) {
			throw new HTTPException(500, e);
		}
		return null;
	}
	
}
