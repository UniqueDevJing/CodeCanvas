package com.codecanvas.security;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C7 组合式认证拦截器测试：会话优先、口令兼容、fail-closed 不变。
 */
class SessionAuthInterceptorTest {

    private final UserAuthService auth = new UserAuthService("alice:pw");

    private SessionAuthInterceptor interceptor(String apiKey) {
        SessionAuthInterceptor i = new SessionAuthInterceptor(auth);
        // @Value 字段在单测里手动注入
        org.springframework.test.util.ReflectionTestUtils.setField(i, "expectedApiKey", apiKey);
        return i;
    }

    private String token() {
        return auth.login("alice", "pw").orElseThrow();
    }

    private MockHttpServletResponse pass(SessionAuthInterceptor i, MockHttpServletRequest req)
            throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean ok = i.preHandle(req, res, new Object());
        assertTrue(ok == (res.getStatus() == 200), "放行与否应与状态一致");
        return res;
    }

    @Test
    void validSessionTokenPasses() throws Exception {
        SessionAuthInterceptor i = interceptor("");
        String token = token();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/projects");
        req.addHeader("X-Session-Token", token);
        MockHttpServletResponse res = pass(i, req);
        assertEquals(200, res.getStatus());
        assertEquals("alice", SessionAuthInterceptor.currentUser());
    }

    @Test
    void apiKeyStillPassesAsAnonymous() throws Exception {
        SessionAuthInterceptor i = interceptor("shared-key");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/projects");
        req.addHeader("X-API-Key", "shared-key");
        pass(i, req);
        assertNull(SessionAuthInterceptor.currentUser(), "共享口令模式没有用户身份");
    }

    @Test
    void wrongApiKeyRejected() throws Exception {
        SessionAuthInterceptor i = interceptor("shared-key");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/projects");
        req.addHeader("X-API-Key", "wrong");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(i.preHandle(req, res, new Object()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
    }

    @Test
    void nothingProvidedRejectedFailClosed() throws Exception {
        SessionAuthInterceptor i = interceptor("shared-key");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/projects");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(i.preHandle(req, res, new Object()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
    }

    @Test
    void noApiKeyConfiguredAndNoSessionIs503() throws Exception {
        SessionAuthInterceptor i = interceptor("");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/projects");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(i.preHandle(req, res, new Object()));
        assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, res.getStatus());
    }

    @Test
    void forgedTokenFallsThroughToApiKeyCheck() throws Exception {
        SessionAuthInterceptor i = interceptor("shared-key");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/projects");
        req.addHeader("X-Session-Token", "forged");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertFalse(i.preHandle(req, res, new Object()));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, res.getStatus());
        assertNull(SessionAuthInterceptor.currentUser());
    }

    @Test
    void afterCompletionClearsThreadLocal() throws Exception {
        SessionAuthInterceptor i = interceptor("");
        String token = token();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/projects");
        req.addHeader("X-Session-Token", token);
        pass(i, req);
        i.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);
        assertNull(SessionAuthInterceptor.currentUser(), "线程池复用前必须清身份");
    }
}
