# bta-usage-test

## Project structure

### Main source files (`src/main/kotlin/`)

#### Example usage
**`Main.kt`** demonstrates how to use the Build Tools API programmatically:
- Setting up temporary workspaces
- Creating source files
- Loading toolchains with different execution policies
- Configuring compiler arguments
- Executing compilation operations
- Handling results and generated files

Run the example with:
```bash
./gradlew run
```

#### Core framework
- **`BtaTestFramework.kt`** - main facade providing a simplified interface for testing Kotlin compilation operations. Delegates to specialized managers following Single Responsibility Principle.
- **`TestLogger.kt`** captures and analyzes compilation log messages, including error tracking and retry count detection.
- **`WorkspaceManager.kt`** manages temporary workspaces and source file creation for tests.
- **`ToolchainManager.kt`** handles toolchain loading, configuration, and execution policy management.

#### Compilation utilities
- **`CompilationTestUtils.kt`** - utility methods for creating and executing compilation operations.
- **`IncrementalCompilationUtils.kt`** - specialized utilities for configuring incremental compilation with snapshot-based options.
- **`IncrementalCompilationTestBuilder.kt`** - builder pattern implementation for complex multi-module incremental compilation test scenarios.

#### Support classes
- **`ClasspathUtils.kt`** - utilities for classpath management and URLClassLoader creation.
- **`DaemonPolicy.kt`** - configuration and management of daemon execution policies.
- **`JavaInteropTestScenario.kt`** - specialized test scenario builder for Java-Kotlin interoperability testing.

### Test files (`src/test/`)

#### Test foundation
- **`TestBase.kt`** - abstract base class providing common test setup and utility methods. Includes workspace creation, compilation result verification, and class file validation utilities.

#### Test suites

##### 1. **`JvmCompilationTest.kt`** - Basic JVM Compilation Tests
**Purpose**: verifies fundamental compilation operations and error handling.

**What it tests**:
- ✅ **Simple Kotlin class compilation** ensures basic Kotlin source compilation works correctly
- ✅ **Mixed Kotlin and Java source compilation** verifies interoperability between Kotlin and Java sources
- ✅ **Compilation error handling** tests proper error reporting for invalid code (unresolved references)
- ✅ **Daemon execution failure handling** tests retry mechanisms and daemon startup failure scenarios

##### 2. **`IncrementalCompilationTest.kt`** - Incremental Compilation Tests
**Purpose**: verifies sophisticated incremental compilation behavior across various scenarios.

**What it tests**:
- ✅ **Multi-module ABI changes** ensures downstream modules recompile when upstream ABI changes
- ✅ **Transitive dependency changes** verifies that changes propagate correctly through dependency chains (A→B→C)
- ✅ **Java interop with precise tracking** tests whether Kotlin recompiles when Java dependencies change, with precise Java tracking enabled/disabled
- ✅ **ABI vs non-ABI Java changes** distinguishes between changes that require recompilation vs those that don't
- ✅ **Kotlin body vs signature changes** verifies that method body changes don't trigger dependent recompilation, while signature changes do

##### 3. **`ExecutionPolicyTest.kt`** - Execution Policy Tests
**Purpose**: verifies that different execution modes work correctly.

**What it tests**:
- ✅ **In-process execution** ensures compilation works in the same JVM process
- ✅ **Daemon execution** verifies compilation works with daemon process
- ✅ **Daemon with custom JVM arguments** tests daemon configuration with custom memory settings

##### 4. **`CompilerArgumentsTest.kt`** - Compiler Arguments Tests
**Purpose**: verifies passing and conversion of common and JVM-specific compiler arguments and error handling for invalid/missing arguments.

**What it tests**:
- ✅ **Common compiler arguments** checks languageVersion, apiVersion, progressive mode, and opt-in markers
- ✅ **JVM-specific arguments** checks jvmTarget (e.g., 17 → classfile major 61), moduleName, and -java-parameters flag
- ✅ **Error handling** ensures unresolved references are reported when stdlib is excluded or classpath is invalid


##### 5. **`CompilerArgumentsLifecycleTest.kt`** - Compiler Arguments Lifecycle and Validation
**Purpose**: validates lifecycle attributes on compiler arguments (experimental/deprecated/removed), parsing errors for argument strings, and availability guards tied to Build Tools API versions.

**What it tests**:
- ✅ **Experimental options with explicit opt-in** compile successfully (e.g., X_NO_INLINE, X_DEBUG)
- ✅ **Removed options** produce a clear failure either via exception or a compilation error; diagnostics must mention the option being removed and an unrecognized parameter
- ✅ **Deprecated options** still work with opt-in (no requirement for runtime deprecation log)
- ✅ **applyArgumentStrings** reports missing value with CompilerArgumentsParseException
- ✅ **Availability guards** setting an argument whose availableSinceVersion is greater than the current BTA version fails early with a helpful message

##### 6. **`CancellationTest.kt`** - Compilation Cancellation Tests
**Purpose**: verifies that compilation operations can be cancelled correctly.

**What it tests**:
- ✅ **Non-incremental in-process cancellation** ensures cancellation works for non-incremental compilation with in-process execution strategy
- ✅ **Non-incremental daemon cancellation** verifies cancellation works for non-incremental compilation with daemon execution strategy
- ✅ **Incremental in-process cancellation** tests cancellation of incremental compilation with in-process execution strategy
- ✅ **Incremental daemon cancellation** verifies cancellation of incremental compilation with daemon execution strategy
- ✅ **Backward compatibility with old compiler** verifies that cancellation throws `IllegalStateException` with compiler version 2.3.0 (cancellation is supported from 2.3.20)

##### 7. **`JvmPlatformToolchainTest.java`** - Java API Compatibility Test
**Purpose**: verifies that the Build Tools API can be used from Java code.

**What it tests**:
- ✅ **JvmPlatformToolchain.from() API** ensures Java clients can obtain a JvmPlatformToolchain and compile Kotlin sources using the Java-friendly entrypoint
