package fc.auth;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.MessageProp;
import org.ietf.jgss.Oid;

/**
 * 可选 Kerberos/GSSAPI 认证入口。
 *
 * <p>使用标准 GSS API 建立客户端/服务端上下文；Token 由调用方传输。没有
 * KDC、keytab 或服务主体时，{@link #probe()} 会诚实报告未就绪，认证步骤会抛
 * {@link GSSException}，绝不伪造成功。</p>
 */
public final class GssAuthenticator {

    public static final String KERBEROS_OID = "1.2.840.113554.1.2.2";

    private GssAuthenticator() {
    }

    public static KerberosEnvironment.GssProbe probe() {
        return KerberosEnvironment.fromSystem().probe();
    }

    /** 创建客户端上下文；真正的票据获取发生在 step()，错误原样抛出。 */
    public static Handshake initiator(KerberosEnvironment environment)
            throws GSSException {
        GSSManager manager = GSSManager.getInstance();
        Oid oid = new Oid(KERBEROS_OID);
        GSSName target = serviceName(manager, environment.servicePrincipal());
        GSSContext context = manager.createContext(target, oid, null,
                GSSContext.DEFAULT_LIFETIME);
        context.requestMutualAuth(true);
        context.requestCredDeleg(false);
        context.requestConf(false);
        context.requestInteg(true);
        return new Handshake(true, context, environment);
    }

    /** 创建服务端上下文；没有可用接受凭据时由 GSSManager 抛出异常。 */
    public static Handshake acceptor(KerberosEnvironment environment)
            throws GSSException {
        GSSManager manager = GSSManager.getInstance();
        Oid oid = new Oid(KERBEROS_OID);
        GSSName service = serviceName(manager, environment.servicePrincipal());
        GSSCredential credential = manager.createCredential(service,
                GSSCredential.INDEFINITE_LIFETIME, oid, GSSCredential.ACCEPT_ONLY);
        GSSContext context = manager.createContext(credential);
        context.requestMutualAuth(true);
        return new Handshake(false, context, environment);
    }

    private static GSSName serviceName(GSSManager manager, String principal)
            throws GSSException {
        try {
            return manager.createName(principal, GSSName.NT_HOSTBASED_SERVICE);
        } catch (GSSException e) {
            // 带 realm 的主体通常用用户主体名形式；失败保留原始错误语义。
            try {
                return manager.createName(principal, GSSName.NT_USER_NAME);
            } catch (GSSException ignored) {
                throw e;
            }
        }
    }

    /** 一次 GSS 上下文；必须 close() 释放原生资源。 */
    public static final class Handshake implements AutoCloseable {
        private final boolean initiator;
        private final GSSContext context;
        private final KerberosEnvironment environment;
        private boolean closed;

        private Handshake(boolean initiator, GSSContext context,
                          KerberosEnvironment environment) {
            this.initiator = initiator;
            this.context = context;
            this.environment = environment;
        }

        /** 处理对方 Token，返回下一轮 Token；可能为空。 */
        public byte[] step(byte[] token) throws GSSException {
            if (closed) {
                throw new GSSException(GSSException.NO_CONTEXT, -1,
                        "GSSContext 已释放");
            }
            byte[] input = token == null ? new byte[0] : token;
            return initiator
                    ? context.initSecContext(input, 0, input.length)
                    : context.acceptSecContext(input, 0, input.length);
        }

        public boolean isComplete() {
            return context.isEstablished();
        }

        /** 完成后提取对端主体作为项目身份。 */
        public AuthIdentity identity() throws GSSException {
            if (!context.isEstablished()) {
                throw new GSSException(GSSException.NO_CONTEXT, -1,
                        "GSS 上下文尚未建立");
            }
            GSSName peer = initiator ? context.getTargName() : context.getSrcName();
            String name = peer == null ? "" : peer.toString();
            return new AuthIdentity(name, name, "Kerberos", environment.realm(),
                    java.time.Instant.now());
        }

        /** 建立上下文后可选的 GSS 消息保护（这里仅用于验证上下文完整性）。 */
        public byte[] protect(byte[] data) throws GSSException {
            if (!context.isEstablished()) {
                throw new GSSException(GSSException.NO_CONTEXT, -1,
                        "GSS 上下文尚未建立");
            }
            MessageProp prop = new MessageProp(0, true);
            return context.wrap(data, 0, data.length, prop);
        }

        public byte[] unprotect(byte[] data) throws GSSException {
            if (!context.isEstablished()) {
                throw new GSSException(GSSException.NO_CONTEXT, -1,
                        "GSS 上下文尚未建立");
            }
            MessageProp prop = new MessageProp(0, true);
            return context.unwrap(data, 0, data.length, prop);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                context.dispose();
            } catch (GSSException ignored) {
                // dispose 尽力而为。
            }
        }
    }

    /** 把 GSSException 转成明确的用户可读原因（保留原始错误码）。 */
    public static String describe(GSSException e) {
        String reason = switch (e.getMajor()) {
            case GSSException.BAD_MECH -> "不支持的 GSS 机制";
            case GSSException.BAD_NAME -> "服务主体名无效";
            case GSSException.BAD_NAMETYPE -> "主体名类型无效";
            case GSSException.NO_CRED -> "没有可用 Kerberos 凭据";
            case GSSException.CREDENTIALS_EXPIRED -> "凭据已过期";
            case GSSException.CONTEXT_EXPIRED -> "安全上下文已过期";
            case GSSException.DEFECTIVE_TOKEN -> "Token 损坏或格式不符";
            case GSSException.DEFECTIVE_CREDENTIAL -> "凭据损坏";
            case GSSException.FAILURE -> "GSS 认证失败（可能是 KDC 不可达或票据无效）";
            default -> "GSS 错误";
        };
        return reason + "（major=" + e.getMajor() + ", minor=" + e.getMinor()
                + "）：" + e.getMessage();
    }
}
