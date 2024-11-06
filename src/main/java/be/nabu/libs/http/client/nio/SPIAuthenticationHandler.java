/*
* Copyright (C) 2015 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

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
	
	public static SPIAuthenticationHandler instance = new SPIAuthenticationHandler();
	
	public static SPIAuthenticationHandler getInstance() {
		return instance;
	}

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
