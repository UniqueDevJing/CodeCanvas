package com.codecanvas.llm;

/**
 * 模型档位。分级路由的目标：让"任务复杂度"决定"模型贵不贵"，避免所有请求都打旗舰模型。
 */
public enum ModelTier {

    /** 旗舰档：架构级生成、复杂交互、跨模块改动。 */
    FLAGSHIP("旗舰", "复杂生成与架构决策"),

    /** 标准档：一次完整的页面生成、功能增改。 */
    STANDARD("标准", "常规代码生成"),

    /** 轻量档：文案微调、颜色调整、结构化信息抽取。 */
    LIGHT("轻量", "轻量编辑与信息抽取");

    private final String label;
    private final String description;

    ModelTier(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 降级链：出问题时向下一档退，而不是直接失败。 */
    public ModelTier degrade() {
        return switch (this) {
            case FLAGSHIP -> STANDARD;
            case STANDARD -> LIGHT;
            case LIGHT -> LIGHT;
        };
    }
}
