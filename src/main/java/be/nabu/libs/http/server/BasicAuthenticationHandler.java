package be.nabu.libs.http.server;

import java.io.IOException;
import java.security.Principal;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.BasicPrincipal;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.RealmHandler;
import be.nabu.libs.http.api.server.ServerAuthenticationHandler;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.utils.codec.TranscoderUtils;
import be.nabu.utils.codec.impl.Base64Decoder;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

/**
 * It currently has no state so the client has to authenticate on every call
 */
public class BasicAuthenticationHandler implements EventHandler<HTTPRequest, HTTPResponse> {
	
	private ServerAuthenticationHandler authenticator;
	private RealmHandler realmHandler;
	
	public BasicAuthenticationHandler(ServerAuthenticationHandler handler) {
		this(handler, null);
	}
	
	public BasicAuthenticationHandler(ServerAuthenticationHandler handler, RealmHandler realmHandler) {
		this.authenticator = handler;
		this.realmHandler = realmHandler;
	}

	@Override
	public HTTPResponse handle(HTTPRequest request) {
		String realm = realmHandler == null ? "default" : realmHandler.getRealm(request);
		if (request.getContent() != null) {
			Header header = MimeUtils.getHeader("Authorization", request.getContent().getHeaders());
			if (header != null && header.getValue().substring(0, 5).equalsIgnoreCase("basic")) {
				HTTPUtils.setHeader(request.getContent(), ServerHeader.AUTHENTICATION_SCHEME, "basic");
				try {
					String decoded = new String(IOUtils.toBytes(TranscoderUtils.transcodeBytes(
						IOUtils.wrap(header.getValue().substring(6).getBytes("ASCII"), true), 
						new Base64Decoder())
					), "UTF-8");
					int index = decoded.indexOf(':');
					if (index <= 0) {
						throw new HTTPException(400, "The basic authentication is not of the correct format");
					}
					String username = decoded.substring(0, index);
					String password = decoded.substring(index + 1);
					Principal principal = new BasicPrincipalImpl(username, password);
					String userId = authenticator.authenticate(realm, principal);
					if (userId != null) {
						request.getContent().setHeader(new SimpleAuthenticationHeader(userId, principal));
						return null;
					}
				}
				catch (IOException e) {
					throw new HTTPException(500, e);
				}
			}
		}
		return new DefaultHTTPResponse(401, "Unauthorized", new PlainMimeEmptyPart(null, 
			new MimeHeader("Content-Length", "0"),
			new MimeHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\"")
		));
	}
	
	private static class BasicPrincipalImpl implements BasicPrincipal {

		private String name;
		private String password;

		public BasicPrincipalImpl(String name, String password) {
			this.name = name;
			this.password = password;
		}
		
		@Override
		public String getName() {
			return name;
		}

		@Override
		public String getPassword() {
			return password;
		}
	}
}
