package com.codecanvas.project;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个生成项目。
 *
 * <p>用 record + with 方法而非可变对象：每次迭代产出新实例，避免并发下共享状态被改坏。
 *
 * <p>ignoreUnknown：落盘的是整个 record 的 JSON，应用升级后新增字段写的文件要能被旧版本读，
 * 旧字段写的文件也要能被新版本读——否则一次升级就会让启动时的 loadAll 静默丢掉全部历史项目。
 *
 * @param prompt           用户第一句话说的需求
 * @param prd              结构化需求文档，多轮迭代时的稳定上下文
 * @param currentVersionId 当前生效的版本
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Project(
        String id,
        String name,
        String prompt,
        String prd,
        Instant createdAt,
        Instant updatedAt,
        List<ProjectFile> files,
        List<VersionSnapshot> versions,
        String currentVersionId,
        String deployKey,
        Instant deployedAt) {

    public static Project create(String id, String name, String prompt) {
        Instant now = Instant.now();
        return new Project(id, name, prompt, "", now, now,
                List.of(), new ArrayList<>(), null, null, null);
    }

    public Project withPrd(String newPrd) {
        return new Project(id, name, prompt, newPrd, createdAt, Instant.now(), files, versions,
                currentVersionId, deployKey, deployedAt);
    }

    public Project withName(String newName) {
        return new Project(id, newName, prompt, prd, createdAt, Instant.now(), files, versions,
                currentVersionId, deployKey, deployedAt);
    }

    /**
     * 发布：记录部署密钥与时间。
     *
     * <p>密钥只在首次发布时生成，之后保持不变——已分享出去的链接不能因为再点一次发布就失效。
     */
    public Project withDeploy(String key, Instant at) {
        return new Project(id, name, prompt, prd, createdAt, Instant.now(), files, versions,
                currentVersionId, key, at);
    }

    /** 追加一个新版本并置为当前版本。 */
    public Project withNewVersion(VersionSnapshot snapshot) {
        List<VersionSnapshot> next = new ArrayList<>(versions);
        next.add(snapshot);
        return new Project(id, name, prompt, prd, createdAt, Instant.now(),
                snapshot.files(), next, snapshot.id(), deployKey, deployedAt);
    }

    /** 切换到指定版本（回滚）。 */
    public Project withActiveVersion(String versionId) {
        VersionSnapshot target = versionOf(versionId);
        if (target == null) {
            return this;
        }
        return new Project(id, name, prompt, prd, createdAt, Instant.now(),
                target.files(), versions, versionId, deployKey, deployedAt);
    }

    public boolean deployed() {
        return deployKey != null && !deployKey.isBlank();
    }

    public VersionSnapshot versionOf(String versionId) {
        if (versions == null) {
            return null;
        }
        return versions.stream().filter(v -> v.id().equals(versionId)).findFirst().orElse(null);
    }

    public VersionSnapshot currentVersion() {
        return currentVersionId == null ? null : versionOf(currentVersionId);
    }

    public String mainFileContent() {
        return files == null || files.isEmpty() || files.get(0) == null
                ? "" : files.get(0).content();
    }

    @JsonIgnore   // 否则 Jackson 按 isX() 约定把 "empty" 写进项目 JSON（实测已发生）
    public boolean isEmpty() {
        return files == null || files.isEmpty();
    }
}
