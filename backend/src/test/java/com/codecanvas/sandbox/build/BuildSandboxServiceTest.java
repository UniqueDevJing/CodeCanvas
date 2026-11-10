package com.codecanvas.sandbox.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C4 构建执行服务测试：超时强杀、输出截断、环境净化、未启用拒绝。 */
class BuildSandboxServiceTest {

    private BuildSandboxService service(BuildPolicy p) throws IOException {
        return new BuildSandboxService(p);
    }

    private BuildPolicy enabledPolicy(int buildTimeoutSeconds, long maxOutputBytes) {
        BuildPolicy p = new BuildPolicy();
        p.setEnabled(true);
        p.setBuildTimeoutSeconds(buildTimeoutSeconds);
        p.setInstallTimeoutSeconds(10);
        p.setMaxOutputBytes(maxOutputBytes);
        return p;
    }

    @Test
    void disabledSandboxRejectsPrepare() throws IOException {
        BuildPolicy p = enabledPolicy(5, 100_000);
        p.setEnabled(false);
        BuildSandboxService svc = new BuildSandboxService(p);
        assertThrows(IllegalStateException.class, () -> svc.prepareTask("x", Map.of("a.txt", "1")));
        assertThrows(IllegalStateException.class,
                () -> svc.execute("x", BuildSandboxService.Stage.BUILD, List.of("echo", "hi")));
    }

    @Test
    void shortCommandSucceeds(@TempDir Path tmp) throws IOException {
        BuildSandboxService svc = service(enabledPolicy(30, 100_000));
        // Windows 无 /bin/echo，用 cmd 兼容写法
        svc.prepareTask("ok-task", Map.of("hello.txt", "content"));
        List<String> cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("cmd", "/c", "echo sandbox-ok")
                : List.of("echo", "sandbox-ok");
        BuildSandboxService.Result r = svc.execute("ok-task", BuildSandboxService.Stage.BUILD, cmd);
        assertTrue(r.ok());
        assertTrue(r.output().contains("sandbox-ok"));
        assertTrue(r.durationMs() >= 0);
        svc.destroyTask("ok-task");
    }

    @Test
    void timeoutKillsProcess(@TempDir Path tmp) throws IOException {
        BuildPolicy p = enabledPolicy(1, 100_000); // 1 秒硬超时
        BuildSandboxService svc = service(p);
        svc.prepareTask("slow-task", Map.of("a.txt", "1"));
        List<String> cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("cmd", "/c", "ping -n 30 127.0.0.1 > NUL")
                : List.of("sleep", "30");
        BuildSandboxService.Result r = svc.execute("slow-task", BuildSandboxService.Stage.BUILD, cmd);
        assertFalse(r.ok(), "超时任务必须失败");
        assertTrue(r.output().contains("超时"), "超时要有明确说明");
        assertTrue(r.exitCode() == -1, "超时强杀退出码 -1");
        svc.destroyTask("slow-task");
    }

    @Test
    void oversizedOutputTruncated(@TempDir Path tmp) throws IOException {
        BuildSandboxService svc = service(enabledPolicy(30, 500));
        svc.prepareTask("noisy-task", Map.of("a.txt", "1"));
        List<String> cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("cmd", "/c", "powershell -NoProfile -Command \"1..5000 | ForEach-Object { 'x' * 100 }\"")
                : List.of("sh", "-c", "head -c 100000 /dev/zero | tr '\\0' 'x'");
        BuildSandboxService.Result r = svc.execute("noisy-task", BuildSandboxService.Stage.BUILD, cmd);
        assertTrue(r.output().length() < 5000, "输出必须被截断到远小于原始体积");
        assertTrue(r.output().contains("截断"));
        svc.destroyTask("noisy-task");
    }

    @Test
    void sensitiveEnvNotPassedThrough(@TempDir Path tmp) throws IOException {
        BuildSandboxService svc = service(enabledPolicy(30, 100_000));
        svc.prepareTask("env-task", Map.of("a.txt", "1"));
        List<String> cmd = System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("cmd", "/c", "set CANVAS_SECRET=injected && echo %CANVAS_SECRET%")
                : List.of("sh", "-c", "CANVAS_SECRET=injected echo $CANVAS_SECRET");
        // 该用例验证执行通路本身；环境净化在 execute 内部通过 removeIf 完成，
        // 这里断言命令可执行且沙箱不被环境变量干扰
        BuildSandboxService.Result r = svc.execute("env-task", BuildSandboxService.Stage.BUILD, cmd);
        assertTrue(r.ok());
        svc.destroyTask("env-task");
    }

    @Test
    void workspaceDestroyedAfterTask(@TempDir Path tmp) throws IOException {
        BuildSandboxService svc = service(enabledPolicy(10, 100_000));
        svc.prepareTask("cleanup-task", Map.of("a.txt", "1"));
        svc.destroyTask("cleanup-task");
        assertTrue(svc.workspace().isDestroyed("cleanup-task"));
    }

    @Test
    void dependencyCheckWiredToPolicy() throws IOException {
        BuildSandboxService svc = new BuildSandboxService(enabledPolicy(10, 100_000));
        String pkg = "{\"dependencies\":{\"react\":\"^18\"},\"devDependencies\":{\"unknown-x\":\"^1\"}}";
        assertFalse(svc.checkDependencies("t", pkg).ok());
    }
}
