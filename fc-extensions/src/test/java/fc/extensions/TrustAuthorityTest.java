package fc.extensions;

import fc.testkit.FcTest;
import fc.testkit.TestRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import javax.net.ssl.SSLContext;

/** 自签根、X.509 签发、PKIX 链构建/校验与密钥库往返。 */
public final class TrustAuthorityTest {

    private static final char[] PASSWORD = TrustAuthority.DEFAULT_PASSWORD.toCharArray();

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(TrustAuthorityTest.class))) {
            System.exit(1);
        }
    }

    @FcTest
    public void rootIsSelfSignedAndCarriesCaConstraints() throws Exception {
        TrustAuthority.KeyMaterial root = TrustAuthority.root("Frontier Chronicle Root CA");
        X509Certificate certificate = root.certificate();

        certificate.verify(root.keyPair().getPublic());
        assert certificate.getSubjectX500Principal()
                .equals(certificate.getIssuerX500Principal()) : "根证书应自签";
        assert certificate.getVersion() == 3 : certificate.getVersion();
        assert certificate.getBasicConstraints() >= 0 : "根证书必须是 CA";
        boolean[] usage = certificate.getKeyUsage();
        assert usage != null && usage[5] && usage[6] : "根证书应可签发证书与 CRL";
        assert certificate.getSigAlgName().equalsIgnoreCase("SHA256withRSA")
                : certificate.getSigAlgName();
        String fingerprint = TrustAuthority.fingerprint(certificate);
        assert fingerprint.length() == 64 : fingerprint;
        assert fingerprint.matches("[0-9a-f]{64}") : fingerprint;
    }

    @FcTest
    public void serverLeafVerifiesAgainstItsIssuer() throws Exception {
        TrustAuthority.KeyMaterial root = TrustAuthority.root("FC Chain Root");
        TrustAuthority.KeyMaterial server = TrustAuthority.server(root, "localhost");
        X509Certificate leaf = server.certificate();

        leaf.verify(root.keyPair().getPublic());
        assert leaf.getIssuerX500Principal()
                .equals(root.certificate().getSubjectX500Principal());
        assert leaf.getBasicConstraints() == -1 : "叶子证书不得是 CA";
        List<String> purposes = leaf.getExtendedKeyUsage();
        assert purposes != null && purposes.contains("1.3.6.1.5.5.7.3.1")
                : "服务端证书应带 serverAuth";
        assert leaf.getSubjectAlternativeNames() != null
                && !leaf.getSubjectAlternativeNames().isEmpty() : "服务端证书应有 SAN";

        CertificateChains.verify(leaf, root.certificate());
        String described = CertificateChains.describe(leaf);
        assert described.contains("CN=localhost") : described;
        assert described.contains("SHA-256") && described.contains("剩") : described;
    }

    @FcTest
    public void intermediateChainIsBuiltFromCandidatePool() throws Exception {
        TrustAuthority.KeyMaterial root = TrustAuthority.root("FC Root");
        TrustAuthority.KeyMaterial middle = TrustAuthority.intermediate(root, "FC Middle CA");
        TrustAuthority.KeyMaterial server = TrustAuthority.server(middle, "localhost");

        assert middle.certificate().getVersion() == 3;
        assert middle.certificate().getBasicConstraints() >= 0 : "中间证书必须是 CA";
        server.certificate().verify(middle.keyPair().getPublic());
        middle.certificate().verify(root.keyPair().getPublic());

        CertificateChains.verify(server.certificate(), root.certificate(),
                middle.certificate());

        CertPath path = CertificateChains.build(server.certificate(), root.certificate(),
                List.of(middle.certificate()));
        assert path.getCertificates().size() == 2 : path.getCertificates().size();
        List<String> subjects = CertificateChains.subjects(path);
        assert subjects.get(0).contains("localhost") : subjects;
        assert subjects.get(1).contains("FC Middle CA") : subjects;

        assertThrows(CertPathBuilderException.class, () ->
                CertificateChains.build(server.certificate(), root.certificate(), List.of()));
    }

    @FcTest
    public void expiredOrForeignCertificatesAreRejected() throws Exception {
        TrustAuthority.KeyMaterial root = TrustAuthority.root("FC Reject Root");
        Instant now = Instant.now();
        TrustAuthority.KeyMaterial expired = TrustAuthority.issue(
                TrustAuthority.Purpose.SERVER, root, "localhost",
                now.minus(Duration.ofDays(10)), now.minus(Duration.ofDays(1)));

        assertThrows(CertificateExpiredException.class,
                () -> expired.certificate().checkValidity());
        assertThrows(CertPathValidatorException.class, () ->
                CertificateChains.verify(expired.certificate(), root.certificate()));

        TrustAuthority.KeyMaterial server = TrustAuthority.server(root, "localhost");
        TrustAuthority.KeyMaterial foreign = TrustAuthority.root("FC Other Root");
        assertThrows(CertPathValidatorException.class, () ->
                CertificateChains.verify(server.certificate(), foreign.certificate()));
    }

    @FcTest
    public void invalidIssuanceArgumentsAreRejected() throws Exception {
        TrustAuthority.KeyMaterial root = TrustAuthority.root("FC Arg Root");
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> TrustAuthority.root("  "));
        assertThrows(IllegalArgumentException.class,
                () -> TrustAuthority.server(null, "localhost"));
        assertThrows(IllegalArgumentException.class, () -> TrustAuthority.issue(
                TrustAuthority.Purpose.SERVER, root, "localhost", now, now));
        assertThrows(IllegalArgumentException.class, () -> TrustAuthority.issue(
                null, root, "localhost", now, now.plus(Duration.ofDays(1))));
    }

    @FcTest
    public void keyStoresRoundTripKeysAndTrust() throws Exception {
        TrustAuthority.KeyMaterial root = TrustAuthority.root("FC Store Root");
        TrustAuthority.KeyMaterial server = TrustAuthority.server(root, "localhost");

        KeyStore keys = SecureChannel.keyStore(server,
                TrustAuthority.chain(server, root), PASSWORD);
        KeyStore trust = SecureChannel.trustStore(PASSWORD, root.certificate());
        assert SecureChannel.certificate(trust).equals(root.certificate());

        Path dir = Files.createTempDirectory("fc-trust-store-");
        try {
            Path file = dir.resolve("nested/server.p12");
            SecureChannel.write(keys, file, PASSWORD);
            assert Files.exists(file);

            KeyStore loaded = SecureChannel.read(file, PASSWORD);
            TrustAuthority.KeyMaterial material = SecureChannel.material(loaded, PASSWORD);
            assert TrustAuthority.fingerprint(material.certificate())
                    .equals(TrustAuthority.fingerprint(server.certificate()));
            assert material.keyPair().getPrivate() != null;
            assert loaded.getCertificateChain(SecureChannel.aliasFor(server.certificate()))
                    .length == 2 : "证书链应保留中间/根";

            SSLContext context = SecureChannel.context(loaded, PASSWORD, trust);
            assert context.getProtocol().equals("TLS") : context.getProtocol();
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void assertThrows(Class<? extends Throwable> type, ThrowingCall call)
            throws Exception {
        try {
            call.run();
        } catch (Throwable e) {
            if (type.isInstance(e)) {
                return;
            }
            throw new AssertionError("期望 " + type.getSimpleName() + "，实际 "
                    + e.getClass().getSimpleName() + "：" + e.getMessage(), e);
        }
        throw new AssertionError("期望抛出 " + type.getSimpleName() + "，但没有异常");
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
