package com.codecanvas.context;

import com.codecanvas.common.TokenEstimator;
import com.codecanvas.project.ProjectFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 上下文组装：决定"这一轮到底把哪些内容喂给模型"。
 *
 * <p>解决的问题：多轮迭代时如果把全部历史对话和全部代码原样拼进提示词，会出现三个后果——
 * 成本随轮次线性上涨、模型被无关内容干扰开始忘事、最后超出上下文窗口直接报错。
 *
 * <p>两个手段：
 * <ol>
 *   <li><b>相关性选择</b>：按需求文本与文件内容的匹配度打分，只注入相关文件。
 *       单文件项目下这一步退化为"就是它自己"，但文件一多它就开始起作用。</li>
 *   <li><b>预算裁剪</b>：给注入内容设 token 上限，超了就截掉长文件的中段并明确标注，
 *       而不是让请求直接失败。</li>
 * </ol>
 */
@Component
public class ContextAssembler {

    /** 单次注入代码的 token 预算。留足余量给模型输出。 */
    public static final int DEFAULT_BUDGET = 12000;

    /** 裁剪时保留的头部比例与尾部比例，中间部分丢弃。 */
    private static final double HEAD_RATIO = 0.6;
    private static final double TAIL_RATIO = 0.3;

    public record Selection(List<ProjectFile> files, int injectedTokens, String truncationNote) {
    }

    public record ContextReport(int injectedTokens, int rawTokens, int savedTokens, int savedPercent) {
    }

    public Selection select(List<ProjectFile> files, String requirement) {
        return select(files, requirement, DEFAULT_BUDGET);
    }

    public Selection select(List<ProjectFile> files, String requirement, int budget) {
        if (files == null || files.isEmpty()) {
            return new Selection(List.of(), 0, "");
        }

        Set<String> probes = shingles(requirement);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            ProjectFile file = files.get(i);
            int score = relevance(file, probes);
            // 主文件（第一个）始终优先，它承载了页面主体
            scored.add(new Scored(file, score + (i == 0 ? 1000 : 0)));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());

        List<ProjectFile> picked = new ArrayList<>();
        int used = 0;
        for (Scored item : scored) {
            int cost = TokenEstimator.estimate(item.file().content());
            if (!picked.isEmpty() && used + cost > budget) {
                continue;
            }
            picked.add(item.file());
            used += cost;
        }

        if (used <= budget) {
            return new Selection(picked, used, "");
        }

        // 单个文件就超预算：截掉中段，头尾保留，并把这件事明确告诉模型
        List<ProjectFile> trimmed = new ArrayList<>();
        int trimmedTokens = 0;
        for (ProjectFile file : picked) {
            String content = file.content();
            int cost = TokenEstimator.estimate(content);
            if (trimmedTokens + cost <= budget) {
                trimmed.add(file);
                trimmedTokens += cost;
                continue;
            }
            String shortened = truncateMiddle(content, Math.max(800, budget - trimmedTokens));
            trimmed.add(new ProjectFile(file.path(), shortened));
            trimmedTokens += TokenEstimator.estimate(shortened);
        }
        String note = "【注意】代码过长，中间部分已省略，请只在可见部分基础上改动，并保持被省略部分的功能不变。\n";
        return new Selection(trimmed, trimmedTokens, note);
    }

    /**
     * 计算"不注入全量历史"省下了多少。
     *
     * <p><b>刻意不做非负截断</b>：结构化 PRD 是一笔固定开销（约 200 token），
     * 前两轮还没有足够的历史可以摊薄它，此时这个数会<b>是负的</b>——
     * 也就是说压缩方案在前两轮其实更费 token，从第三轮左右才开始净收益，轮次越多收益越大。
     *
     * <p>把这个负数如实显示出来，比强行报一个好看的百分比有意义：它说明了这条优化路线的
     * 适用范围和盈亏平衡点，也避免了拿一个经不起追问的数字去讲故事。
     */
    public static ContextReport report(int injectedTokens, int rawTokens) {
        int saved = rawTokens - injectedTokens;
        int percent = rawTokens <= 0 ? 0 : (int) Math.round(saved * 100.0 / rawTokens);
        return new ContextReport(injectedTokens, rawTokens, saved, percent);
    }

    /** 截断：保留头部与尾部，中间以标记替代。 */
    private String truncateMiddle(String content, int keepTokens) {
        int totalTokens = TokenEstimator.estimate(content);
        if (totalTokens <= keepTokens || content.isEmpty()) {
            return content;
        }
        int headChars = (int) (content.length() * HEAD_RATIO);
        int tailChars = (int) (content.length() * TAIL_RATIO);
        String head = content.substring(0, Math.min(headChars, content.length()));
        String tail = tailChars > 0 && content.length() > tailChars
                ? content.substring(content.length() - tailChars) : "";
        String dropped = "<!-- [已省略 " + (content.length() - head.length() - tail.length()) + " 字符] -->\n";
        return head + "\n" + dropped + tail;
    }

    private int relevance(ProjectFile file, Set<String> probes) {
        if (probes.isEmpty()) {
            return 0;
        }
        String path = file.path() == null ? "" : file.path().toLowerCase();
        String content = file.content() == null ? "" : file.content();
        int score = 0;
        for (String probe : probes) {
            if (path.contains(probe)) {
                score += 3;
            }
            if (content.contains(probe)) {
                score += 1;
            }
        }
        return score;
    }

    /**
     * 把需求文本切成用于匹配的片段：中文取相邻两字组合，英文数字取单词。
     * 这是一个轻量的关键词探针，不引入分词库也不做向量检索——文件数不多时规则匹配就够了。
     */
    private Set<String> shingles(String text) {
        Set<String> result = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String cleaned = text.replaceAll("[\\s，。、；：！？,.;:!?\"'（）()【】\\[\\]]+", " ");
        for (String part : cleaned.split(" ")) {
            if (part.isEmpty()) {
                continue;
            }
            boolean cjk = part.chars().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
            if (cjk) {
                for (int i = 0; i + 2 <= part.length(); i++) {
                    result.add(part.substring(i, i + 2));
                }
            } else if (part.length() >= 2) {
                result.add(part.toLowerCase());
            }
        }
        return result;
    }

    private record Scored(ProjectFile file, int score) {
    }
}
