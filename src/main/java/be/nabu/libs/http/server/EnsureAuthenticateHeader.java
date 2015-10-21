package be.nabu.libs.http.server;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

/**
 * According to the spec, when sending back a 401, we should ensure that a WWW-Authenticate is set enabling the user to remedy the situation
 */
public class EnsureAuthenticateHeader implements EventHandler<HTTPResponse, HTTPResponse> {

	private String realm;

	public EnsureAuthenticateHeader(String realm) {
		this.realm = realm;
	}
	
	@Override
	public HTTPResponse handle(HTTPResponse event) {
		if (event.getCode() == 401 && event.getContent() != null && MimeUtils.getHeader("WWW-Authenticate", event.getContent().getHeaders()) == null) {
			event.getContent().setHeader(new MimeHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\""));
		}
		return null;
	}

}
