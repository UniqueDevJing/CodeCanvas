import { apiGet } from './client';
import type { UsageLedger } from '../types';

/**
 * GET /api/usage —— 成本台账。
 *
 * 返回里带 assumption 与 pricing：反事实的节省比例是算出来的，
 * 界面上必须把前提一起显示，否则这个数字脱离语境就没意义了。
 */
export function fetchUsage(): Promise<UsageLedger> {
  return apiGet<UsageLedger>('/usage');
}
