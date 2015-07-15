package be.nabu.libs.http.server;

import java.security.Principal;

import be.nabu.libs.http.api.server.ServerAuthenticationHandler;

public class AcceptAllAuthenticationHandler implements ServerAuthenticationHandler {

	@Override
	public String authenticate(String realm, Principal principal) {
		return realm + "." + principal.getName();
	}

}
