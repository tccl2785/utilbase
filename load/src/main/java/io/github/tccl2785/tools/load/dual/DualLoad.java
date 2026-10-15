package io.github.tccl2785.tools.load.dual;

import java.lang.annotation.*;
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DualLoad {
    /** 对应 LoadItem 的 Mode */
    LoadMode mode() default LoadMode.NONE;

    /** 对应 LoadItem 的 isEnable */
    boolean isEnable() default true;
}