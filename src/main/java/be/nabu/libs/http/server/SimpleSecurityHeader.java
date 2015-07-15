package be.nabu.libs.http.server;

import java.security.cert.Certificate;

import javax.net.ssl.SSLContext;

import be.nabu.libs.http.api.server.SecurityHeader;
import be.nabu.libs.http.core.ServerHeader;

public class SimpleSecurityHeader implements SecurityHeader {

	private SSLContext context;
	private Certificate[] peerCertificates;

	public SimpleSecurityHeader(SSLContext context, Certificate [] peerCertificates) {
		this.context = context;
		this.peerCertificates = peerCertificates;
	}
	
	@Override
	public String getName() {
		return ServerHeader.REQUEST_SECURITY.getName();
	}

	@Override
	public String getValue() {
		return context == null ? "none" : context.getProtocol();
	}

	@Override
	public String[] getComments() {
		return new String[0];
	}

	@Override
	public SSLContext getSecurityContext() {
		return context;
	}

	@Override
	public Certificate[] getPeerCertificates() {
		return peerCertificates;
	}
}
