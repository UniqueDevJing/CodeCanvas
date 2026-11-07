package com.codecanvas.project;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 同一项目的"正在生成"互斥（真实风险修复）。
 *
 * <p>为什么要在应用层拦：存储层已经能做到并发不丢版本，但**并发生成本身是错的**——
 * 两个模型同时对同一个项目改写，用户看到的最终页面是两条需求互相覆盖后的结果，
 * 既不可解释也不可复现。这里直接拒绝第二次请求（409），让用户等前一次结束。
 *
 * <p>实现用内存集合（单实例足够）；多副本部署需换成 Redis 锁，接口保持不变。
 */
@Component
public class ProjectRunLock {

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /** 尝试占用；已被占用返回 false。 */
    public boolean tryAcquire(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return true;   // 新项目没有竞争对象
        }
        return running.add(projectId);
    }

    public void release(String projectId) {
        if (projectId != null && !projectId.isBlank()) {
            running.remove(projectId);
        }
    }

    public boolean isRunning(String projectId) {
        return projectId != null && running.contains(projectId);
    }
}
