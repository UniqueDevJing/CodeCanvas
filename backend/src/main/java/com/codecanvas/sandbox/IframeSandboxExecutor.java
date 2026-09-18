package com.codecanvas.sandbox;

import org.springframework.stereotype.Component;

/**
 * 浏览器侧 iframe 隔离。
 *
 * <p>隔离原理：预览 iframe 使用 {@code sandbox="allow-scripts"} 且<b>不给</b> allow-same-origin，
 * 浏览器会把它当作 opaque origin 处理，于是生成的脚本无法访问平台页面的 DOM、Cookie、
 * localStorage 和 IndexedDB——这是"不引入容器也能拿到隔离"的关键。
 *
 * <p>额外收益：在页面最前面注入一个错误探针，把运行时异常通过 postMessage 回传给平台，
 * 平台据此自动触发修复。这样"生成的页面报错了"这件事从用户投诉变成了系统能力。
 */
@Component
public class IframeSandboxExecutor implements SandboxExecutor {

    private static final String MARKER = "codecanvas-runtime-probe";

    @Override
    public String id() {
        return "iframe-sandbox";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String unavailableReason() {
        return null;
    }

    @Override
    public SandboxedPreview prepare(String html, String projectId, String versionId) {
        if (html == null || html.contains(MARKER)) {
            return new SandboxedPreview(html, id(), note());
        }
        String probe = probeScript(projectId, versionId);
        return new SandboxedPreview(inject(html, probe), id(), note());
    }

    private String note() {
        return "预览运行在独立源的沙箱 iframe 中，无法访问平台页面数据；"
                + "由于隔离源限制，页面内的 localStorage 会被浏览器拒绝，生成代码需要做降级处理。";
    }

    /** 运行时错误探针。放在页面最前面，保证在业务脚本之前生效。 */
    private String probeScript(String projectId, String versionId) {
        return """
                <script id="codecanvas-runtime-probe">
                (function () {
                  var ctx = { projectId: %s, versionId: %s };
                  function report(payload) {
                    try {
                      payload.projectId = ctx.projectId;
                      payload.versionId = ctx.versionId;
                      window.parent.postMessage({ source: 'codecanvas', type: 'runtime-error', payload: payload }, '*');
                    } catch (e) { }
                  }
                  window.addEventListener('error', function (event) {
                    if (event.target && event.target.tagName && event.target.tagName !== 'SCRIPT') { return; }
                    report({
                      message: String((event && event.message) || 'Unknown error'),
                      source: (event && event.filename) || 'index.html',
                      line: (event && event.lineno) || 0,
                      stack: (event && event.error && event.error.stack) || ''
                    });
                  }, true);
                  window.addEventListener('unhandledrejection', function (event) {
                    report({
                      message: 'Unhandled promise rejection: ' + String(event && event.reason),
                      source: 'index.html',
                      line: 0,
                      stack: ''
                    });
                  });
                })();
                </script>
                """.formatted(quote(projectId), quote(versionId));
    }

    private String quote(String value) {
        return value == null ? "null" : "'" + value.replace("'", "") + "'";
    }

    /** 探针尽量插在 <head> 之后，其次 <body> 之后，都没有就整体前置。 */
    private String inject(String html, String probe) {
        String lower = html.toLowerCase();
        int head = lower.indexOf("<head");
        if (head >= 0) {
            int close = html.indexOf('>', head);
            if (close > 0) {
                return html.substring(0, close + 1) + "\n" + probe + html.substring(close + 1);
            }
        }
        int body = lower.indexOf("<body");
        if (body >= 0) {
            int close = html.indexOf('>', body);
            if (close > 0) {
                return html.substring(0, close + 1) + "\n" + probe + html.substring(close + 1);
            }
        }
        return probe + "\n" + html;
    }
}
