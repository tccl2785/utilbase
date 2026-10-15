package io.github.tccl2785.tools.load.dual.spring;

import io.github.tccl2785.tools.load.dual.LoadItem;
import io.github.tccl2785.tools.load.dual.PreLaunchLoadItems;
import org.springframework.context.ApplicationContext;

import java.util.List;

@FunctionalInterface
public interface PreLaunchDelegate {
    /**
     * 在引擎 launch 之前的最后干预点。
     *
     * @param loadItems 当前通过扫描注解生成的业务 LoadItem 操作视图。
     *                  内部持有原始 List<LoadItem>，可通过 getItems() 直接增删改。
     * @param context   Spring 容器上下文，方便获取环境配置或其他 Bean。
     */
    void process(PreLaunchLoadItems loadItems, ApplicationContext context);
}