package fc.extensions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * 密钥库与 TLS 上下文的装配。
 *
 * <p>统一使用 PKCS#12 密钥库：私钥条目带叶子证书链，信任库只放证书条目。
 * {@link SSLContext} 只启用 TLS 协议（JDK 21 默认拒绝 SSLv3/TLSv1.0/1.1），
 * 消息面仍是回环地址上的行协议，证书只负责回答「对端是否持有本信任库签发的身份」。</p>
 */
public final class SecureChannel {

    private SecureChannel() {
    }

    /** 建立含私钥与证书链的 PKCS#12 密钥库。 */
    public static KeyStore keyStore(TrustAuthority.KeyMaterial material,
                                    List<X509Certificate> chain, char[] password)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(aliasFor(material.certificate()), material.keyPair().getPrivate(),
                password, chain.toArray(Certificate[]::new));
        return store;
    }

    /** 建立只含信任锚的 PKCS#12 信任库。 */
    public static KeyStore trustStore(char[] password, X509Certificate... anchors)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        for (X509Certificate anchor : anchors) {
            store.setCertificateEntry("trust-" + TrustAuthority.fingerprint(anchor)
                    .substring(0, 12), anchor);
        }
        return store;
    }

    /** 把密钥库写入文件；父目录不存在时创建。 */
    public static void write(KeyStore store, Path file, char[] password) throws IOException,
            GeneralSecurityException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, password);
        }
    }

    /** 从文件读取 PKCS#12 密钥库。 */
    public static KeyStore read(Path file, char[] password) throws IOException,
            GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, password);
        }
        return store;
    }

    /** 取密钥库里的第一个私钥条目，重建为证书 + 密钥对。 */
    public static TrustAuthority.KeyMaterial material(KeyStore store, char[] password)
            throws GeneralSecurityException {
        for (String alias : aliases(store)) {
            if (store.isKeyEntry(alias)) {
                java.security.Key key = store.getKey(alias, password);
                Certificate[] chain = store.getCertificateChain(alias);
                if (key instanceof java.security.PrivateKey privateKey
                        && chain != null && chain.length > 0
                        && chain[0] instanceof X509Certificate leaf) {
                    return new TrustAuthority.KeyMaterial(leaf,
                            new java.security.KeyPair(leaf.getPublicKey(), privateKey));
                }
            }
        }
        throw new GeneralSecurityException("密钥库中没有私钥条目");
    }

    /** 取密钥库里第一个证书条目的证书。 */
    public static X509Certificate certificate(KeyStore store) throws GeneralSecurityException {
        for (String alias : aliases(store)) {
            Certificate certificate = store.getCertificate(alias);
            if (certificate instanceof X509Certificate x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("密钥库中没有证书条目");
    }

    /**
     * 建立 TLS 上下文。
     *
     * @param keys     本端私钥库；客户端不提供证书时为 {@code null}
     * @param password 私钥口令
     * @param trust    信任库；为 {@code null} 时退回 JDK 默认信任库
     */
    public static SSLContext context(KeyStore keys, char[] password, KeyStore trust)
            throws GeneralSecurityException {
        KeyManagerFactory keyManagers = null;
        if (keys != null) {
            keyManagers = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password);
        }
        TrustManagerFactory trustManagers = null;
        if (trust != null) {
            trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers == null ? null : keyManagers.getKeyManagers(),
                trustManagers == null ? null : trustManagers.getTrustManagers(),
                new SecureRandom());
        return context;
    }

    /** 服务端上下文：必须提供私钥；信任库用于校验客户端证书。 */
    public static SSLContext serverContext(Path keysFile, Path trustFile, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore keys = read(keysFile, password);
        KeyStore trust = read(trustFile, password);
        return context(keys, password, trust);
    }

    /** 客户端上下文：必须提供客户端证书与信任库（双向认证）。 */
    public static SSLContext clientContext(Path keysFile, Path trustFile, char[] password)
            throws IOException, GeneralSecurityException {
        KeyStore keys = read(keysFile, password);
        KeyStore trust = read(trustFile, password);
        return context(keys, password, trust);
    }

    /** 一次 TLS 握手后对端出示的证书链，叶子在前。 */
    public static List<X509Certificate> peerChain(SSLSocket socket)
            throws SSLPeerUnverifiedException {
        Certificate[] peers = socket.getSession().getPeerCertificates();
        List<X509Certificate> chain = new ArrayList<>(peers.length);
        for (Certificate certificate : peers) {
            if (certificate instanceof X509Certificate x509) {
                chain.add(x509);
            }
        }
        if (chain.isEmpty()) {
            throw new SSLPeerUnverifiedException("对端没有出示 X.509 证书");
        }
        return List.copyOf(chain);
    }

    /** 对端叶子证书。 */
    public static X509Certificate peerLeaf(SSLSocket socket) throws SSLPeerUnverifiedException {
        return peerChain(socket).get(0);
    }

    /** 私钥条目别名：由叶子证书指纹截断得到，避免猜别名。 */
    public static String aliasFor(X509Certificate certificate) {
        return "fc-" + TrustAuthority.fingerprint(certificate).substring(0, 12);
    }

    private static List<String> aliases(KeyStore store) throws GeneralSecurityException {
        List<String> aliases = new ArrayList<>();
        for (var it = store.aliases(); it.hasMoreElements(); ) {
            aliases.add(it.nextElement());
        }
        return aliases;
    }
}
