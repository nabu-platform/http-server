package be.nabu.libs.http.client.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.nio.api.ExceptionFormatter;

public class HTTPClientExceptionFormatter implements ExceptionFormatter<HTTPResponse, HTTPRequest> {

	private Logger logger = LoggerFactory.getLogger(getClass());
	
	@Override
	public HTTPRequest format(HTTPResponse response, Exception e) {
		logger.error("Exception occurred", e);
		return null;
	}

}
