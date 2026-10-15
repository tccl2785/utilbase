package io.github.tccl2785.tools.load.dual.spring;

import io.github.tccl2785.tools.load.dual.DualLoad;
import io.github.tccl2785.tools.load.dual.LoadMode;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

public class DualPhaseScannerProcessor implements BeanFactoryPostProcessor {

    public static final List<ScanMeta> SCANNED_METAS = new CopyOnWriteArrayList<>();

    private final Predicate<Class<?>> ignoreDelegate;
    private final String[] basePackages;

    public DualPhaseScannerProcessor(Predicate<Class<?>> ignoreDelegate,
                                     String[] basePackages) {
        this.ignoreDelegate = ignoreDelegate != null ? ignoreDelegate : clazz -> false;
        this.basePackages = basePackages != null ? basePackages : new String[]{""};
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {

        if (!(beanFactory instanceof BeanDefinitionRegistry)) {
            return;
        }

        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);

        /*
         * AnnotationTypeFilter 默认支持元注解扫描。
         *
         * 因此：
         *
         * @PreBoot(...)
         * public class Xxx extends PreBootUnit {}
         *
         * 只要 @PreBoot 上标记了：
         *
         * @DualLoad(mode = LoadMode.DISCOVER_ONLY)
         *
         * 就可以被这里扫描到。
         */
        scanner.addIncludeFilter(new AnnotationTypeFilter(DualLoad.class));

        for (String basePackage : basePackages) {
            for (BeanDefinition beanDefinition : scanner.findCandidateComponents(basePackage)) {
                String className = beanDefinition.getBeanClassName();
                if (className == null || className.trim().isEmpty()) {
                    continue;
                }

                Class<?> clazz = loadClass(className);
                if (clazz == null) {
                    continue;
                }

                if (ignoreDelegate.test(clazz)) {
                    continue;
                }

                /*
                 * 重要：
                 * 这里不能用 clazz.getAnnotation(DualLoad.class)。
                 *
                 * 因为 @PreBoot 是通过元注解方式标记 @DualLoad 的。
                 *
                 * AnnotationUtils.findAnnotation 可以查找直接注解和元注解。
                 */
                DualLoad dualLoad = AnnotationUtils.findAnnotation(clazz, DualLoad.class);
                if (dualLoad == null) {
                    continue;
                }

                LoadMode mode = dualLoad.mode();

                Object companionInstance = null;
                if (mode == LoadMode.COMPANION) {
                    companionInstance = resolveCompanionInstance(clazz);
                }

                Object kotlinObjectInstance = resolveKotlinObjectInstance(clazz);

                /*
                 * 先记录扫描结果。
                 *
                 * DISCOVER_ONLY 也会进入 SCANNED_METAS，
                 * 这样 beforeLaunch / afterLaunch / @PreBoot 都能拿到它。
                 */
                SCANNED_METAS.add(
                        new ScanMeta(
                                clazz,
                                mode,
                                companionInstance,
                                kotlinObjectInstance
                        )
                );

                /*
                 * DISCOVER_ONLY 宽松策略：
                 *
                 * 只发现，不创建 Bean，不注册 BeanDefinition。
                 * 即使类里面有 @LoadStage / @OnType，也不在这里报错。
                 *
                 * @PreBoot 默认就是 DISCOVER_ONLY。
                 */
                if (mode == LoadMode.DISCOVER_ONLY) {
                    continue;
                }

                /*
                 * NONE 模式需要注册成 Spring Bean，方便后续 context.getBean(clazz)。
                 *
                 * STATIC / COMPANION 一般不需要注册 Bean。
                 */
                if (mode == LoadMode.NONE) {
                    /*
                     * 如果它是 Kotlin object，不能让 Spring 用普通方式注册 BeanDefinition，
                     * 否则 Spring 实例化时会因为私有构造函数报错。
                     * 会在 Bootstrapper 里手动注册。
                     */
                    if (kotlinObjectInstance == null) {
                        registerBeanIfAbsent(registry, clazz);
                    }
                }
            }
        }
    }

    private Object resolveKotlinObjectInstance(Class<?> clazz) {
        try {
            Field field = clazz.getDeclaredField("INSTANCE");
            if (Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                return field.get(null);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // 没有 INSTANCE 字段，说明不是 Kotlin object，忽略
        }
        return null;
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Failed to load class [" + className + "].",
                    throwable
            );
        }
    }

    private void registerBeanIfAbsent(BeanDefinitionRegistry registry, Class<?> clazz) {
        String beanName = clazz.getName();

        if (registry.containsBeanDefinition(beanName)) {
            return;
        }

        BeanDefinition beanDefinition =
                BeanDefinitionBuilder.genericBeanDefinition(clazz)
                        .getBeanDefinition();

        registry.registerBeanDefinition(beanName, beanDefinition);
    }

    private Object resolveCompanionInstance(Class<?> clazz) {
        try {
            Field field = clazz.getDeclaredField("Companion");

            if (!Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException(
                        "Field [" + clazz.getName() + "#Companion] is not static."
                );
            }

            field.setAccessible(true);

            Object value = field.get(null);
            if (value == null) {
                throw new IllegalStateException(
                        "Companion object of class [" + clazz.getName() + "] is null."
                );
            }

            return value;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException(
                    "Cannot find Companion object for class [" + clazz.getName() + "].",
                    exception
            );
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Cannot access Companion object for class [" + clazz.getName() + "].",
                    exception
            );
        }
    }

    public static class ScanMeta {
        public final Class<?> clazz;
        public final LoadMode mode;
        public final Object companionInstance;
        public final Object kotlinObjectInstance;

        public ScanMeta(Class<?> clazz,
                        LoadMode mode,
                        Object companionInstance,
                        Object kotlinObjectInstance) {
            this.clazz = clazz;
            this.mode = mode;
            this.companionInstance = companionInstance;
            this.kotlinObjectInstance = kotlinObjectInstance;
        }
    }
}