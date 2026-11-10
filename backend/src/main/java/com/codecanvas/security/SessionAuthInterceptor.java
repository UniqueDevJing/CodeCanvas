package com.codecanvas.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * 组合式认证拦截器（C7）：两条放行路径，fail-closed 不变。
 *
 * <ol>
 *   <li>共享口令：X-API-Key === CODECANVAS_API_KEY（原 S5 语义，向后兼容，身份=anonymous）</li>
 *   <li>用户会话：X-Session-Token 命中 {@link UserAuthService}（真实身份，享受用户级配额）</li>
 * </ol>
 * 两条都不满足 → 401。共享口令从此不再是唯一保护：即使泄露，
 * 攻击者也拿不到任何登录用户的身份与配额。
 *
 * <p>/api/auth/** 由注册方排除（登录本身不需要已持有凭证）。
 */
@Component
public class SessionAuthInterceptor implements HandlerInterceptor {

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String SESSION_HEADER = "X-Session-Token";

    private final UserAuthService userAuth;

    @Value("${codecanvas.api.key:}")
    private String expectedApiKey;

    /** 当前请求的登录身份（成功通过用户会话时非空），供下游读。 */
    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

    public SessionAuthInterceptor(UserAuthService userAuth) {
        this.userAuth = userAuth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        CURRENT_USER.remove();

        // 路径 1：用户会话（优先识别真实身份）
        String token = request.getHeader(SESSION_HEADER);
        Optional<String> user = userAuth.resolve(token);
        if (user.isPresent()) {
            CURRENT_USER.set(user.get());
            return true;
        }

        // 路径 2：共享口令（兼容原模式；fail-closed 与 S5 一致）
        if (!StringUtils.hasText(expectedApiKey)) {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "服务端未配置 API Key (CODECANVAS_API_KEY)，且请求未携带有效会话，已拒绝");
            return false;
        }
        if (!expectedApiKey.equals(request.getHeader(API_KEY_HEADER))) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(),
                    "缺少或无效的 X-API-Key / X-Session-Token");
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CURRENT_USER.remove(); // 线程池复用，防止身份串号
    }

    /** 当前登录身份；共享口令模式返回 null。 */
    public static String currentUser() {
        return CURRENT_USER.get();
    }
}
