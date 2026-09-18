package com.codecanvas.project;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 项目仓储抽象。
 *
 * <p>当前用文件实现（零依赖、随时可跑、便于观察数据结构）。按技术选型要接 MySQL 时，
 * 只需要再写一个实现类，业务层不用动——这也是"抽象放在需要变化的地方"的一个具体例子。
 */
public interface ProjectRepository {

    Optional<Project> findById(String id);

    List<Project> findAll();

    Project save(Project project);

    default List<Project> findAllSorted() {
        return findAll().stream()
                .sorted(Comparator.comparing(Project::updatedAt).reversed())
                .toList();
    }

    /**
     * 按部署密钥找项目（公开发布的页面靠它定位）。
     *
     * <p>当前实现是内存里线性查找：项目量级在几百以内没问题。
     * 如果发布的项目规模上去，这里应该换成"部署密钥 → 项目 ID"的索引表。
     */
    default Optional<Project> findByDeployKey(String deployKey) {
        if (deployKey == null || deployKey.isBlank()) {
            return Optional.empty();
        }
        return findAll().stream()
                .filter(project -> deployKey.equals(project.deployKey()))
                .findFirst();
    }
}
