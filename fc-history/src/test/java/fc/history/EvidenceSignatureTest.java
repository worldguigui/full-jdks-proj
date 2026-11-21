package fc.history;

import fc.history.sign.EvidenceSignature;
import fc.testkit.FcTest;
import fc.testkit.TestRunner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Comparator;

/** XML Signature 档案签章：嵌入公钥验签、篡改/错钥拒绝、密钥持久化。 */
public final class EvidenceSignatureTest {

    private static final String CHRONICLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <chronicle count="2">
              <event day="1" date="2200-01-01" type="CrewAte" category="Crew"
                     location="L1" actors="A7"/>
              <event day="2" date="2200-01-02" type="IonStorm" category="World"
                     location="L1" actors=""/>
            </chronicle>
            """;

    public static void main(String[] args) {
        if (!TestRunner.report(TestRunner.run(EvidenceSignatureTest.class))) {
            System.exit(1);
        }
    }

    private static Path tempDir() throws Exception {
        return Files.createTempDirectory("fc-sign-test-");
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static Path source(Path dir) throws Exception {
        Path file = dir.resolve("chronicle.xml");
        Files.writeString(file, CHRONICLE, StandardCharsets.UTF_8);
        return file;
    }

    private static void rewritePayload(Path signedFile, Path output, String suffix) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(signedFile.toFile());
        var nodes = doc.getElementsByTagNameNS(EvidenceSignature.EVIDENCE_NS, "chronicle");
        Element chronicle = (Element) nodes.item(0);
        byte[] payload = Base64.getMimeDecoder().decode(chronicle.getTextContent());
        String tampered = new String(payload, StandardCharsets.UTF_8) + suffix;
        chronicle.setTextContent(Base64.getMimeEncoder(76, new byte[] {'\n'})
                .encodeToString(tampered.getBytes(StandardCharsets.UTF_8)));
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.transform(new DOMSource(doc), new StreamResult(output.toFile()));
    }

    @FcTest
    public void signAndVerifyWithEmbeddedKeyValue() throws Exception {
        Path dir = tempDir();
        try {
            KeyPair keys = EvidenceSignature.generateKeyPair(2048);
            Path signed = EvidenceSignature.sign(source(dir), dir.resolve("chronicle.evidence.xml"), keys);
            assert Files.size(signed) > 1000 : Files.size(signed);
            String xml = Files.readString(signed, StandardCharsets.UTF_8);
            assert xml.contains("SignatureValue") : xml;
            assert xml.contains("KeyValue") : xml;
            assert xml.contains("id=\"chronicle\"") : xml;

            EvidenceSignature.Verification verification = EvidenceSignature.verify(signed);
            assert verification.valid() : "内嵌公钥应能验签";
            assert verification.payloadText().contains("CrewAte") : verification.payloadText();
            assert verification.signerFingerprint()
                    .equals(EvidenceSignature.fingerprint(keys.getPublic()))
                    : verification.signerFingerprint();
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void tamperedPayloadIsRejected() throws Exception {
        Path dir = tempDir();
        try {
            KeyPair keys = EvidenceSignature.generateKeyPair(2048);
            Path signed = EvidenceSignature.sign(source(dir), dir.resolve("signed.xml"), keys);
            Path tampered = dir.resolve("tampered.xml");
            rewritePayload(signed, tampered, "<!-- forged -->");
            assert !EvidenceSignature.verify(tampered).valid() : "改动负载必须验签失败";
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void wrongPublicKeyIsRejected() throws Exception {
        Path dir = tempDir();
        try {
            KeyPair signer = EvidenceSignature.generateKeyPair(2048);
            KeyPair other = EvidenceSignature.generateKeyPair(2048);
            Path signed = EvidenceSignature.sign(source(dir), dir.resolve("signed.xml"), signer);
            assert EvidenceSignature.verify(signed, signer.getPublic()).valid();
            assert !EvidenceSignature.verify(signed, other.getPublic()).valid()
                    : "可信公钥不匹配必须失败";
            assert !EvidenceSignature.verify(dir.resolve("missing.xml")).valid()
                    : "缺失文件必须失败";
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void keysRoundTripAndKeepFingerprint() throws Exception {
        Path dir = tempDir();
        try {
            KeyPair keys = EvidenceSignature.generateKeyPair(2048);
            Path publicFile = dir.resolve("keys/id_rsa.pub");
            Path privateFile = dir.resolve("keys/id_rsa");
            EvidenceSignature.writePublicKey(keys.getPublic(), publicFile);
            EvidenceSignature.writePrivateKey(keys.getPrivate(), privateFile);

            var loadedPublic = EvidenceSignature.readPublicKey(publicFile);
            var loadedPrivate = EvidenceSignature.readPrivateKey(privateFile);
            assert EvidenceSignature.fingerprint(loadedPublic)
                    .equals(EvidenceSignature.fingerprint(keys.getPublic()))
                    : "公钥指纹应稳定";
            KeyPair loaded = new KeyPair(loadedPublic, loadedPrivate);
            Path signed = EvidenceSignature.sign(source(dir), dir.resolve("signed.xml"), loaded);
            assert EvidenceSignature.verify(signed, loadedPublic).valid()
                    : "落盘再读出的密钥应可签可验";

            KeyPair first = EvidenceSignature.loadOrCreateKeyPair(dir.resolve("vault"), 2048);
            KeyPair second = EvidenceSignature.loadOrCreateKeyPair(dir.resolve("vault"), 2048);
            assert EvidenceSignature.fingerprint(first.getPublic())
                    .equals(EvidenceSignature.fingerprint(second.getPublic()))
                    : "第二次加载应复用已有密钥";
        } finally {
            deleteTree(dir);
        }
    }

    @FcTest
    public void invalidInputsAreRejected() throws Exception {
        boolean small = false;
        try {
            EvidenceSignature.generateKeyPair(1024);
        } catch (IllegalArgumentException e) {
            small = true;
        }
        assert small : "小于 2048 位应被拒绝";
        boolean badSource = false;
        try {
            EvidenceSignature.sign(Path.of("no-such-file.xml"), Path.of("out.xml"),
                    EvidenceSignature.generateKeyPair(2048));
        } catch (Exception e) {
            badSource = true;
        }
        assert badSource;
        Path dir = tempDir();
        try {
            Path fake = dir.resolve("fake.xml");
            Files.writeString(fake, "<not-evidence/>", StandardCharsets.UTF_8);
            assert !EvidenceSignature.verify(fake).valid() : "非证据文件必须失败";
        } finally {
            deleteTree(dir);
        }
    }
}
