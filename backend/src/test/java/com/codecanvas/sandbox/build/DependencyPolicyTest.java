package com.codecanvas.sandbox.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C4 依赖白名单校验测试。 */
class DependencyPolicyTest {

    private BuildPolicy policy() {
        BuildPolicy p = new BuildPolicy();
        p.setAllowedDependencies(List.of("react", "react-dom", "@babel/*"));
        return p;
    }

    @Test
    void whitelistedDependenciesPass() {
        String pkg = "{\"dependencies\":{\"react\":\"^18.0.0\",\"react-dom\":\"^18.0.0\"}}";
        DependencyPolicy.CheckResult r = DependencyPolicy.check(pkg, policy());
        assertTrue(r.ok());
        assertEquals(2, r.dependencies().size());
    }

    @Test
    void wildcardScopePrefixMatches() {
        String pkg = "{\"dependencies\":{\"@babel/standalone\":\"^7.0.0\"}}";
        assertTrue(DependencyPolicy.check(pkg, policy()).ok());
    }

    @Test
    void nonWhitelistedRejected() {
        String pkg = "{\"dependencies\":{\"left-pad\":\"^1.0.0\"}}";
        DependencyPolicy.CheckResult r = DependencyPolicy.check(pkg, policy());
        assertFalse(r.ok());
        assertTrue(r.violations().get(0).contains("不在允许列表"));
    }

    @Test
    void devDependenciesAlsoChecked() {
        String pkg = "{\"dependencies\":{\"react\":\"^18\"},\"devDependencies\":{\"evil-tool\":\"^1\"}}";
        DependencyPolicy.CheckResult r = DependencyPolicy.check(pkg, policy());
        assertFalse(r.ok());
        assertTrue(r.violations().stream().anyMatch(v -> v.contains("devDependencies")));
    }

    @Test
    void escapeVectorsRejected() {
        String[] versions = {"file:../local", "git+https://github.com/x/y",
                "git://host/repo", "https://evil.example/x.tgz", "./local", "link:../x"};
        for (String v : versions) {
            String pkg = "{\"dependencies\":{\"react\":\"" + v + "\"}}";
            DependencyPolicy.CheckResult r = DependencyPolicy.check(pkg, policy());
            assertFalse(r.ok(), "版本 " + v + " 应被拒绝");
            assertTrue(r.violations().stream().anyMatch(s -> s.contains("逃逸向量")));
        }
    }

    @Test
    void normalSemverAllowed() {
        String pkg = "{\"dependencies\":{\"react\":\"^18.2.0\",\"react-dom\":\"~18.2.1\",\"react\":\"1.2.3\"}}";
        // 重复 key 后者覆盖前者（JSON 语义），保持通过
        assertTrue(DependencyPolicy.check(pkg, policy()).ok());
    }

    @Test
    void unparseablePackageJsonRejected() {
        DependencyPolicy.CheckResult r = DependencyPolicy.check("{broken", policy());
        assertFalse(r.ok());
        assertTrue(r.violations().get(0).contains("无法解析"));
    }

    @Test
    void offlineInstallCommandHasOfflineFlags() {
        assertTrue(BuildSandboxService.NPM_INSTALL_OFFLINE.contains("--prefer-offline"));
        assertTrue(BuildSandboxService.NPM_INSTALL_OFFLINE.contains("--no-audit"));
    }
}
