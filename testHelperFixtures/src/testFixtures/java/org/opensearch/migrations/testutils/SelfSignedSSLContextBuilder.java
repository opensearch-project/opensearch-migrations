package org.opensearch.migrations.testutils;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

public class SelfSignedSSLContextBuilder {
    public static final char[] KEYSTORE_PASSWORD = "".toCharArray();

    private static KeyStore buildKeyStoreForTesting() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null); // don't load from file, load a new key on the next line
        keyStore.setKeyEntry("selfsignedtestcert", keyPair.getPrivate(), KEYSTORE_PASSWORD,
                new X509Certificate[]{generateSelfSignedCertificate(keyPair)});
        return keyStore;
    }

    private static X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws OperatorCreationException, CertificateException {
        var startValidityInstant = Instant.now();
        var validityEndDate = Date.from(startValidityInstant.plus(Duration.ofHours(1)));
        var validityStartDate = Date.from(startValidityInstant);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider(new BouncyCastleProvider())
                .build(keyPair.getPrivate());
        var certBuilder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=localhost"), // use your domain here
                new BigInteger(64, new SecureRandom()),
                validityStartDate,
                validityEndDate,
                new X500Name("CN=localhost"), // use your domain here
                keyPair.getPublic()
        );

        return new JcaX509CertificateConverter().getCertificate(certBuilder.build(signer));
    }

    public static SSLContext getSSLContext() throws Exception {
        KeyStore ks = buildKeyStoreForTesting();
        //ks.load(fis, KEYSTORE_PASSWORD);

        var kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, KEYSTORE_PASSWORD);

        var tmf = TrustManagerFactory.getInstance("SunX509");
        tmf.init(ks);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return sslContext;
    }
}
