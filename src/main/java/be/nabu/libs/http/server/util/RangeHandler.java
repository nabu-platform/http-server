package be.nabu.libs.http.server.util;

import java.util.Date;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.LinkableHTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.api.ContentPart;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

// this handler adds support for range
// note that the actual ranging is done by the formatter!
public class RangeHandler implements EventHandler<HTTPResponse, HTTPResponse> {

	// if there is a 304, we can ignore the range request (as per the spec)
	@Override
	public HTTPResponse handle(HTTPResponse event) {
		if (event instanceof LinkableHTTPResponse && event.getCode() == 200 && event.getContent() instanceof ContentPart) {
			Long contentLength = MimeUtils.getContentLength(event.getContent().getHeaders());
			if (contentLength != null) {
				HTTPRequest request = ((LinkableHTTPResponse) event).getRequest();
				if (request != null && request.getMethod().equalsIgnoreCase("GET") && request.getContent() != null) {
					// check if we already got a range header, if so, cut the response in pieces
					Header rangeHeader = MimeUtils.getHeader("Range", request.getContent().getHeaders());
					if (rangeHeader != null) {
						// check if we have an "if-range" header that needs to be validated against etag/last modified (never both)
						Header ifRange = MimeUtils.getHeader("If-Range", request.getContent().getHeaders());
						if (ifRange != null) {
							boolean validated = false;
							if (ifRange.getValue().endsWith("GMT")) {
								try {
									Date rangeDate = HTTPUtils.parseDate(ifRange.getValue());
									Header lastModified = MimeUtils.getHeader("Last-Modified", event.getContent().getHeaders());
									if (lastModified != null) {
										Date lastModifiedDate = HTTPUtils.parseDate(lastModified.getValue());
										// if we modified the resource in the future, don't range it
										if (lastModifiedDate.after(rangeDate)) {
											return null;
										}
										// not changed, we're good
										else {
											validated = true;
										}
									}
									// no last modified, should be good?
									else {
										validated = true;
									}
								}
								catch (Exception e) {
									// ignore, it is likely not a date
								}
							}
							if (!validated) {
								// first check the etag, if any
								Header etag = MimeUtils.getHeader("ETag", request.getContent().getHeaders());
								// we have a mismatched etag
								if (etag != null && !etag.getValue().equals(ifRange.getValue())) {
									return null;
								}
							}
						}
						
						String[] split = rangeHeader.getValue().split("[\\s]*,[\\s]*");
						// we don't support multiple ranges yet
						if (split.length > 1) {
							throw new HTTPException(416, "No support yet for multiple ranges");
						}
						split = split[0].split("[\\s]*=[\\s]*");
						if (split.length != 2) {
							throw new HTTPException(416, "Invalid range header, need 1 equals: " + rangeHeader.getValue());
						}
						else if (!split[0].equalsIgnoreCase("bytes")) {
							throw new HTTPException(416, "Invalid range header, only bytes are supported: " + rangeHeader.getValue());
						}
						Long from = null, to = null;
						// we only have a to
						if (split[1].startsWith("-")) {
							to = Long.parseLong(split[1].substring(1));
						}
						// we only have a from
						else if (split[1].endsWith("-")) {
							from = Long.parseLong(split[1].substring(0, split[1].length() - 1));
						}
						else {
							split = split[1].split("[\\s]*-[\\s]*");
							if (split.length != 2) {
								throw new HTTPException(416, "Invalid range header, need from and to range: " + rangeHeader.getValue());
							}
							from = split[0].trim().isEmpty() ? null : Long.parseLong(split[0]);
							to = split[1].trim().isEmpty() ? null : Long.parseLong(split[1]);
						}
						
						if (from != null && from < 0) {
							throw new HTTPException(416, "Invalid range header, from can not be negative: " + rangeHeader.getValue());
						}
						if (to != null && to >= contentLength) {
							throw new HTTPException(416, "Invalid range header, to can not be larger than the entire content: " + rangeHeader.getValue());
						}
						try {
							Long newLength = contentLength;
//							ReadableContainer<ByteBuffer> readable = ((ContentPart) event.getContent()).getReadable();
							if (from != null && from >= 0) {
//								IOUtils.skipBytes(readable, from);
								if (to != null) {
//									readable = IOUtils.limitReadable(readable, to - from);
									newLength = to - from;
								}
								else {
									newLength -= from;
									to = contentLength - 1;
								}
							}
//							// if we have no from, but we have a to, it is actually a "suffix-length", so basically you get the last x bytes
							else if (from == null && to != null) {
//								IOUtils.skipBytes(readable, contentLength - to);
								// update from & to
								newLength = to;
								from = contentLength - to;
								to = contentLength - 1;
							}
//							PlainMimeContentPart contentPart = new PlainMimeContentPart(null, readable, event.getContent().getHeaders());
//							contentPart.removeHeader("Content-Length");
//							contentPart.setHeader(new MimeHeader("Content-Length", newLength.toString()));
//							contentPart.setHeader(new MimeHeader("Content-Range", "bytes " + from + "-" + to + "/" + contentLength));
//							contentPart.setHeader(new MimeHeader("Accept-Ranges", "bytes"));
//							event = new DefaultHTTPResponse(request, 206, HTTPCodes.getMessage(206), contentPart);
//							return event;
							
							event.getContent().removeHeader("Content-Length");
							event.getContent().setHeader(new MimeHeader("Content-Length", newLength.toString()));
							event.getContent().setHeader(new MimeHeader("Content-Range", "bytes " + from + "-" + to + "/" + contentLength));
							event.getContent().setHeader(new MimeHeader("Accept-Ranges", "bytes"));
							// create a new event for the 206 response
							event = new DefaultHTTPResponse(request, 206, HTTPCodes.getMessage(206), event.getContent());
							return event;
						}
						catch (Exception e) {
							throw new HTTPException(416, "Invalid range header: " + rangeHeader.getValue(), e);
						}
					}
				}
				// indicate that we support ranges
				event.getContent().setHeader(new MimeHeader("Accept-Ranges", "bytes"));
			}
		}
		return null;
	}

}
