package fc.extensions;

/** mod 描述符非法、签名缺失、编译失败或激活失败。 */
public final class ModException extends Exception {

    public ModException(String message) {
        super(message);
    }

    public ModException(String message, Throwable cause) {
        super(message, cause);
    }
}
