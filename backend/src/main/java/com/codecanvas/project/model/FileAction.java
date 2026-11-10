package com.codecanvas.project.model;

/**
 * 一个文件的变更动作（C3 多文件项目模型）。
 */
public enum FileAction {
    CREATE,
    UPDATE,
    DELETE;

    /** 宽松解析：模型输出大小写不定，未知动作按内容回退。 */
    public static FileAction parse(String raw, boolean hasContent) {
        if (raw != null) {
            switch (raw.trim().toUpperCase()) {
                case "CREATE", "ADD", "NEW" -> {
                    return CREATE;
                }
                case "DELETE", "REMOVE" -> {
                    return DELETE;
                }
                default -> {
                }
            }
        }
        return hasContent ? UPDATE : DELETE;
    }
}
