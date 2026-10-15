package io.github.tccl2785.tools.load.dual;



import io.github.tccl2785.tools.load.dual.LoadItem;
import io.github.tccl2785.tools.load.dual.LoadMode;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * beforeLaunch 阶段暴露给使用者的 LoadItem 操作视图。
 *
 * 内部持有原始 List<LoadItem>，因此：
 * 1. getItems() 返回的是原始可变列表。
 * 2. 使用者仍然可以 add/remove/clear，影响最终进入 orchestrator 的内容。
 * 3. 额外提供按类型、父类、接口、注解筛选的便捷方法。
 */
public class PreLaunchLoadItems {

    /**
     * 原 beforeLaunch 的第一个参数：List<LoadItem>
     *
     * 这里保留为内部字段。
     * 不复制，直接持有原始引用，保证使用者修改后能影响最终启动列表。
     */
    private final List<LoadItem> items;

    public PreLaunchLoadItems(List<LoadItem> items) {
        if (items == null) {
            throw new IllegalArgumentException("items cannot be null.");
        }
        this.items = items;
    }

    /**
     * 获取原始 LoadItem 列表。
     *
     * 注意：
     * 这里返回的是原始可变列表，不是副本。
     * 对返回值执行 add/remove/clear 会直接影响最终注册进 DualPhaseOrchestrator 的内容。
     */
    public List<LoadItem> getItems() {
        return items;
    }

    /**
     * 根据 Class 获取 LoadItem。
     *
     * 支持：
     * 1. 精确类型匹配
     * 2. 父类匹配
     * 3. 接口匹配
     *
     * 例如：
     * - getItemsByClass(BaseService.class, LoadMode.NONE)
     *   可以获取所有 classz 继承 BaseService 的 LoadItem。
     *
     * - getItemsByClass(SomeInterface.class, LoadMode.NONE)
     *   可以获取所有 classz 实现 SomeInterface 的 LoadItem。
     *
     * mode 规则：
     * - mode == null：不限制 LoadMode
     * - mode != null：只返回指定 LoadMode 的 LoadItem
     *
     * @param classz 目标类型、父类或接口
     * @param mode   LoadMode 过滤条件，可为 null
     * @return 匹配到的 LoadItem 列表，返回的是新 List，但其中元素是原始 LoadItem 引用
     */
    public List<LoadItem> getItemsByClass(Class<?> classz, LoadMode mode) {
        if (classz == null) {
            return Collections.emptyList();
        }

        List<LoadItem> result = new ArrayList<>();

        for (LoadItem item : items) {
            if (item == null || item.classz == null) {
                continue;
            }

            if (!matchMode(item, mode)) {
                continue;
            }

            /*
             * classz.isAssignableFrom(item.classz)
             *
             * 含义：
             * - 如果 classz == item.classz，true
             * - 如果 item.classz 是 classz 的子类，true
             * - 如果 item.classz 实现了 classz 接口，true
             */
            if (classz.isAssignableFrom(item.classz)) {
                result.add(item);
            }
        }

        return result;
    }

    /**
     * 根据注解获取 LoadItem。
     *
     * 会检查 item.classz 上是否存在指定注解。
     *
     * mode 规则：
     * - mode == null：不限制 LoadMode
     * - mode != null：只返回指定 LoadMode 的 LoadItem
     *
     * @param annotationClassz 注解类型
     * @param mode            LoadMode 过滤条件，可为 null
     * @return 匹配到的 LoadItem 列表，返回的是新 List，但其中元素是原始 LoadItem 引用
     */
    public List<LoadItem> getItemsByannotations(Class<? extends Annotation> annotationClassz, LoadMode mode) {
        if (annotationClassz == null) {
            return Collections.emptyList();
        }

        List<LoadItem> result = new ArrayList<>();

        for (LoadItem item : items) {
            if (item == null || item.classz == null) {
                continue;
            }

            if (!matchMode(item, mode)) {
                continue;
            }

            if (item.classz.isAnnotationPresent(annotationClassz)) {
                result.add(item);
            }
        }

        return result;
    }

    /**
     * 判断是否存在指定类型。
     *
     * 支持父类和接口判断。
     */
    public boolean containsClass(Class<?> classz, LoadMode mode) {
        return !getItemsByClass(classz, mode).isEmpty();
    }

    /**
     * 判断是否存在带指定注解的 LoadItem。
     */
    public boolean containsAnnotation(Class<? extends Annotation> annotationClassz, LoadMode mode) {
        return !getItemsByannotations(annotationClassz, mode).isEmpty();
    }

    /**
     * 添加一个 LoadItem。
     *
     * 等价于 getItems().add(item)，只是提供一个更直观的入口。
     */
    public boolean add(LoadItem item) {
        return items.add(item);
    }

    /**
     * 删除一个 LoadItem。
     */
    public boolean remove(LoadItem item) {
        return items.remove(item);
    }

    /**
     * 当前 LoadItem 数量。
     */
    public int size() {
        return items.size();
    }

    /**
     * 是否为空。
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /**
     * 清空所有 LoadItem。
     *
     * 慎用。
     */
    public void clear() {
        items.clear();
    }

    private boolean matchMode(LoadItem item, LoadMode mode) {
        if (mode == null) {
            return true;
        }
        return item.mode == mode;
    }
}