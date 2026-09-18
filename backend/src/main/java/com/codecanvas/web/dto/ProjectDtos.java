package com.codecanvas.web.dto;

import com.codecanvas.project.Project;
import com.codecanvas.project.ProjectFile;
import com.codecanvas.project.VersionSnapshot;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** 对外返回的数据结构。字段名与前端约定一致，不要随意改。 */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    public record ProjectSummary(String id, String name, Instant updatedAt, int versionCount) {

        public static ProjectSummary of(Project project) {
            return new ProjectSummary(project.id(), project.name(), project.updatedAt(),
                    project.versions() == null ? 0 : project.versions().size());
        }
    }

    public record ProjectList(List<ProjectSummary> projects) {
    }

    public record VersionItem(String id, String label, Instant createdAt, String summary, boolean active,
                              int healAttempts, double cost, String model, String tierLabel) {

        public static VersionItem of(VersionSnapshot snapshot, String currentVersionId) {
            return new VersionItem(snapshot.id(), snapshot.label(), snapshot.createdAt(), snapshot.summary(),
                    snapshot.id().equals(currentVersionId), snapshot.healAttempts(), snapshot.cost(),
                    snapshot.model(), snapshot.tierLabel());
        }
    }

    public record ProjectDetail(
            String id,
            String name,
            String prompt,
            String prd,
            List<ProjectFile> files,
            List<VersionItem> versions,
            String previewHtml,
            boolean deployed,
            String deployKey,
            Instant deployedAt) {
    }

    /**
     * 发布结果。
     *
     * <p>只返回密钥、不返回完整 URL：后端并不知道自己被部署在哪个域名或子路径下，
     * 硬拼一个地址迟早会错（换域名、加 HTTPS、改前缀都会让它失效）。
     * 由前端用当前访问地址推导出来的链接才是永远正确的。
     */
    public record DeployInfo(String projectId, boolean deployed, String deployKey, Instant deployedAt) {
    }

    /** 作品广场里的一个作品。 */
    public record GalleryItem(
            String id,
            String name,
            String prompt,
            String deployKey,
            String summary,
            Instant deployedAt,
            Instant updatedAt) {
    }

    public record GalleryResponse(List<GalleryItem> items) {
    }

    public record RenameRequest(String name) {
    }

    /** 平台能力与运行方式的自述，界面上如实展示，不夸大。 */
    public record PlatformConfig(
            String llmMode,
            String provider,
            String modeLabel,
            String notes,
            Models models,
            String sandbox,
            String sandboxNote,
            String containerSandboxReason,
            String executor,
            boolean virtualThreads,
            String storage,
            String repository) {
    }

    /** 键名保持大写，与前端约定的档位枚举一致。 */
    public record Models(
            @JsonProperty("FLAGSHIP") String flagship,
            @JsonProperty("STANDARD") String standard,
            @JsonProperty("LIGHT") String light) {
    }
}
