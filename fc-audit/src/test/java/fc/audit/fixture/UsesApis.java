package fc.audit.fixture;

import java.io.File;
import java.time.LocalDate;
import java.util.List;

/** 审计测试夹具：方法体真实使用若干 java.* 类型。 */
public final class UsesApis {

    public String describe(List<String> items, File file) {
        return items.toString() + file.getName() + LocalDate.now();
    }
}
