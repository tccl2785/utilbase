package io.github.tccl2785.tools.load.begin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class BeginBoot {

    /**
     * 顺序串行加载 1 个或多个 BootUnit 配置类
     *
     * @param classes 待加载的 BootUnit 配置类列表（按参数顺序串行执行）
     * @param factory 依赖对象创建委托，仅用于 Step 方法参数注入
     */
    public static void run(Class<? extends BootUnit>[] classes, Function<Class<?>, Object> factory) {
        if (factory == null) {
            throw new IllegalArgumentException("必须提供有效的对象创建委托 (factory)");
        }
        if (classes == null || classes.length == 0) {
            return;
        }

        // Ctx 仅保留接口性质，用于参数获取
        Ctx ctx = new Ctx(factory);

        // 按顺序串行加载每一个 BootUnit
        for (Class<? extends BootUnit> clazz : classes) {
            if (clazz != null) {
                runSingleUnit(clazz, ctx);
            }
        }
    }

    /**
     * 执行单个 BootUnit 的生命周期
     */
    private static void runSingleUnit(Class<? extends BootUnit> clazz, Ctx ctx) {
        // 直接通过无参构造函数创建 BootUnit 实例
        BootUnit instance = createBootUnit(clazz);

        // 1. 生命周期：onBegin
        invokeLifecycleMethod(clazz, instance, "onBegin", ctx);

        // 2. 扫描并排序当前类声明的所有 public 实例方法
        List<Method> publicMethods = Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .sorted(BeginBoot::compareStepMethods)
                .collect(Collectors.toList());

        // 依次执行 Step 方法
        for (Method method : publicMethods) {
            method.setAccessible(true);

            // 所有参数实例均通过外部委托获取
            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                args[i] = ctx.get(paramTypes[i]);
            }

            // 获取步骤的异常忽略策略
            boolean ignoreError = false;
            if (method.isAnnotationPresent(Step.class)) {
                ignoreError = method.getAnnotation(Step.class).ignoreError();
            }

            // 反射执行
            try {
                method.invoke(instance, args);
            } catch (Throwable t) {
                Throwable realCause = (t instanceof InvocationTargetException)
                        ? ((InvocationTargetException) t).getTargetException()
                        : t;

                if (ignoreError) {
                    System.err.printf("⚠️ [BootLite 警告] [%s#%s] 执行异常（忽略）。原因: %s%n",
                            clazz.getSimpleName(), method.getName(), realCause.getMessage());
                } else {
                    System.err.printf("❌ [BootLite 致命] [%s#%s] 执行异常，流程中断！原因: %s%n",
                            clazz.getSimpleName(), method.getName(), realCause.getMessage());
                    throw new RuntimeException(
                            "BootLite 加载中断于: " + clazz.getSimpleName() + "#" + method.getName(),
                            realCause
                    );
                }
            }
        }

        // 3. 生命周期：onReady
        invokeLifecycleMethod(clazz, instance, "onReady", ctx);
    }

    /**
     * 使用无参构造函数创建 BootUnit 实例
     */
    public static BootUnit createBootUnit(Class<? extends BootUnit> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("类 " + clazz.getName() + " 缺少无参构造函数", e);
        } catch (Exception e) {
            throw new RuntimeException("创建 BootUnit 实例失败: " + clazz.getName(), e);
        }
    }

    /**
     * 方法执行顺序比较逻辑
     */
    private static int compareStepMethods(Method m1, Method m2) {
        boolean hasS1 = m1.isAnnotationPresent(Step.class);
        boolean hasS2 = m2.isAnnotationPresent(Step.class);

        if (!hasS1 && !hasS2) return 0;
        if (!hasS1) return -1;
        if (!hasS2) return 1;

        return Integer.compare(m1.getAnnotation(Step.class).value(), m2.getAnnotation(Step.class).value());
    }

    /**
     * 反射调用 protected 生命周期钩子
     */
     static void invokeLifecycleMethod(Class<?> clazz, Object instance, String methodName, Ctx ctx) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Method method = current.getDeclaredMethod(methodName, Ctx.class);
                method.setAccessible(true);
                method.invoke(instance, ctx);
                return;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            } catch (Exception e) {
                throw new RuntimeException("执行生命周期钩子 [" + methodName + "] 失败", e);
            }
        }
    }
}