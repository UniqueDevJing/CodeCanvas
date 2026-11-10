package com.codecanvas.project.model;

import com.codecanvas.agent.tool.AgentWorkspace;
import com.codecanvas.common.JsonText;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次迭代产生的文件变更集合（C3 多文件项目模型核心交付物）。
 *
 * <p>模型可按 JSON 输出一批文件改动（新增/修改/删除），{@link #parse}
 * 负责从模型回复文本里容错提取；解析失败返回 null，调用方降级回单文件路径。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileChangeSet(String summary, List<FileChange> changes) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public FileChangeSet {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** 应用全部变更，返回应用结果统计；存在非法路径时返回失败原因。 */
    public ApplyResult applyTo(AgentWorkspace workspace) {
        int created = 0;
        int updated = 0;
        int deleted = 0;
        for (FileChange change : changes) {
            String error = change.applyTo(workspace);
            if (error != null) {
                return ApplyResult.failed(error, created, updated, deleted);
            }
            switch (change.action()) {
                case CREATE -> created++;
                case UPDATE -> updated++;
                case DELETE -> deleted++;
            }
        }
        return ApplyResult.ok(created, updated, deleted);
    }

    public record ApplyResult(boolean ok, String error, int created, int updated, int deleted) {
        static ApplyResult ok(int created, int updated, int deleted) {
            return new ApplyResult(true, null, created, updated, deleted);
        }

        static ApplyResult failed(String error, int created, int updated, int deleted) {
            return new ApplyResult(false, error, created, updated, deleted);
        }

        public int totalWrites() {
            return created + updated;
        }
    }

    /**
     * 从模型回复文本解析变更集合。
     *
     * @return 解析成功返回集合；文本不含合法 JSON、changes 为空或全部非法时返回 null（降级）
     */
    public static FileChangeSet parse(String llmText) {
        RawSet raw = JsonText.extract(llmText, RawSet.class);
        if (raw == null || raw.changes() == null || raw.changes().isEmpty()) {
            return null;
        }
        List<FileChange> changes = new ArrayList<>();
        for (RawChange rc : raw.changes()) {
            if (rc == null || rc.path() == null || rc.path().isBlank()) {
                continue;
            }
            FileAction action = FileAction.parse(rc.action(),
                    rc.content() != null && !rc.content().isEmpty());
            if (AgentWorkspace.checkPath(rc.path()) != null) {
                continue;
            }
            changes.add(new FileChange(rc.path(), rc.content(), action));
        }
        if (changes.isEmpty()) {
            return null;
        }
        return new FileChangeSet(raw.summary(), changes);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawSet(String summary, List<RawChange> changes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawChange(String path, String content, String action) {
    }
}
