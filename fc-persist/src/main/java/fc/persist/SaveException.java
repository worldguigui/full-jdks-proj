package fc.persist;

/**
 * 存档格式错误、口令错误或校验和不一致。
 *
 * <p>继承 {@link java.io.IOException}，与文件 I/O 错误一起由调用方处理。</p>
 */
public final class SaveException extends java.io.IOException {

    public SaveException(String message) {
        super(message);
    }

    public SaveException(String message, Throwable cause) {
        super(message, cause);
    }
}
