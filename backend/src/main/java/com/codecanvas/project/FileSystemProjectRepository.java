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
        cache.put(project.id(), project);
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
            log.error("保存项目失败 {}：{}", project.id(), e.getMessage());
        }
        return project;
    }
}
