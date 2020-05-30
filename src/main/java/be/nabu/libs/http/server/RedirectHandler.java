package be.nabu.libs.http.server;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class RedirectHandler implements EventHandler<HTTPRequest, HTTPResponse> {

	private String redirect;
	private String path;
	private boolean permanent;

	public RedirectHandler(String path, String redirect, boolean permanent) {
		this.path = path;
		this.redirect = redirect;
		this.permanent = permanent;
	}
	
	@Override
	public HTTPResponse handle(HTTPRequest request) {
		try {
			if (HTTPUtils.getURI(request, false).getPath().startsWith(path)) {
				return new DefaultHTTPResponse(request,
						permanent ? 301 : 307, 
						permanent ? HTTPCodes.getMessage(301) : HTTPCodes.getMessage(307), new PlainMimeEmptyPart(null, 
					new MimeHeader("Location", redirect),
					new MimeHeader("Content-Length", "0")
				));
			}
		}
		catch (FormatException e) {
			throw new HTTPException(500, e);
		}
		return null;
	}
}
