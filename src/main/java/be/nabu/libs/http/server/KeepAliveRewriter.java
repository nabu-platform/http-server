package be.nabu.libs.http.server;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

public class KeepAliveRewriter implements EventHandler<HTTPResponse, HTTPResponse> {

	@Override
	public HTTPResponse handle(HTTPResponse response) {
		if (response.getVersion() > 1.0 && response.getContent() != null) {
			Header header = MimeUtils.getHeader("Connection", response.getContent().getHeaders());
			if (header == null) {
				response.getContent().setHeader(new MimeHeader("Connection", "Keep-Alive"));
			}
		}
		return null;
	}

}
