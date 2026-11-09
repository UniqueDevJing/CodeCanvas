package com.codecanvas.sandbox;

import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 沙箱后端选择器（P1-4）：业务层只依赖本类，不再硬绑某一个具体实现。
 *
 * <p>选择规则：按 {@link SandboxExecutor#priority()} 升序，取第一个
 * {@link SandboxExecutor#available()} 为 true 的后端。priority 表达"隔离能力优先级"：
 * 容器级隔离若哪天真正实现并可用，会自动排在 iframe 之前，业务代码一行都不用改。
 *
 * <p><b>诚实性</b>：不可用的后端不会从候选里消失，而是连同原因一起通过
 * {@link #backends()} 暴露给平台自述接口——"我没用上 Docker"和"我假装用了 Docker"
 * 是两回事。
 */
@Component
public class SandboxExecutorResolver {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutorResolver.class);

    private final List<SandboxExecutor> candidates;
    private final SandboxExecutor current;

    public SandboxExecutorResolver(List<SandboxExecutor> executors) {
        this.candidates = executors.stream()
                .sorted(Comparator.comparingInt(SandboxExecutor::priority))
                .toList();
        this.current = candidates.stream()
                .filter(SandboxExecutor::available)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "没有可用的沙箱后端，拒绝以无隔离方式预览生成代码"));
    }

    @PostConstruct
    public void logSelection() {
        log.info("沙箱后端已选定：{}（候选 {} 个，未启用 {} 个）",
                current.id(), candidates.size(), unavailableCount());
    }

    public String id() {
        return current.id();
    }

    public SandboxedPreview prepare(String html, String projectId, String versionId) {
        return current.prepare(html, projectId, versionId);
    }

    /** 给 /api/config 之类的自述端点用：全部后端 + 是否可用 + 不可用原因。 */
    public List<Map<String, Object>> backends() {
        return candidates.stream().map(e -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.id());
            row.put("priority", e.priority());
            row.put("available", e.available());
            row.put("active", e == current);
            row.put("reason", e.unavailableReason() == null ? "" : e.unavailableReason());
            return row;
        }).toList();
    }

    public String dockerReason() {
        return candidates.stream()
                .filter(e -> "docker-sandbox".equals(e.id()))
                .map(SandboxExecutor::unavailableReason)
                .findFirst()
                .orElse("未注册容器沙箱后端");
    }

    private long unavailableCount() {
        return candidates.stream().filter(e -> !e.available()).count();
    }
}
