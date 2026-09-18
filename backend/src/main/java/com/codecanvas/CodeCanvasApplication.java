package com.codecanvas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * CodeCanvas 启动入口。
 *
 * <p>平台职责：用户用一句自然语言描述需求，后端 Agent 完成
 * 需求结构化 → 任务规划 → 代码生成 → 校验 → 自检修复 → 版本快照，
 * 前端通过 SSE 实时看到生成过程并在沙箱 iframe 中预览结果。
 */
@SpringBootApplication
public class CodeCanvasApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeCanvasApplication.class, args);
    }
}
