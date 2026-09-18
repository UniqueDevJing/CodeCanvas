package com.codecanvas.llm;

import com.codecanvas.config.LlmProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 模型分级路由。
 *
 * <p>解决的问题：如果所有请求都打同一个模型，要么贵（全用旗舰），要么差（全用轻量）。
 * 这里按"任务本身的复杂度特征"打分，再映射到档位：
 *
 * <ul>
 *   <li>任务类型：完整生成 &gt; 增量修改/修复 &gt; 需求抽取</li>
 *   <li>需求体量：越长的需求通常意味着越多功能点</li>
 *   <li>关键词信号：出现"登录/权限/持久化/图表"这类词说明涉及架构决策</li>
 *   <li>编辑类信号："颜色/字号/文案"这类小改动直接走轻量档</li>
 * </ul>
 *
 * <p>得分不用于任何"精确性"承诺，只是一个可解释的启发式规则；关键是每次决策都能给出理由，
 * 并且可以按真实的成本/质量数据回头调参。
 */
@Component
public class ModelRouter {

    /** 触发架构复杂度的信号词。 */
    private static final List<String> HEAVY_SIGNALS = List.of(
            "系统", "平台", "多页面", "多页", "登录", "注册", "权限", "鉴权", "角色",
            "数据库", "持久化", "后端", "接口", "管理后台", "仪表盘", "图表", "统计",
            "工作流", "实时", "协作", "支付", "订单", "电商", "完整", "项目", "算法");

    /** 轻量编辑信号词：出现这些说明是局部小改。 */
    private static final List<String> LIGHT_SIGNALS = List.of(
            "把", "改成", "换成", "换个", "颜色", "配色", "字体", "字号", "圆角",
            "标题", "文案", "间距", "背景", "加深", "变浅", "居中", "图标");

    private final LlmProperties properties;

    public ModelRouter(LlmProperties properties) {
        this.properties = properties;
    }

    /**
     * 做一次路由决策。
     *
     * @param purpose       任务类型
     * @param prompt        用户这次的需求文本
     * @param firstTurn     是否首轮（首轮要建立整体结构，通常更重）
     * @param existingFiles 项目已有文件数（文件越多，改动波及面越大）
     */
    public RouteDecision route(LlmPurpose purpose, String prompt, boolean firstTurn, int existingFiles) {
        int score = baseScore(purpose);
        List<String> reasons = new ArrayList<>();
        reasons.add("任务类型=" + purpose.label() + "(基础分" + score + ")");

        int length = prompt == null ? 0 : prompt.trim().length();
        if (length > 120) {
            score += 2;
            reasons.add("需求描述较长(+" + 2 + ")");
        } else if (length > 60) {
            score += 1;
            reasons.add("需求描述中等(+" + 1 + ")");
        }

        int heavyHits = countHits(prompt, HEAVY_SIGNALS);
        if (heavyHits > 0) {
            int bonus = Math.min(heavyHits, 3);
            score += bonus;
            reasons.add("涉及架构关键词 " + heavyHits + " 处(+" + bonus + ")");
        }

        int lightHits = countHits(prompt, LIGHT_SIGNALS);
        if (lightHits > 0 && heavyHits == 0) {
            int penalty = Math.min(lightHits, 2);
            score -= penalty;
            reasons.add("属于局部微调(-" + penalty + ")");
        }

        if (firstTurn && purpose == LlmPurpose.CODE) {
            score += 1;
            reasons.add("首轮需建立整体结构(+1)");
        }
        if (existingFiles > 1) {
            score += 1;
            reasons.add("项目已有 " + existingFiles + " 个文件，改动面较大(+1)");
        }

        ModelTier tier;
        if (score >= 8) {
            tier = ModelTier.FLAGSHIP;
        } else if (score >= 4) {
            tier = ModelTier.STANDARD;
        } else {
            tier = ModelTier.LIGHT;
        }

        reasons.add("总分 " + score + " → " + tier.label() + "档");
        return new RouteDecision(tier, modelOf(tier), score, String.join("，", reasons));
    }

    public String modelOf(ModelTier tier) {
        LlmProperties.Models models = properties.getModels();
        return switch (tier) {
            case FLAGSHIP -> models.getFlagship();
            case STANDARD -> models.getStandard();
            case LIGHT -> models.getLight();
        };
    }

    private int baseScore(LlmPurpose purpose) {
        return switch (purpose) {
            // 首轮生成要建立整体结构，最重
            case CODE -> 5;
            // 增量修改默认按标准档起步：它要写新代码；真正的局部微调会被下面的轻量信号减下去
            case MODIFY -> 4;
            // 修复通常范围窄，但报错信息本身较长会带来加分，实际多数落在标准档
            case FIX -> 3;
            // 需求抽取输出短、结构固定，用轻量档足够
            case PRD -> 1;
        };
    }

    private int countHits(String text, List<String> signals) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String signal : signals) {
            if (text.contains(signal)) {
                hits++;
            }
        }
        return hits;
    }
}
