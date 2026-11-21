package fc.audit;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * classfile 扫描器：直接解析 JVM 常量池，提取被引用的类型。
 *
 * <p>为什么不用注解处理器：注解只能证明「标注了」，无法证明方法体真正使用；
 * classfile 的 CONSTANT_Class、描述符与泛型签名则是编译器实际写出的证据。
 * 本扫描器零第三方依赖，支持 Java 21 常量池全部条目类型。</p>
 *
 * <p>判定规则：</p>
 * <ul>
 *   <li>{@code CONSTANT_Class}：内部名 {@code java/lang/String} 与数组描述符；</li>
 *   <li>字段/方法描述符、泛型签名、注解描述符中的 {@code L...;} 类型；</li>
 *   <li>预览类文件：{@code minor_version == 0xFFFF}（JEP 12 标记）。</li>
 * </ul>
 */
public final class ClassScanner {

    private static final int MAGIC = 0xCAFEBABE;
    private static final Pattern DESCRIPTOR_TYPE =
            Pattern.compile("L([A-Za-z0-9_$]+(?:/[A-Za-z0-9_$]+)*)");

    /** 单个 class 的扫描结果。 */
    public record Result(String className, boolean preview, TreeSet<String> references) {
        public Result {
            references = new TreeSet<>(references);
        }
    }

    private ClassScanner() {
    }

    /** 递归扫描目录下全部 class 文件（跳过 module-info.class）。 */
    public static List<Result> scanTree(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("不是目录: " + root);
        }
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                    .sorted()
                    .toList();
        }
        List<Result> results = new ArrayList<>(files.size());
        for (Path file : files) {
            results.add(scan(file));
        }
        return results;
    }

    /** 扫描单个 class 文件。 */
    public static Result scan(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("不是 class 文件: " + file);
            }
            int minor = in.readUnsignedShort();
            in.readUnsignedShort(); // major：Java 21 = 65；此处不限制未来版本
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classIndex = new int[count];
            java.util.Arrays.fill(classIndex, -1);
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> skip(in, 4);
                    case 5, 6 -> {
                        skip(in, 8);
                        i++; // long/double 占两个常量池槽位
                    }
                    case 7 -> classIndex[i] = in.readUnsignedShort();
                    case 8, 16, 19, 20 -> skip(in, 2);
                    case 9, 10, 11, 12, 17, 18 -> skip(in, 4);
                    case 15 -> skip(in, 3);
                    default -> throw new IOException("未知常量池标签 " + tag + " @ " + file);
                }
            }
            in.readUnsignedShort(); // access_flags
            int thisClass = in.readUnsignedShort();

            String self = classIndex[thisClass] > 0 ? utf8[classIndex[thisClass]] : "";
            TreeSet<String> refs = new TreeSet<>();
            for (int i = 1; i < count; i++) {
                if (classIndex[i] > 0) {
                    String name = utf8[classIndex[i]];
                    if (name != null) {
                        collectFromInternalName(name, refs);
                    }
                }
                String text = utf8[i];
                if (text != null && isDescriptorLike(text)) {
                    Matcher m = DESCRIPTOR_TYPE.matcher(text);
                    while (m.find()) {
                        addInternal(m.group(1), refs);
                    }
                }
            }
            String normalizedSelf = normalize(self);
            if (!normalizedSelf.isEmpty()) {
                refs.remove(normalizedSelf);
            }
            return new Result(normalizedSelf, minor == 0xFFFF, refs);
        }
    }

    private static void collectFromInternalName(String name, TreeSet<String> refs) {
        if (name.startsWith("[")) {
            Matcher m = DESCRIPTOR_TYPE.matcher(name);
            while (m.find()) {
                addInternal(m.group(1), refs);
            }
        } else {
            addInternal(name, refs);
        }
    }

    private static void addInternal(String internal, TreeSet<String> refs) {
        if (internal.isEmpty() || internal.startsWith("[")) {
            return;
        }
        refs.add(internal.replace('/', '.'));
    }

    private static String normalize(String internal) {
        if (internal.isEmpty()) {
            return "";
        }
        if (internal.startsWith("[")) {
            Matcher m = DESCRIPTOR_TYPE.matcher(internal);
            return m.find() ? m.group(1).replace('/', '.') : "";
        }
        return internal.replace('/', '.');
    }

    private static boolean isDescriptorLike(String text) {
        return !text.isEmpty()
                && (text.charAt(0) == '(' || text.charAt(0) == '[' || text.charAt(0) == 'L')
                && text.indexOf(';') > 0;
    }

    private static void skip(DataInputStream in, int bytes) throws IOException {
        in.readFully(new byte[bytes]);
    }
}
