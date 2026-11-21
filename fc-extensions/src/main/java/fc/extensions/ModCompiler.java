package fc.extensions;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时 Java 编译器：把 mod/宏源码在进程内编译成可加载的类。
 *
 * <p>使用 JDK 自带的 {@link javax.tools.JavaCompiler}（JSR 199）。两种模式：</p>
 * <ul>
 *   <li>{@link #compile(String, String)}：只在内存中保留 class 字节，适合即时宏；</li>
 *   <li>{@link #compileTo(Path, String, String)}：把 class 写入目录，供
 *       {@link ModLoader} 打包/加载。</li>
 * </ul>
 *
 * <p>不支持预览特性（不传 {@code --enable-preview}），与项目「核心不使用
 * Preview/Incubator API」的约束一致。</p>
 */
public final class ModCompiler {

    private ModCompiler() {
    }

    /** 内存编译并返回已装载的类。 */
    public static Class<?> compile(String className, String source) throws ModException {
        JavaCompiler compiler = compiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> classes = new HashMap<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8);
        try (MemoryFileManager manager = new MemoryFileManager(standard, classes)) {
            JavaFileObject unit = source(className, source);
            boolean ok = compiler.getTask(null, manager, diagnostics,
                    options(), null, List.of(unit)).call();
            if (!ok) {
                throw new ModException("编译失败: " + format(diagnostics));
            }
        } catch (IOException e) {
            throw new ModException("编译输出失败: " + e.getMessage(), e);
        }
        return new MemoryClassLoader(classes).load(className.replace('.', '/'));
    }

    /** 编译到目录（输出 {@code className} 对应的 .class 文件）。 */
    public static Path compileTo(Path outputDir, String className, String source)
            throws ModException {
        JavaCompiler compiler = compiler();
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new ModException("无法创建输出目录: " + outputDir, e);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8);
        try {
            List<String> options = new ArrayList<>(List.of("-d", outputDir.toString()));
            String cp = System.getProperty("java.class.path");
            if (cp != null && !cp.isBlank()) {
                options.add("-classpath");
                options.add(cp);
            }
            options.addAll(List.of("-proc:none", "-encoding", "UTF-8"));
            boolean ok = compiler.getTask(null, manager, diagnostics, options, null,
                    List.of(source(className, source))).call();
            if (!ok) {
                throw new ModException("编译失败: " + format(diagnostics));
            }
        } finally {
            try {
                manager.close();
            } catch (IOException ignored) {
                // 关闭失败不影响已写出的 class 文件。
            }
        }
        return outputDir.resolve(className.replace('.', '/') + ".class");
    }

    /** 编译错误的人类可读摘要（含行列号）。 */
    public static String format(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append("第 ").append(d.getLineNumber()).append(" 行: ")
                        .append(d.getMessage(java.util.Locale.ROOT));
            }
        }
        return sb.isEmpty() ? "未知错误" : sb.toString();
    }

    private static List<String> options() {
        List<String> options = new ArrayList<>(List.of("-proc:none", "-encoding", "UTF-8"));
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            options.add("-classpath");
            options.add(cp);
        }
        return options;
    }

    private static JavaCompiler compiler() throws ModException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ModException("当前运行环境没有 javac（需要 JDK 而非 JRE）");
        }
        return compiler;
    }

    private static JavaFileObject source(String className, String source) throws ModException {
        if (className == null || !className.matches(
                "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            throw new ModException("类名非法: " + className);
        }
        if (source == null || source.isBlank()) {
            throw new ModException("源码不能为空");
        }
        return new SimpleJavaFileObject(
                URI.create("string:///" + className.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    /** 把 class 输出收集到内存。 */
    private static final class MemoryFileManager
            extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> classes;

        private MemoryFileManager(StandardJavaFileManager delegate,
                                  Map<String, ByteArrayOutputStream> classes) {
            super(delegate);
            this.classes = classes;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            return new SimpleJavaFileObject(
                    URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
                @Override
                public OutputStream openOutputStream() {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
                    classes.put(className, bytes);
                    return bytes;
                }
            };
        }
    }

    /** 从内存 class 字节加载类。 */
    private static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, ByteArrayOutputStream> classes;

        private MemoryClassLoader(Map<String, ByteArrayOutputStream> classes) {
            super(ModCompiler.class.getClassLoader());
            this.classes = classes;
        }

        private Class<?> load(String binarySlashName) throws ModException {
            String binary = binarySlashName.replace('/', '.');
            ByteArrayOutputStream bytes = classes.get(binary);
            if (bytes == null) {
                throw new ModException("编译未产出类: " + binary);
            }
            byte[] data = bytes.toByteArray();
            return defineClass(binary, data, 0, data.length);
        }
    }
}
