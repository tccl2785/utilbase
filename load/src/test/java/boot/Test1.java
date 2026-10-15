package boot;


import io.github.tccl2785.tools.load.begin.BootUnit;
import io.github.tccl2785.tools.load.begin.Ctx;
import io.github.tccl2785.tools.load.begin.Step;

public class Test1 {

    public static class AppConfig extends BootUnit {
        @Override
        protected void onBegin(Ctx ctx) {
            System.out.println("=== [1] 初始化 BootUnit 开始 ===");
        }
        // 无 @Step 注解：最优先执行，自动注入 DbConfig 和 RedisConfig
        public void initCore(Test123 db) {
            db.a=13;


        }
        // @Step(1) & ignoreError = true：发生异常会被捕捉打印警告，但不中断程序
        @Step(value = 1, ignoreError = true)
        public void initOptionalMetrics() {

            throw new RuntimeException("第三方日志云端网络连接超时！");
        }
        // @Step(2)：按顺序执行
        @Step(2)
        public void checkStatus() {
            System.out.println("\n[步骤 3] 状态检查通过");

         //  throw new RuntimeException("测试异常！");
        }
        @Override
        protected void onReady(Ctx ctx) {
            System.out.println("\n=== [3] 全部加载完毕，系统就绪 ===");
            System.out.println("Ctx 容器包含 DbConfig: " + (ctx.get(Test123.class)));
        }
    }
    public static void main(String[] args) {
//        System.out.println("---- 测试 A: 默认工厂运行 ----");
//        BeginBoot.run(AppConfig.class,type->{
//            return Singleton.get(type);
//        });
//        System.out.println("\n------------------------------------\n");
//        System.out.println("---- 测试 B: 传入自定义外部委托工厂运行 ----");
//
//      System.out.println(  Singleton.get(Test123.class));

    }

}
