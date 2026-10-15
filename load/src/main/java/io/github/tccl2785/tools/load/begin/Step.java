package io.github.tccl2785.tools.load.begin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 步骤控制注解
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Step {
    /** 执行顺序，数值越小越优先执行（未标记的方法最优先执行） */
    int value() default Integer.MAX_VALUE;
    /** 发生异常时是否忽略并继续后续步骤，默认 false（即抛出异常并终止加载） */
    boolean ignoreError() default false;
}