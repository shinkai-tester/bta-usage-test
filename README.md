# bta-usage-test

## Project Structure

### Main Source (`src/main/kotlin/`)

**`BtaTestFacade.kt`** - main facade providing a unified interface for testing Kotlin compilation operations.

**`Main.kt`** demonstrates programmatic usage of the Build Tools API. Run with `./gradlew run`.

#### `framework`
- **`ToolchainManager.kt`** - toolchain loading and compiler argument configuration
- **`WorkspaceManager.kt`** - temporary workspace and source file management
- **`TestLogger.kt`** - compilation log capturing and analysis
- **`TestBuildMetricsCollector.kt`** - build metrics collection and aggregation
- **`DaemonPolicy.kt`** - daemon execution policy configuration

#### `utils`
- **`CompilationTestUtils.kt`** - Compilation execution and output file utilities
- **`IncrementalCompilationUtils.kt`** - Incremental compilation configuration helpers
- **`StdlibUtils.kt`** - Kotlin stdlib JAR resolution

### Test Source (`src/test/kotlin/`)

#### `support` - test infra
- **`TestBase.kt`** - base class providing common test setup, assertions, and verification utilities
- **`ExecutionPolicyArgumentProvider.kt`** - JUnit argument provider for parameterized execution policy tests

