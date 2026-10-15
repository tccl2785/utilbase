package io.github.tccl2785.tools.load.dual.spring;

import io.github.tccl2785.tools.load.dual.DualPhaseOrchestrator;
import io.github.tccl2785.tools.load.dual.LoadItem;
import io.github.tccl2785.tools.load.dual.LoadMode;
import io.github.tccl2785.tools.load.dual.PreLaunchLoadItems;
import io.github.tccl2785.tools.load.dual.spring.sp.LaunchStrategy;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

public class DualPhaseBootstrapper implements ApplicationRunner {

    private final ApplicationContext context;
    private final PreLaunchDelegate beforeLaunchDelegate;
    private final PreLaunchDelegate afterLaunchDelegate;
    private final LaunchStrategy launchStrategy;

    public DualPhaseBootstrapper(ApplicationContext context,
                                 PreLaunchDelegate beforeLaunchDelegate,
                                 PreLaunchDelegate afterLaunchDelegate,
                                 LaunchStrategy launchStrategy) {
        this.context = context;
        this.beforeLaunchDelegate = beforeLaunchDelegate;
        this.afterLaunchDelegate = afterLaunchDelegate;
        this.launchStrategy = launchStrategy;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        ConfigurableListableBeanFactory beanFactory =
                ((ConfigurableApplicationContext) context).getBeanFactory();
        // 获取支持手动依赖注入的工厂！这是核心！
        AutowireCapableBeanFactory autowireFactory = context.getAutowireCapableBeanFactory();
        DualPhaseOrchestrator orchestrator = new DualPhaseOrchestrator(
                targetClass -> {
                    try {
                        return context.getBean(targetClass);
                    } catch (Exception e) {
                        return null;
                    }
                },
                instance -> {
                    if (instance == null) {
                        return;
                    }

                    String beanName = instance.getClass().getName() + "_DualPublished";
                    beanFactory.registerSingleton(beanName, instance);
                }
        );

        /*
         * allLoadItems:
         * beforeLaunch / afterLaunch 能看到的完整扫描结果。
         *
         * executableLoadItems:
         * 真正交给 DualPhaseOrchestrator 执行的项目。
         */
        List<LoadItem> allLoadItems = new ArrayList<>();
        List<LoadItem> executableLoadItems = new ArrayList<>();

        for (DualPhaseScannerProcessor.ScanMeta meta : DualPhaseScannerProcessor.SCANNED_METAS) {
            Object obj = null;

            switch (meta.mode) {
                case NONE:
                    if (meta.kotlinObjectInstance != null) {
                        // 场景 1：它是 Kotlin object 且 Mode 是 NONE
                        obj = meta.kotlinObjectInstance;

                        // 1. 让 Spring 对这个已存在的 JVM 单例进行 @Autowired 注入
                        autowireFactory.autowireBean(obj);


                    } else {
                        // 普通 Java/Kotlin 类，Spring 已经接管并完成了注入
                        obj = context.getBean(meta.clazz);
                    }
                    break;

                case COMPANION:
                    // 场景 2：伴生对象
                    obj = meta.companionInstance;
                    if (obj != null) {
                        // 让 Spring 对伴生对象里的 @Autowired 进行注入
                        autowireFactory.autowireBean(obj);
                    }
                    break;

                case STATIC:
                    obj = null;
                    break;

                case DISCOVER_ONLY:
                    /*
                     * 宽松策略：
                     * 只发现，不创建对象，不 getBean。
                     */
                    obj = null;
                    break;

                default:
                    throw new IllegalStateException(
                            "Unsupported LoadMode [" + meta.mode + "] for class [" + meta.clazz.getName() + "]."
                    );
            }

            LoadItem item = new LoadItem(
                    meta.clazz,
                    obj,
                    meta.mode,
                    true
            );

            allLoadItems.add(item);

            /*
             * DISCOVER_ONLY 不加入双相加载。
             *
             * 即：
             * - 不执行 @LoadStage
             * - 不执行 @OnType
             * - 不执行 static load method
             * - 不校验方法签名
             */
            if (meta.mode != LoadMode.DISCOVER_ONLY) {
                executableLoadItems.add(item);
            }
        }

        PreLaunchLoadItems launchItems = new PreLaunchLoadItems(allLoadItems);

        if (beforeLaunchDelegate != null) {
            beforeLaunchDelegate.process(launchItems, context);
        }

        /*
         * 注意：
         * beforeLaunch 可能会修改 allLoadItems。
         *
         * 如果你希望 beforeLaunch 里动态禁用 / 添加也能影响执行，
         * 可以这里重新过滤一次，而不是使用上面提前生成的 executableLoadItems。
         *
         * 推荐这样写，更符合 beforeLaunch 的语义。
         */
        List<LoadItem> finalExecutableLoadItems = new ArrayList<>();
        for (LoadItem item : allLoadItems) {
            if (item.isEnable==false) {
                continue;
            }

            if (item.mode == LoadMode.DISCOVER_ONLY) {
                continue;
            }

            finalExecutableLoadItems.add(item);
        }

        orchestrator.register(finalExecutableLoadItems);

        /*
         * launchStrategy 必须阻塞到双相加载完成后再返回。
         *
         * 如果 enableCoroutines()，内部应该是：
         *
         * runBlocking {
         *     orchestrator.launchSuspend()
         * }
         */
        if (launchStrategy != null) {
            launchStrategy.launch(orchestrator);
        } else {
            orchestrator.launch();
        }

        /*
         * afterLaunch 在双相加载全部完成后执行。
         *
         * DISCOVER_ONLY 的类主要在这里使用。
         */
        if (afterLaunchDelegate != null) {
            afterLaunchDelegate.process(launchItems, context);
        }
    }
}