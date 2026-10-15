package io.github.tccl2785.tools.load.dual;


public  class LoadItem {




    /** 被注解 (@LoadStage / @OnType) 标记的目标类 Class */
    public Class<?> classz;
    /** Class 对应的单例/实例对象；若 staticMode = true 则可以为 null */
    public Object obj;
    /** 静态模式标志：true 表示调用 classz 的 private static 方法，忽略 obj */
    public LoadMode mode = LoadMode.NONE;
    /** 启用状态标志：false 时引擎直接忽略此项 */
    public boolean isEnable = true;

    public LoadItem(Class<?> classz, Object obj, LoadMode mode, boolean isEnable) {
        this.classz = classz;
        this.obj = obj;
        this.mode = mode != null ? mode : LoadMode.NONE;
        this.isEnable = isEnable;
    }

}
