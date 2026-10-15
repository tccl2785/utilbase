package io.github.tccl2785.tools.load.begin.dual;

/**
 * PreBootUnit 在 DualPhase 生命周期中的执行阶段。
 */
public enum PreBootPhase {

    /**
     * DualPhase 正式 launch 之前执行。
     *
     * 执行位置：
     *
     * beforeLaunch
     *     ↓
     * @PreBoot(BEFORE)
     *     ↓
     * beforeBootUnits
     */
    BEFORE,

    /**
     * DualPhase 完整 launch 之后执行。
     *
     * 执行位置：
     *
     * afterLaunch
     *     ↓
     * @PreBoot(AFTER)
     *     ↓
     * afterBootUnits
     */
    AFTER
}