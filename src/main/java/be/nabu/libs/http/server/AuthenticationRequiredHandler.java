package be.nabu.libs.http.server;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class AuthenticationRequiredHandler implements EventHandler<HTTPRequest, HTTPResponse> {

	private String challenge;

	public AuthenticationRequiredHandler(String challenge) {
		this.challenge = challenge;
	}
	
	@Override
	public HTTPResponse handle(HTTPRequest event) {
		Header header = MimeUtils.getHeader(ServerHeader.REMOTE_USER.getName(), event.getContent().getHeaders());
		if (header == null) {
			PlainMimeEmptyPart content = new PlainMimeEmptyPart(null, 
				new MimeHeader("Content-Length", "0")
			);
			if (challenge != null) {
				content.setHeader(new MimeHeader("WWW-Authenticate", challenge));
			}
			return new DefaultHTTPResponse(event, 401, HTTPCodes.getMessage(401), content);
		}
		return null;
	}

}
