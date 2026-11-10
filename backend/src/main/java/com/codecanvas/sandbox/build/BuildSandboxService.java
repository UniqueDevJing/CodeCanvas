package com.codecanvas.sandbox.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 受限构建执行服务（C4）。
 *
 * <p>职责链：任务准备（独立临时工作区 + 配额）→ 依赖白名单校验 →
 * 限时进程执行（硬超时强杀 + 输出体积上限 + 最小环境变量）→ 工作区销毁。
 *
 * <p><b>隔离级别的诚实声明</b>：非容器模式下，"默认禁网"由
 * 依赖白名单（{@link DependencyPolicy}）+ npm 离线标志近似实现，
 * 真正的网络/资源隔离需要容器模式（--network none / memory / cpus / pids-limit），
 * 接入时机见 DockerSandboxExecutor 的加固清单。这里不夸口。
 */
@Service
public class BuildSandboxService {

    private static final Logger log = LoggerFactory.getLogger(BuildSandboxService.class);

    /** npm 离线安装命令（默认禁网模式：只装本地缓存里已有的包，白名单外依赖装不上） */
    static final List<String> NPM_INSTALL_OFFLINE = List.of("npm", "install", "--prefer-offline", "--no-audit", "--no-fund");

    private final BuildPolicy policy;
    private final SandboxWorkspace workspace;

    public BuildSandboxService(BuildPolicy policy) throws IOException {
        this.policy = policy;
        this.workspace = new SandboxWorkspace(policy, null);
    }

    /** 执行结果。 */
    public record Result(boolean ok, int exitCode, String output, long durationMs, String isolationLevel) {
    }

    /** 阶段枚举：各阶段超时独立配置（运行期从 policy 读取，避免类加载期 NPE）。 */
    public enum Stage {
        INSTALL,
        BUILD
    }

    /**
     * 准备任务工作区：创建独立目录并写入初始文件。
     *
     * @throws IllegalStateException 文件数/体积超配额、路径非法
     */
    public Path prepareTask(String taskId, Map<String, String> files) throws IOException {
        requireEnabled();
        workspace.create(taskId);
        for (Map.Entry<String, String> e : files.entrySet()) {
            workspace.writeFile(taskId, e.getKey(), e.getValue());
        }
        workspace.enforceQuota(taskId);
        return workspace.taskDir(taskId);
    }

    /**
     * 校验依赖白名单（构建前第一道闸）。
     */
    public DependencyPolicy.CheckResult checkDependencies(String taskId, String packageJson) {
        DependencyPolicy.CheckResult result = DependencyPolicy.check(packageJson, policy);
        if (!result.ok()) {
            log.warn("构建任务 {} 依赖白名单未通过: {}", taskId, result.violations());
        }
        return result;
    }

    /**
     * 在任务工作区内限时执行命令。
     *
     * @return 执行结果；输出超过 maxOutputBytes 时截断并标记失败
     */
    public Result execute(String taskId, Stage stage, List<String> command) throws IOException {
        requireEnabled();
        Path dir = workspace.taskDir(taskId);
        long started = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        // 最小环境变量：不透传宿主的代理/云凭证，防止构建脚本借环境外联
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.toUpperCase().matches(".*(PROXY|AWS_|AZURE_|GCP_|GOOGLE_|SECRET|TOKEN|KEY|CREDENTIAL).*"));
        if (!policy.isNetworkEnabled()) {
            // 非容器模式的离线近似：npm 系命令强制离线标志
            if (!command.isEmpty() && "npm".equalsIgnoreCase(command.get(0))) {
                pb.command().add("--offline");
            }
        }

        log.info("构建沙箱执行: task={} stage={} cmd={} network={}",
                taskId, stage, command, policy.isNetworkEnabled() ? "allowed" : "offline");
        int timeoutSeconds = stage == Stage.INSTALL
                ? policy.getInstallTimeoutSeconds()
                : policy.getBuildTimeoutSeconds();
        try {
            Process process = pb.start();
            // 输出在独立线程读取：waitFor 限时的是进程而非管道读取，
            // 否则"输出未关流 + 超时强杀"永远不会被触发
            java.util.concurrent.atomic.AtomicReference<String> outputRef =
                    new java.util.concurrent.atomic.AtomicReference<>("");
            Thread reader = new Thread(() -> {
                try {
                    outputRef.set(drainLimited(process.getInputStream(), policy.getMaxOutputBytes()));
                } catch (IOException ignored) {
                    // 流随进程销毁而关闭，读不到即空
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                reader.join(2000);
                long ms = System.currentTimeMillis() - started;
                log.warn("构建超时强杀: task={} stage={} 超时={}s", taskId, stage, timeoutSeconds);
                return new Result(false, -1, outputRef.get() + "\n[构建超时，已强制终止]\n", ms, isolationLevel());
            }
            reader.join(5000);
            long ms = System.currentTimeMillis() - started;
            int exit = process.exitValue();
            String output = outputRef.get();
            boolean truncated = output.endsWith(TRUNCATED_MARK);
            log.info("构建完成: task={} stage={} exit={} 耗时={}ms 输出截断={}", taskId, stage, exit, ms, truncated);
            return new Result(exit == 0 && !truncated, exit, output, ms, isolationLevel());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, -2, "[执行被打断]", System.currentTimeMillis() - started, isolationLevel());
        }
    }

    /** 销毁任务工作区。 */
    public void destroyTask(String taskId) {
        workspace.destroyQuietly(taskId);
    }

    /** 当前隔离级别（/api/config 自述用，不夸口）。 */
    public String isolationLevel() {
        return policy.isNetworkEnabled()
                ? "container-pending-network-allowed"
                : "local-restricted-offline(白名单+离线标志近似禁网,容器级隔离待接入)";
    }

    public SandboxWorkspace workspace() {
        return workspace;
    }

    private void requireEnabled() {
        if (!policy.isEnabled()) {
            throw new IllegalStateException("构建沙箱未启用（codecanvas.sandbox.build.enabled=false），拒绝执行构建");
        }
    }

    private static final String TRUNCATED_MARK = "\n[输出超过上限，已截断]\n";

    /** 限时限量读取输出：达到上限即截断并继续读完丢弃（避免进程被管道阻塞挂住）。 */
    private static String drainLimited(InputStream in, long maxBytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        long total = 0;
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (total < maxBytes) {
                int keep = (int) Math.min(n, maxBytes - total);
                sb.append(new String(buf, 0, keep, StandardCharsets.UTF_8));
            }
            total += n;
            if (total >= maxBytes && sb.length() < maxBytes + TRUNCATED_MARK.length()) {
                sb.append(TRUNCATED_MARK);
            }
        }
        in.close();
        return sb.toString();
    }
}
