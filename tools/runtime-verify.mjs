// CodeCanvas 运行时验证脚本（Node + Playwright）
//
// 职责：把生成的单文件 HTML 放进真实无头浏览器跑一遍，捕获"能预览"和"跑得过验收"之间的差距：
//   1. 加载 / 交互时的未捕获异常（pageerror / unhandledrejection）
//   2. 控制台 error 级日志（常被业务代码静默吞掉的运行时错误信号）
//   3. 点主按钮后是否抛异常 / 页面是否毫无反应（BUTTON_NOOP）
//   4. PRD 承诺的文案 / 元素是否真的出现（MISSING_TEXT / MISSING_ELEMENT）
//   5. 可选：落一张截图
//
// 输入：argv[2] = html 文件路径，argv[3] = spec JSON 文件路径
// 输出：单行 JSON 到 stdout，形如 { skipped, verified, screenshotPath, issues: [...] }
//
// 失败软降级：任何环境级问题（浏览器起不来 / 依赖缺失 / 过程异常）都回一张 skipped 报告，
// 退出码仍为 0，让调用方（Java 侧）把它当作"本次不阻断"而非崩溃。

import { chromium } from 'playwright';
import fs from 'node:fs';

function emit(report) {
  process.stdout.write(JSON.stringify(report));
}

function skipped(message) {
  emit({
    skipped: true,
    verified: false,
    screenshotPath: null,
    issues: [{ type: 'SKIPPED', message, source: null, line: 0, stack: null }],
  });
  process.exit(0);
}

async function main() {
  const [, , htmlPath, specPath] = process.argv;
  if (!htmlPath || !specPath) {
    skipped('缺少参数：需要 html 路径与 spec 路径');
    return;
  }

  let html;
  let spec;
  try {
    html = fs.readFileSync(htmlPath, 'utf8');
    spec = JSON.parse(fs.readFileSync(specPath, 'utf8'));
  } catch (e) {
    skipped('读取输入失败：' + e.message);
    return;
  }

  let browser;
  try {
    browser = await chromium.launch();
  } catch (e) {
    skipped('无法启动无头浏览器（Playwright chromium 未安装？）：' + e.message);
    return;
  }

  const issues = [];
  const settle = Number.isFinite(spec.settleMs) ? spec.settleMs : 300;

  try {
    const context = await browser.newContext();
    const page = await context.newPage();

    page.on('pageerror', (err) => {
      issues.push({
        type: 'PAGE_CRASH',
        message: String(err && err.message ? err.message : err),
        source: 'index.html',
        line: 0,
        stack: err && err.stack ? String(err.stack) : null,
      });
    });
    page.on('console', (msg) => {
      if (msg.type() === 'error') {
        issues.push({
          type: 'CONSOLE_ERROR',
          message: String(msg.text()),
          source: 'index.html',
          line: 0,
          stack: null,
        });
      }
    });

    await page.setContent(html, { waitUntil: 'load' });
    await page.waitForTimeout(settle);

    // 点主按钮：第一个可交互控件。目的不是"断言页面该变什么样"，
    // 而是触发其点击处理里的未捕获异常（会被上面的 pageerror 捕获成 PAGE_CRASH）。
    // 只有显式开启 assertClickChanges（PRD 承诺过点击会产生变化）时，才把"点击后无变化"视为问题，
    // 避免对合法落地页（弹窗/视觉变化/DOM 文本不变）误报 BUTTON_NOOP。
    if (spec.clickMainButton) {
      const selector =
        'button, [role="button"], a.btn, input[type="button"], input[type="submit"]';
      const handle = await page.$(selector);
      if (handle) {
        const before = await page.evaluate(() => document.body.innerText);
        try {
          await handle.click();
          await page.waitForTimeout(settle);
          if (spec.assertClickChanges) {
            const after = await page.evaluate(() => document.body.innerText);
            if (before === after) {
              issues.push({
                type: 'BUTTON_NOOP',
                message: '点击主按钮后页面内容未发生变化',
                source: 'index.html',
                line: 0,
                stack: null,
              });
            }
          }
        } catch (e) {
          issues.push({
            type: 'BUTTON_NOOP',
            message: '点击主按钮时抛出异常：' + e.message,
            source: 'index.html',
            line: 0,
            stack: e.stack ? String(e.stack) : null,
          });
        }
      }
    }

    for (const text of spec.expectTexts || []) {
      const found = await page.evaluate((t) => document.body.innerText.includes(t), text);
      if (!found) {
        issues.push({
          type: 'MISSING_TEXT',
          message: '页面未包含预期文案：' + text,
          source: 'index.html',
          line: 0,
          stack: null,
        });
      }
    }
    for (const sel of spec.expectSelectors || []) {
      const found = await page.$(sel);
      if (!found) {
        issues.push({
          type: 'MISSING_ELEMENT',
          message: '页面缺少预期元素：' + sel,
          source: 'index.html',
          line: 0,
          stack: null,
        });
      }
    }

    let screenshotPath = null;
    if (spec.screenshotPath) {
      try {
        await page.screenshot({ path: spec.screenshotPath, fullPage: false });
        // 确认文件真的落盘，避免把不存在的路径写进报告
        screenshotPath = fs.existsSync(spec.screenshotPath) ? spec.screenshotPath : null;
      } catch (_) {
        screenshotPath = null;
      }
    }

    emit({
      skipped: false,
      verified: true,
      screenshotPath,
      issues,
    });
  } catch (e) {
    // 验证过程本身异常（不是页面 bug），如实报成 PAGE_CRASH 但标记为 verified=false
    emit({
      skipped: false,
      verified: false,
      screenshotPath: null,
      issues: [
        {
          type: 'PAGE_CRASH',
          message: '运行时验证过程异常：' + e.message,
          source: 'index.html',
          line: 0,
          stack: e.stack ? String(e.stack) : null,
        },
      ],
    });
  } finally {
    await browser.close().catch(() => {});
  }
  process.exit(0);
}

main().catch((e) => {
  skipped('运行时验证脚本异常：' + (e && e.message ? e.message : e));
});
