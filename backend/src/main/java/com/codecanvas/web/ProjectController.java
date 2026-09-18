package com.codecanvas.web;

import com.codecanvas.project.Project;
import com.codecanvas.project.ProjectService;
import com.codecanvas.project.VersionSnapshot;
import com.codecanvas.sandbox.IframeSandboxExecutor;
import com.codecanvas.sandbox.SandboxedPreview;
import com.codecanvas.web.dto.ProjectDtos;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 项目与版本管理。 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;
    private final IframeSandboxExecutor sandbox;

    public ProjectController(ProjectService projectService, IframeSandboxExecutor sandbox) {
        this.projectService = projectService;
        this.sandbox = sandbox;
    }

    @GetMapping
    public ProjectDtos.ProjectList list() {
        List<ProjectDtos.ProjectSummary> items = projectService.list().stream()
                .map(ProjectDtos.ProjectSummary::of)
                .toList();
        return new ProjectDtos.ProjectList(items);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProjectDtos.ProjectDetail> detail(@PathVariable String id) {
        return projectService.find(id)
                .map(this::toDetail)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 版本回滚。 */
    @PostMapping("/{id}/versions/{versionId}/activate")
    public ResponseEntity<ProjectDtos.ProjectDetail> activate(@PathVariable String id,
                                                              @PathVariable String versionId) {
        return projectService.find(id)
                .map(project -> projectService.activate(id, versionId))
                .map(this::toDetail)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/rename")
    public ResponseEntity<ProjectDtos.ProjectDetail> rename(@PathVariable String id,
                                                            @RequestBody ProjectDtos.RenameRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return projectService.find(id)
                .map(project -> projectService.rename(id, request.name().trim()))
                .map(this::toDetail)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ProjectDtos.ProjectDetail toDetail(Project project) {
        List<ProjectDtos.VersionItem> versions = project.versions() == null ? List.of()
                : project.versions().stream()
                .map(snapshot -> ProjectDtos.VersionItem.of(snapshot, project.currentVersionId()))
                .toList();
        SandboxedPreview preview = project.isEmpty()
                ? new SandboxedPreview("", sandbox.id(), "")
                : sandbox.prepare(project.mainFileContent(), project.id(), project.currentVersionId());
        return new ProjectDtos.ProjectDetail(
                project.id(),
                project.name(),
                project.prompt(),
                project.prd(),
                project.files(),
                versions,
                preview.html(),
                project.deployed(),
                project.deployKey(),
                project.deployedAt());
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<ProjectDtos.VersionItem>> versions(@PathVariable String id) {
        return projectService.find(id)
                .map(project -> {
                    List<VersionSnapshot> snapshots = project.versions() == null ? List.of() : project.versions();
                    return ResponseEntity.ok(snapshots.stream()
                            .map(snapshot -> ProjectDtos.VersionItem.of(snapshot, project.currentVersionId()))
                            .toList());
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
