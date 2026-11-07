package com.codecanvas.llm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 模型可用性登记表（启动自检结果）。
 *
 * <p>为什么需要：各家平台的模型名是**动态目录**，"配置里写了"不等于"账号上存在"。
 * 实测踩过：默认配的 {@code qwen3-coder-next} 在某个百炼账号上是 404，
 * 于是每次生成都先撞 404 再走回退链——慢、贵、还让人误以为是模型能力问题。
 *
 * <p>这三态是刻意的：{@code UNKNOWN} 表示"没探到"（无密钥/网络失败），
 * 不能当成 AVAILABLE 也不能当成 UNAVAILABLE，否则会误导排查方向。
 */
@Component
public class ModelAvailability {

    public enum Status {
        /** 探测成功：账号上确实有这个模型 */
        AVAILABLE,
        /** 探测明确失败（404 model_not_found 等） */
        UNAVAILABLE,
        /** 没探到（未配置密钥、网络不通、接口不支持） */
        UNKNOWN
    }

    public record ModelProbe(String model, Status status, String detail, Instant checkedAt) {
    }

    private final Map<String, ModelProbe> probes = new ConcurrentHashMap<>();

    public void record(String model, Status status, String detail) {
        if (model == null || model.isBlank()) {
            return;
        }
        probes.put(model, new ModelProbe(model, status, detail == null ? "" : detail, Instant.now()));
    }

    public ModelProbe get(String model) {
        ModelProbe probe = probes.get(model);
        return probe == null
                ? new ModelProbe(model, Status.UNKNOWN, "未探测（可能未配置密钥）", Instant.now())
                : probe;
    }

    public List<ModelProbe> all(List<String> configured) {
        Map<String, ModelProbe> ordered = new LinkedHashMap<>();
        for (String model : configured) {
            if (model != null && !model.isBlank()) {
                ordered.put(model, get(model));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    public List<String> unavailableModels(List<String> configured) {
        List<String> bad = new ArrayList<>();
        for (ModelProbe probe : all(configured)) {
            if (probe.status() == Status.UNAVAILABLE) {
                bad.add(probe.model());
            }
        }
        return bad;
    }

    public void clear() {
        probes.clear();
    }
}