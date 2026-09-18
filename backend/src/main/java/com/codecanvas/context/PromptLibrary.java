package com.codecanvas.context;

import com.codecanvas.project.ProjectFile;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 提示词组装。
 *
 * <p>所有提示词都在这里集中管理，好处是：改提示词不用翻遍业务代码，且每个阶段的输入形状是固定的。
 * 提示词里用【】标记分节，编排层与离线引擎都按这些标记取内容，避免靠自然语言猜结构。
 */
public final class PromptLibrary {

    private PromptLibrary() {
    }

    // ==================== 系统提示词 ====================

    public static final String PRD_SYSTEM = """
            你是一名需求分析师。用户会用一句话描述他想要的软件。
            请把它整理成结构化需求文档，只输出以下字段，不要输出任何解释性文字：
            目标 / 目标用户 / 核心能力 / 数据保存 / 视觉风格 / 交付形态 / 验收要点
            要求：判断要具体，不要写"界面友好"这类空话；篇幅控制在 200 字内。
            """;

    public static final String CODE_SYSTEM = """
            你是一名资深前端工程师。请根据结构化需求，生成一个完整可运行的单文件网页应用。

            硬性约束：
            1. 只输出 HTML 代码本身，不要 Markdown 代码围栏，不要任何解释文字。
            2. CSS 和 JavaScript 全部内联在同一个文件里，不引用任何外部资源（不要 CDN、不要外链字体和图片）。
            3. 界面语言用简体中文，视觉风格简洁克制：浅色卡片式布局，单一强调色，信息密度适中。
            4. 必须响应式，手机宽度下不出现横向滚动。
            5. 主题色统一定义为 CSS 变量 --accent，方便后续调整。
            6. 需要本地持久化数据时，必须对存储不可用的情况做降级处理：
               优先使用 localStorage，若被浏览器安全策略禁止则退回内存存储，保证功能不中断。
            7. 代码必须是完整的，不允许出现"此处省略""其他功能类似"这类占位。
            """;

    public static final String MODIFY_SYSTEM = """
            你是一名资深前端工程师，正在对已有的单文件网页应用做增量修改。

            硬性约束：
            1. 只输出修改后的完整 HTML 代码本身，不要 Markdown 代码围栏，不要解释文字。
            2. 必须保留已有功能，只改动与本次需求相关的部分，不要顺手重写页面结构。
            3. 如果不确定改动范围，选择改动更小的那个方案。
            4. 代码必须完整，不允许出现占位或省略。
            """;

    public static final String FIX_SYSTEM = """
            你是一名资深前端工程师，正在修复一段报错的网页代码。

            硬性约束：
            1. 只输出修复后的完整 HTML 代码，不要 Markdown 代码围栏，不要解释文字。
            2. 做最小必要修改，只修掉报错相关的问题，不要重写页面。
            3. 如果报错是浏览器安全策略导致的（例如沙箱内 localStorage 不可用），
               请加入降级方案让功能在受限环境下也能工作。
            4. 代码必须完整，不允许出现占位或省略。
            """;

    // ==================== 用户提示词 ====================

    public static String prdUserPrompt(String requirement) {
        return """
                【原始需求】
                %s
                """.formatted(requirement);
    }

    public static String codeUserPrompt(String prd, String requirement) {
        return """
                【结构化需求】
                %s

                【原始需求】
                %s
                """.formatted(prd, requirement);
    }

    /**
     * 增量修改的提示词。
     *
     * <p>注意这里注入的是"结构化需求 + 相关文件 + 本轮修改点"，而不是把全部历史对话原样塞进去。
     * 这是长对话下模型还能记住约定的关键。
     */
    public static String modifyUserPrompt(String prd,
                                          String requirement,
                                          List<ProjectFile> files,
                                          String changeSummary,
                                          String truncationNote) {
        return """
                【结构化需求】
                %s

                【历史变更摘要】
                %s

                【本次修改需求】
                %s

                %s
                【当前代码开始】
                %s
                【当前代码结束】
                """.formatted(prd, changeSummary, requirement, truncationNote, joinFiles(files));
    }

    public static String fixUserPrompt(String prd, List<ProjectFile> files, String errorText) {
        return """
                【结构化需求】
                %s

                【错误信息】
                %s

                【当前代码开始】
                %s
                【当前代码结束】
                """.formatted(prd, errorText, joinFiles(files));
    }

    /** 多文件时按路径分节拼装，便于模型定位。 */
    private static String joinFiles(List<ProjectFile> files) {
        if (files == null || files.isEmpty()) {
            return "";
        }
        return files.stream()
                .map(file -> files.size() == 1
                        ? file.content()
                        : "// ===== " + file.path() + " =====\n" + file.content())
                .collect(Collectors.joining("\n\n"));
    }
}
