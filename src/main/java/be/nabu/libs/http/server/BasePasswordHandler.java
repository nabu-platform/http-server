package be.nabu.libs.http.server;

import java.io.IOException;
import java.io.InputStream;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import be.nabu.libs.http.api.BasicPrincipal;
import be.nabu.libs.http.api.server.ServerAuthenticationHandler;

abstract public class BasePasswordHandler implements ServerAuthenticationHandler {
	
	private Map<String, Properties> realms = new HashMap<String, Properties>();
	
	private boolean forceDefaultRealm;
	
	public BasePasswordHandler(boolean forceDefaultRealm) {
		this.forceDefaultRealm = forceDefaultRealm;
	}

	@Override
	public String authenticate(String realm, Principal principal) {
		return getRealm(realm).containsKey(principal.getName()) && getRealm(realm).get(principal.getName()).equals(((BasicPrincipal) principal).getPassword())
			? realm + "." + principal.getName()
			: null;
	}
	
	abstract protected InputStream getInput(String fileName) throws IOException;
	
	private Properties getRealm(String name) {
		if (forceDefaultRealm) {
			name = "default";
		}
		if (!realms.containsKey(name)) {
			synchronized(realms) {
				if (!realms.containsKey(name)) {
					try {
						InputStream input = getInput("realm." + cleanup(name) + ".properties");
						if (input == null) {
							throw new IllegalArgumentException("Non existent realm: " + name);
						}
						try {
							Properties properties = new Properties();
							properties.load(input);
							realms.put(name, properties);
						}
						finally {
							input.close();
						}
					}
					catch (IOException e) {
						throw new RuntimeException("Could not load realm " + name, e);
					}
				}
			}
		}
		return realms.get(name);
	}

	private String cleanup(String name) {
		return name.replaceAll("[^\\w._-]+", ".");
	}
}
