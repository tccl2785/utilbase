# load · Modular Loading & Initialization Framework

`load` is a lightweight, non-intrusive framework for component initialization and step orchestration. It solves the startup-phase problems of complex applications (servers, game engines, desktop apps, microservices): **ordered multi-module initialization, dependency-driven loading, fine-grained step orchestration, and seamless Spring integration**.

- Coordinates: `io.github.tccl2785.tools:load:0.0.1`
- Requirements: JDK 17+
- Spring integration: Spring Boot 3.x (`spring-boot-starter` is an `optional` dependency — add it yourself when needed)

## Key Features

- **Step orchestration (BootLite)**: no container binding required; ordered, method-level initialization of one or more `BootUnit` classes with lifecycle hooks.
- **Dual-phase loading engine (DualPhaseOrchestrator)**
  - Phase 1 (stage-driven): serial execution in ascending `@LoadStage(order)`.
  - Phase 2 (type dispatch): `@OnType` methods triggered by published context events — "only act once something is available".
  - Instance / static / Kotlin companion-object invocation modes; optional Kotlin `suspend` method support via `launchSuspend()`.
- **PreBoot units**: `BootUnit`-style configuration classes discovered by the DualPhase scanner and executed around the launch boundary (`BEFORE` / `AFTER`).
- **Seamless Spring Boot integration**: package scanning, rule filtering (`ignoreIf`), and pre/post-launch interception (`beforeLaunch` / `afterLaunch`), removing messy `@DependsOn` chains.
- **Isolation and fault tolerance**: step- and stage-level exception ignoring (`ignoreError` / `ignoreException`) prevents non-critical modules from blocking startup.
- **YAML field assignment (YamlAssignUtils)**: assign YAML content directly onto Java/Kotlin object fields, with JavaBean setter priority and `!fully.qualified.ClassName` type tags.

## Installation

```xml
<dependency>
    <groupId>io.github.tccl2785.tools</groupId>
    <artifactId>load</artifactId>
    <version>0.0.1</version>
</dependency>
```

## Usage 1: Single-file / basic module serial loading (BootLite)

Extend `BootUnit` and annotate methods with `@Step` to invoke them in order; dependencies are resolved through an external factory / container.

Execution rules:

1. Lifecycle hook `onBegin(Ctx)` runs first.
2. Public instance methods **without** `@Step` run next (declaration order).
3. Methods with `@Step(value = n)` run in ascending `n`.
4. Lifecycle hook `onReady(Ctx)` runs last.
5. A failing step aborts the whole process, unless `@Step(ignoreError = true)` is set.

```java
import io.github.tccl2785.tools.load.begin.*;

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
        container.put(DatabaseService.class, new DatabaseService());
        container.put(CacheService.class, new CacheService());

        BeginBoot.run(
            new Class<?>[]{ InfraBoot.class },
            container::get // external dependency resolver: step-method parameters are injected from here
        );
    }
}
```

## Usage 2: Declarative dual-phase loading (DualPhaseOrchestrator)

For startup flows that strongly depend on explicit ordering and type-ready events.

```java
import io.github.tccl2785.tools.load.dual.*;

// 1. A component that produces the database connection
public class DatabaseManager {
    // Phase 1: absolute ordering, smaller order runs first
    @LoadStage(order = 1)
    private void initDb(InitializationContext ctx) {
        Connection conn = new Connection("jdbc:mysql://localhost:3306");
        ctx.publish(conn); // publish to the context, which triggers phase 2
    }
}

// 2. A component that consumes the connection once it is ready
public class UserService {
    // Phase 2: type dispatch, triggered when a Connection is published
    @OnType(Connection.class)
    private void onDatabaseReady(InitializationContext ctx) {
        Connection conn = ctx.require(Connection.class);
        System.out.println("Phase 2: database ready: " + conn);
    }
}

// 3. Register and launch the engine
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

Notes:

- `LoadItem(classz, obj, mode, isEnable)`:
  - `LoadMode.NONE` — invoke methods on the given singleton `obj`.
  - `LoadMode.STATIC` — invoke `private static` methods of `classz` (`obj` may be `null`).
  - `LoadMode.COMPANION` — invoke `private` methods on a Kotlin companion object inside `classz`.
  - `LoadMode.DISCOVER_ONLY` — discover only, do not execute (used by `@PreBoot`).
  - `isEnable = false` — the engine ignores this item.
- Both `@LoadStage` and `@OnType` accept `ignoreException = true` to warn-and-continue instead of aborting.
- `InitializationContext` offers `require(Class)`, `publish(Object)`, and `contains(Class)`.
- `launch()` is the synchronous Java-friendly entry and **rejects Kotlin `suspend` methods**. From Kotlin, call `suspend fun launchSuspend()` to mix normal and `suspend` load methods.

## Usage 3: Spring Boot integration

On Spring Boot startup, managed beans are scanned and attached to the dual-phase initialization pipeline.

```java
import io.github.tccl2785.tools.load.dual.*;
import io.github.tccl2785.tools.load.dual.spring.DualPhaseInitializer;

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
                    // inspect / mutate discovered items before launch
                })
                .afterLaunch((items, ctx) -> {                             // post-launch hook (optional)
                    // runs after the whole dual-phase pipeline completes
                })
                .build();

        new SpringApplicationBuilder(ApplicationMain.class)
                .web(WebApplicationType.NONE)
                .initializers(dualInitializer)
                .run(args);
    }
}
```

`DualLoad` attributes: `mode()` (default `LoadMode.NONE`) and `isEnable()` (default `true`), mapping directly onto the generated `LoadItem`.

Kotlin projects can additionally call `enableCoroutines()` on the builder to launch via `launchSuspend()` (blocking until completion, so `afterLaunch` still runs after everything):

```kotlin
val initializer = DualPhaseInitializer.builder()
    .scanPackages("com.demo.biz")
    .enableCoroutines()   // from io.github.tccl2785.tools.load.dual.spring.DualPhaseCoroutinesExtKt
    .build()
```

A fully custom launch strategy can be supplied with `withLaunchStrategy(...)`; the strategy **must block until dual-phase loading has completed**.

## Usage 4: PreBoot units (boot units around the launch boundary)

`PreBootUnit` combines BootLite-style step orchestration with DualPhase discovery: annotate the class with `@PreBoot`, and it is picked up by package scanning and executed near the launch boundary — without participating in the normal `@LoadStage` / `@OnType` pipeline.

Fixed execution order:

```
beforeLaunch hook
    ↓
@PreBoot(PreBootPhase.BEFORE) units (discovered by scanning)
    ↓
builder.beforeBootUnits(...) (explicit)
    ↓
=== dual-phase launch ===
    ↓
afterLaunch hook
    ↓
@PreBoot(PreBootPhase.AFTER) units (discovered by scanning)
    ↓
builder.afterBootUnits(...) (explicit)
```

```java
import io.github.tccl2785.tools.load.begin.Ctx;
import io.github.tccl2785.tools.load.begin.Step;
import io.github.tccl2785.tools.load.begin.dual.*;

@PreBoot(PreBootPhase.BEFORE)
public class ConfigWarmUpBoot extends PreBootUnit {

    @Override
    protected void onBegin(Ctx ctx) {
        System.out.println("warming up before dual-phase launch");
    }

    @Step(1)
    public void loadLocalCache(ConfigService config) {
        // step-method parameters are resolved from the Spring context (ctx::getBean)
    }

    @Override
    protected void onReady(Ctx ctx) {
        // loadItems() is available inside the unit: the discovered PreLaunchLoadItems
    }
}
```

Explicit (non-scanned) units can be attached directly on the builder:

```java
DualPhaseInitializer.builder()
        .scanPackages("com.demo.biz")
        .beforeBootUnits(ConfigWarmUpBoot.class)
        .afterBootUnits(ReportBoot.class)
        .build();
```

## Usage 5: YAML field assignment (YamlAssignUtils)

`YamlAssignUtils` assigns YAML content directly onto the fields of a Kotlin `object` singleton or any Java/Kotlin instance. JavaBean setters take priority over direct field access, and `!fully.qualified.ClassName` tags select concrete types.

```kotlin
import io.github.tccl2785.tools.load.yaml.YamlAssignUtils

object DataSourceConfig {
    var url: String = ""
    var maxTotal: Int = 0
    var readDb: MutableList<BasicDataSource> = mutableListOf()
}

val yaml = """
url: jdbc:mysql://localhost:3306/app
maxTotal: 32
readDb: !org.apache.commons.dbcp2.BasicDataSource
  - url: jdbc:mysql://replica-1:3306/app
    username: reader
  - url: jdbc:mysql://replica-2:3306/app
    username: reader
""".trimIndent()

YamlAssignUtils.parseAssignFields(yaml, DataSourceConfig)
// or multiple targets at once:
YamlAssignUtils.parseAssignFields(yaml, arrayOf(configA, configB))
```

Type conversion covers `String`, all primitive wrappers, and `Char`; mapping nodes are materialized via the `!tag` type or the target field type; sequence elements resolve their type from (1) the collection's own `!tag`, (2) each element's `!tag`, or (3) the `List<T>` generic parameter. Failures raise `YamlAssignUtils.YamlAssignException` with the exact field path, target type, and YAML tag.

## Engine comparison & selection guide

| Feature | BootLite (`BeginBoot`) | Dual-phase (`DualPhaseOrchestrator`) | Spring Boot integration |
| --- | --- | --- | --- |
| Driving mechanism | `@Step` weight order + lifecycle hooks | `@LoadStage` (order) + `@OnType` (event) | Package scan + Spring container |
| Best for | Method-level orchestration inside a module | Complex stage dependencies with explicit publish / subscribe | Automated initialization of Spring Boot apps |
| Container dependency | None (resolver function) | None (instances or classes passed in) | Auto-bound to Spring `ApplicationContext` |
| Kotlin suspend methods | Not supported | `launchSuspend()` | `enableCoroutines()` |

## API reference

| Package | Type | Description |
| --- | --- | --- |
| `begin` | `BeginBoot` | BootLite entry point: `run(Class<?>[], Function<Class<?>, Object>)` |
| `begin` | `BootUnit` | Base class with `onBegin(Ctx)` / `onReady(Ctx)` lifecycle hooks |
| `begin` | `@Step` | Step ordering (`value`) + per-step fault tolerance (`ignoreError`) |
| `begin` | `Ctx` | Dependency context backed by an external factory delegate |
| `begin.dual` | `@PreBoot` | Marks a `PreBootUnit` for discovery, executed `BEFORE` / `AFTER` launch |
| `begin.dual` | `PreBootUnit` / `PreLaunchBoot` / `PreBootSelector` | BootUnit variant with access to `PreLaunchLoadItems`; runner and selector |
| `dual` | `DualPhaseOrchestrator` | Dual-phase engine: `register(...)`, `launch()`, `launchSuspend()` |
| `dual` | `LoadItem` / `LoadMode` | Load unit descriptor; `NONE` / `STATIC` / `COMPANION` / `DISCOVER_ONLY` |
| `dual` | `@LoadStage` / `@OnType` | Phase-1 ordered stage and phase-2 type dispatch annotations |
| `dual` | `InitializationContext` | `require(Class)` / `publish(Object)` / `contains(Class)` |
| `dual` | `@DualLoad` | Marks a class for Spring scanning; `mode` + `isEnable` |
| `dual.spring` | `DualPhaseInitializer` | `ApplicationContextInitializer` builder: `scanPackages`, `ignoreIf`, `beforeLaunch`, `afterLaunch`, `beforeBootUnits`, `afterBootUnits`, `withLaunchStrategy` |
| `dual.spring` | `enableCoroutines()` | Kotlin builder extension enabling the coroutine launch strategy |
| `yaml` | `YamlAssignUtils` | YAML-to-fields assignment with setter priority and `!tag` typing |

## License

Licensed under the [Apache License 2.0](../LICENSE).
