package com.codecanvas.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 文件系统仓储实现。
 *
 * <p>一个项目一个 JSON 文件，放在 {@code codecanvas-data/projects/} 下，
 * 内存里保留一份缓存避免每次请求都读盘。
 */
@Repository
public class FileSystemProjectRepository implements ProjectRepository {

    private static final Logger log = LoggerFactory.getLogger(FileSystemProjectRepository.class);

    private final ObjectMapper objectMapper;
    private final Path root;
    private final Map<String, Project> cache = new ConcurrentHashMap<>();

    public FileSystemProjectRepository(ObjectMapper objectMapper,
                                       @Value("${codecanvas.storage.dir:./codecanvas-data}") String dir) {
        this.objectMapper = objectMapper;
        this.root = Paths.get(dir, "projects");
    }

    @PostConstruct
    void loadAll() {
        try {
            Files.createDirectories(root);
            try (Stream<Path> files = Files.list(root)) {
                files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(this::loadFile);
            }
            log.info("已加载 {} 个项目，存储目录 {}", cache.size(), root.toAbsolutePath());
        } catch (IOException e) {
            log.warn("加载历史项目失败：{}", e.getMessage());
        }
    }

    private void loadFile(Path path) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            Project project = objectMapper.readValue(json, Project.class);
            if (project != null && project.id() != null) {
                cache.put(project.id(), project);
            }
        } catch (Exception e) {
            log.warn("读取项目文件失败 {}：{}", path.getFileName(), e.getMessage());
        }
    }

    @Override
    public Optional<Project> findById(String id) {
        return Optional.ofNullable(cache.get(id));
    }

    @Override
    public List<Project> findAll() {
        return new ArrayList<>(cache.values());
    }

    @Override
    public synchronized Project save(Project project) {
        // 并发写入合并（真实 bug 修复）：
        // 生成是"读项目 → 追加一版 → 保存"的读改写，两个并发生成会各自基于旧快照写回，
        // 结果后写者把先写者的版本整条覆盖掉（实测：两次并发只留下 1 个新版本）。
        // 这里在锁内按版本 id 做并集，保证"谁生成过谁的版本一定在"。
        Project merged = mergeVersions(cache.get(project.id()), project);
        cache.put(merged.id(), merged);
        project = merged;
        Path target = root.resolve(project.id() + ".json");
        try {
            Files.createDirectories(root);
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(project);
            // 原子写：先写同目录临时文件再 rename。此前是直接覆盖目标文件，
            // 进程在写入中途被杀（OOM / 重启 / 断电）会留下半截 JSON，下次启动整个项目加载失败。
            // rename 在同一文件系统内是原子操作，任何时刻磁盘上要么是旧完整版、要么是新完整版。
            Path tmp = root.resolve(project.id() + ".json.tmp-" + System.nanoTime());
            try {
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    // 个别文件系统不支持原子 rename（如某些网络挂载），退化为普通替换——仍优于直接覆写
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);   // rename 成功后文件已不在，防御异常路径残留
            }
        } catch (IOException e) {
            // 不能吞：磁盘满 / 权限变更 / 序列化失败都会走这里。
            // 之前只打日志，调用方以为保存成功，用户看到"生成成功"但版本没落盘——
            // 这是静默丢数据。现在显式失败，让上层能告诉用户"保存失败，请重试"。
            log.error("保存项目失败 {}：{}", project.id(), e.getMessage(), e);
            throw new IllegalStateException("保存项目失败: " + project.id(), e);
        }
        return project;
    }
    /**
     * 把磁盘/缓存里已有的版本与新写的版本做并集。
     *
     * <p>顺序策略：老版本按原序在前，新版本按到达顺序追加——这样版本号 v1..vN 单调递增，
     * 前端"版本历史"不会跳序。{@code currentVersionId} 采用本次写入的值（最后一次生成为准）。
     */
    private static Project mergeVersions(Project existing, Project incoming) {
        if (existing == null || existing.versions() == null || existing.versions().isEmpty()) {
            return incoming;
        }
        if (incoming.versions() == null || incoming.versions().isEmpty()) {
            return incoming;
        }
        java.util.LinkedHashMap<String, com.codecanvas.project.VersionSnapshot> byId = new java.util.LinkedHashMap<>();
        for (com.codecanvas.project.VersionSnapshot v : existing.versions()) {
            byId.put(v.id(), v);
        }
        for (com.codecanvas.project.VersionSnapshot v : incoming.versions()) {
            com.codecanvas.project.VersionSnapshot present = byId.get(v.id());
            if (present == null) {
                byId.put(v.id(), v);
                continue;
            }
            // 关键：并发生成的版本号是按"当前版本数+1"算的，两个线程可能算出**同一个 vN**。
            // 若内容不同就必须各自保留（实测：按 id 去重会把其中一次的产物整条吞掉），
            // 所以这里给冲突的后者加内容指纹后缀，宁可版本号不连续也不能丢产物。
            if (!java.util.Objects.equals(signature(present), signature(v))) {
                byId.put(v.id() + "-" + signature(v), v);
            }
        }
        java.util.List<com.codecanvas.project.VersionSnapshot> union = new java.util.ArrayList<>(byId.values());
        if (union.size() == incoming.versions().size() && union.containsAll(incoming.versions())) {
            return incoming;   // 无丢失，保持原对象
        }
        log.info("[project] 合并并发版本：disk={} incoming={} union={}",
                existing.versions().size(), incoming.versions().size(), union.size());
        return new Project(
                incoming.id(), incoming.name(), incoming.prompt(), incoming.prd(),
                incoming.createdAt(), incoming.updatedAt(), incoming.files(), union,
                incoming.currentVersionId(), incoming.deployKey(), incoming.deployedAt());
    }

    /** 版本内容指纹：只用于并发同号时的去重判定，不参与展示。 */
    private static String signature(com.codecanvas.project.VersionSnapshot v) {
        String html = v.files() == null || v.files().isEmpty() ? "" : v.files().get(0).content();
        return java.util.HexFormat.of().toHexDigits(
                (v.requirement() + "|" + html).hashCode()).substring(0, 8);
    }

}
