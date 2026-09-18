package com.codecanvas.llm;

/**
 * 模型调用目的。用于：① 分级路由判断任务类型 ② 离线引擎选择对应行为。
 */
public enum LlmPurpose {

    /** 把一句话需求抽成结构化 PRD。 */
    PRD("需求澄清"),

    /** 首轮完整生成代码。 */
    CODE("代码生成"),

    /** 在已有代码上增量修改。 */
    MODIFY("增量修改"),

    /** 针对校验/运行时错误做定点修复。 */
    FIX("自检修复");

    private final String label;

    LlmPurpose(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
