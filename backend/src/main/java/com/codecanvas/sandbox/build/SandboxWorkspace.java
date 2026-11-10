package com.codecanvas.sandbox.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 每任务独立的隔离工作区（C4）。
 *
 * <p>规则（对应方案"沙箱最低安全要求-文件系统"）：
 * 每个构建任务在系统临时目录下拿一个一次性目录，所有产物只允许落在这里；
 * 路径校验拒绝目录穿越；任务结束整个目录连同内容一起销毁。
 */
public class SandboxWorkspace {

    private static final String ROOT_PREFIX = "codecanvas-build-sandbox-";

    private final Path root;
    private final BuildPolicy policy;
    private final ConcurrentHashMap<String, Path> tasks = new ConcurrentHashMap<>();

    /** root 传入 null 时使用系统临时目录（生产用法）；测试可指定独立目录。 */
    public SandboxWorkspace(BuildPolicy policy, Path root) throws IOException {
        this.policy = policy;
        this.root = root != null ? root
                : Files.createTempDirectory(ROOT_PREFIX + UUID.randomUUID() + "-home");
    }

    /**
     * 创建任务工作区目录。
     */
    public Path create(String taskId) throws IOException {
        Path dir = root.resolve(ROOT_PREFIX + sanitize(taskId));
        Files.createDirectories(dir);
        tasks.put(taskId, dir);
        return dir;
    }

    public Path taskDir(String taskId) {
        Path dir = tasks.get(taskId);
        if (dir == null) {
            throw new IllegalArgumentException("工作区不存在: " + taskId);
        }
        return dir;
    }

    /**
     * 向工作区写文件。路径必须相对且不逃逸出任务目录——这是"恶意脚本不能访问宿主"的写入侧闸门。
     */
    public void writeFile(String taskId, String relativePath, String content) throws IOException {
        Path dir = taskDir(taskId);
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")
                || relativePath.contains("\\") || relativePath.startsWith("/")
                || relativePath.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("非法工作区路径: " + relativePath);
        }
        Path target = dir.resolve(relativePath).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("路径逃逸出工作区: " + relativePath);
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        enforceQuota(taskId);
    }

    /** 统计当前任务工作区的文件数与总字节，超限即抛出（资源限制）。 */
    public void enforceQuota(String taskId) throws IOException {
        Path dir = taskDir(taskId);
        AtomicLong bytes = new AtomicLong();
        AtomicLong count = new AtomicLong();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                try {
                    bytes.addAndGet(Files.size(p));
                    count.incrementAndGet();
                } catch (IOException ignored) {
                    // 并发删除时个别文件消失，不计
                }
            });
        }
        if (count.get() > policy.getMaxFiles()) {
            throw new IllegalStateException("工作区文件数超限（" + count.get() + " > " + policy.getMaxFiles() + "）");
        }
        if (bytes.get() > policy.getMaxTotalBytes()) {
            throw new IllegalStateException("工作区体积超限（" + bytes.get() + " > " + policy.getMaxTotalBytes() + "B）");
        }
    }

    /** 任务结束销毁工作区（整个目录递归删除）。 */
    public void destroy(String taskId) throws IOException {
        Path dir = tasks.remove(taskId);
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Windows 下偶发句柄占用，尽力删除
                }
            });
        }
    }

    /** 进程结束后的兜底清理（SimpleFileVisitor 防符号链接逃逸）。 */
    public void destroyQuietly(String taskId) {
        try {
            destroy(taskId);
        } catch (Exception ignored) {
            // 兜底通道，失败不影响主流程
        }
    }

    /** 任务目录内是否还存在文件（清理校验用）。 */
    public boolean isDestroyed(String taskId) {
        Path dir = tasks.get(taskId);
        if (dir != null) {
            return false;
        }
        Path legacy = root.resolve(ROOT_PREFIX + sanitize(taskId));
        return !Files.exists(legacy);
    }

    private static String sanitize(String taskId) {
        return taskId == null ? "unknown"
                : taskId.replaceAll("[^a-zA-Z0-9_-]", "_").substring(0, Math.min(64, taskId.length()));
    }
}
