package com.codecanvas.config;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 应用装配。 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class CodeCanvasConfig {

    private static final Logger log = LoggerFactory.getLogger(CodeCanvasConfig.class);

    /**
     * Agent 执行线程池。
     *
     * <p>一次生成要经历多次模型调用和长时间阻塞 IO，不能占用 Web 容器的请求线程。
     *
     * <p>这里优先使用虚拟线程：它专为"大量阻塞等待"设计，能显著降低线程资源占用。
     * 虚拟线程需要 JDK 21+，因此用反射探测而不是硬编码，JDK 17 环境自动退回有界线程池，
     * 升级 JDK 后不需要改代码就能切换到虚拟线程。
     */
    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        ExecutorService virtual = tryVirtualThreadExecutor();
        if (virtual != null) {
            log.info("Agent 线程池：启用虚拟线程（JDK 21+）");
            return virtual;
        }
        log.info("Agent 线程池：当前 JDK 不支持虚拟线程，使用有界平台线程池（升级 JDK 21 后自动切换）");
        return Executors.newFixedThreadPool(16, namedFactory("agent-"));
    }

    /**
     * 尝试创建虚拟线程执行器。
     *
     * <p>用反射而不是直接调用：{@code Executors.newVirtualThreadPerTaskExecutor()} 是 JDK 21 才有的 API，
     * 直接写会导致整个工程在 JDK 17 上编译不过。反射让同一份代码在 17 上跑线程池、在 21 上自动升级为虚拟线程。
     */
    private static ExecutorService tryVirtualThreadExecutor() {
        try {
            Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
            return (ExecutorService) method.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前运行时用的是虚拟线程还是有界线程池，用于在界面上如实展示。 */
    public static boolean virtualThreadSupported() {
        try {
            return Thread.class.getMethod("ofVirtual") != null;
        } catch (Exception e) {
            return false;
        }
    }

    private ThreadFactory namedFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 前端开发服务器直连时允许跨域（生产由网关处理）。 */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
