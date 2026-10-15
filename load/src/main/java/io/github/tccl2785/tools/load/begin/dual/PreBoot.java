package io.github.tccl2785.tools.load.begin.dual;

import io.github.tccl2785.tools.load.dual.DualLoad;
import io.github.tccl2785.tools.load.dual.LoadMode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 PreBootUnit 由 DualPhase 自动发现并在指定阶段执行。
 *
 * 注意：
 * 这里使用 @DualLoad(mode = LoadMode.DISCOVER_ONLY) 作为元注解。
 *
 * 因此被 @PreBoot 标记的类会：
 *
 * 1. 被 DualPhaseScannerProcessor 扫描到。
 * 2. 进入 PreLaunchLoadItems。
 * 3. 不参与普通 DualPhase 的 @LoadStage / @OnType 执行。
 * 4. 只在 beforeLaunch / afterLaunch 附近由 PreLaunchBoot 执行。
 */
@DualLoad(mode = LoadMode.DISCOVER_ONLY)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PreBoot {

    /**
     * 当前 PreBootUnit 的执行阶段。
     */
    PreBootPhase value();
}