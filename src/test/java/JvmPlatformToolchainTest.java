import org.jetbrains.kotlin.buildtools.api.*;
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments;
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain;
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the Java-friendly static accessor for obtaining JvmPlatformToolchain from KotlinToolchains.
 * <p>
 * This test verifies that the Kotlin extension property:
 * <pre>
 *     public inline val KotlinToolchains.jvm: JvmPlatformToolchain get() = getToolchain<JvmPlatformToolchain>()
 * </pre>
 * 
 * Is accessible from Java via the static method JvmPlatformToolchain.from(KotlinToolchains).
 */
@ExperimentalBuildToolsApi
public class JvmPlatformToolchainTest {

    @Test
    @DisplayName("JvmPlatformToolchain.from(KotlinToolchains) compiles Kotlin")
    public void compilesSimpleKotlin() throws Exception {
        BtaTestFramework framework = new BtaTestFramework();
        Path workspace = framework.createTempWorkspace();
        Path outDir = workspace.resolve("out");
        java.nio.file.Files.createDirectories(outDir);

        Path source = framework.createKotlinSource(workspace, "TodoList.kt", """
            package sample

            data class Todo(val title: String, val done: Boolean = false)

            class TodoList {
                private val items = mutableListOf<Todo>()
                fun add(title: String) { items += Todo(title) }
                fun complete(title: String) {
                    val i = items.indexOfFirst { it.title == title }
                    if (i >= 0) items[i] = items[i].copy(done = true)
                }
                fun openCount(): Int = items.count { !it.done }
            }

            fun demoTodos(): Int {
                val list = TodoList()
                list.add("write test")
                list.add("run build")
                list.complete("write test")
                return list.openCount()
            }
        """);

        KotlinToolchains toolchains = framework.loadToolchain();

        JvmPlatformToolchain jvmToolchain;
        try {
            Method from = JvmPlatformToolchain.class.getMethod("from", KotlinToolchains.class);
            Object res = from.invoke(null, toolchains);
            assertNotNull(res, "JvmPlatformToolchain.from(toolchains) returned null");
            assertInstanceOf(JvmPlatformToolchain.class, res);
            jvmToolchain = (JvmPlatformToolchain) res;
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                "JvmPlatformToolchain.from(KotlinToolchains) is not available; " +
                "check kotlin-build-tools-api version (requires 2.3.0-Beta2+).", e
            );
        }

        JvmCompilationOperation.Builder builder = jvmToolchain.jvmCompilationOperationBuilder(List.of(source), outDir);
        JvmCompilerArguments.Builder args = builder.getCompilerArguments();
        framework.configureBasicCompilerArguments(args, "test-module");
        JvmCompilationOperation op = builder.build();

        CompilationResult result = CompilationTestUtils.runCompile(toolchains, op, new TestLogger(false));

        assertEquals(CompilationResult.COMPILATION_SUCCESS, result, "Expected compilation through .from() API to succeed");
    }
}
