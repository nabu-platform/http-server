package be.nabu.libs.http.server;

import java.security.cert.CertPath;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.security.auth.x500.X500Principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.server.ServerAuthenticationHandler;
import be.nabu.libs.http.api.server.RealmHandler;
import be.nabu.libs.http.api.server.SecurityHeader;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.http.core.ServerHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.security.SecurityUtils;

public class CertificateAuthenticationHandler implements EventHandler<HTTPRequest, HTTPResponse> {

	private ServerAuthenticationHandler authenticationHandler;
	private RealmHandler realmHandler;
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	public CertificateAuthenticationHandler(ServerAuthenticationHandler authenticationHandler) {
		this(authenticationHandler, null);
	}
	
	public CertificateAuthenticationHandler(ServerAuthenticationHandler authenticationHandler, RealmHandler realmHandler) {
		this.authenticationHandler = authenticationHandler;
		this.realmHandler = realmHandler;
	}

	@Override
	public HTTPResponse handle(HTTPRequest request) {
		if (request.getContent() != null) {
			String realm = realmHandler == null ? "default" : realmHandler.getRealm(request);
			SecurityHeader securityHeader = (SecurityHeader) MimeUtils.getHeader(ServerHeader.REQUEST_SECURITY.getName(), request.getContent().getHeaders());
			if (securityHeader != null) {
				Certificate[] peerCertificates = securityHeader.getPeerCertificates();
				if (peerCertificates != null) {
					CertPath certificatePath;
					try {
						certificatePath = SecurityUtils.generateCertificatePath(peerCertificates);
						Certificate certificate = certificatePath.getCertificates().get(0);
						X500Principal principal = ((X509Certificate) certificate).getSubjectX500Principal();
						String userId = authenticationHandler.authenticate(realm, principal);
						if (userId != null) {
							HTTPUtils.setHeader(request.getContent(), ServerHeader.AUTHENTICATION_SCHEME, "certificate");
							request.getContent().setHeader(new SimpleAuthenticationHeader(userId, principal));
						}
					}
					catch (CertificateException e) {
						logger.error("Could not authenticate with certificate", e);
					}
				}
			}
		}
		return null;
	}
}