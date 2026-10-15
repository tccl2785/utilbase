package io.github.tccl2785.tools.load.dual;

public enum LoadMode {
    /** 普通单例实例模式（obj 为单例对象） */
    NONE,
    /** 静态模式（obj 为 null，直接调用 classz 的 private static 方法） */
    STATIC,
    /** Kotlin 伴生对象模式（加载 classz 内部 Companion 对象的 private 方法） */
    COMPANION,
    /**
     * 只发现不处理
     */
    DISCOVER_ONLY
}
