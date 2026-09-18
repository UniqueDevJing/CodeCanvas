package com.codecanvas.sandbox;

/**
 * 代码执行/预览的隔离抽象。
 *
 * <p>当前实现只有"浏览器侧 iframe 隔离"一种，因为它已经把 MVP 阶段的问题解决干净了：
 * 用户看到的页面与平台本身不同源，生成的脚本拿不到平台页面的 DOM、Cookie 和存储。
 *
 * <p>什么情况下才需要换实现：当生成的项目需要安装 npm 依赖、需要跑构建、或者平台要对外开放
 * 执行陌生代码时，再引入容器级隔离。到那时业务层不用改——它只依赖这个接口。
 */
public interface SandboxExecutor {

    String id();

    /** 当前环境是否具备启用条件。 */
    boolean available();

    /** 不可用时的原因说明，用于在界面上如实展示而不是假装可用。 */
    String unavailableReason();

    SandboxedPreview prepare(String html, String projectId, String versionId);
}
