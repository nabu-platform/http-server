package be.nabu.libs.http.server;

import be.nabu.libs.authentication.api.Token;
import be.nabu.libs.http.api.server.AuthenticationHeader;
import be.nabu.libs.http.core.ServerHeader;

public class SimpleAuthenticationHeader implements AuthenticationHeader {

	private Token token;

	public SimpleAuthenticationHeader(Token token) {
		this.token = token;
	}
	
	@Override
	public String getName() {
		return ServerHeader.REMOTE_USER.getName();
	}

	@Override
	public String getValue() {
		return token.getName();
	}

	@Override
	public String[] getComments() {
		return new String[0];
	}
	
	@Override
	public Token getToken() {
		return token;
	}
}
