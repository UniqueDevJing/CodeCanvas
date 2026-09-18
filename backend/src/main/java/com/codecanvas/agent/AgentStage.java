package com.codecanvas.agent;

/**
 * Agent 执行阶段。
 *
 * <p>这是一个显式状态机，而不是把编排藏在某个框架里。好处是每一步的输入输出、失败处理、
 * 以及在前端展示什么都很明确；出问题能直接定位到阶段，而不是面对一个黑盒回调链。
 */
public enum AgentStage {

    CLARIFY(1, "需求澄清", "把一句话需求拆成结构化 PRD"),
    PLAN(2, "任务规划", "确定这一轮要动哪些文件、走哪个模型档位"),
    GENERATE(3, "代码生成", "流式产出页面代码"),
    VALIDATE(4, "质量校验", "静态检查代码结构与脚本正确性"),
    HEAL(5, "自检修复", "把校验或运行时错误回注，做定点修复");

    private final int index;
    private final String label;
    private final String description;

    AgentStage(int index, String label, String description) {
        this.index = index;
        this.label = label;
        this.description = description;
    }

    public int index() {
        return index;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static int total() {
        return values().length;
    }
}
