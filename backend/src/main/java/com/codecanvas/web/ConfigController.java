package com.codecanvas.web;

import com.codecanvas.config.CodeCanvasConfig;
import com.codecanvas.config.LlmProperties;
import com.codecanvas.llm.LlmGateway;
import com.codecanvas.llm.ModelAvailability;
import com.codecanvas.llm.ModelAvailabilityProbe;
import com.codecanvas.sandbox.SandboxExecutorResolver;
import com.codecanvas.web.dto.ProjectDtos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台自述接口。
 *
 * <p>把"当前用的是真实模型还是离线引擎""沙箱是哪种隔离级别""跑在虚拟线程还是平台线程池"
 * 如实告诉前端。产品界面敢把这些写出来，比含糊地说"由 AI 驱动"更可信。
 *
 * <p>P1-4：沙箱信息改由 {@link SandboxExecutorResolver} 提供——报告的是<b>实际选中的后端</b>，
 * 而不是硬编码 iframe；未启用的容器后端也会带上真实的探测结论与原因。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final LlmProperties properties;
    private final LlmGateway gateway;
    private final SandboxExecutorResolver sandboxResolver;
    private final String storageDir;
    private final ModelAvailability modelAvailability;
    private final ModelAvailabilityProbe modelProbe;

    public ConfigController(LlmProperties properties,
                            LlmGateway gateway,
                            SandboxExecutorResolver sandboxResolver,
                            @Value("${codecanvas.storage.dir:./codecanvas-data}") String storageDir,
                            ModelAvailability modelAvailability,
                            ModelAvailabilityProbe modelProbe) {
        this.properties = properties;
        this.gateway = gateway;
        this.sandboxResolver = sandboxResolver;
        this.storageDir = storageDir;
        this.modelAvailability = modelAvailability;
        this.modelProbe = modelProbe;
    }

    @GetMapping
    public ProjectDtos.PlatformConfig config() {
        boolean real = gateway.usingRealModel();
        return new ProjectDtos.PlatformConfig(
                real ? "REAL" : "MOCK",
                properties.getActiveProvider(),
                gateway.currentModeLabel(),
                gateway.currentModeLabel(),
                new ProjectDtos.Models(
                        properties.getModels().getFlagship(),
                        properties.getModels().getStandard(),
                        properties.getModels().getLight()),
                sandboxResolver.id(),
                "预览运行在独立源的沙箱 iframe 中，无法访问平台页面数据。"
                        + "由于隔离源限制，页面内的 localStorage 会被浏览器拒绝，"
                        + "生成代码必须做降级处理——这正是自检闭环要覆盖的真实场景。",
                sandboxResolver.dockerReason(),
                CodeCanvasConfig.virtualThreadSupported()
                        ? "虚拟线程（Java " + CodeCanvasConfig.runtimeJavaVersion() + "）"
                        : "有界平台线程池（Java " + CodeCanvasConfig.runtimeJavaVersion() + "，虚拟线程需 21+）",
                CodeCanvasConfig.virtualThreadSupported(),
                storageDir,
                "文件系统仓储（ProjectRepository 已抽象，可替换为 MySQL 实现）");
    }

    /**
     * 沙箱后端清单（P1-4）。
     *
     * <p>单独开一个端点而不塞进 /api/config：那个响应是前端依赖的固定结构（record），
     * 加字段会牵动多方构造。这里把全部候选、优先级、是否可用、不可用原因摊开，
     * 便于确认"当前到底用了哪种隔离、为什么没用容器"。
     */
    @GetMapping("/sandbox")
    public java.util.Map<String, Object> sandbox() {
        return java.util.Map.of(
                "active", sandboxResolver.id(),
                "backends", sandboxResolver.backends());
    }

    /**
     * 模型可用性（启动自检结果）。
     *
     * <p>为什么单独开一个端点而不塞进 /api/config：那个响应是前端依赖的固定结构（record），
     * 加字段会牵动多方构造；诊断信息单独暴露，前端想显示再接。
     *
     * <p>也支持 {@code ?recheck=true} 现场重探一次（换 key/换模型后不必重启）。
     */
    @GetMapping("/models")
    public java.util.Map<String, Object> models(@RequestParam(name = "recheck", defaultValue = "false") boolean recheck) {
        if (recheck) {
            modelProbe.probeAll();
        }
        var models = properties.getModels();
        var configured = java.util.List.of(models.getFlagship(), models.getStandard(), models.getLight());
        var items = modelAvailability.all(configured).stream()
                .map(p -> java.util.Map.of(
                        "model", p.model(),
                        "status", p.status().name(),
                        "detail", p.detail(),
                        "checkedAt", p.checkedAt().toString()))
                .toList();
        var unavailable = modelAvailability.unavailableModels(configured);
        return java.util.Map.of(
                "provider", properties.getActiveProvider(),
                "baseUrl", properties.getOpenai().getBaseUrl(),
                "mode", gateway.usingRealModel() ? "REAL" : "MOCK",
                "models", items,
                "unavailable", unavailable,
                "hint", unavailable.isEmpty()
                        ? "未发现不可用模型"
                        : "以下模型在账号上不存在，请改 CODECANVAS_MODEL_*：" + unavailable);
    }

}
