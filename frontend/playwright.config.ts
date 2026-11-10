import { defineConfig } from '@playwright/test';

/**
 * E2E 骨架（C8）：默认跳过，设置 CODECANVAS_E2E=1 且后端/前端可达时真跑。
 * 这是刻意的诚实降级——CI 里没起后端就假装 E2E 通过，比不写更糟。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 120_000,
  retries: 0,
  use: {
    baseURL: process.env.CODECANVAS_FRONTEND_URL ?? 'http://localhost:3000',
    trace: 'retain-on-failure',
  },
});
