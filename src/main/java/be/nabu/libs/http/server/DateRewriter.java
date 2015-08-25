package be.nabu.libs.http.server;

import java.util.Date;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

public class DateRewriter implements EventHandler<HTTPResponse, HTTPResponse> {

	@Override
	public HTTPResponse handle(HTTPResponse response) {
		if (response.getVersion() > 1.0 && response.getContent() != null) {
			Header header = MimeUtils.getHeader("Date", response.getContent().getHeaders());
			if (header == null) {
				response.getContent().setHeader(new MimeHeader("Date", HTTPUtils.formatDate(new Date())));
			}
		}
		return null;
	}

}
