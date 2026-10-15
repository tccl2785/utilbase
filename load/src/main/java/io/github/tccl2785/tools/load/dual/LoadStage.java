package io.github.tccl2785.tools.load.dual;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
/**
 * 阶段加载注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LoadStage {
    /**
     * 阶段执行顺序，数字越小越先执行
     */
    int order() default 1;
    /**
     * 是否忽略该方法抛出的异常
     * - false (默认): 打印具体异常位置并终止后续加载流程
     * - true: 打印具体异常位置警告，但忽略异常继续后续加载
     */
    boolean ignoreException() default false;
}