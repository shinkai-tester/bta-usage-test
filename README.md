# bta-usage-test

## Project Structure

### Main Source (`src/main/kotlin/`)

**`Main.kt`** - self-contained in-process JVM compilation demo using the Build Tools API. Run with `./gradlew run`.

#### `framework`
- **`BuildToolsApiSupport.kt`** - toolchain loading (`loadToolchain`) and compiler-argument configuration helpers
- **`TestLogger.kt`** - compilation log capturing and analysis
- **`TestBuildMetricsCollector.kt`** - build metrics collection and aggregation
- **`DaemonPolicy.kt`** - daemon execution policy configuration

#### `utils`
- **`CompilationTestUtils.kt`** - Compilation execution and output file utilities
- **`IncrementalCompilationUtils.kt`** - Incremental compilation configuration helpers
- **`StdlibUtils.kt`** - Kotlin stdlib JAR resolution

### Test Source (`src/test/kotlin/`)

#### `support` - test infra
- **`TestBase.kt`** - base class providing temp workspace/source creation (with cleanup) and common compilation assertions
- **`ExecutionPolicyArgumentProvider.kt`** - JUnit argument provider for parameterized execution policy tests

