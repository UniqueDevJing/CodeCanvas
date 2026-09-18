import { CheckIcon } from './icons';
import { STAGES } from '../types';

interface StageStepperProps {
  /** 当前阶段序号（1..5），0 表示尚未开始 */
  stageIndex: number;
  /** 全部阶段是否已完成 */
  done: boolean;
}

type StepState = 'done' | 'active' | 'pending';

/** 五阶段进度条：已完成打勾，当前脉冲，未到灰色 */
export function StageStepper({ stageIndex, done }: StageStepperProps) {
  return (
    <ol className="stepper">
      {STAGES.map((stage, index) => {
        const position = index + 1;
        const state: StepState = done || position < stageIndex ? 'done' : position === stageIndex ? 'active' : 'pending';
        return (
          <li key={stage.key} className={`step is-${state}`}>
            <span className="step-rail" aria-hidden="true" />
            <span className="step-dot">
              {state === 'done' ? <CheckIcon size={11} /> : <span className="step-dot-core" />}
            </span>
            <span className="step-label">{stage.label}</span>
          </li>
        );
      })}
    </ol>
  );
}

export default StageStepper;
