package fc.testkit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自研测试注解：标记一个测试方法。
 *
 * <p>由 {@link TestRunner} 通过反射发现并执行。零第三方依赖，替代 JUnit 的
 * 最小测试能力。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface FcTest {

    /** 测试名（缺省为方法名）。 */
    String name() default "";

    /** 期望抛出的异常类型（缺省不期望异常）。 */
    Class<? extends Throwable> expected() default None.class;

    /** 表示「不期望异常」的哨兵类型。 */
    final class None extends Throwable {
        private None() {
        }
    }
}
