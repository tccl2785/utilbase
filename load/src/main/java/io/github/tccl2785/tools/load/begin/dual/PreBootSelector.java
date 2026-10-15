package io.github.tccl2785.tools.load.begin.dual;

import io.github.tccl2785.tools.load.dual.LoadItem;
import io.github.tccl2785.tools.load.dual.PreLaunchLoadItems;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 DualPhase 的 PreLaunchLoadItems 中筛选 @PreBoot 标记的 PreBootUnit。
 */
public final class PreBootSelector {

    private PreBootSelector() {
    }

    /**
     * 从 PreLaunchLoadItems 中筛选指定阶段的 PreBootUnit。
     *
     * 注意：
     * 如果某个 LoadItem 已经被 beforeLaunch / afterLaunch 修改为 isEnable = false，
     * 这里会跳过它。
     */
    @SuppressWarnings("unchecked")
    public static Class<? extends PreBootUnit>[] select(
            PreLaunchLoadItems loadItems,
            PreBootPhase phase
    ) {
        if (loadItems == null || phase == null) {
            return new Class[0];
        }

        List<Class<? extends PreBootUnit>> result = new ArrayList<>();

        for (LoadItem item : loadItems.getItems()) {
            if (item == null || item.classz == null) {
                continue;
            }

            if (!item.isEnable) {
                continue;
            }

            Class<?> clazz = item.classz;

            PreBoot preBoot = clazz.getAnnotation(PreBoot.class);
            if (preBoot == null) {
                continue;
            }

            if (preBoot.value() != phase) {
                continue;
            }

            if (!PreBootUnit.class.isAssignableFrom(clazz)) {
                throw new IllegalStateException(
                        "Class [" + clazz.getName() + "] is annotated with @PreBoot, " +
                                "but it does not extend PreBootUnit."
                );
            }

            result.add((Class<? extends PreBootUnit>) clazz);
        }

        return result.toArray(new Class[0]);
    }
}