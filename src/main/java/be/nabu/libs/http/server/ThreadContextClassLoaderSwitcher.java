package be.nabu.libs.http.server;

import java.net.URI;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.HTTPException;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.impl.FormatException;

public class ThreadContextClassLoaderSwitcher implements EventHandler<HTTPRequest, HTTPRequest> {
	
	/**
	 * Sort the longest match to the front so we have the most specific classloader
	 */
	private Map<String, ClassLoader> classloaders = new TreeMap<String, ClassLoader>(new Comparator<String>() {
		@Override
		public int compare(String arg0, String arg1) {
			if (arg0 == null && arg1 == null) {
				return 0;
			}
			else if (arg0 == null) {
				return 1;
			}
			else if (arg1 == null) {
				return -1;
			}
			else {
				return arg1.length() - arg0.length();
			}
		}
	});
	
	public void register(ClassLoader loader, String serverPath, boolean regex) {
		classloaders.put(regex ? serverPath : serverPath + ".*", loader);
	}
	
	public void unregister(String serverPath) {
		classloaders.remove(serverPath);
	}

	@Override
	public HTTPRequest handle(HTTPRequest request) {
		try {
			URI uri = HTTPUtils.getURI(request, false);
			for (String path : classloaders.keySet()) {
				if (uri.getPath().matches(path)) {
					Thread.currentThread().setContextClassLoader(classloaders.get(path));
					break;
				}
			}
			return null;
		}
		catch (FormatException e) {
			throw new HTTPException(500, e);
		}
	}
}
