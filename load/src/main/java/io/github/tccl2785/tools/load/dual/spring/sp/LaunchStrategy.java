package io.github.tccl2785.tools.load.dual.spring.sp;



import io.github.tccl2785.tools.load.dual.DualPhaseOrchestrator;

/**
 * 用于抽象最后的启动动作
 */
@FunctionalInterface
public interface LaunchStrategy {
    /**
     * 执行双阶段引擎的启动动作
     * @param orchestrator 已完成注册的引擎实例
     */
    void launch(DualPhaseOrchestrator orchestrator) throws Exception;
}