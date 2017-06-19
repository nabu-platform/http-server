package be.nabu.libs.http.server;

import java.io.IOException;
import java.security.Principal;

import be.nabu.libs.authentication.api.Authenticator;
import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.authentication.api.principals.BasicPrincipal;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPCodes;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.AuthenticationHeader;
import be.nabu.libs.http.api.server.RealmHandler;
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
	
	private Authenticator authenticator;
	private RealmHandler realmHandler;
	private boolean required = true;
	
	public BasicAuthenticationHandler(Authenticator authenticator) {
		this(authenticator, null);
	}
	
	public BasicAuthenticationHandler(Authenticator authenticator, RealmHandler realmHandler) {
		this.authenticator = authenticator;
		this.realmHandler = realmHandler;
	}

	@Override
	public HTTPResponse handle(HTTPRequest request) {
		String realm = realmHandler == null ? "default" : realmHandler.getRealm(request);
		if (request.getContent() != null) {
			// it is possible another handler set an authentication header, if that is the case, ignore the basic one (if any)
			AuthenticationHeader authenticationHeader = HTTPUtils.getAuthenticationHeader(request);
			if (authenticationHeader == null) {
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
						Token token = authenticator.authenticate(realm, principal);
						if (token != null) {
							request.getContent().setHeader(new SimpleAuthenticationHeader(token));
							return null;
						}
					}
					catch (IOException e) {
						throw new HTTPException(500, e);
					}
					catch (RuntimeException e) {
						throw new HTTPException(401, e);
					}
				}
			}
		}
		return required ? newAuthenticationRequiredResponse(request, realm) : null;
	}
	
	public static HTTPResponse newAuthenticationRequiredResponse(HTTPRequest request, String realm) {
		return new DefaultHTTPResponse(request, 401, HTTPCodes.getMessage(401), new PlainMimeEmptyPart(null, 
			new MimeHeader("Content-Length", "0"),
			new MimeHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\"")
		));
	}
	
	public static class BasicPrincipalImpl implements BasicPrincipal {

		private static final long serialVersionUID = 1L;
		private String name;
		private String password;

		public BasicPrincipalImpl() {
			// autoconstruct
		}
		
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

		public void setName(String name) {
			this.name = name;
		}

		public void setPassword(String password) {
			this.password = password;
		}
	}

	public boolean isRequired() {
		return required;
	}

	public void setRequired(boolean required) {
		this.required = required;
	}
	
}
