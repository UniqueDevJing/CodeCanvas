package com.codecanvas.project.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C3 项目规划解析测试。 */
class ProjectPlanTest {

    @Test
    void parseHtmlPlan() {
        String json = """
                {"appName":"待办清单","summary":"一个待办事项管理页面","tech":"html","entry":"index.html",
                 "files":[
                   {"path":"index.html","purpose":"页面结构与入口"},
                   {"path":"styles.css","purpose":"全部样式"},
                   {"path":"app.js","purpose":"业务逻辑"}
                 ]}
                """;
        ProjectPlan plan = ProjectPlan.parse(json);
        assertEquals("待办清单", plan.appName());
        assertEquals(ProjectPlan.TECH_HTML, plan.tech());
        assertEquals("index.html", plan.entry());
        assertEquals(3, plan.files().size());
        assertTrue(plan.isGeneratable());
        assertFalse(plan.isReact());
        assertEquals(List.of("index.html", "styles.css", "app.js"), plan.plannedPaths());
    }

    @Test
    void parseReactPlan() {
        String json = """
                {"appName":"计数器","summary":"React 计数器","tech":"react","entry":"index.html",
                 "files":[
                   {"path":"index.html","purpose":"CDN 引入"},
                   {"path":"app.jsx","purpose":"组件"}
                 ]}
                """;
        ProjectPlan plan = ProjectPlan.parse(json);
        assertTrue(plan.isReact());
    }

    @Test
    void unknownTechFallsBackToHtml() {
        ProjectPlan plan = ProjectPlan.parse("{\"tech\":\"vue\",\"files\":[{\"path\":\"a.js\"}]}");
        assertEquals(ProjectPlan.TECH_HTML, plan.tech());
    }

    @Test
    void emptyEntryDefaultsToIndex() {
        ProjectPlan plan = ProjectPlan.parse("{\"tech\":\"html\",\"files\":[{\"path\":\"a.js\"}]}");
        assertEquals("index.html", plan.entry());
    }

    @Test
    void invalidJsonReturnsNull() {
        assertNull(ProjectPlan.parse(null));
        assertNull(ProjectPlan.parse("说明文字但没有 JSON"));
        assertNull(ProjectPlan.parse("{broken"));
    }

    @Test
    void emptyOrOversizedPlanRejected() {
        assertNull(ProjectPlan.parse("{\"files\":[]}"), "空文件清单应降级");
        StringBuilder files = new StringBuilder("[");
        for (int i = 0; i < 9; i++) {
            files.append(i > 0 ? "," : "").append("{\"path\":\"f").append(i).append(".js\"}");
        }
        files.append("]");
        assertNull(ProjectPlan.parse("{\"files\":" + files + "}"), "超过 8 个文件应降级");
    }

    @Test
    void illegalPathsFiltered() {
        String json = "{\"files\":[{\"path\":\"../x.js\",\"purpose\":\"bad\"},{\"path\":\"ok.js\",\"purpose\":\"good\"}]}";
        ProjectPlan plan = ProjectPlan.parse(json);
        assertEquals(1, plan.files().size());
        assertEquals("ok.js", plan.files().get(0).path());
    }
}
