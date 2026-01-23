import org.jetbrains.kotlin.buildtools.api.*;
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation;
import org.junit.jupiter.api.*;

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
        
        JvmCompilationOperation op = framework.createJvmCompilationOperation(toolchains, List.of(source), outDir);

        CompilationResult result = CompilationTestUtils.runCompile(toolchains, op, new TestLogger(false));

        assertEquals(CompilationResult.COMPILATION_SUCCESS, result, "Expected compilation through builder API to succeed");
    }
}
