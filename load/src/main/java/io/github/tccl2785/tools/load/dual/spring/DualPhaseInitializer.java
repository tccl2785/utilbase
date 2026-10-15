package io.github.tccl2785.tools.load.dual.spring;

import io.github.tccl2785.tools.load.begin.dual.PreBootPhase;
import io.github.tccl2785.tools.load.begin.dual.PreBootSelector;
import io.github.tccl2785.tools.load.begin.dual.PreBootUnit;
import io.github.tccl2785.tools.load.begin.dual.PreLaunchBoot;
import io.github.tccl2785.tools.load.dual.spring.sp.LaunchStrategy;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

public class DualPhaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private final Predicate<Class<?>> ignoreDelegate;
    private final PreLaunchDelegate beforeLaunchDelegate;
    private final PreLaunchDelegate afterLaunchDelegate;
    private final String[] basePackages;
    private final LaunchStrategy launchStrategy;

    private DualPhaseInitializer(Predicate<Class<?>> ignoreDelegate,
                                 PreLaunchDelegate beforeLaunchDelegate,
                                 PreLaunchDelegate afterLaunchDelegate,
                                 String[] basePackages,
                                 LaunchStrategy launchStrategy) {
        this.ignoreDelegate = ignoreDelegate;
        this.beforeLaunchDelegate = beforeLaunchDelegate;
        this.afterLaunchDelegate = afterLaunchDelegate;
        this.basePackages = basePackages;
        this.launchStrategy = launchStrategy;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        applicationContext.addBeanFactoryPostProcessor(
                new DualPhaseScannerProcessor(ignoreDelegate, basePackages)
        );

        applicationContext.addBeanFactoryPostProcessor(beanFactory -> {
            ((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(
                    "dualPhaseBootstrapper",
                    BeanDefinitionBuilder.genericBeanDefinition(
                                    DualPhaseBootstrapper.class,
                                    () -> new DualPhaseBootstrapper(
                                            applicationContext,
                                            beforeLaunchDelegate,
                                            afterLaunchDelegate,
                                            launchStrategy
                                    )
                            )
                            .getBeanDefinition()
            );
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private Predicate<Class<?>> ignoreDelegate = clazz -> false;

        /**
         * 用户原始 beforeLaunch。
         *
         * 多次调用 beforeLaunch(...) 时，后面的覆盖前面的。
         */
        private PreLaunchDelegate beforeLaunchDelegate = (items, ctx) -> {
        };

        /**
         * 用户原始 afterLaunch。
         *
         * 多次调用 afterLaunch(...) 时，后面的覆盖前面的。
         */
        private PreLaunchDelegate afterLaunchDelegate = (items, ctx) -> {
        };

        /**
         * 显式 before BootUnit。
         *
         * 多次调用 beforeBootUnits(...) 时，后面的覆盖前面的。
         */
        private List<Class<? extends PreBootUnit>> beforeBootUnits = Collections.emptyList();

        /**
         * 显式 after BootUnit。
         *
         * 多次调用 afterBootUnits(...) 时，后面的覆盖前面的。
         */
        private List<Class<? extends PreBootUnit>> afterBootUnits = Collections.emptyList();

        private String[] basePackages = new String[]{""};

        /**
         * 默认非协程启动策略。
         */
        private LaunchStrategy launchStrategy = orchestrator -> orchestrator.launch();

        public Builder scanPackages(String... packages) {
            this.basePackages = packages;
            return this;
        }

        public Builder ignoreIf(Predicate<Class<?>> condition) {
            this.ignoreDelegate = condition != null ? condition : clazz -> false;
            return this;
        }

        /**
         * 双相加载开始前执行。
         *
         * 执行顺序：
         *
         * beforeLaunch
         *     ↓
         * @PreBoot(BEFORE)
         *     ↓
         * beforeBootUnits
         */
        public Builder beforeLaunch(PreLaunchDelegate handler) {
            this.beforeLaunchDelegate = handler != null ? handler : (items, ctx) -> {
            };
            return this;
        }

        /**
         * 双相加载全部完成之后执行。
         *
         * 执行顺序：
         *
         * afterLaunch
         *     ↓
         * @PreBoot(AFTER)
         *     ↓
         * afterBootUnits
         *
         * 如果使用 enableCoroutines()，
         * afterLaunch 会在 launchSuspend() 完整执行完成后才执行。
         */
        public Builder afterLaunch(PreLaunchDelegate handler) {
            this.afterLaunchDelegate = handler != null ? handler : (items, ctx) -> {
            };
            return this;
        }

        /**
         * DualPhase 正式 launch 前执行的显式 PreBootUnit。
         *
         * 执行位置：
         *
         * beforeLaunch
         *     ↓
         * @PreBoot(BEFORE)
         *     ↓
         * beforeBootUnits
         *
         * 多次调用 beforeBootUnits(...) 时，后面的覆盖前面的。
         */
        @SafeVarargs
        public final Builder beforeBootUnits(Class<? extends PreBootUnit>... units) {
            this.beforeBootUnits = normalizeBootUnits(units);
            return this;
        }

        /**
         * DualPhase 完整 launch 后执行的显式 PreBootUnit。
         *
         * 执行位置：
         *
         * afterLaunch
         *     ↓
         * @PreBoot(AFTER)
         *     ↓
         * afterBootUnits
         *
         * 多次调用 afterBootUnits(...) 时，后面的覆盖前面的。
         */
        @SafeVarargs
        public final Builder afterBootUnits(Class<? extends PreBootUnit>... units) {
            this.afterBootUnits = normalizeBootUnits(units);
            return this;
        }

        /**
         * 自定义启动策略。
         *
         * 重要：
         * 自定义策略必须阻塞到双相加载完成后才返回。
         */
        public Builder withLaunchStrategy(LaunchStrategy strategy) {
            this.launchStrategy = strategy != null ? strategy : orchestrator -> orchestrator.launch();
            return this;
        }

        public DualPhaseInitializer build() {
            PreLaunchDelegate effectiveBeforeLaunchDelegate = buildEffectiveBeforeLaunchDelegate();
            PreLaunchDelegate effectiveAfterLaunchDelegate = buildEffectiveAfterLaunchDelegate();

            return new DualPhaseInitializer(
                    ignoreDelegate,
                    effectiveBeforeLaunchDelegate,
                    effectiveAfterLaunchDelegate,
                    basePackages,
                    launchStrategy
            );
        }

        /**
         * 构建最终 beforeLaunch 委托。
         *
         * 固定顺序：
         *
         * 1. 用户 beforeLaunch
         * 2. 注解型 @PreBoot(BEFORE)
         * 3. 显式 beforeBootUnits
         */
        private PreLaunchDelegate buildEffectiveBeforeLaunchDelegate() {
            PreLaunchDelegate userBeforeLaunch = this.beforeLaunchDelegate;
            Class<? extends PreBootUnit>[] manualBeforeUnits = toBootUnitArray(this.beforeBootUnits);

            return (items, ctx) -> {
                // 1. 原始 beforeLaunch
                if (userBeforeLaunch != null) {
                    userBeforeLaunch.process(items, ctx);
                }

                // 2. 注解型 @PreBoot(BEFORE)
                Class<? extends PreBootUnit>[] annotatedBeforeUnits =
                        PreBootSelector.select(items, PreBootPhase.BEFORE);

                if (annotatedBeforeUnits.length > 0) {
                    PreLaunchBoot.run(
                            annotatedBeforeUnits,
                            ctx::getBean,
                            items
                    );
                }

                // 3. 显式 beforeBootUnits
                if (manualBeforeUnits.length > 0) {
                    PreLaunchBoot.run(
                            manualBeforeUnits,
                            ctx::getBean,
                            items
                    );
                }
            };
        }

        /**
         * 构建最终 afterLaunch 委托。
         *
         * 固定顺序：
         *
         * 1. 用户 afterLaunch
         * 2. 注解型 @PreBoot(AFTER)
         * 3. 显式 afterBootUnits
         */
        private PreLaunchDelegate buildEffectiveAfterLaunchDelegate() {
            PreLaunchDelegate userAfterLaunch = this.afterLaunchDelegate;
            Class<? extends PreBootUnit>[] manualAfterUnits = toBootUnitArray(this.afterBootUnits);

            return (items, ctx) -> {
                // 1. 原始 afterLaunch
                if (userAfterLaunch != null) {
                    userAfterLaunch.process(items, ctx);
                }

                // 2. 注解型 @PreBoot(AFTER)
                Class<? extends PreBootUnit>[] annotatedAfterUnits =
                        PreBootSelector.select(items, PreBootPhase.AFTER);

                if (annotatedAfterUnits.length > 0) {
                    PreLaunchBoot.run(
                            annotatedAfterUnits,
                            ctx::getBean,
                            items
                    );
                }

                // 3. 显式 afterBootUnits
                if (manualAfterUnits.length > 0) {
                    PreLaunchBoot.run(
                            manualAfterUnits,
                            ctx::getBean,
                            items
                    );
                }
            };
        }

        private static List<Class<? extends PreBootUnit>> normalizeBootUnits(
                Class<? extends PreBootUnit>[] units
        ) {
            if (units == null || units.length == 0) {
                return Collections.emptyList();
            }

            List<Class<? extends PreBootUnit>> list = new ArrayList<>();

            for (Class<? extends PreBootUnit> unit : units) {
                if (unit != null) {
                    list.add(unit);
                }
            }

            if (list.isEmpty()) {
                return Collections.emptyList();
            }

            return list;
        }

        @SuppressWarnings("unchecked")
        private static Class<? extends PreBootUnit>[] toBootUnitArray(
                List<Class<? extends PreBootUnit>> units
        ) {
            if (units == null || units.isEmpty()) {
                return new Class[0];
            }

            return units.toArray(new Class[0]);
        }
    }
}