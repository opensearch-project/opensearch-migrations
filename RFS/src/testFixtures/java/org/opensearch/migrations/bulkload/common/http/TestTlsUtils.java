package org.opensearch.migrations.bulkload.common.http;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public class TestTlsUtils {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static class CertificateBundle {
        public final X509Certificate certificate;
        public final PrivateKey privateKey;
        public final String pemCertificate;
        public final String pemPrivateKey;

        public CertificateBundle(X509Certificate cert, PrivateKey key) throws Exception {
            this.certificate = cert;
            this.privateKey = key;
            this.pemCertificate = toPEM(cert);
            this.pemPrivateKey = toPEM(key);
        }

        public InputStream getCertificateInputStream() {
            return new ByteArrayInputStream(pemCertificate.getBytes());
        }

        public InputStream getPrivateKeyInputStream() {
            return new ByteArrayInputStream(pemPrivateKey.getBytes());
        }

        private static String toPEM(Object obj) throws Exception {
            try (StringWriter sw = new StringWriter(); JcaPEMWriter writer = new JcaPEMWriter(sw)) {
                writer.writeObject(obj);
                writer.flush();
                return sw.toString();
            }
        }
    }

    public static CertificateBundle generateCaCertificate() throws Exception {
        KeyPair caKeyPair = generateKeyPair();
        X500Name subject = new X500Name("CN=Test CA");
        X509v3CertificateBuilder builder = createCertBuilder(subject, subject, caKeyPair.getPublic());

        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        X509Certificate cert = signCertificate(builder, caKeyPair.getPrivate());
        return new CertificateBundle(cert, caKeyPair.getPrivate());
    }

    public static CertificateBundle generateServerCertificate(CertificateBundle ca) throws Exception {
        return generateSignedCertificate("CN=localhost", ca, false);
    }

    public static CertificateBundle generateClientCertificate(CertificateBundle ca) throws Exception {
        return generateSignedCertificate("CN=client", ca, false);
    }

    private static CertificateBundle generateSignedCertificate(String subjectDn, CertificateBundle ca, boolean isCa)
            throws Exception {
        KeyPair keyPair = generateKeyPair();
        X500Name subject = new X500Name(subjectDn);
        X500Name issuer = new X500Name(ca.certificate.getSubjectX500Principal().getName());

        X509v3CertificateBuilder builder = createCertBuilder(issuer, subject, keyPair.getPublic());

        if (isCa) {
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        } else {
            builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        }

        X509Certificate cert = signCertificate(builder, ca.privateKey);
        return new CertificateBundle(cert, keyPair.getPrivate());
    }

    private static X509v3CertificateBuilder createCertBuilder(X500Name issuer, X500Name subject, PublicKey pubKey) {
        Instant now = Instant.now();
        return new JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now),
                Date.from(now.plusSeconds(365 * 24 * 60 * 60)),
                subject,
                pubKey
        );
    }

    private static X509Certificate signCertificate(X509v3CertificateBuilder builder, PrivateKey signerKey)
            throws Exception {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(signerKey);
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
