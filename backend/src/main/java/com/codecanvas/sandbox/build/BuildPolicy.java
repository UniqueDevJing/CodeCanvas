package com.codecanvas.sandbox.build;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 受限构建沙箱策略（C4）。
 *
 * <p>默认禁网、依赖白名单、各阶段硬超时、输出体积上限——全部收敛在这一个配置点，
 * 并在 /api/config 自述里如实暴露当前隔离级别。
 */
@Component
@ConfigurationProperties(prefix = "codecanvas.sandbox.build")
public class BuildPolicy {

    /** 是否启用构建沙箱能力（默认关闭：不启用时平台拒绝构建类请求，而不是裸跑） */
    private boolean enabled = false;

    /**
     * 网络策略：false = 默认禁网（非容器模式通过 npm 离线标志 + 白名单近似隔离）；
     * true 仅在容器模式下真正放行受控 registry。
     */
    private boolean networkEnabled = false;

    /** 依赖白名单：构建任务 package.json 里出现的包必须全部在此列表 */
    private List<String> allowedDependencies = new ArrayList<>(List.of(
            "react", "react-dom", "babel-standalone", "@babel/standalone"));

    /** 依赖安装硬超时（秒） */
    private int installTimeoutSeconds = 300;

    /** 构建硬超时（秒） */
    private int buildTimeoutSeconds = 180;

    /** 单次进程输出的体积上限（字节），超出截断并终止 */
    private long maxOutputBytes = 1024L * 1024;

    /** 工作区文件数上限 */
    private int maxFiles = 200;

    /** 工作区总字节数上限 */
    private long maxTotalBytes = 5L * 1024 * 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isNetworkEnabled() {
        return networkEnabled;
    }

    public void setNetworkEnabled(boolean networkEnabled) {
        this.networkEnabled = networkEnabled;
    }

    public List<String> getAllowedDependencies() {
        return allowedDependencies;
    }

    public void setAllowedDependencies(List<String> allowedDependencies) {
        this.allowedDependencies = allowedDependencies;
    }

    public int getInstallTimeoutSeconds() {
        return installTimeoutSeconds;
    }

    public void setInstallTimeoutSeconds(int installTimeoutSeconds) {
        this.installTimeoutSeconds = installTimeoutSeconds;
    }

    public int getBuildTimeoutSeconds() {
        return buildTimeoutSeconds;
    }

    public void setBuildTimeoutSeconds(int buildTimeoutSeconds) {
        this.buildTimeoutSeconds = buildTimeoutSeconds;
    }

    public long getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public void setMaxOutputBytes(long maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    public int getMaxFiles() {
        return maxFiles;
    }

    public void setMaxFiles(int maxFiles) {
        this.maxFiles = maxFiles;
    }

    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public void setMaxTotalBytes(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }
}
