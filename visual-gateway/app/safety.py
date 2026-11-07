"""提示词安全前置（图像维度）。

文本侧护栏拦的是"开处方"；图像侧要拦的是**伪造与侵权**：
医疗影像、证件、真人肖像、他人病历——这些一旦生成，比多说一句话危险得多。

口径：命中即 400 拒绝，**不进队列、不计费**；拒绝原因写进日志但不回显敏感词本身。
"""

from __future__ import annotations

import re

# (正则, 归因标签)
_RULES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"(x\s*光|ct|核磁|mri|b\s*超|超声|心电图|脑电图)\s*(片|照片|影像|图像|图|报告)?", re.I), "medical-imaging"),
    (re.compile(r"(病历|诊断书|处方单|诊断证明|病危通知)"), "medical-document"),
    (re.compile(r"(身份证|护照|驾驶证|社保卡|银行卡)\s*(照片|正面|反面|扫描)?"), "id-document"),
    (re.compile(r"(真人|明星|名人|网红|某人)\s*的?\s*(照片|肖像|脸|头像)"), "real-person-likeness"),
    (re.compile(r"(尸检|尸体|血淋淋|肢解|开膛)"), "graphic-violence"),
    (re.compile(r"(裸|色情|情色|性行为)"), "sexual"),
]


class SafetyRejection(Exception):
    def __init__(self, labels: list[str]) -> None:
        self.labels = labels
        super().__init__("blocked: " + ",".join(labels))


def screen(prompt: str) -> None:
    """命中规则抛 SafetyRejection；不修改、不截断，只判定。"""
    hits = [label for pattern, label in _RULES if pattern.search(prompt or "")]
    if hits:
        raise SafetyRejection(sorted(set(hits)))