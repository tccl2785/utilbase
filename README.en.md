# utilbase · Base Utility Library

`utilbase` is an aggregator project that hosts reusable Java / Kotlin base modules and publishes them to the **Maven Central Repository**, so any project with a Maven environment can consume them directly.

- Source repository: https://github.com/tccl2785/utilbase
- License: [Apache-2.0](LICENSE)

## Modules

| Module | Description | Central status |
| --- | --- | --- |
| [`load`](load) | Loading / initialization framework: dual-phase loading + step orchestration + Spring Boot integration | ✅ Published `0.0.1` |

> Other submodules (`me-tools1`, `socket-tools`, `persistence`, `socket-server-tools`, `api_tools`) are kept in local development and are **not included in this public repository**; they will be added when published to Maven Central.

## Quick Start

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.tccl2785.tools</groupId>
    <artifactId>load</artifactId>
    <version>0.0.1</version>
</dependency>
```

### 2. Requirements

- JDK 17 or later
- Spring Boot 3.x when using the Spring integration (`spring-boot-starter` is an `optional` dependency; you must add it yourself)

## Module `load`: DualPhase & BootLite Modular Loading Framework

A lightweight, non-intrusive framework for component initialization and step orchestration. It solves the startup-phase problems of complex applications (servers, game engines, desktop apps, microservices): **ordered multi-module initialization, dependency-driven loading, fine-grained step orchestration, and seamless Spring integration**.

### Key Features

- **Modular step orchestration (BootLite)**: no container binding required; supports ordered, method-level initialization of single files / multiple units.
- **Dual-phase loading engine (DualPhaseOrchestrator)**
  - Phase 1 (stage-driven): serial execution in ascending `order`.
  - Phase 2 (type dispatch): triggered by context events — "only act once something is available".
- **Seamless Spring Boot integration**: package scanning, rule filtering (`ignoreIf`), and pre-launch interception (`beforeLaunch`), removing messy `@DependsOn` chains.
- **Isolation and fault tolerance**: step- and stage-level exception ignoring (`ignoreError` / `ignoreException`) prevents non-critical modules from blocking startup.

### Usage 1: Single-file / basic module serial loading (BootLite)

Extend `BootUnit` and annotate methods with `@Step` to invoke them in order; dependencies are resolved through an external factory / container.

```java
// Declare a boot unit
public class InfraBoot extends BootUnit {

    @Override
    protected void onBegin(Ctx ctx) {
        System.out.println("=== Phase 1: infrastructure initialization starts ===");
    }

    // Methods without @Step run first
    public void initDatabase(DatabaseService db) {
        db.connect();
    }

    // @Step(value = 1): runs second; failures are only warned, not fatal
    @Step(value = 1, ignoreError = true)
    public void initCache(CacheService cache) {
        cache.connect(); // exceptions are isolated
    }

    @Override
    protected void onReady(Ctx ctx) {
        System.out.println("=== Phase 1 initialization finished ===");
    }
}

// Run the bootstrapper
public class SimpleApp {
    public static void main(String[] args) {
        Map<Class<?>, Object> container = new HashMap<>();
        container.put(InfraBoot.class, new InfraBoot());
        container.put(DatabaseService.class, new DatabaseService());
        container.put(CacheService.class, new CacheService());

        BeginBoot.run(
            new Class<?>[]{ InfraBoot.class },
            type -> container.get(type) // external dependency resolver
        );
    }
}
```

### Usage 2: Declarative dual-phase loading (DualPhaseOrchestrator)

For startup flows that strongly depend on explicit ordering and type-ready events.

```java
// 1. A component that depends on the database
public class DatabaseManager {
    // Phase 1: absolute ordering, smaller order runs first
    @LoadStage(order = 1)
    private void initDb(InitializationContext ctx) {
        Connection conn = new Connection("jdbc:mysql://localhost:3306");
        ctx.publish(conn); // publish to the context, which triggers phase 2
    }
}

public class UserService {
    // Phase 2: type dispatch, triggered when a Connection is published
    @OnType(Connection.class)
    private void onDatabaseReady(InitializationContext ctx) {
        Connection conn = ctx.require(Connection.class);
        System.out.println("Phase 2: database ready: " + conn);
    }
}

// 2. Register and launch the engine
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

### Usage 3: Spring Boot integration

On Spring Boot startup, managed beans are scanned and attached to the dual-phase initialization pipeline.

```java
// Step A: mark your services
@DualLoad
@Component
public class OrderService {

    @LoadStage(order = 10)
    private void initOrderEngine(InitializationContext ctx) {
        System.out.println("Spring bean OrderService loaded at Stage(10)");
    }
}

// Step B: build and attach the initializer
@SpringBootApplication
public class ApplicationMain {

    public static void main(String[] args) {
        DualPhaseInitializer dualInitializer = DualPhaseInitializer.builder()
                .scanPackages("com.demo.biz")                              // packages to scan
                .ignoreIf(clazz -> clazz.getName().contains("Mock"))       // exclusion rule (optional)
                .beforeLaunch((items, ctx) -> {                            // pre-launch hook (optional)
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

### Engine comparison & selection guide

| Feature | Single-unit loading (BootLite) | Dual-phase loading (DualPhase) | Spring Boot integration |
| --- | --- | --- | --- |
| Driving mechanism | `@Step` weight order | Stage (order) + OnType (event) | Package scan + Spring container |
| Best for | Method-level orchestration inside a module | Complex stage dependencies with explicit publish / subscribe | Automated initialization of Spring Boot apps |
| Container dependency | None (resolver function) | None (instances or classes passed in) | Auto-bound to Spring `ApplicationContext` |

### Main API

| Type | Description |
| --- | --- |
| `BootUnit` / `Ctx` / `@Step` / `BeginBoot` | BootLite: unit-level step orchestration and entry point |
| `DualPhaseOrchestrator` / `LoadItem` / `LoadMode` | Dual-phase loading engine |
| `@LoadStage` / `@OnType` / `InitializationContext` | Dual-phase declarative annotations and context |
| `@DualLoad` / `DualPhaseInitializer` | Spring Boot integration entry point |

## Build Locally

```bash
# Build only `load` and its upstream (parent `utilbase`)
mvn -pl load -am clean install

# Build all modules
mvn clean install
```

## License

Licensed under the [Apache License 2.0](LICENSE).
