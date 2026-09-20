import { useEffect, useState } from 'react';
import { fetchUsage } from '../api/usage';
import type { TokensInfo, UsageLedger } from '../types';
import './UsagePanel.css';

interface UsagePanelProps {
  /** 每轮生成会更新这个对象，用它当刷新触发器，保证在面板上能实时看到累计变化 */
  tokens: TokensInfo | null;
}

/**
 * 成本台账。
 *
 * 分级路由如果没有对照，就只是一句"我做了优化"。这里把两条曲线摆在一起：
 * 实际花了多少、同样的 token 全走旗舰档会花多少、差出来多少。
 *
 * 三个刻意的做法：
 *  - 节省比例和它的**前提**一起显示（假设、所用单价），数字脱离语境就没意义
 *  - 档位分布是客观事实（与单价无关），单独列出来，即使定价是占位值也能说明问题
 *  - 累计归零（服务重启）时如实显示"还没有调用记录"，不补一个好看的值
 */
export function UsagePanel({ tokens }: UsagePanelProps) {
  const [ledger, setLedger] = useState<UsageLedger | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const data = await fetchUsage();
        if (!cancelled) {
          setLedger(data);
          setFailed(false);
        }
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tokens]);

  if (failed) {
    return (
      <div className="usage-scroll">
        <div className="usage-hint">没能读取成本台账，请确认后端已启动。</div>
      </div>
    );
  }

  if (!ledger) {
    return (
      <div className="usage-scroll">
        <div className="usage-hint">正在加载…</div>
      </div>
    );
  }

  if (ledger.calls === 0) {
    return (
      <div className="usage-scroll">
        <div className="usage-hint">
          还没有调用记录。生成一次应用后，这里会显示实际花费与"全走旗舰档"的对照。
        </div>
      </div>
    );
  }

  const activeTiers = ledger.tiers.filter((line) => line.calls > 0);

  return (
    <div className="usage-scroll">
      <div className="usage-hero">
        <div className="usage-hero-col">
          <span className="usage-hero-value">{money(ledger.cost)}</span>
          <span className="usage-hero-label">实际累计花费</span>
        </div>
        <div className="usage-hero-col">
          <span className="usage-hero-value is-baseline">{money(ledger.baselineCost)}</span>
          <span className="usage-hero-label">若全走旗舰档</span>
        </div>
        <div className="usage-hero-col">
          <span className="usage-hero-value is-save">{ledger.savedPercent.toFixed(1)}%</span>
          <span className="usage-hero-label">
            省下 {money(ledger.savedCost)}
          </span>
        </div>
      </div>

      <p className="usage-assumption">{ledger.assumption}</p>

      <section className="usage-section">
        <div className="usage-section-head">
          <h3 className="usage-section-title">档位分布</h3>
          <span className="usage-section-meta">
            {ledger.calls} 次调用 · {compact(ledger.inputTokens + ledger.outputTokens)} token
          </span>
        </div>
        <ul className="usage-rows">
          {activeTiers.map((line) => (
            <li className="usage-row" key={line.tier}>
              <span className="usage-tier">{line.label}</span>
              <span className="usage-num">{line.calls} 次</span>
              <span className="usage-num usage-dim">
                {compact(line.inputTokens + line.outputTokens)}
              </span>
              <span className="usage-num">{money(line.cost)}</span>
            </li>
          ))}
        </ul>
      </section>

      <section className="usage-section">
        <div className="usage-section-head">
          <h3 className="usage-section-title">所用单价</h3>
          <span className="usage-section-meta">每 1K token · 输入 / 输出</span>
        </div>
        <ul className="usage-rows">
          <li className="usage-row is-price">
            <span className="usage-tier">旗舰</span>
            <span className="usage-num usage-dim">
              {ledger.pricing.flagshipInput} / {ledger.pricing.flagshipOutput}
            </span>
          </li>
          <li className="usage-row is-price">
            <span className="usage-tier">标准</span>
            <span className="usage-num usage-dim">
              {ledger.pricing.standardInput} / {ledger.pricing.standardOutput}
            </span>
          </li>
          <li className="usage-row is-price">
            <span className="usage-tier">轻量</span>
            <span className="usage-num usage-dim">
              {ledger.pricing.lightInput} / {ledger.pricing.lightOutput}
            </span>
          </li>
        </ul>
      </section>

      {tokens && (
        <section className="usage-section">
          <h3 className="usage-section-title">最近一轮</h3>
          <ul className="usage-rows">
            <li className="usage-row is-price">
              <span className="usage-tier">{tokens.model}</span>
              <span className="usage-num">{money(tokens.cost)}</span>
            </li>
            <li className="usage-row is-price">
              <span className="usage-tier usage-dim">
                {tokens.promptTokens + tokens.completionTokens} token
                {tokens.totalCost > 0 ? ` · 累计 ${money(tokens.totalCost)}` : ''}
              </span>
              <span className="usage-num usage-save">省 {tokens.savedPercent.toFixed(1)}%</span>
            </li>
          </ul>
        </section>
      )}

      <p className="usage-foot">进程启动以来累计，服务重启后归零。</p>
    </div>
  );
}

/** 金额：小额固定 4 位小数，够看清又不啰嗦 */
function money(value: number): string {
  if (!Number.isFinite(value)) return '—';
  return `¥${value.toFixed(4)}`;
}

/** token 数：超过 1000 折成 k */
function compact(value: number): string {
  if (!Number.isFinite(value)) return '—';
  if (value < 1000) return String(Math.round(value));
  return `${(value / 1000).toFixed(1)}k`;
}

export default UsagePanel;
