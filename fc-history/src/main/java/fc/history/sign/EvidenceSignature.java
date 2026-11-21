package fc.history.sign;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.XMLStructure;
import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.TransformException;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.KeyValue;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * 档案签章：用 XML Signature（JSR-105）给编年史或任意导出文件做「封装式」签名。
 *
 * <p>签名产物是一份自包含的 XML 证据文件：</p>
 *
 * <pre>{@code
 * <evidence xmlns="urn:fc:evidence" version="1">
 *   <chronicle id="chronicle" encoding="base64"
 *              source="chronicle.xml">BASE64…</chronicle>
 *   <ds:Signature xmlns:ds="http://www.w3.org/2000/09/xmldsig#">…</ds:Signature>
 * </evidence>
 * }</pre>
 *
 * <p>Reference 指向 {@code #chronicle}，摘要算法 SHA-256，签名算法 RSA-SHA256，
 * KeyInfo 内嵌 RSA 公钥（KeyValue），因此接收方无需额外文件即可验签。内嵌公钥只证明
 * 「同一把钥匙签的」，不证明身份；需要身份保证时应使用 {@link #verify(Path, PublicKey)}
 * 传入可信公钥，并用 {@link #fingerprint(PublicKey)} 做带外核对。</p>
 *
 * <p>解析关闭 DOCTYPE 与外部实体；XML 转换关闭外部 DTD/样式表访问。</p>
 */
public final class EvidenceSignature {

    public static final String EVIDENCE_NS = "urn:fc:evidence";
    public static final String SIGNATURE_NS = XMLSignature.XMLNS;
    public static final String DIGEST_ALGORITHM =
            "http://www.w3.org/2001/04/xmlenc#sha256";
    public static final String SIGNATURE_ALGORITHM =
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
    public static final String CANONICALIZATION =
            "http://www.w3.org/2001/10/xml-exc-c14n#";

    /** 验签结果：是否有效、签名者公钥指纹（SHA-256）、被签名负载。 */
    public record Verification(boolean valid, String signerFingerprint, byte[] payload) {
        public Verification {
            payload = payload == null ? new byte[0] : payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        /** 还原负载文本（UTF-8）。 */
        public String payloadText() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private EvidenceSignature() {
    }

    /** 生成 RSA 密钥对（默认 2048 位、公开指数 65537）。 */
    public static KeyPair generateKeyPair(int bits) throws GeneralSecurityException {
        if (bits < 2048 || bits > 8192) {
            throw new IllegalArgumentException("RSA 密钥长度须在 2048–8192 之间");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(new RSAKeyGenParameterSpec(bits, RSAKeyGenParameterSpec.F4),
                new SecureRandom());
        return generator.generateKeyPair();
    }

    /** 把文件字节签成自包含证据 XML。 */
    public static Path sign(Path source, Path output, KeyPair keyPair) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("待签名文件不存在: " + source);
        }
        if (output == null) {
            throw new IllegalArgumentException("输出路径不能为空");
        }
        if (keyPair == null) {
            throw new IllegalArgumentException("密钥对不能为空");
        }
        byte[] payload = Files.readAllBytes(source);
        Document doc = newDocument();
        Element root = doc.createElementNS(EVIDENCE_NS, "evidence");
        root.setAttribute("version", "1");
        doc.appendChild(root);

        Element chronicle = doc.createElementNS(EVIDENCE_NS, "chronicle");
        chronicle.setAttribute("id", "chronicle");
        chronicle.setAttribute("encoding", "base64");
        chronicle.setAttribute("mime", "application/xml");
        chronicle.setAttribute("source", source.getFileName().toString());
        chronicle.setTextContent(Base64.getMimeEncoder(76, new byte[] {'\n'})
                .encodeToString(payload));
        root.appendChild(chronicle);

        try {
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            DigestMethod digestMethod = factory.newDigestMethod(DIGEST_ALGORITHM, null);
            Reference reference = factory.newReference("#chronicle", digestMethod,
                    null, null, null);
            SignedInfo signedInfo = factory.newSignedInfo(
                    factory.newCanonicalizationMethod(CANONICALIZATION,
                            (C14NMethodParameterSpec) null),
                    factory.newSignatureMethod(SIGNATURE_ALGORITHM, null),
                    List.of(reference));
            KeyInfoFactory keyInfoFactory = factory.getKeyInfoFactory();
            KeyValue keyValue = keyInfoFactory.newKeyValue(keyPair.getPublic());
            KeyInfo keyInfo = keyInfoFactory.newKeyInfo(List.of(keyValue));
            XMLSignature signature = factory.newXMLSignature(signedInfo, keyInfo);

            DOMSignContext context = new DOMSignContext(keyPair.getPrivate(), root);
            context.setIdAttributeNS(chronicle, null, "id");
            context.setDefaultNamespacePrefix("ds");
            signature.sign(context);
        } catch (GeneralSecurityException | MarshalException | XMLSignatureException e) {
            throw new IOException("XML 签名失败: " + e.getMessage(), e);
        }

        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        write(doc, normalized);
        return normalized;
    }

    /** 用内嵌 KeyValue 公钥验签（不验证身份，只验证完整性）。 */
    public static Verification verify(Path signed) {
        return verify(signed, null);
    }

    /** 用可信公钥验签；expected 为 null 时退回内嵌 KeyValue。 */
    public static Verification verify(Path signed, PublicKey expected) {
        byte[] payload = new byte[0];
        String fingerprint = expected == null ? null : fingerprint(expected);
        try {
            Document doc = parse(signed);
            Element chronicle = firstElement(doc, EVIDENCE_NS, "chronicle");
            Element signatureElement = firstElement(doc, SIGNATURE_NS, "Signature");
            if (chronicle == null || signatureElement == null) {
                return new Verification(false, fingerprint, payload);
            }
            payload = Base64.getMimeDecoder().decode(chronicle.getTextContent());

            DOMValidateContext context = expected == null
                    ? new DOMValidateContext(new EmbeddedKeySelector(), signatureElement)
                    : new DOMValidateContext(expected, signatureElement);
            context.setIdAttributeNS(chronicle, null, "id");
            XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
            XMLSignature signature = factory.unmarshalXMLSignature(context);
            boolean valid = signature.validate(context);
            if (fingerprint == null) {
                fingerprint = embeddedFingerprint(signature);
            }
            return new Verification(valid, fingerprint, payload);
        } catch (IOException | IllegalArgumentException | MarshalException
                 | XMLSignatureException e) {
            return new Verification(false, fingerprint, payload);
        }
    }

    /** 公钥指纹：SHA-256(X.509 SubjectPublicKeyInfo) 的小写十六进制。 */
    public static String fingerprint(PublicKey key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /** 读取目录中的密钥对；不存在则生成并落盘（公钥 {@code evidence.pub}、私钥 {@code evidence.key}）。 */
    public static KeyPair loadOrCreateKeyPair(Path directory, int bits) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("密钥目录不能为空");
        }
        Path publicFile = directory.resolve("evidence.pub");
        Path privateFile = directory.resolve("evidence.key");
        if (Files.isRegularFile(publicFile) && Files.isRegularFile(privateFile)) {
            PublicKey publicKey = readPublicKey(publicFile);
            PrivateKey privateKey = readPrivateKey(privateFile);
            return new KeyPair(publicKey, privateKey);
        }
        try {
            KeyPair generated = generateKeyPair(bits);
            writePublicKey(generated.getPublic(), publicFile);
            writePrivateKey(generated.getPrivate(), privateFile);
            return generated;
        } catch (GeneralSecurityException e) {
            throw new IOException("证据密钥生成失败: " + e.getMessage(), e);
        }
    }

    /** 保存公钥（X.509 SubjectPublicKeyInfo DER）。 */
    public static void writePublicKey(PublicKey key, Path output) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        Files.write(normalized, key.getEncoded());
    }

    /** 读取公钥（X.509 SubjectPublicKeyInfo DER）。 */
    public static PublicKey readPublicKey(Path input) throws IOException {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Files.readAllBytes(input)));
        } catch (GeneralSecurityException e) {
            throw new IOException("公钥读取失败: " + e.getMessage(), e);
        }
    }

    /** 保存私钥（PKCS#8 DER）。调用方负责文件权限与保密。 */
    public static void writePrivateKey(PrivateKey key, Path output) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        Files.write(normalized, key.getEncoded());
    }

    /** 读取私钥（PKCS#8 DER）。 */
    public static PrivateKey readPrivateKey(Path input) throws IOException {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(input)));
        } catch (GeneralSecurityException e) {
            throw new IOException("私钥读取失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ 内部

    /** 从内嵌 KeyInfo 取 RSA KeyValue 的 KeySelector。 */
    public static final class EmbeddedKeySelector extends KeySelector {
        @Override
        public KeySelectorResult select(KeyInfo keyInfo, Purpose purpose,
                                        AlgorithmMethod method,
                                        XMLCryptoContext context)
                throws KeySelectorException {
            if (keyInfo == null) {
                throw new KeySelectorException("签名缺少 KeyInfo");
            }
            for (XMLStructure structure : keyInfo.getContent()) {
                if (structure instanceof KeyValue keyValue) {
                    try {
                        PublicKey key = keyValue.getPublicKey();
                        return () -> key;
                    } catch (GeneralSecurityException e) {
                        throw new KeySelectorException("KeyValue 无法解析", e);
                    }
                }
            }
            throw new KeySelectorException("KeyInfo 中没有 KeyValue");
        }
    }

    private static String embeddedFingerprint(XMLSignature signature) {
        try {
            KeyInfo keyInfo = signature.getKeyInfo();
            if (keyInfo == null) {
                return null;
            }
            for (XMLStructure structure : keyInfo.getContent()) {
                if (structure instanceof KeyValue keyValue) {
                    return fingerprint(keyValue.getPublicKey());
                }
            }
        } catch (GeneralSecurityException e) {
            return null;
        }
        return null;
    }

    private static Document newDocument() throws IOException {
        try {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            return factory.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IOException("无法创建 XML 文档", e);
        }
    }

    private static Document parse(Path file) throws IOException {
        try {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            return factory.newDocumentBuilder().parse(file.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("签名文件解析失败: " + e.getMessage(), e);
        }
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static Element firstElement(Document doc, String namespace, String localName) {
        var nodes = doc.getElementsByTagNameNS(namespace, localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return (Element) nodes.item(0);
    }

    private static void write(Document doc, Path output) throws IOException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.transform(new DOMSource(doc), new StreamResult(output.toFile()));
        } catch (TransformerException e) {
            throw new IOException("签名文件写出失败: " + e.getMessage(), e);
        }
    }
}
