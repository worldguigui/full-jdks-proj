package fc.auth;

import javax.security.auth.kerberos.KerberosKey;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.kerberos.KeyTab;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Kerberos 环境探测与配置：服务主体、realm/KDC 系统属性、keytab 是否可读。
 * 只做本地检查，不会把“机制存在”伪装成“已经能登录”。
 */
public record KerberosEnvironment(String servicePrincipal, String realm, String kdc,
                                  Path keyTab) {

    public static final String DEFAULT_SERVICE = "frontier/localhost@FRONTIER.CHRONICLE";

    public static KerberosEnvironment fromSystem() {
        String service = System.getProperty("fc.auth.gss.service", DEFAULT_SERVICE);
        String realm = System.getProperty("java.security.krb5.realm", "");
        String kdc = System.getProperty("java.security.krb5.kdc", "");
        String keyTab = System.getProperty("fc.auth.gss.keytab", "");
        return new KerberosEnvironment(service, realm, kdc,
                keyTab.isBlank() ? null : Path.of(keyTab));
    }

    public static KerberosEnvironment of(String servicePrincipal, String realm,
                                         String kdc, String keyTab) {
        return new KerberosEnvironment(servicePrincipal, realm, kdc,
                keyTab == null || keyTab.isBlank() ? null : Path.of(keyTab));
    }

    public GssProbe probe() {
        boolean mechanism;
        java.util.List<String> mechanisms = new java.util.ArrayList<>();
        try {
            var manager = org.ietf.jgss.GSSManager.getInstance();
            var oids = manager.getMechs();
            for (var oid : oids) {
                mechanisms.add(oid.toString());
            }
            mechanism = mechanisms.contains(GssAuthenticator.KERBEROS_OID);
        } catch (Exception e) {
            return new GssProbe(false, mechanisms, false, false, false, 0,
                    servicePrincipal, "GSSManager 不可用：" + e.getMessage());
        }
        boolean principalValid = false;
        String detail = "";
        int keys = 0;
        boolean keyTabReadable = false;
        if (keyTab != null) {
            keyTabReadable = Files.isReadable(keyTab);
            if (keyTabReadable) {
                try {
                    KerberosPrincipal principal = new KerberosPrincipal(servicePrincipal);
                    principalValid = true;
                    KeyTab table = KeyTab.getInstance(new File(keyTab.toString()));
                    KerberosKey[] found = table.getKeys(principal);
                    keys = found == null ? 0 : found.length;
                    if (keys == 0) {
                        detail = "keytab 中没有该服务主体的密钥";
                    }
                } catch (Exception e) {
                    detail = "keytab/主体读取失败：" + e.getMessage();
                }
            } else {
                detail = "keytab 不存在或不可读：" + keyTab;
            }
        }
        if (realm.isBlank() || kdc.isBlank()) {
            detail = (detail.isBlank() ? "" : detail + "；")
                    + "未配置 java.security.krb5.realm / java.security.krb5.kdc";
        }
        boolean ready = mechanism && !realm.isBlank() && !kdc.isBlank()
                && (keyTabReadable ? keys > 0 : true);
        return new GssProbe(mechanism, mechanisms, !realm.isBlank(), !kdc.isBlank(),
                keyTabReadable, keys, servicePrincipal,
                detail.isBlank() ? "本地配置就绪" : detail);
    }

    public record GssProbe(boolean mechanismAvailable, java.util.List<String> mechanisms,
                           boolean realmConfigured, boolean kdcConfigured,
                           boolean keyTabReadable, int keyCount, String servicePrincipal,
                           String detail) {
        public GssProbe {
            mechanisms = java.util.List.copyOf(mechanisms);
        }

        /** 足以尝试登录的本地配置（不代表 KDC 一定可达）。 */
        public boolean readyForLogin() {
            return mechanismAvailable && realmConfigured && kdcConfigured;
        }

        public String summary() {
            return (mechanismAvailable ? "Kerberos 机制可用" : "Kerberos 机制不可用")
                    + "｜realm " + (realmConfigured ? "已配置" : "未配置")
                    + "｜KDC " + (kdcConfigured ? "已配置" : "未配置")
                    + "｜keytab " + (keyTabReadable ? keyCount + " 把密钥" : "未配置")
                    + "｜主体 " + servicePrincipal
                    + "｜" + detail;
        }
    }
}
