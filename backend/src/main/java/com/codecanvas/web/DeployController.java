package com.codecanvas.web;

import com.codecanvas.project.Project;
import com.codecanvas.project.ProjectService;
import com.codecanvas.project.VersionSnapshot;
import com.codecanvas.web.dto.ProjectDtos;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发布与公开访问。
 *
 * <p>「发布」把项目变成一个固定链接，任何人拿到链接就能打开——打开看到的是<b>应用本身</b>，
 * 而不是平台界面。这是"做出来的东西能被别人看到"的关键一步。
 *
 * <p>两个刻意的设计：
 * <ol>
 *   <li><b>密钥幂等</b>：重复发布沿用同一个密钥，避免把已经发出去的链接作废</li>
 *   <li><b>发布的永远是当前版本</b>：改了需求重新发布，链接不变、内容更新</li>
 * </ol>
 */
@RestController
public class DeployController {

    private final ProjectService projectService;

    public DeployController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** 发布项目（幂等）。 */
    @PostMapping("/api/projects/{id}/deploy")
    public ResponseEntity<ProjectDtos.DeployInfo> deploy(@PathVariable String id) {
        try {
            Project project = projectService.deploy(id);
            return ResponseEntity.ok(new ProjectDtos.DeployInfo(
                    project.id(), project.deployed(), project.deployKey(), project.deployedAt()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * 作品广场：所有已发布作品。
     *
     * <p>不返回文件内容（可能很大），只给列表展示需要的字段；点进去看详情直接走发布地址。
     */
    @GetMapping("/api/gallery")
    public ProjectDtos.GalleryResponse gallery() {
        List<ProjectDtos.GalleryItem> items = projectService.gallery().stream()
                .map(project -> new ProjectDtos.GalleryItem(
                        project.id(),
                        project.name(),
                        project.prompt(),
                        project.deployKey(),
                        latestSummary(project),
                        project.deployedAt(),
                        project.updatedAt()))
                .toList();
        return new ProjectDtos.GalleryResponse(items);
    }

    private String latestSummary(Project project) {
        List<VersionSnapshot> versions = project.versions();
        if (versions == null || versions.isEmpty()) {
            return "";
        }
        String summary = versions.get(versions.size() - 1).summary();
        return summary == null ? "" : summary;
    }

    /**
     * 公开发布页：直接返回生成的应用本体。
     *
     * <p>不带平台外壳、不需要登录。路径短（/a/{key}）是为了让链接好记好发。
     */
    @GetMapping(value = "/a/{key}", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> publicApp(@PathVariable String key) {
        return projectService.findByDeployKey(key)
                .filter(project -> !project.isEmpty())
                .map(project -> ResponseEntity.ok()
                        // 应用可能被反复迭代，不能让浏览器或 CDN 缓存住旧版本
                        .cacheControl(CacheControl.noStore())
                        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                        .body(project.mainFileContent()))
                .orElseGet(() -> ResponseEntity.status(404)
                        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                        .body(notFoundPage()));
    }

    private String notFoundPage() {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>页面不存在</title>
                <style>
                  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
                    background:#f6f6f4;color:#1a1a18;
                    font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}
                  .box{text-align:center;padding:40px}
                  h1{font-size:20px;font-weight:600;margin:0 0 8px}
                  p{color:#8a8a85;margin:0;font-size:14px}
                </style>
                </head>
                <body>
                  <div class="box">
                    <h1>这个应用不存在或已被删除</h1>
                    <p>请确认链接是否完整。</p>
                  </div>
                </body>
                </html>
                """;
    }
}
