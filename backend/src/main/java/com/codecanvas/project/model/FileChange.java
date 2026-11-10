package com.codecanvas.project.model;

import com.codecanvas.agent.tool.AgentWorkspace;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 单个文件变更（C3）。DELETE 时 content 为 null。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileChange(String path, String content, FileAction action) {

    public FileChange {
        if (action == null) {
            action = FileAction.parse(null, content != null && !content.isEmpty());
        }
        if (action == FileAction.DELETE) {
            content = null;
        }
    }

    /** 应用到工作区，返回动作结果；路径非法时返回错误说明。 */
    public String applyTo(AgentWorkspace workspace) {
        String invalid = AgentWorkspace.checkPath(path);
        if (invalid != null) {
            return "非法文件路径（" + invalid + "）: " + path;
        }
        if (action == FileAction.DELETE) {
            if (workspace.read(path) == null) {
                return null; // 删除不存在的文件视为幂等成功
            }
            workspace.delete(path);
            return null;
        }
        if (content == null) {
            return "变更缺少内容: " + path;
        }
        workspace.write(path, content);
        return null;
    }
}
