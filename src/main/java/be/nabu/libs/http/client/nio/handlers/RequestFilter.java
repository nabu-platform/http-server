package be.nabu.libs.http.client.nio.handlers;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;

public class RequestFilter implements EventHandler<HTTPResponse, Boolean> {

	private HTTPRequest request;

	public RequestFilter(HTTPRequest request) {
		this.request = request;
	}
	
	@Override
	public Boolean handle(HTTPResponse event) {
		HTTPRequest request = event instanceof LinkableHTTPResponse ? ((LinkableHTTPResponse) event).getRequest() : null;
		// if we can't find the original request or it doesn't match the expected request, filter it
		if (request == null || !request.equals(this.request)) {
			return true;
		}
		return false;
	}

}
