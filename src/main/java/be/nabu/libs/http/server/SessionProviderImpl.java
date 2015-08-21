package be.nabu.libs.http.server;

import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import be.nabu.libs.http.api.server.Session;
import be.nabu.libs.http.api.server.SessionProvider;

public class SessionProviderImpl implements SessionProvider {

	private Map<String, SessionImpl> sessions = new HashMap<String, SessionImpl>();
	
	/**
	 * A value of 0 means indefinite sessions
	 */
	private long sessionTimeout;
	
	public SessionProviderImpl(long sessionTimeout) {
		this.sessionTimeout = sessionTimeout;
	}
	
	@Override
	public Session getSession(String sessionId) {
		SessionImpl session = sessions.get(sessionId);
		if (session != null) {
			// if the session is timed out, remove it
			if (sessionTimeout > 0 && new Date().getTime() - session.getLastAccessed().getTime() >= sessionTimeout) {
				session.destroy();
				session = null;
			}
			else {
				session.setLastAccessed(new Date());
			}
		}
		return session;
	}

	@Override
	public Session newSession() {
		SessionImpl session = new SessionImpl(generateId());
		synchronized(sessions) {
			sessions.put(session.getId(), session);
		}
		return session;
	}
	
	/**
	 * The id that is generated must be non-predictable, otherwise it creates a surface for attack
	 * UUIDs are uniquely suited as they are globally unique and contain a secure random component, just make sure we use the correct type or we expose hardware information (MAC)
	 * The default implementation generates a type 4
	 */
	private String generateId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	private class SessionImpl implements Session {

		private Date lastAccessed = new Date();
		private Map<String, Object> context = new HashMap<String, Object>();
		private String id;
		
		public SessionImpl(String id) {
			this.id = id;
		}
		
		@Override
		public Iterator<String> iterator() {
			return context.keySet().iterator();
		}

		@Override
		public String getId() {
			return id;
		}

		@Override
		public Object get(String name) {
			return context.get(name);
		}

		@Override
		public void set(String name, Object value) {
			if (value == null) {
				context.remove(name);
			}
			else {
				context.put(name, value);
			}
		}

		@Override
		public void destroy() {
			synchronized(sessions) {
				sessions.remove(id);
			}
		}

		public Date getLastAccessed() {
			return lastAccessed;
		}

		public void setLastAccessed(Date lastAccessed) {
			this.lastAccessed = lastAccessed;
		}
		
	}
}
