"""内容寻址产物库：asset_id = sha256(bytes)。

为什么不用 uuid 文件名：
- 同一张图重复生成 → 命中同一 asset，**天然去重**；
- asset_id 本身就是校验和，前端/CDN 可以长期缓存（immutable）；
- 审计时能证明"这张图确实是这次生成的产物"。
"""

from __future__ import annotations

import hashlib
import pathlib

EXT = ".png"


class ArtifactStore:
    def __init__(self, root: str | pathlib.Path) -> None:
        self.root = pathlib.Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, asset_id: str) -> pathlib.Path:
        return self.root / asset_id[:2] / f"{asset_id}{EXT}"

    def put(self, data: bytes) -> tuple[str, pathlib.Path]:
        asset_id = hashlib.sha256(data).hexdigest()
        path = self._path(asset_id)
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            tmp = path.with_suffix(".tmp")
            tmp.write_bytes(data)
            tmp.replace(path)  # 原子落盘，避免读到半张图
        return asset_id, path

    def get(self, asset_id: str) -> bytes | None:
        path = self._path(asset_id)
        return path.read_bytes() if path.exists() else None

    def url(self, asset_id: str) -> str:
        return f"/v1/assets/{asset_id}{EXT}"