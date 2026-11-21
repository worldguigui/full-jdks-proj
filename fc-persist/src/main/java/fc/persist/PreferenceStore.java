package fc.persist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 玩家偏好（java.util.prefs.Preferences）。
 *
 * <p>保存语言、音量、UI 缩放等轻量设置；与存档（一局游戏的状态）分离。
 * 默认节点为 {@code /fc/frontier-chronicle/<档案名>}，遵循操作系统惯例
 * （Windows 注册表、Linux/BSD 用户目录）。</p>
 *
 * <p>偏好写入可能失败（只读配置、权限受限），调用方应容忍：{@link #flush()}
 * 返回 false 表示未持久化，但内存中的值仍可用于本局。</p>
 */
public final class PreferenceStore {

    private static final String ROOT = "/fc/frontier-chronicle";
    private static final java.util.regex.Pattern PROFILE =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Preferences node;

    /** 使用给定节点（测试或自定义后端）。 */
    public PreferenceStore(Preferences node) {
        if (node == null) {
            throw new IllegalArgumentException("Preferences 节点不能为空");
        }
        this.node = node;
    }

    /** 用户级默认档案。 */
    public static PreferenceStore userDefaults() {
        return user("default");
    }

    /** 指定档案名；名称限制为字母/数字/下划线/短横线，避免节点注入。 */
    public static PreferenceStore user(String profile) {
        if (profile == null || !PROFILE.matcher(profile).matches()) {
            throw new IllegalArgumentException("档案名只能包含字母、数字、下划线或短横线");
        }
        return new PreferenceStore(Preferences.userRoot().node(ROOT + "/" + profile));
    }

    public String get(String key, String fallback) {
        validateKey(key);
        return node.get(key, fallback);
    }

    public int getInt(String key, int fallback) {
        validateKey(key);
        return node.getInt(key, fallback);
    }

    public boolean getBoolean(String key, boolean fallback) {
        validateKey(key);
        return node.getBoolean(key, fallback);
    }

    public void put(String key, String value) {
        validateKey(key);
        if (value == null) {
            throw new IllegalArgumentException("偏好值不能为 null");
        }
        if (value.length() > 4096) {
            throw new IllegalArgumentException("偏好值过长（上限 4096 字符）");
        }
        node.put(key, value);
    }

    public void putInt(String key, int value) {
        validateKey(key);
        node.putInt(key, value);
    }

    public void putBoolean(String key, boolean value) {
        validateKey(key);
        node.putBoolean(key, value);
    }

    public void remove(String key) {
        validateKey(key);
        node.remove(key);
    }

    /** 全部键（排序后返回）。 */
    public List<String> keys() {
        try {
            List<String> keys = new ArrayList<>(List.of(node.keys()));
            keys.sort(String::compareTo);
            return keys;
        } catch (BackingStoreException e) {
            return List.of();
        }
    }

    /** 语言设置的便捷读写（BCP 47 标签）。 */
    public Locale language() {
        String tag = get("language", Locale.getDefault().toLanguageTag());
        try {
            return Locale.forLanguageTag(tag);
        } catch (IllegalArgumentException e) {
            return Locale.getDefault();
        }
    }

    public void setLanguage(Locale locale) {
        put("language", (locale == null ? Locale.getDefault() : locale).toLanguageTag());
    }

    /** 强制同步到后端；失败返回 false（不抛异常，保持游戏可玩）。 */
    public boolean flush() {
        try {
            node.flush();
            return true;
        } catch (BackingStoreException e) {
            return false;
        }
    }

    /** 删除整个档案节点（退出账号/重置设置时使用）。 */
    public void delete() {
        try {
            node.removeNode();
        } catch (BackingStoreException ignored) {
            // 删除失败时保留节点，不影响退出。
        }
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128) {
            throw new IllegalArgumentException("偏好键非法（1–128 字符）");
        }
        if (key.indexOf('/') >= 0 || key.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("偏好键不能包含 '/' 或 NUL");
        }
    }
}
