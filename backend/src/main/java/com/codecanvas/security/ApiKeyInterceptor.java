package com.codecanvas.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API Key 鉴权拦截器 (S5 安全修复)。
 *
 * <p>CodeCanvas 此前零鉴权，所有 /api/** 接口与 SSE 流式端点匿名可访问。
 * 现要求所有后端接口必须携带 {@code X-API-Key} 请求头，且值等于服务端配置的
 * {@code CODECANVAS_API_KEY} 环境变量。
 *
 * <p>fail-closed 设计：
 * <ul>
 *   <li>未配置 {@code CODECANVAS_API_KEY} → 拒绝一切请求 (503)，杜绝「零密钥裸奔」。</li>
 *   <li>请求头缺失或值不匹配 → 拒绝 (401)。</li>
 * </ul>
 *
 * <p>说明：前端通过构建期环境变量 {@code VITE_API_KEY} 注入同一密钥并随请求发送，
 * 该值会打包进前端 JS（浏览器可见），属于「访问口令」而非机密——
 * 主要防止外部脚本/爬虫直接裸调后端 API 烧钱；若需真机密，请升级为登录鉴权。
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    /** 从环境变量 CODECANVAS_API_KEY 读取；未配置则拒绝所有请求 (fail-closed)。 */
    @Value("${codecanvas.api.key:}")
    private String expectedKey;

    private static final String HEADER = "X-API-Key";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!StringUtils.hasText(expectedKey)) {
            response.sendError(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "服务端未配置 API Key (CODECANVAS_API_KEY)，已拒绝请求");
            return false;
        }
        String provided = request.getHeader(HEADER);
        if (!expectedKey.equals(provided)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "缺少或无效的 X-API-Key");
            return false;
        }
        return true;
    }
}
