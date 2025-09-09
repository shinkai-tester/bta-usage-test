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

### Test files (`src/test/kotlin/`)

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
