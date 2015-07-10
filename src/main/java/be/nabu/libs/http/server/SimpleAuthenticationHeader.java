package be.nabu.libs.http.server;

import java.security.Principal;

import be.nabu.libs.http.api.server.AuthenticationHeader;
import be.nabu.libs.http.core.ServerHeader;

public class SimpleAuthenticationHeader implements AuthenticationHeader {

	private Principal principal;

	public SimpleAuthenticationHeader(Principal principal) {
		this.principal = principal;
	}
	
	@Override
	public String getName() {
		return ServerHeader.NAME_REMOTE_USER;
	}

	@Override
	public String getValue() {
		return principal.getName();
	}

	@Override
	public String[] getComments() {
		return new String[0];
	}
	
	public Principal getPrincipal() {
		return principal;
	}

}
