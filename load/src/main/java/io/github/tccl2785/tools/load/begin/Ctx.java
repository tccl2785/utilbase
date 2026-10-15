package io.github.tccl2785.tools.load.begin;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 上下文容器：存储与解析组件依赖
 */
public class Ctx {
    private final Function<Class<?>, Object> factory;
    public Ctx(Function<Class<?>, Object> factory) {
        this.factory = Objects.requireNonNull(factory, "对象创建委托 (factory) 不能为 null");
    }
    /**
     * 根据 Class 类型直接通过外部委托获取/创建实例
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        Objects.requireNonNull(type, "类型参数不能为 null");
        try {
            Object obj = factory.apply(type);
            if (obj == null) {
                throw new RuntimeException("外部委托工厂未能成功获取 [" + type.getName() + "] 的实例 (返回了 null)");
            }
            return (T) obj;
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("通过外部委托工厂获取类型 [" + type.getName() + "] 失败", e);
        }
    }
    /**
     * 兼容接口：语义同 get(type)
     */
    public <T> T getOrCreate(Class<T> type) {
        return get(type);
    }
}