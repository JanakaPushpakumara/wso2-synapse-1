/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.synapse.transport.http.conn;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.apache.http.conn.ssl.AbstractVerifier;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.nio.reactor.IOSession;
import org.apache.http.nio.reactor.ssl.SSLSetupHandler;
import org.apache.synapse.transport.certificatevalidation.CertificateVerificationException;
import org.apache.synapse.transport.certificatevalidation.CertificateVerificationManager;

public class ClientSSLSetupHandler implements SSLSetupHandler {

    private final static String[] LOCALHOSTS = {"::1", "127.0.0.1",
            "localhost",
            "localhost.localdomain"};

    /** Enabled SSL handshake protocols (e.g. SSLv3, TLSv1) */
    private String[] httpsProtocols;

    /**
     * Preferred Cipher suites config (e.g. TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384,TLS_RSA_WITH_AES_256_CBC_SHA256)
     **/
    private String[] preferredCiphers;

    static {
        Arrays.sort(LOCALHOSTS);
    }

    static boolean isLocalhost(String host) {
        host = host != null ? host.trim().toLowerCase() : "";
        if (host.startsWith("::1")) {
            int x = host.lastIndexOf('%');
            if (x >= 0) {
                host = host.substring(0, x);
            }
        }
        int x = Arrays.binarySearch(LOCALHOSTS, host);
        return x >= 0;
    }

    /**
     * The DEFAULT HostnameVerifier works the same way as Curl and Firefox.
     * <p/>
     * The hostname must match either the first CN, or any of the subject-alts.
     * A wildcard can occur in the CN, and in any of the subject-alts.
     * <p/>
     * The only difference between DEFAULT and STRICT is that a wildcard (such
     * as "*.foo.com") with DEFAULT matches all subdomains, including
     * "a.b.foo.com".
     */
    public final static X509HostnameVerifier DEFAULT = new AbstractVerifier() {

        public void verify(
                final String host, final String[] cns, final String[] subjectAlts) throws SSLException {
            verify(host, cns, subjectAlts, false);
        }

    };

    /**
     * The DEFAULT_AND_LOCALHOST HostnameVerifier works like the DEFAULT
     * one with one additional relaxation:  a host of "localhost",
     * "localhost.localdomain", "127.0.0.1", "::1" will always pass, no matter
     * what is in the server's certificate.
     */
    public final static X509HostnameVerifier DEFAULT_AND_LOCALHOST = new AbstractVerifier() {

        public void verify(
                final String host, final String[] cns, final String[] subjectAlts) throws SSLException {
            if (isLocalhost(host)) {
                return;
            }
            verify(host, cns, subjectAlts, false);
        }
    };

    /**
     * The STRICT HostnameVerifier works the same way as java.net.URL in Sun
     * Java 1.4, Sun Java 5, Sun Java 6.  It's also pretty close to IE6.
     * This implementation appears to be compliant with RFC 2818 for dealing
     * with wildcards.
     * <p/>
     * The hostname must match either the first CN, or any of the subject-alts.
     * A wildcard can occur in the CN, and in any of the subject-alts.  The
     * one divergence from IE6 is how we only check the first CN.  IE6 allows
     * a match against any of the CNs present.  We decided to follow in
     * Sun Java 1.4's footsteps and only check the first CN.
     * <p/>
     * A wildcard such as "*.foo.com" matches only subdomains in the same
     * level, for example "a.foo.com".  It does not match deeper subdomains
     * such as "a.b.foo.com".
     */
    public final static X509HostnameVerifier STRICT = new AbstractVerifier() {

        public void verify(
                final String host, final String[] cns, final String[] subjectAlts) throws SSLException {
            verify(host, cns, subjectAlts, true);
        }

    };

    /**
     * The ALLOW_ALL HostnameVerifier essentially turns hostname verification
     * off.  This implementation is a no-op, and never throws the SSLException.
     */
    public final static X509HostnameVerifier ALLOW_ALL = new AbstractVerifier() {

        public void verify(
                final String host, final String[] cns, final String[] subjectAlts) throws SSLException {
        }

    };

    private final X509HostnameVerifier hostnameVerifier;
    private final CertificateVerificationManager verificationManager;

    public ClientSSLSetupHandler(final X509HostnameVerifier hostnameVerifier,
                                 final CertificateVerificationManager verificationManager) {
        this.hostnameVerifier = hostnameVerifier != null ? hostnameVerifier : DEFAULT;
        this.verificationManager = verificationManager;
    }

    public void initalize(SSLEngine sslengine) {
        /*
            set handshake protocols if they are specified in transport configuration.
            eg: <parameter name="HttpsProtocols">TLSv1.1,TLSv1.2</parameter>
        */
        if(null != httpsProtocols) {
            sslengine.setEnabledProtocols(httpsProtocols);
        }

        if (preferredCiphers != null) {
            sslengine.setEnabledCipherSuites(preferredCiphers);
        }
    }

    public void verify(IOSession iosession, SSLSession sslsession) throws SSLException {
        SocketAddress remoteAddress = iosession.getRemoteAddress();
        String address;
        String endpoint = (String) iosession.getAttribute(SynapseHTTPRequestFactory.ENDPOINT_URL);
        if (endpoint != null && !endpoint.isEmpty()) {
            try {
                URI endpointURI = new URI(endpoint);
                address = endpointURI.getHost();
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("Invalid endpointURI: "+ endpoint, e);
            }

        } else if (remoteAddress instanceof InetSocketAddress) {
            address = ((InetSocketAddress) remoteAddress).getHostName();
        } else {
            address = remoteAddress.toString();
        }
        if (!hostnameVerifier.verify(address, sslsession)) {
            throw new SSLException("Host name verification failed for host : " + address);
        }

        if (verificationManager!=null) {
            try {
                verificationManager.verifyCertificateValidity(sslsession.getPeerCertificateChain());
            } catch (CertificateVerificationException e) {
                throw new SSLException("Certificate Chain Validation failed for host : " + address, e);
            }
        }
    }

    /**
     * Set HTTPS protocols if mentioned in axis2 configuration
     *
     * @param httpsProtocols  Array of protocols
     */
    public void setHttpsProtocols(String[] httpsProtocols) {
        this.httpsProtocols = httpsProtocols;
    }

    /**
     * Set the enabled Cipher suites. All the ciphers will be enabled if not specified
     *
     * @param enabledCiphers Array of ciphers
     */
    public void setPreferredCiphers(String[] enabledCiphers) {
        this.preferredCiphers = enabledCiphers;
    }

}