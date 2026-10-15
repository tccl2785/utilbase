# utilbase · Base Utility Library

`utilbase` is an aggregator project that hosts reusable Java / Kotlin base modules and publishes them to the **Maven Central Repository**, so any project with a Maven environment can consume them directly.

- Source repository: https://github.com/tccl2785/utilbase
- License: [Apache-2.0](LICENSE)
- Requirements: JDK 17+

## Modules

| Module | Description | Central status | Documentation |
| --- | --- | --- | --- |
| [`load`](load) | Modular loading / initialization framework: step orchestration (BootLite), dual-phase loading (DualPhase), pre-launch boot units (PreBoot), Spring Boot integration, and YAML field assignment | `io.github.tccl2785.tools:load:0.0.1` | [load/README.md](load/README.md) |

> Other submodules are kept in local development and are **not included in this public repository**; they will be added here once published to Maven Central.

## Quick Start

```xml
<dependency>
    <groupId>io.github.tccl2785.tools</groupId>
    <artifactId>load</artifactId>
    <version>0.0.1</version>
</dependency>
```

For component usage, Spring Boot integration, and the full API reference, see the module documentation: **[load/README.md](load/README.md)**.

## Build Locally

```bash
# Build only `load` and its upstream (parent `utilbase`)
mvn -pl load -am clean install
```

## License

Licensed under the [Apache License 2.0](LICENSE).
