import org.jetbrains.kotlin.buildtools.api.*;
import org.jetbrains.kotlin.buildtools.api.jvm.JvmPlatformToolchain;
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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

        KotlinToolchains toolchains = framework.loadToolchain(false);

        // Obtain the Jvm toolchain using the Java-friendly entrypoint across versions
        JvmPlatformToolchain jvmToolchain;
        try {
            // Preferred on 2.3.0-Beta2+
            Method from = JvmPlatformToolchain.class.getMethod("from", KotlinToolchains.class);
            Object res = from.invoke(null, toolchains);
            assertNotNull(res, "from(toolchains) returned null");
            assertInstanceOf(JvmPlatformToolchain.class, res);
            jvmToolchain = (JvmPlatformToolchain) res;
        } catch (NoSuchMethodException e) {
            // Fallback for older builds (e.g., 2.3.0-Beta1) that still expose get(KotlinToolchains)
            try {
                Method get = JvmPlatformToolchain.class.getMethod("get", KotlinToolchains.class);
                Object res = get.invoke(null, toolchains);
                assertNotNull(res, "get(toolchains) returned null");
                assertInstanceOf(JvmPlatformToolchain.class, res);
                jvmToolchain = (JvmPlatformToolchain) res;
            } catch (NoSuchMethodException ex) {
                throw new AssertionError(
                    "Neither from(KotlinToolchains) [2.3.0-Beta2+] nor get(KotlinToolchains) [<= 2.3.0-Beta1] is available on JvmPlatformToolchain; " +
                    "check kotlin-build-tools-api version.", ex
                );
            }
        }

        // Create an operation and configure it using our framework helpers
        JvmCompilationOperation op = jvmToolchain.createJvmCompilationOperation(List.of(source), outDir);
        framework.configureMinimalCompilation(op);

        // Execute
        CompilationResult result = CompilationTestUtils.runCompile(toolchains, op, new TestLogger(false));

        // Assert
        assertEquals(CompilationResult.COMPILATION_SUCCESS, result, "Expected compilation through .from() API to succeed");
    }
}
