package be.nabu.libs.http.server;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.utils.mime.impl.MimeUtils;

public class AbsenceOfHeadersValidator implements EventHandler<HTTPRequest, HTTPResponse> {
	
	private String[] headersToCheck;
	private boolean fail;

	public AbsenceOfHeadersValidator(boolean fail, String...headersToCheck) {
		this.fail = fail;
		if (headersToCheck == null || headersToCheck.length == 0) {
			headersToCheck = new String[ServerHeader.values().length];
			for (int i = 0; i < ServerHeader.values().length; i++) {
				headersToCheck[i] = ServerHeader.values()[i].getName();
			}
		}
		this.headersToCheck = headersToCheck;
	}
	
	@Override
	public HTTPResponse handle(HTTPRequest request) {
		if (request.getContent() != null) {
			if (fail) {
				for (String header : headersToCheck) {
					if (MimeUtils.getHeader(header, request.getContent().getHeaders()) != null) {
						throw new HTTPException(400, "Header not allowed: " + header);
					}
				}
			}
			else {
				request.getContent().removeHeader(headersToCheck);
			}
		}
		return null;
	}

}
