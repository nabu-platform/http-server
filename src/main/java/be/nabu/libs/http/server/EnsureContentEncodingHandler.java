package be.nabu.libs.http.server;

import java.util.List;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

public class EnsureContentEncodingHandler implements EventHandler<HTTPResponse, HTTPResponse> {

	@Override
	public HTTPResponse handle(HTTPResponse response) {
		if (response.getContent() != null) {
			HTTPRequest request = response instanceof LinkableHTTPResponse ? ((LinkableHTTPResponse) response).getRequest() : null;
			if (request != null && request.getContent() != null && MimeUtils.getHeader("Content-Encoding", response.getContent().getHeaders()) == null) {
				List<String> acceptedEncodings = MimeUtils.getAcceptedEncodings(request.getContent().getHeaders());
				if (acceptedEncodings.contains("gzip")) {
					response.getContent().setHeader(new MimeHeader("Content-Encoding", "gzip"));
				}
				else if (acceptedEncodings.contains("deflate")) {
					response.getContent().setHeader(new MimeHeader("Content-Encoding", "deflate"));
				}
			}
		}
		return null;
	}

}
