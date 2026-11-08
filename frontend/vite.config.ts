import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// 前端所有请求统一走 API 前缀，由 dev server 代理到后端，避免跨域与 CORS 预检。
//
// base 由 CODECANVAS_BASE 控制：开发保持根路径 '/'，
// 生产构建时由 .env.production 指定，例如部署到 nginx 子路径 /canvas/。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'CODECANVAS_');
  return {
    base: env.CODECANVAS_BASE || '/',
    plugins: [react()],
    server: {
      port: 5173,
      // 允许通过隧道域名（*.trycloudflare.com）访问：Vite 5 默认只接受本机 Host
      // 否则公网访问会被 403 挡掉。
      allowedHosts: true,

      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true,
          // SSE 需要保持连接不被代理缓冲
          ws: false,
        },
      },
    },
  };
});
