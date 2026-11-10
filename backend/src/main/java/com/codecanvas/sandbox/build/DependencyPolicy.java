package com.codecanvas.sandbox.build;

import com.codecanvas.common.JsonText;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * 依赖白名单校验（C4）。
 *
 * <p>package.json 里的每一项都必须通过两道检查：
 * ① 包名在允许列表；② 版本声明不含逃逸向量（file://、git+、http(s) 直链、workspace: 之外的本地路径）。
 * 任何一条不过，整个构建任务直接拒绝——依赖白名单是默认禁网的第一道闸。
 */
public final class DependencyPolicy {

    private DependencyPolicy() {
    }

    /** 校验结果：ok=false 时 violations 说明每条违规原因。 */
    public record CheckResult(boolean ok, List<String> violations, Set<String> dependencies) {
    }

    public static CheckResult check(String packageJsonText, BuildPolicy policy) {
        RawPackage raw = JsonText.extract(packageJsonText, RawPackage.class);
        if (raw == null) {
            return new CheckResult(false,
                    List.of("package.json 无法解析（拒绝构建而非猜测依赖）"), Set.of());
        }
        Set<String> all = new LinkedHashSet<>();
        List<String> violations = new ArrayList<>();
        collect(raw.dependencies(), all, "dependencies", violations, policy);
        collect(raw.devDependencies(), all, "devDependencies", violations, policy);
        // 白名单检查只针对 dependencies（构建产物需要的运行时依赖）；
        // devDependencies（如 vite 构建工具）也必须全部在允许列表——恶意脚本最常用的就是伪装成构建工具
        if (violations.isEmpty()) {
            return new CheckResult(true, List.of(), all);
        }
        return new CheckResult(false, List.copyOf(violations), all);
    }

    private static void collect(Map<String, String> section, Set<String> all,
                                String sectionName, List<String> violations, BuildPolicy policy) {
        if (section == null) {
            return;
        }
        for (Map.Entry<String, String> entry : section.entrySet()) {
            String name = entry.getKey();
            String version = entry.getValue() == null ? "" : entry.getValue();
            all.add(name);
            if (policy.getAllowedDependencies().stream().noneMatch(a ->
                    a.equals(name) || (a.endsWith("/*") && name.startsWith(a.substring(0, a.length() - 1))))) {
                violations.add("[" + sectionName + "] 依赖 " + name + " 不在允许列表");
            }
            String escape = escapeVector(version);
            if (escape != null) {
                violations.add("[" + sectionName + "] " + name
                        + " 的版本声明包含逃逸向量（" + escape + "）: " + version);
            }
        }
    }

    /** 版本声明逃逸向量识别：本地路径 / 远程直链 / git 源。 */
    static String escapeVector(String version) {
        if (version == null) {
            return null;
        }
        String v = version.trim().toLowerCase();
        if (v.startsWith("file:")) {
            return "file:";
        }
        if (v.startsWith("git+") || v.startsWith("git://") || v.startsWith("git@")) {
            return "git";
        }
        if (v.startsWith("http://") || v.startsWith("https://") || v.startsWith("//")) {
            return "http 直链";
        }
        if (v.startsWith(".") || v.startsWith("/") || v.startsWith("/")) {
            return "本地路径";
        }
        if (v.startsWith("link:")) {
            return "link:";
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawPackage(Map<String, String> dependencies, Map<String, String> devDependencies) {
    }
}
