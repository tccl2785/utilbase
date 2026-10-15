package io.github.tccl2785.tools.load.dual;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

class DefaultInitializationContext implements InitializationContext {

    private final Function<Class<?>, Object> beanResolver;
    private final Consumer<Object> beanPublisher;

    // 独立模式下的内部容器
    private final Map<Class<?>, Object> internalContainer = new ConcurrentHashMap<>();

    // 记录所有已被 publish 的 Class 类型（用于第二相派发）
    private final Set<Class<?>> publishedTypes = ConcurrentHashMap.newKeySet();

    public DefaultInitializationContext(Function<Class<?>, Object> beanResolver,
                                        Consumer<Object> beanPublisher) {
        this.beanResolver = beanResolver;
        this.beanPublisher = beanPublisher;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T require(Class<T> clazz) {
        Object instance = null;
        // 1. 优先从外部委托工厂获取
        if (beanResolver != null) {
            instance = beanResolver.apply(clazz);
        }
        // 2. 外部没有获取到，尝试从内部容器获取
        if (instance == null) {
            instance = internalContainer.get(clazz);
        }

        if (instance == null) {
            throw new IllegalStateException("Required bean of type [" + clazz.getName() + "] is not found.");
        }
        return (T) instance;
    }

    @Override
    public void publish(Object instance) {
        if (instance == null) {
            throw new IllegalArgumentException("Cannot publish null instance.");
        }

        Class<?> instanceType = instance.getClass();

        // 1. 若配置了外部发布委托，推入外部工厂
        if (beanPublisher != null) {
            beanPublisher.accept(instance);
        } else {
            // 2. 否则存入内部容器
            internalContainer.put(instanceType, instance);
        }

        // 3. 记录类型，标记为已就绪
        publishedTypes.add(instanceType);
    }

    @Override
    public boolean contains(Class<?> clazz) {
        if (publishedTypes.contains(clazz) || internalContainer.containsKey(clazz)) {
            return true;
        }
        if (beanResolver != null) {
            try {
                return beanResolver.apply(clazz) != null;
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    public Set<Class<?>> getPublishedTypes() {
        return publishedTypes;
    }
}