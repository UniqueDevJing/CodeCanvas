package com.codecanvas.project;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 项目服务：创建、落版本、回滚、改名、发布。 */
@Service
public class ProjectService {

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    public Optional<Project> find(String id) {
        return id == null ? Optional.empty() : repository.findById(id);
    }

    public Project require(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + id));
    }

    public List<Project> list() {
        return repository.findAllSorted();
    }

    public Project create(String prompt) {
        Project project = Project.create("p_" + shortId(), deriveName(prompt), prompt);
        return repository.save(project);
    }

    /** 写入一个新版本快照，并置为当前版本。 */
    public Project saveVersion(Project project,
                               List<ProjectFile> files,
                               String summary,
                               String requirement,
                               int healAttempts,
                               double cost,
                               long inputTokens,
                               long outputTokens,
                               String model,
                               String tierLabel) {
        int index = project.versions() == null ? 0 : project.versions().size();
        VersionSnapshot snapshot = new VersionSnapshot(
                "v" + (index + 1),
                "第 " + (index + 1) + " 版",
                summary,
                requirement,
                Instant.now(),
                files,
                healAttempts,
                cost,
                inputTokens,
                outputTokens,
                model,
                tierLabel);
        return repository.save(project.withNewVersion(snapshot));
    }

    public Project updatePrd(Project project, String prd) {
        return repository.save(project.withPrd(prd));
    }

    public Project activate(String projectId, String versionId) {
        Project project = require(projectId);
        if (project.versionOf(versionId) == null) {
            throw new IllegalArgumentException("版本不存在: " + versionId);
        }
        return repository.save(project.withActiveVersion(versionId));
    }

    public Project rename(String projectId, String name) {
        return repository.save(require(projectId).withName(name));
    }

    /**
     * 发布项目，返回带部署密钥的项目。
     *
     * <p>幂等：重复发布会沿用第一次生成的密钥，只刷新发布时间。
     * 这一点很重要——密钥一变，之前发给别人的链接就全废了。
     */
    public Project deploy(String projectId) {
        Project project = require(projectId);
        if (project.isEmpty()) {
            throw new IllegalArgumentException("项目还没有可发布的版本，先生成一个应用");
        }
        String key = project.deployed() ? project.deployKey() : generateDeployKey();
        return repository.save(project.withDeploy(key, Instant.now()));
    }

    public Optional<Project> findByDeployKey(String deployKey) {
        return repository.findByDeployKey(deployKey);
    }

    /**
     * 作品广场：已发布的项目，最近的排在前面。
     *
     * <p>只列已发布的——没发布的是用户自己的草稿，不该出现在公开列表里。
     */
    public List<Project> gallery() {
        return repository.findAll().stream()
                .filter(Project::deployed)
                .sorted(Comparator.comparing(Project::deployedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * 生成部署密钥：8 位，去掉了容易混淆的字符（0/O、1/l/I）。
     *
     * <p>密钥是短串、可被枚举，但它不承担鉴权职责——发布出去的内容本来就是给所有人看的。
     */
    private String generateDeployKey() {
        final String alphabet = "23456789abcdefghjkmnpqrstuvwxyz";
        StringBuilder sb = new StringBuilder(8);
        Random random = new Random();
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** 从需求里取一个短名字当项目名。 */
    static String deriveName(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "未命名应用";
        }
        String text = prompt.trim();
        for (String prefix : List.of("帮我做", "帮我写", "帮我生成", "帮我", "请帮我", "做一个", "做个",
                "写一个", "写个", "设计一个", "设计个", "生成一个", "生成个", "创建一个", "创建个", "我想要一个", "我想要")) {
            if (text.startsWith(prefix)) {
                text = text.substring(prefix.length());
                break;
            }
        }
        text = text.replaceAll("[。，,.！!?？\\s]+$", "").trim();
        if (text.isEmpty()) {
            text = prompt.trim();
        }
        return text.length() > 16 ? text.substring(0, 16) + "…" : text;
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
