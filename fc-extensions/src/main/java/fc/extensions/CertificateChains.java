package fc.extensions;

import java.security.GeneralSecurityException;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderResult;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 证书链构建与 PKIX 校验。
 *
 * <p>聊天中继的管理通道用「根 → 中间 → 服务端/客户端」三级链说明信任边界：
 * 根证书放在信任库里，中间证书可随节点分发，叶子证书只有对应端点持有。
 * 校验使用 JDK 自带 PKIX 实现，吊销检查关闭（自签信任库没有 CRL/OCSP 端点），
 * 因此「自签 + 显式信任锚」就是完整语义，不假装有在线吊销能力。</p>
 */
public final class CertificateChains {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'").withZone(ZoneOffset.UTC);

    private CertificateChains() {
    }

    /**
     * 用信任锚校验叶子证书的链。
     *
     * @param target        待校验的叶子证书
     * @param anchor        信任锚（自签根）
     * @param intermediates 链上中间证书（不含锚与叶子），可为空
     * @throws GeneralSecurityException 链不完整、签名/有效期/用法不符时抛出
     */
    public static void verify(X509Certificate target, X509Certificate anchor,
                              X509Certificate... intermediates) throws GeneralSecurityException {
        if (target == null || anchor == null) {
            throw new IllegalArgumentException("待校验证书与信任锚都不能为空");
        }
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<Certificate> chain = new ArrayList<>();
        chain.add(target);
        chain.addAll(List.of(intermediates));
        CertPath path = factory.generateCertPath(chain);

        PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(anchor, null)));
        parameters.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(path, parameters);
    }

    /**
     * 从候选池中为叶子证书构建到信任锚的链。
     *
     * @param target 目标证书
     * @param anchor 信任锚（自签根）
     * @param pool   中间证书候选；不含锚也可以，锚会作为校验终点
     * @return 构建出的证书链（叶子在前，不含锚）
     * @throws GeneralSecurityException 没有可达路径时抛出
     */
    public static CertPath build(X509Certificate target, X509Certificate anchor,
                                 Collection<X509Certificate> pool)
            throws GeneralSecurityException {
        X509CertSelector selector = new X509CertSelector();
        selector.setCertificate(target);
        PKIXBuilderParameters parameters =
                new PKIXBuilderParameters(Set.of(new TrustAnchor(anchor, null)), selector);
        parameters.setRevocationEnabled(false);
        parameters.addCertStore(CertStore.getInstance("Collection",
                new CollectionCertStoreParameters(pool)));
        CertPathBuilderResult result = CertPathBuilder.getInstance("PKIX").build(parameters);
        return result.getCertPath();
    }

    /** 链上各证书的主题，叶子在前。 */
    public static List<String> subjects(CertPath path) {
        List<String> list = new ArrayList<>();
        for (Certificate certificate : path.getCertificates()) {
            list.add(((X509Certificate) certificate).getSubjectX500Principal().getName());
        }
        return List.copyOf(list);
    }

    /** 单行描述：主题、签发者、有效期与指纹。 */
    public static String describe(X509Certificate certificate) {
        return certificate.getSubjectX500Principal().getName()
                + "｜签发者 " + certificate.getIssuerX500Principal().getName()
                + "｜有效期至 " + DATE.format(certificate.getNotAfter().toInstant())
                + "（剩 " + daysLeft(certificate) + " 天）"
                + "｜SHA-256 " + TrustAuthority.fingerprint(certificate);
    }

    /** 距失效的整天数；已过期时为负。 */
    public static long daysLeft(X509Certificate certificate) {
        return java.time.Duration.between(Instant.now(),
                certificate.getNotAfter().toInstant()).toDays();
    }
}
