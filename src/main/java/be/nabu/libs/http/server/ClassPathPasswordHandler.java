package be.nabu.libs.http.server;

import java.io.IOException;
import java.io.InputStream;

public class ClassPathPasswordHandler extends BasePasswordHandler {

	public ClassPathPasswordHandler() {
		this(false);
	}
	
	public ClassPathPasswordHandler(boolean forceDefaultRealm) {
		super(forceDefaultRealm);
	}
	
	@Override
	protected InputStream getInput(String fileName) throws IOException {
		return Thread.currentThread().getContextClassLoader().getResourceAsStream(fileName);
	}
}
