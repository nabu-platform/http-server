package be.nabu.libs.http.server;

import be.nabu.libs.authentication.api.RoleHandler;
import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.AuthenticationHeader;
import be.nabu.libs.http.core.HTTPUtils;

public class RoleValidator implements EventHandler<HTTPRequest, HTTPResponse> {

	private RoleHandler roleHandler;
	private String[] roles;

	public RoleValidator(RoleHandler roleHandler, String...roles) {
		this.roleHandler = roleHandler;
		this.roles = roles;
	}
	
	@Override
	public HTTPResponse handle(HTTPRequest request) {
		AuthenticationHeader authenticationHeader = HTTPUtils.getAuthenticationHeader(request);
		Token token = authenticationHeader == null ? null : authenticationHeader.getToken();
		if (token == null) {
			throw new HTTPException(401);
		}
		boolean allowed = false;
		for (String role : roles) {
			if (roleHandler.hasRole(token, role)) {
				allowed = true;
				break;
			}
		}
		if (!allowed) {
			throw new HTTPException(403);
		}
		return null;
	}

}
