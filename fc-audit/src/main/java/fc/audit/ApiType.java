package fc.audit;

/**
 * 阶段一冻结清单中的一行（只读取，绝不写回冻结 CSV）。
 *
 * @param module      JDK 模块名
 * @param packageName 包名
 * @param binary      二进制类型名（嵌套类用 {@code $}）
 * @param display     文档显示名（嵌套类用 {@code .}）
 * @param kind        class/interface/enum/annotation/record...
 * @param scope       {@code IN_DENOMINATOR} / {@code EXCLUDED_PREVIEW}
 * @param preview     是否 Preview API
 */
public record ApiType(String module, String packageName, String binary, String display,
                      String kind, String scope, boolean preview) {

    public boolean inDenominator() {
        return "IN_DENOMINATOR".equals(scope);
    }
}
