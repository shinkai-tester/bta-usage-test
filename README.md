# Kotlin Build Tools API (BTA) compatibility sample

This repository demonstrates how to use the Kotlin Build Tools API (BTA) while selecting different Kotlin compiler implementations at runtime. It shows how:

- Your application depends on the stable API module (`kotlin-build-tools-api`) and uses the BTA v2 surface in code.
- The actual compiler implementation is supplied via a separate classpath (Gradle configuration `myCompiler`) so you can run with older/newer compilers without changing application code.
- The `kotlin-build-tools-compat` module provides a fallback bridge when the chosen compiler only exposes the previous BTA v1, adapting your v2 calls to v1 at runtime.

Official BTA documentation: https://kotlinlang.org/docs/build-tools-api.html

Additional resources:
- [kotlin-build-tools-api README](https://github.com/JetBrains/kotlin/blob/master/compiler/build-tools/kotlin-build-tools-api/README.md)
- [kotlin-build-tools-compat README](https://github.com/JetBrains/kotlin/blob/master/compiler/build-tools/kotlin-build-tools-compat/README.md)

## Compatibility window

The Kotlin Build Tools API supports a moving window across Kotlin compiler major versions:

- Supports the three previous major Kotlin compiler versions.
- Supports one major version forward.

With `kotlin-build-tools-compat` on the app classpath, when you run against a compiler that only implements BTA v1, the compat layer provides a v1 fallback so the same BTA v2-using application code continues to work.

## Project layout

- `src/main/kotlin/Main.kt` — minimal example that:
  - Loads a `KotlinToolchains` using a classloader built from the provided compiler implementation jars.
  - Configures and runs a small JVM compilation using BTA v2.
  - Prints the selected compiler version and compilation results.
- `src/main/kotlin/ClasspathUtils.kt` — small helper for reading the current process classpath and converting it to URL entries.
- `src/main/kotlin/StdlibResolver.kt` — utility for resolving kotlin-stdlib dependencies, handling Gradle cache lookup and fallback strategies.
- `src/main/kotlin/CompilationUtils.kt` — utilities for test source creation, directory setup, classpath logging, and compilation result reporting.
- `src/main/kotlin/ConsoleLogger.kt` — simple console-based logger implementation for the Build Tools API.
- `build.gradle.kts` — declares:
  - `implementation("org.jetbrains.kotlin:kotlin-build-tools-api:…")` for the API used by the program.
  - A `myCompiler` configuration that carries the actual compiler implementation jars, e.g.:
    - `org.jetbrains.kotlin:kotlin-build-tools-impl:<version>` — the compiler implementation you want to run.
    - `org.jetbrains.kotlin:kotlin-build-tools-compat:<version>` — the optional bridge for v1 fallback when using older compilers.
  - A `run` task that passes the `myCompiler` jars as command line arguments to the program.

## Selecting compiler versions

You can choose which Kotlin compiler implementation to run by changing the dependencies in the `myCompiler` configuration in `build.gradle.kts`. For example:

```
myCompiler("org.jetbrains.kotlin:kotlin-build-tools-impl:2.0.20")
myCompiler("org.jetbrains.kotlin:kotlin-build-tools-compat:2.3.255-SNAPSHOT")
```

- `kotlin-build-tools-impl` determines the actual compiler version used by the toolchain.
- `kotlin-build-tools-compat` should typically match the API major you compile against; it allows running against older compiler impls that only expose BTA v1.

The application itself compiles against `kotlin-build-tools-api` (v2 in this sample), and uses that API at runtime.

## Running the sample

From the project root:

- On Unix-like shells:
  - `./gradlew run`
- On Windows PowerShell / CMD:
  - `gradlew.bat run`

The `run` task will:
- Build the app and its dependencies.
- Launch the program with the `myCompiler` configuration resolved to file paths and passed as arguments.
- Compile a tiny in-memory Kotlin file to a temporary directory using the requested compiler toolchain.

The output will include:
- The resolved compiler classpath.
- The toolchain-reported compiler version.
- The classpath used for compilation (including `kotlin-stdlib`).
- The compilation result and list of produced files.

## Notes

- Execution policy: the sample can run either in-process or with the Kotlin Daemon. Toggle the `useDaemon` flag in `Main.kt` to switch.
- Standard library: the example tries to find a `kotlin-stdlib` jar whose version matches the selected compiler in your Gradle cache. If it can’t, it falls back to any `kotlin-stdlib` found on the current process classpath and prints a warning.
- No code changes are needed to switch compiler versions; only the `myCompiler` dependencies affect which compiler impl is used at runtime.

## Tests

- Gradle is configured to pass a `test.compiler.classpath` system property to tests with the files from the `myCompiler` configuration.
- To run tests: `./gradlew test` (Unix) or `gradlew.bat test` (Windows).
- Shared test base class `TestBase` lives under `src/test/kotlin` to avoid repetition (e.g., loading a toolchain, creating test sources, and asserting class file output). Tests `CompilerArgumentsLifecycleTest` and `ErrorScenarioDemonstrationTest` extend this base class. Source file creation logic remains in `src/main/kotlin/CompilationUtils.kt`, with `TestBase` providing helper methods for tests.

## Licenses

This is a documentation-oriented sample. Content is provided as-is for demonstration purposes.