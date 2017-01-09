package be.nabu.libs.http.client.nio;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import be.nabu.libs.http.api.client.ClientAuthenticationHandler;

/**
 * Supports basic & digest
 */
public class SPIAuthenticationHandler implements ClientAuthenticationHandler {

	private List<ClientAuthenticationHandler> handlers;

	private List<ClientAuthenticationHandler> getHandlers() {
		if (handlers == null) {
			synchronized(this) {
				if (handlers == null) {
					List<ClientAuthenticationHandler> handlers = new ArrayList<ClientAuthenticationHandler>();
					for (ClientAuthenticationHandler handler : ServiceLoader.load(ClientAuthenticationHandler.class)) {
						handlers.add(handler);
					}
					this.handlers = handlers;
				}
			}
		}
		return handlers;
	}

	@Override
	public String authenticate(Principal principal, String challenge) {
		for (ClientAuthenticationHandler handler : getHandlers()) {
			String response = handler.authenticate(principal, challenge);
			if (response != null)
				return response;
		}
		return null;
	}
}
