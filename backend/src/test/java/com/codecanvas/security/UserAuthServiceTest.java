package com.codecanvas.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C7 用户会话认证测试。 */
class UserAuthServiceTest {

    private UserAuthService service() {
        return new UserAuthService("alice:pw1,bob:pw2");
    }

    @Test
    void loginSuccessReturnsToken() {
        UserAuthService svc = service();
        assertTrue(svc.enabled());
        var token = svc.login("alice", "pw1");
        assertTrue(token.isPresent());
        assertEquals("alice", svc.resolve(token.get()).orElseThrow());
    }

    @Test
    void wrongPasswordRejected() {
        UserAuthService svc = service();
        assertTrue(svc.login("alice", "wrong").isEmpty());
        assertTrue(svc.login("nobody", "pw1").isEmpty());
        assertTrue(svc.login(null, "pw1").isEmpty());
        assertTrue(svc.login("alice", null).isEmpty());
    }

    @Test
    void disabledWhenNoUsersConfigured() {
        UserAuthService svc = new UserAuthService("");
        assertFalse(svc.enabled());
        assertTrue(svc.login("alice", "pw").isEmpty(), "未配置用户时不得假装登录成功");
    }

    @Test
    void invalidTokenNotResolved() {
        UserAuthService svc = service();
        assertTrue(svc.resolve("forged-token").isEmpty());
        assertTrue(svc.resolve(null).isEmpty());
        assertTrue(svc.resolve("").isEmpty());
    }

    @Test
    void logoutInvalidatesToken() {
        UserAuthService svc = service();
        String token = svc.login("bob", "pw2").orElseThrow();
        svc.logout(token);
        assertTrue(svc.resolve(token).isEmpty());
        svc.logout(token); // 幂等
        assertTrue(svc.resolve(token).isEmpty());
    }

    @Test
    void tokensAreDistinctPerLogin() {
        UserAuthService svc = service();
        String t1 = svc.login("alice", "pw1").orElseThrow();
        String t2 = svc.login("alice", "pw1").orElseThrow();
        assertTrue(!t1.equals(t2), "每次登录应发独立 token");
        assertEquals("alice", svc.resolve(t2).orElseThrow());
    }
}
