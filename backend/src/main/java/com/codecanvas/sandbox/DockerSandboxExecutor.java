package com.codecanvas.sandbox;

import org.springframework.stereotype.Component;

/**
 * 容器级隔离（预留实现）。
 *
 * <p>刻意做成"可用但当前不启用"，而不是先写一堆用不上的容器编排代码。
 * 触发它投入使用的条件很明确，满足任一条再实现：
 * <ol>
 *   <li>生成的项目需要安装 npm 依赖、需要跑构建（iframe 里做不到）</li>
 *   <li>平台要对外开放，需要执行完全陌生的代码（共享内核的容器仍需再加 gVisor 之类加固）</li>
 *   <li>需要把生成结果部署到公网</li>
 * </ol>
 *
 * <p>到那时要落地的加固项：一次性容器、非 root 运行、默认断网、限 CPU/内存/PID、
 * 根文件系统只读、构建超时强杀、镜像白名单。这些才是"沙箱"真正的价值所在，
 * 而不是"用了 Docker"这四个字。
 */
@Component
public class DockerSandboxExecutor implements SandboxExecutor {

    @Override
    public String id() {
        return "docker-sandbox";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String unavailableReason() {
        return "当前生成物是单文件页面、无需构建，用 iframe 隔离已经足够；"
                + "引入容器属于过度设计。等接入依赖安装与构建流程时再启用（需 Docker 环境与镜像白名单）。";
    }

    @Override
    public SandboxedPreview prepare(String html, String projectId, String versionId) {
        throw new UnsupportedOperationException("容器沙箱尚未启用：" + unavailableReason());
    }
}
