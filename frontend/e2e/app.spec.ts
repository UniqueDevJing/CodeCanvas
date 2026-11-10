import { expect, test, type Page } from '@playwright/test';

/**
 * C8 E2E 回归骨架：生成 → 取消 → 回滚 → 发布。
 *
 * 前置条件（满足时才真跑，否则整组 skip）：
 * 1. CODECANVAS_E2E=1
 * 2. 后端（含 CODECANVAS_API_KEY）与前端 dev server 可达
 * 3. 浏览器二进制已安装：npx playwright install chromium
 *
 * 选择器基于现有 UI 的语义文本；UI 调整后按报错提示同步更新。
 */

test.skip(process.env.CODECANVAS_E2E !== '1',
  'E2E 未启用：设置 CODECANVAS_E2E=1 并确保前后端可达后运行');

const API_KEY = process.env.CODECANVAS_API_KEY ?? '';
const REQUIREMENT = process.env.CODECANVAS_E2E_PROMPT ?? '做一个番茄钟计时页面';

/** 等待生成完成（done 事件到达 / 输入框恢复可用）。 */
async function waitForRunFinished(page: Page): Promise<void> {
  await expect(page.locator('[data-testid="composer-input"], textarea, input[type="text"]').first())
    .toBeEnabled({ timeout: 180_000 });
}

test.beforeEach(async ({ page }) => {
  if (API_KEY) {
    // 前端构建期注入密钥为空时，测试里补到 localStorage/运行时存储（按实际实现调整）
    await page.addInitScript((key) => {
      (window as unknown as { __CODECANVAS_API_KEY__?: string }).__CODECANVAS_API_KEY__ = key;
    }, API_KEY);
  }
});

test('生成 → 取消 → 回滚 → 发布 全链路', async ({ page }) => {
  await page.goto('/');

  // ---------- 1. 生成 ----------
  const input = page.locator('textarea, input[type="text"]').first();
  await input.fill(REQUIREMENT);
  await input.press('Enter');

  // 流式过程必须出现阶段或状态反馈（而不是黑盒卡死）
  await expect(page.getByText(/正在|生成|写|规划/).first()).toBeVisible({ timeout: 30_000 });
  await waitForRunFinished(page);
  await expect(page.locator('iframe').first()).toBeVisible({ timeout: 60_000 });

  // ---------- 2. 取消 ----------
  await input.fill('再给页面加一个深色模式');
  await input.press('Enter');
  const stopButton = page.getByRole('button', { name: /停止|停止生成|stop/i });
  if (await stopButton.count()) {
    await stopButton.first().click();
    await expect(page.getByText(/已停止生成/)).toBeVisible({ timeout: 10_000 });
    // 取消后必须能立刻发起新一轮生成（回归点：取消后输入框不被锁死）
    await expect(input).toBeEnabled();
  }

  // ---------- 3. 回滚 ----------
  // 等待至少两个版本后回滚到第一版
  await waitForRunFinished(page);
  const versionTabs = page.locator('[data-testid="version-item"], [class*="version"]');
  if (await versionTabs.count() >= 2) {
    await versionTabs.last().click(); // 时间线上最后的是最早版本
    await expect(page.locator('iframe').first()).toBeVisible();
  }

  // ---------- 4. 发布 ----------
  // 实际 UI：TopBar「发布」按钮点击后直接异步发布，成功弹出「发布结果」对话框
  // （无中间确认步骤——骨架期的"确认"假设与实现不符，已按实况修正）。
  const deployButton = page.getByRole('button', { name: /发布|发布到公网|deploy/i }).first();
  await deployButton.click();
  const deployDialog = page.getByRole('dialog', { name: '发布结果' });
  await expect(deployDialog).toBeVisible({ timeout: 30_000 });
  // 分享链接（/a/{key}）在结果对话框的输入框 value 中（input 的 value 不属于 text node）
  await expect(deployDialog.locator('input.deploy-link')).toHaveValue(/\/a\//, { timeout: 10_000 });
});
