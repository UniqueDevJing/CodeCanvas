package com.codecanvas.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级可观测性过滤器。
 *
 * <p>每个 HTTP 请求分配一个 traceId 并写入 MDC 与响应头 X-Trace-Id，使整条调用链
 * （含被 ApiKeyInterceptor 拒绝的无密钥请求）的日志都能按 traceId 串联。这是生产环境
 * “线上挂了怎么知道、是哪次请求、卡在哪个阶段”的最小可用可观测底座，后续可无缝接入
 * Prometheus / Loki 做指标聚合与链路追踪。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TracingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TracingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long start = System.currentTimeMillis();
        MDC.put("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            long ms = System.currentTimeMillis() - start;
            log.info("request traceId={} method={} path={} status={} ms={}",
                    traceId, request.getMethod(), request.getRequestURI(), status, ms);
            MDC.remove("traceId");
        }
    }
}
