package fc.testkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 自研微型测试 runner。
 *
 * <p>通过反射发现指定类（或包）中所有 {@code @FcTest} 方法并执行，
 * 报告通过/失败与失败定位（类#方法 + 断言差异）。零第三方依赖。</p>
 *
 * <p>用法：{@code TestRunner.run(MyTests.class)} 或 {@code TestRunner.runAll("fc.world")}。</p>
 */
public final class TestRunner {

    /** 单个测试结果。 */
    public record Result(String className, String methodName, boolean passed, String message) {
        public static Result pass(String cls, String m) {
            return new Result(cls, m, true, "");
        }

        public static Result fail(String cls, String m, String message) {
            return new Result(cls, m, false, message);
        }
    }

    private TestRunner() {
    }

    /** 运行单个测试类的全部 {@code @FcTest} 方法。 */
    public static List<Result> run(Class<?> testClass) {
        List<Result> results = new ArrayList<>();
        for (Method m : testClass.getDeclaredMethods()) {
            if (m.isAnnotationPresent(FcTest.class)) {
                results.add(runMethod(testClass, m));
            }
        }
        return results;
    }

    /** 运行某包下所有测试类的全部 {@code @FcTest} 方法（递归子包）。 */
    public static List<Result> runAll(String packageName) {
        List<Class<?>> classes = discover(packageName);
        List<Result> results = new ArrayList<>();
        for (Class<?> c : classes) {
            results.addAll(run(c));
        }
        return results;
    }

    private static Result runMethod(Class<?> testClass, Method method) {
        String cls = testClass.getName();
        String m = method.getName();
        FcTest ann = method.getAnnotation(FcTest.class);
        Class<? extends Throwable> expected = ann.expected();

        try {
            Object instance = null;
            if (!Modifier.isStatic(method.getModifiers())) {
                instance = testClass.getDeclaredConstructor().newInstance();
            }
            method.setAccessible(true);
            method.invoke(instance);

            if (expected != FcTest.None.class) {
                return Result.fail(cls, m, "期望异常 " + expected.getName() + " 但未抛出");
            }
            return Result.pass(cls, m);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (expected != FcTest.None.class && expected.isInstance(cause)) {
                return Result.pass(cls, m);
            }
            return Result.fail(cls, m, cause == null ? "null" : cause.toString());
        } catch (ReflectiveOperationException e) {
            return Result.fail(cls, m, "反射失败: " + e);
        }
    }

    private static List<Class<?>> discover(String packageName) {
        // 骨架：从类路径扫描包（简化实现，完整扫描在 fc-audit 阶段提供）
        // 目前只返回空列表；实际项目测试类显式传给 run(Class)。
        return List.of();
    }

    /** 汇总执行结果并打印报告，返回是否全部通过。 */
    public static boolean report(List<Result> results) {
        long passed = results.stream().filter(Result::passed).count();
        System.out.println("== fc-testkit ==");
        System.out.println("通过 " + passed + " / 共 " + results.size());
        for (Result r : results) {
            if (!r.passed()) {
                System.out.println("  FAIL " + r.className() + "#" + r.methodName() + " -> " + r.message());
            }
        }
        return passed == results.size();
    }
}
