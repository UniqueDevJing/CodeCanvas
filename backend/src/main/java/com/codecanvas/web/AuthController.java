package com.codecanvas.web;

import com.codecanvas.security.SessionAuthInterceptor;
import com.codecanvas.security.UserAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 登录端点（C7）。共享口令（X-API-Key）之外的真实身份入口。
 *
 * <p>未配置用户列表（codecanvas.auth.users 为空）时，登录如实返回 503
 * 「用户认证未启用」，而不是假装登录成功——降级也要诚实。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserAuthService userAuth;
    private final UserQuota userQuota;

    public AuthController(UserAuthService userAuth, UserQuota userQuota) {
        this.userAuth = userAuth;
        this.userQuota = userQuota;
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@RequestBody LoginRequest request) {
        if (!userAuth.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "用户认证未启用：服务端未配置 codecanvas.auth.users"));
        }
        return userAuth.login(request.username(), request.password())
                .<ResponseEntity<Object>>map(token -> ResponseEntity.ok(Map.of(
                        "token", token,
                        "username", request.username(),
                        "expiresInHours", 8,
                        "dailyMax", userQuota.dailyMax(),
                        "costCeiling", userQuota.costCeiling())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "用户名或密码错误")));
    }

    @PostMapping("/logout")
    public ResponseEntity<Object> logout(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        userAuth.logout(token);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<Object> me(@RequestHeader(value = "X-Session-Token", required = false) String token) {
        return userAuth.resolve(token)
                .<ResponseEntity<Object>>map(user -> ResponseEntity.ok(Map.of(
                        "username", user,
                        "chatsUsed", userQuota.chatsUsed(user),
                        "costUsed", userQuota.costUsed(user),
                        "costCeiling", userQuota.costCeiling(),
                        "dailyMax", userQuota.dailyMax())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "会话无效或已过期")));
    }

    /** 给拦截器/前端调试用：当前请求（按共享口令放行时）身份为空。 */
    @GetMapping("/whoami")
    public ResponseEntity<Object> whoami() {
        return ResponseEntity.ok(Map.of("user", SessionAuthInterceptor.currentUser()));
    }
}
