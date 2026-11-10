package com.codecanvas.sandbox.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C4 隔离工作区测试：独立目录、路径逃逸、配额、销毁。 */
class SandboxWorkspaceTest {

    private BuildPolicy policy(int maxFiles, long maxBytes) {
        BuildPolicy p = new BuildPolicy();
        p.setMaxFiles(maxFiles);
        p.setMaxTotalBytes(maxBytes);
        return p;
    }

    @Test
    void taskDirIsIndependentAndUnderRoot(@TempDir Path tmp) throws IOException {
        SandboxWorkspace ws = new SandboxWorkspace(policy(100, 1_000_000), tmp);
        Path a = ws.create("task-a");
        Path b = ws.create("task-b");
        assertFalse(a.equals(b));
        assertTrue(a.startsWith(tmp), "任务目录必须落在指定根下");
        assertTrue(a.getFileName().toString().contains("task-a"));
    }

    @Test
    void writeFileAndReadBack(@TempDir Path tmp) throws IOException {
        SandboxWorkspace ws = new SandboxWorkspace(policy(100, 1_000_000), tmp);
        ws.create("t1");
        ws.writeFile("t1", "src/app.js", "console.log(1);");
        assertEquals("console.log(1);",
                Files.readString(ws.taskDir("t1").resolve("src/app.js")));
    }

    @Test
    void pathTraversalRejected(@TempDir Path tmp) throws IOException {
        SandboxWorkspace ws = new SandboxWorkspace(policy(100, 1_000_000), tmp);
        ws.create("t2");
        assertThrows(IllegalArgumentException.class,
                () -> ws.writeFile("t2", "../../host-file.txt", "evil"));
        assertThrows(IllegalArgumentException.class,
                () -> ws.writeFile("t2", "/absolute/path.txt", "evil"));
        assertThrows(IllegalArgumentException.class,
                () -> ws.writeFile("t2", "C:\\evil.txt", "evil"));
        assertThrows(IllegalArgumentException.class,
                () -> ws.writeFile("t2", "bad\\backslash.txt", "evil"));
        assertFalse(Files.exists(tmp.resolve("host-file.txt")), "宿主目录不得被写入");
    }

    @Test
    void normalizedEscapeRejected(@TempDir Path tmp) throws IOException {
        SandboxWorkspace ws = new SandboxWorkspace(policy(100, 1_000_000), tmp);
        ws.create("t3");
        // a/../../b 归一化后逃逸，必须拒绝
        assertThrows(IllegalArgumentException.class,
                () -> ws.writeFile("t3", "a/../../escape.txt", "x"));
    }

    @Test
    void fileQuotaEnforced(@TempDir Path tmp) throws IOException {
        SandboxWorkspace ws = new SandboxWorkspace(policy(2, 1_000_000), tmp);
        ws.create("t4");
        ws.writeFile("t4", "a.txt", "1");
        ws.writeFile("t4", "b.txt", "2");
        assertThrows(IllegalStateException.class, () -> ws.writeFile("t4", "c.txt", "3"));
    }

    @Test
    void byteQuotaEnforced(@TempDir Path tmp) throws IOException {
        SandboxWorkspace ws = new SandboxWorkspace(policy(100, 10), tmp);
        ws.create("t5");
        ws.writeFile("t5", "a.txt", "0123456789");
        assertThrows(IllegalStateException.class, () -> ws.writeFile("t5", "b.txt", "01234567890"));
    }

    @Test
    void destroyRemovesEverything(@TempDir Path tmp) throws IOException {
        SandboxWorkspace ws = new SandboxWorkspace(policy(100, 1_000_000), tmp);
        ws.create("t6");
        ws.writeFile("t6", "a/b/c.txt", "content");
        ws.destroy("t6");
        assertTrue(ws.isDestroyed("t6"), "销毁后任务目录应不存在");
    }

    @Test
    void unknownTaskRejected(@TempDir Path tmp) throws IOException {
        SandboxWorkspace ws = new SandboxWorkspace(policy(100, 1_000_000), tmp);
        assertThrows(IllegalArgumentException.class, () -> ws.taskDir("nope"));
    }
}
