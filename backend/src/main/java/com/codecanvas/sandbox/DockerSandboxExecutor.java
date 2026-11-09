package com.codecanvas.sandbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 容器级隔离后端（P1-4：可探测、可选、但明确未实现）。
 *
 * <p>现状与取舍：生成物是单文件页面、不需要装依赖也不需要构建，独立源 iframe
 * 已经拿到"拿不到平台 DOM / Cookie / 存储"的隔离效果。在平台对外开放执行完全陌生代码之前，
 * 引入容器属于过度设计，所以这里<b>刻意保持 available() == false</b>。
 *
 * <p>但"不可用"不再是一句写死的话：会真实探测策略开关与 Docker 守护进程连通性，
 * 由 {@link #unavailableReason()} 如实说明到底卡在哪一步。这样运维看到
 * 「守护进程可达，但容器编排尚未实现」和「守护进程没起来」能立刻区分，
 * 而不是笼统一句"没启用"。
 *
 * <p>{@link #available()} 必须恒为 false，直到 {@link #prepare} 真正实现：
 * 一旦只因为"docker 命令能跑"就返回 true，选择器会把它选成当前后端，
 * 然后 prepare 直接抛异常——那是比不启用严重得多的故障。
 *
 * <p>真正启用时要落地的加固项：一次性容器、非 root 运行、默认断网、限 CPU/内存/PID、
 * 根文件系统只读、构建超时强杀、镜像白名单。这些才是"沙箱"的价值，
 * 而不是"用了 Docker"四个字。
 */
@Component
public class DockerSandboxExecutor implements SandboxExecutor {

    private final boolean enabled;
    private volatile Boolean daemonReachable;

    public DockerSandboxExecutor(
            @Value("${codecanvas.sandbox.docker.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** 隔离能力更强，一旦真正实现并启用就自动排在 iframe 之前。 */
    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String id() {
        return "docker-sandbox";
    }

    @Override
    public boolean available() {
        return false;
    }

    /** 策略开关 + 守护进程连通性的真实探测结论，只用于说明，不用于启用。 */
    public String probeSummary() {
        if (!enabled) {
            return "策略未启用（codecanvas.sandbox.docker.enabled=false）";
        }
        return daemonReachable()
                ? "Docker 守护进程可达，但容器编排尚未实现"
                : "Docker 守护进程不可达（未启动 Docker Desktop / engine）";
    }

    private boolean daemonReachable() {
        Boolean cached = daemonReachable;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (daemonReachable != null) {
                return daemonReachable;
            }
            boolean ok = false;
            try {
                Process p = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                        .redirectErrorStream(true)
                        .start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                boolean done = p.waitFor(5, TimeUnit.SECONDS);
                ok = done && p.exitValue() == 0 && !out.isBlank()
                        && !out.toLowerCase().contains("failed")
                        && !out.toLowerCase().contains("cannot");
            } catch (Exception e) {
                ok = false;
            }
            daemonReachable = ok;
            return ok;
        }
    }

    @Override
    public String unavailableReason() {
        return "容器沙箱未启用（" + probeSummary() + "）。当前生成物是单文件页面、无需构建，"
                + "独立源 iframe 已提供隔离；等接入依赖安装与构建、或平台对外开放执行陌生代码时，"
                + "再实现一次性容器 / 非 root / 默认断网 / 资源限额 / 镜像白名单等加固。";
    }

    @Override
    public SandboxedPreview prepare(String html, String projectId, String versionId) {
        throw new UnsupportedOperationException("容器沙箱尚未实现：" + unavailableReason());
    }
}
