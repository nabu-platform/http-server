package be.nabu.libs.http.server;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.server.RealmHandler;

public class FixedRealmHandler implements RealmHandler {

	private String realm;
	
	public FixedRealmHandler(String realm) {
		this.realm = realm;
	}

	@Override
	public String getRealm(HTTPRequest request) {
		return realm;
	}
}
