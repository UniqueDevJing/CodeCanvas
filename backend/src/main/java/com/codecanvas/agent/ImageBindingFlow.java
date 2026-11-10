package com.codecanvas.agent;

import com.codecanvas.agent.tool.AgentWorkspace;
import com.codecanvas.agent.tool.ToolRegistry;
import com.codecanvas.agent.tool.VisualGatewayClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配图绑定流（T1/C1 从 AgentOrchestrator 拆出的组件，W2.5/W2.6）。
 * 模型只声明「这里需要一张图」（data-slot），是否真出图由运行时决定：
 * 网关可用就换成产物 URL，不可用就换成渐变占位图——页面永远可渲染。
 * 配图预算优先于「多一张图」：宁可降级成占位图，也不让一次生成把额度烧穿。
 */
@Component
public class ImageBindingFlow {

    /** 视觉能力网关客户端：为 null 或未启用时，配图槽位直接降级为占位图。 */
    @Autowired(required = false)
    private VisualGatewayClient visualClient;

    /** 配图预算（W2.6）：每次运行的花费上限，超了就把剩余槽位降级为占位图。 */
    private final ImageBudget imageBudget = ImageBudget.fromEnv();

    public boolean enabled() {
        return visualClient != null && visualClient.enabled();
    }

    public void bind(AgentWorkspace workspace, ToolRegistry tools, AgentListener listener) {
        if (!enabled()) {
            return;
        }
        ImageSlotBinder.Result bound = ImageSlotBinder.bind(workspace.read(GenerationFlow.MAIN_FILE),
                (slot, prompt, aspect) -> {
                    if (!imageBudget.tryReserve()) {
                        listener.onStatus(imageBudget.summary() + "，剩余槽位降级为占位图");
                        return VisualGatewayClient.Result.degraded("image-budget-exhausted");
                    }
                    try {
                        VisualGatewayClient.Result result =
                                visualClient.generate(prompt, aspect, slot, null);
                        imageBudget.settle(result.degraded() ? 0.0 : result.estimatedUsd());
                        return result;
                    } catch (AgentOrchestrator.CancelledException cancelled) {
                        throw cancelled;
                    } catch (Exception e) {
                        imageBudget.settle(0.0);
                        return VisualGatewayClient.Result.degraded(
                                "client-error:" + e.getClass().getSimpleName());
                    }
                });
        if (bound.changed()) {
            Map<String, Object> reWrite = new LinkedHashMap<>();
            reWrite.put("path", GenerationFlow.MAIN_FILE);
            reWrite.put("content", bound.html());
            tools.call("write_file", reWrite);
            listener.onFile(GenerationFlow.MAIN_FILE, "update",
                    GenerationFlow.byteLengthOf(bound.html()));
            for (ImageSlotBinder.Bound image : bound.bound()) {
                listener.onImage(image.assetId(), image.url(), image.slot(),
                        image.width(), image.height(), image.costUsd());
            }
            long generated = bound.bound().stream().filter(b -> !b.degraded()).count();
            listener.onStatus("配图 " + generated + "/" + bound.bound().size()
                    + " 张（失败处已用渐变占位）；" + imageBudget.summary());
        }
    }
}
