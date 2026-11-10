package com.codecanvas.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户会话认证（C7）：登录 → 短期 token → 请求头身份识别。
 *
 * <p>定位：前端打包进去的 X-API-Key 是「访问口令」而非身份（浏览器可见），
 * 本服务在其上增加真实的用户身份层——共享口令不再作为唯一保护。
 *
 * <p>账号来源：{@code codecanvas.auth.users}（环境变量 CODECANVAS_AUTH_USERS），
 * 格式 {@code name:password,name2:password2}。未配置时 {@link #enabled()} 为 false，
 * 平台如实降级为共享口令模式（不假装有用户体系）。
 *
 * <p>单实例内存会话，重启即失效（重新登录即可）；多副本需外置存储（接口不变）。
 */
@Service
public class UserAuthService {

    /** 会话有效期（小时） */
    private static final int SESSION_HOURS = 8;

    private final Map<String, String> users = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private record Session(String username, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public UserAuthService(@Value("${codecanvas.auth.users:}") String config) {
        if (config != null && !config.isBlank()) {
            for (String pair : config.split(",")) {
                int idx = pair.indexOf(':');
                if (idx > 0) {
                    users.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
                }
            }
        }
    }

    /** 用户体系是否已配置。未配置时登录端点如实返回未启用，而不是假装登录成功。 */
    public boolean enabled() {
        return !users.isEmpty();
    }

    /** 登录成功返回新 token；失败返回 empty（不区分用户名错/密码错，避免枚举）。 */
    public Optional<String> login(String username, String password) {
        if (username == null || password == null || !enabled()) {
            return Optional.empty();
        }
        String expected = users.get(username.trim());
        if (expected == null || !expected.equals(password.trim())) {
            return Optional.empty();
        }
        purgeExpired();
        String token = newToken();
        sessions.put(token, new Session(username.trim(), Instant.now().plusSeconds(SESSION_HOURS * 3600L)));
        return Optional.of(token);
    }

    /** 从请求头解析登录身份；token 无效或过期返回 empty。 */
    public Optional<String> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expired()) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.username());
    }

    /** 登出；token 立即失效（幂等）。 */
    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    private void purgeExpired() {
        sessions.entrySet().removeIf(e -> e.getValue().expired());
    }

    /** token 生成：UUID + 时间戳摘要，不带任何可猜测语义。 */
    private static String newToken() {
        String raw = UUID.randomUUID() + ":" + System.nanoTime();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }
}
