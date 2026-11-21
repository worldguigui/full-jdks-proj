package fc.persist;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * 本地化文案（java.util.ResourceBundle + MessageFormat）。
 *
 * <p>资源包位于 {@code fc/persist/messages*.properties}，UTF-8 编码。
 * 缺少键时返回 {@code !key!}，避免因单个文案缺失导致存档/启动失败。</p>
 *
 * <p>加载时**显式禁用默认 Locale 回退**：若默认环境是中文，请求英文时不得
 * 悄悄退回中文包；没有对应语言时统一退回 base（英文）。这样语言选择是
 * 可预测的，也便于测试。</p>
 */
public final class Localization {

    private static final String BUNDLE = "fc.persist.messages";
    private static final ResourceBundle.Control NO_FALLBACK =
            new ResourceBundle.Control() {
                @Override
                public Locale getFallbackLocale(String baseName, Locale locale) {
                    return null;
                }
            };

    private final Locale locale;
    private final ResourceBundle bundle;

    private Localization(Locale locale, ResourceBundle bundle) {
        this.locale = locale;
        this.bundle = bundle;
    }

    /** 按 Locale 取资源包；找不到对应语言时回退到 base（英文）包。 */
    public static Localization of(Locale locale) {
        Locale target = locale == null ? Locale.getDefault() : locale;
        ClassLoader loader = Localization.class.getClassLoader();
        ResourceBundle bundle;
        try {
            bundle = ResourceBundle.getBundle(BUNDLE, target, loader, NO_FALLBACK);
        } catch (MissingResourceException e) {
            // 某些 ClassLoader 对「语言后缀缺失 + base 存在」的组合会误判；
            // 显式再按 root 取一次 base 包，保证英文/缺省语言可用。
            bundle = ResourceBundle.getBundle(BUNDLE, Locale.ROOT, loader, NO_FALLBACK);
        }
        return new Localization(target, bundle);
    }

    /** 系统默认语言。 */
    public static Localization defaults() {
        return of(Locale.getDefault());
    }

    /** 取出原始模式串。 */
    public String text(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return "!" + key + "!";
        }
    }

    /** 取出并用 {@link MessageFormat} 填充参数。 */
    public String format(String key, Object... args) {
        return MessageFormat.format(text(key), args);
    }

    public Locale locale() {
        return locale;
    }

    /** 是否包含某个键（便于测试与诊断）。 */
    public boolean has(String key) {
        return bundle.containsKey(key);
    }
}
