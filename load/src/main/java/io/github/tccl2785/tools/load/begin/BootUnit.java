package io.github.tccl2785.tools.load.begin;

/**
 * 配置单元生命周期基类
 */
public abstract class BootUnit {

    /**
     * 生命周期 1：初始化开始钩子
     *
     * @param ctx 上下文容器
     */
    protected abstract void onBegin(Ctx ctx) ;

    /**
     * 生命周期 3：所有步骤执行完毕后的就绪钩子
     *
     * @param ctx 上下文容器
     */
    protected abstract void onReady(Ctx ctx) ;
}