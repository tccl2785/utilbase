package io.github.tccl2785.tools.load.dual;

public interface InitializationContext {
    /**
     * 获取指定类型的单例实例
     */
    <T> T require(Class<T> clazz);
    /**
     * 发布一个单例实例，并将其 Class 标记为已就绪，用于第二相 @OnType 派发
     */
    void publish(Object instance);
    /**
     * 检查某个类型的实例是否存在
     */
    boolean contains(Class<?> clazz);
}
