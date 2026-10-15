package io.github.tccl2785.tools.load.dual.spring

import io.github.tccl2785.tools.load.dual.spring.sp.LaunchStrategy
import kotlinx.coroutines.runBlocking
/**
 * 启用协程版双相加载。
 *
 * 重要：
 * 这里必须使用 runBlocking 等待 orchestrator.launchSuspend() 完整执行完成。
 *
 * 这样才能保证：
 * 1. 所有普通 @LoadStage / @OnType 方法执行完成。
 * 2. 所有 suspend @LoadStage / @OnType 方法执行完成。
 * 3. DualPhaseBootstrapper 中 launchStrategy.launch(orchestrator) 返回之后，
 *    才会执行 afterLaunch。
 */
fun DualPhaseInitializer.Builder.enableCoroutines(): DualPhaseInitializer.Builder {
    return this.withLaunchStrategy(
        LaunchStrategy { orchestrator ->
            runBlocking {
                orchestrator.launchSuspend()
            }
        }
    )
}