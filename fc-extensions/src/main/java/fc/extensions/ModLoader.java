package fc.extensions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * mod 发现与加载：目录中每个 {@code *.jar} 或含 {@code mod.properties} 的子目录是一个 mod。
 *
 * <p>加载流程：读取描述符 → 校验 id/版本/API 版本 → （可选）校验 JAR 签名 →
 * 通过独立 {@link URLClassLoader} 装载主类 → 实例化 → 调用 {@code activate}。
 * 单个 mod 失败不会阻止其它 mod，错误进入 {@link LoadReport#errors()}。</p>
 *
 * <p>安全性：JAR 以 {@code verify=true} 打开并完整读取全部条目，使 JAR 签名
 * 真正参与校验；若 JAR 带签名，还要求每个已签名条目由同一证书签名集合签署，
 * 并把签名者写入加载结果，便于运维审计。</p>
 */
public final class ModLoader {

    /** 一次加载的结果。 */
    public record LoadReport(List<LoadedMod> mods, List<String> errors) {
        public LoadReport {
            mods = List.copyOf(mods);
            errors = List.copyOf(errors);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /** 已激活的 mod；关闭时停用并释放类加载器。 */
    public static final class LoadedMod implements AutoCloseable {
        private final ModDescriptor descriptor;
        private final FrontierMod mod;
        private final URLClassLoader loader;
        private final Path source;
        private final List<String> signers;

        private LoadedMod(ModDescriptor descriptor, FrontierMod mod, URLClassLoader loader,
                          Path source, List<String> signers) {
            this.descriptor = descriptor;
            this.mod = mod;
            this.loader = loader;
            this.source = source;
            this.signers = List.copyOf(signers);
        }

        public ModDescriptor descriptor() {
            return descriptor;
        }

        public FrontierMod mod() {
            return mod;
        }

        public Path source() {
            return source;
        }

        /** 签名者证书主体；未签名时为空。 */
        public List<String> signers() {
            return signers;
        }

        @Override
        public void close() {
            try {
                mod.deactivate();
            } catch (RuntimeException ignored) {
                // 停用失败不应阻止进程退出；加载错误已在激活阶段报告。
            }
            try {
                loader.close();
            } catch (IOException ignored) {
                // 类加载器关闭失败不影响后续。
            }
        }
    }

    private final Path directory;
    private final boolean requireSignature;

    public ModLoader(Path directory, boolean requireSignature) {
        this.directory = directory.toAbsolutePath().normalize();
        this.requireSignature = requireSignature;
    }

    /** 加载目录中全部 mod；目录不存在时返回空结果。 */
    public LoadReport loadAll() {
        List<LoadedMod> loaded = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return new LoadReport(loaded, errors);
        }
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString()
                            .toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(candidates::add);
        } catch (IOException e) {
            errors.add("无法列目录 " + directory + ": " + e.getMessage());
            return new LoadReport(loaded, errors);
        }
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(p.resolve("mod.properties")))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(candidates::add);
        } catch (IOException e) {
            errors.add("无法列目录 " + directory + ": " + e.getMessage());
        }

        Map<String, Path> ids = new LinkedHashMap<>();
        for (Path candidate : candidates) {
            try {
                LoadedMod mod = loadOne(candidate);
                Path duplicate = ids.putIfAbsent(mod.descriptor().id(), candidate);
                if (duplicate != null) {
                    mod.close();
                    throw new ModException("mod id 重复: " + mod.descriptor().id()
                            + "（已由 " + duplicate.getFileName() + " 提供）");
                }
                loaded.add(mod);
            } catch (ModException | RuntimeException e) {
                errors.add(candidate.getFileName() + ": " + e.getMessage());
            }
        }
        loaded.sort(Comparator.comparing(m -> m.descriptor().id()));
        return new LoadReport(loaded, errors);
    }

    private LoadedMod loadOne(Path candidate) throws ModException {
        boolean jar = Files.isRegularFile(candidate);
        Properties props;
        List<String> signers = List.of();
        if (jar) {
            JarFile jarFile;
            try {
                jarFile = new JarFile(candidate.toFile(), true);
            } catch (IOException e) {
                throw new ModException("无法打开 mod JAR: " + e.getMessage(), e);
            }
            try (jarFile) {
                JarEntry entry = jarFile.getJarEntry("mod.properties");
                if (entry == null) {
                    throw new ModException("JAR 缺少 mod.properties");
                }
                try (InputStream in = jarFile.getInputStream(entry)) {
                    props = loadProperties(in);
                }
                signers = verifyAllEntries(jarFile);
            } catch (java.util.zip.ZipException e) {
                throw new ModException("mod JAR 结构损坏: " + e.getMessage(), e);
            } catch (SecurityException e) {
                throw new ModException("mod JAR 签名校验失败: " + e.getMessage(), e);
            } catch (IOException e) {
                throw new ModException("读取 mod JAR 失败: " + e.getMessage(), e);
            }
        } else {
            Path meta = candidate.resolve("mod.properties");
            try (InputStream in = Files.newInputStream(meta)) {
                props = loadProperties(in);
            } catch (IOException e) {
                throw new ModException("读取 mod.properties 失败: " + e.getMessage(), e);
            }
        }
        ModDescriptor descriptor = ModDescriptor.from(props);
        if ((requireSignature || descriptor.requireSignature()) && signers.isEmpty()) {
            throw new ModException("mod " + descriptor.id()
                    + " 要求签名，但未发现有效 JAR 签名");
        }
        URL url;
        try {
            url = candidate.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new ModException("mod 路径非法", e);
        }
        URLClassLoader loader = new URLClassLoader(new URL[]{url},
                FrontierMod.class.getClassLoader());
        try {
            Class<?> type = Class.forName(descriptor.mainClass(), true, loader);
            if (!FrontierMod.class.isAssignableFrom(type)) {
                throw new ModException("主类未实现 FrontierMod: " + descriptor.mainClass());
            }
            FrontierMod mod = (FrontierMod) type.getDeclaredConstructor().newInstance();
            java.util.logging.Logger log = java.util.logging.Logger.getLogger(
                    "fc.mods." + descriptor.id());
            mod.activate(new ModContext(candidate, descriptor.settings(), log));
            return new LoadedMod(descriptor, mod, loader, candidate, signers);
        } catch (ModException e) {
            closeQuietly(loader);
            throw e;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            closeQuietly(loader);
            throw new ModException("激活 mod 失败: " + e, e);
        } catch (Exception e) {
            closeQuietly(loader);
            throw new ModException("激活 mod 失败: " + e.getMessage(), e);
        }
    }

    private static Properties loadProperties(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        return props;
    }

    /**
     * 完整读取 JAR 以触发签名验证，返回签名者主体名。
     *
     * <p>若存在已签名条目，要求所有条目的签名者集合一致，防止「只签描述符、
     * 不签代码」的混签 JAR 绕过信任判断。</p>
     */
    private static List<String> verifyAllEntries(JarFile jar) throws IOException, ModException {
        TreeSet<String> first = null;
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            CodeSigner[] signers = entry.getCodeSigners();
            if (signers == null || signers.length == 0) {
                continue;
            }
            TreeSet<String> current = new TreeSet<>();
            for (CodeSigner signer : signers) {
                for (Certificate certificate : signer.getSignerCertPath().getCertificates()) {
                    current.add(certificate instanceof java.security.cert.X509Certificate x
                            ? x.getSubjectX500Principal().getName()
                            : certificate.getType());
                }
            }
            if (first == null) {
                first = current;
            } else if (!first.equals(current)) {
                throw new ModException("mod JAR 存在不同签名者，拒绝加载");
            }
        }
        return first == null ? List.of() : List.copyOf(first);
    }

    private static void closeQuietly(URLClassLoader loader) {
        try {
            loader.close();
        } catch (IOException ignored) {
            // 忽略关闭异常。
        }
    }
}
