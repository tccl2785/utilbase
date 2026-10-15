# utilbase · 基础工具库

`utilbase` 是一个聚合工程，用于收录可独立复用的 Java / Kotlin 基础模块，并通过 **Maven 中央仓库**对外发布，任何具备 Maven 环境的项目都可直接引用。

- 源码仓库：https://github.com/tccl2785/utilbase
- 许可证：[Apache-2.0](LICENSE)

## 模块一览

| 模块 | 说明 | 中央仓库状态 |
| --- | --- | --- |
| [`load`](load) | 加载 / 初始化框架：双相加载 + 步骤编排 + Spring Boot 整合 | ✅ 已发布 `0.0.1` |

> 其他子模块（`me-tools1`、`socket-tools`、`persistence`、`socket-server-tools`、`api_tools`）保留在本地开发，**未包含在本公开仓库中**；待发布到中央仓库时会相应加入。

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.tccl2785.tools</groupId>
    <artifactId>load</artifactId>
    <version>0.0.1</version>
</dependency>
```

### 2. 环境要求

- JDK 17 及以上
- 与 Spring Boot 整合时，需要 Spring Boot 3.x（`spring-boot-starter` 为 `optional` 依赖，需自行引入）

## load 模块：DualPhase & BootLite 模块化加载框架

一个轻量级、无侵入的组件初始化与步骤调度框架，用于解决复杂应用（服务端、游戏引擎、桌面应用、微服务）启动阶段**多模块按序初始化、依赖驱动加载、精细化步骤编排以及与 Spring 无缝集成**的问题。

### 核心特性

- **模块化步骤编排（BootLite）**：无需容器绑定，支持单文件 / 多单元方法级按顺序串行初始化。
- **双相加载引擎（DualPhaseOrchestrator）**
  - 第一相（阶段驱动）：基于 `order` 绝对升序串行执行。
  - 第二相（类型派发）：基于上下文事件触发（"有了什么，才干什么"）。
- **Spring Boot 无缝整合**：包扫描、规则过滤（`ignoreIf`）、预启动干预（`beforeLaunch`），摆脱混乱的 `@DependsOn`。
- **隔离与容错**：支持步骤与阶段级别的异常忽略（`ignoreError` / `ignoreException`），防止非核心模块阻塞启动。

### 用法一：单文件 / 基础模块串行加载（BootLite）

继承 `BootUnit` 并在方法上标注 `@Step`，即可按序调用，依赖通过外部工厂 / 容器解析。

```java
// 声明业务启动单元
public class InfraBoot extends BootUnit {

    @Override
    protected void onBegin(Ctx ctx) {
        System.out.println("=== 阶段 1: 基础设施开始初始化 ===");
    }

    // 无 @Step 注解的方法：默认最优先执行
    public void initDatabase(DatabaseService db) {
        db.connect();
    }

    // @Step(value = 1): 第二顺序执行；发生异常仅警告，不中断主流程
    @Step(value = 1, ignoreError = true)
    public void initCache(CacheService cache) {
        cache.connect(); // 失败会被隔离捕获
    }

    @Override
    protected void onReady(Ctx ctx) {
        System.out.println("=== 阶段 1 初始化完成 ===");
    }
}

// 运行启动器
public class SimpleApp {
    public static void main(String[] args) {
        Map<Class<?>, Object> container = new HashMap<>();
        container.put(InfraBoot.class, new InfraBoot());
        container.put(DatabaseService.class, new DatabaseService());
        container.put(CacheService.class, new CacheService());

        BeginBoot.run(
            new Class<?>[]{ InfraBoot.class },
            type -> container.get(type) // 外部依赖解析委托
        );
    }
}
```

### 用法二：双相声明式加载（DualPhaseOrchestrator）

适用于启动强依赖显式顺序与类型就绪事件的场景。

```java
// 1. 声明依赖数据库的业务组件
public class DatabaseManager {
    // 第一相：绝对顺序驱动，order 小的先执行
    @LoadStage(order = 1)
    private void initDb(InitializationContext ctx) {
        Connection conn = new Connection("jdbc:mysql://localhost:3306");
        ctx.publish(conn); // 向上下文发布对象，触发第二相
    }
}

public class UserService {
    // 第二相：类型派发驱动，当 Connection 被 publish 时自动触发
    @OnType(Connection.class)
    private void onDatabaseReady(InitializationContext ctx) {
        Connection conn = ctx.require(Connection.class);
        System.out.println("Phase 2: 感知数据库就绪: " + conn);
    }
}

// 2. 注册并执行引擎
public class DualPhaseApp {
    public static void main(String[] args) {
        DualPhaseOrchestrator orchestrator = new DualPhaseOrchestrator();

        orchestrator.register(
            new LoadItem(DatabaseManager.class, new DatabaseManager(), LoadMode.NONE, true),
            new LoadItem(UserService.class, new UserService(), LoadMode.NONE, true)
        );

        orchestrator.launch();
    }
}
```

### 用法三：与 Spring Boot 整合

在 Spring Boot 启动时自动扫描受管 Bean，并挂载到双相初始化流水线。

```java
// 步骤 A：标记业务服务
@DualLoad
@Component
public class OrderService {

    @LoadStage(order = 10)
    private void initOrderEngine(InitializationContext ctx) {
        System.out.println("Spring Bean OrderService 按 Stage(10) 加载");
    }
}

// 步骤 B：构建并挂载 Initializer
@SpringBootApplication
public class ApplicationMain {

    public static void main(String[] args) {
        DualPhaseInitializer dualInitializer = DualPhaseInitializer.builder()
                .scanPackages("com.demo.biz")                              // 扫描包路径
                .ignoreIf(clazz -> clazz.getName().contains("Mock"))       // 排除规则（可选）
                .beforeLaunch((items, ctx) -> {                            // 预启动干预（可选）
                    BeginBoot.run(new Class<?>[]{ LiteFirst.class }, ctx::getBean);
                })
                .build();

        new SpringApplicationBuilder(ApplicationMain.class)
                .web(WebApplicationType.NONE)
                .initializers(dualInitializer)
                .run(args);
    }
}
```

### 引擎对比与选择指南

| 功能特性 | 单文件 / 单元加载（BootLite） | 双相加载（DualPhase） | Spring Boot 整合 |
| --- | --- | --- | --- |
| 驱动方式 | `@Step` 权重顺序 | Stage(顺序) + OnType(事件) | 包扫描 + Spring 容器关联 |
| 适用场景 | 模块内部方法级步骤编排 | 复杂阶段依赖与显式发布 / 订阅 | Spring Boot 应用自动化初始化 |
| 容器依赖 | 无（通过委托函数获取） | 无（直接传入实例或类） | 自动关联 Spring `ApplicationContext` |

### 主要 API

| 类型 | 说明 |
| --- | --- |
| `BootUnit` / `Ctx` / `@Step` / `BeginBoot` | BootLite：单元级步骤编排与启动入口 |
| `DualPhaseOrchestrator` / `LoadItem` / `LoadMode` | 双相加载引擎 |
| `@LoadStage` / `@OnType` / `InitializationContext` | 双相声明式注解与上下文 |
| `@DualLoad` / `DualPhaseInitializer` | Spring Boot 整合入口 |

## 本地构建

```bash
# 只构建 load 及其上游（父工程 utilbase）
mvn -pl load -am clean install

# 构建全部模块
mvn clean install
```

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 许可。
