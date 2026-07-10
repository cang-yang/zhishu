#!/usr/bin/env python3
"""Generate public SVG charts for 智枢 performance documentation.

The charts are intentionally card-based instead of axis-based. The values have
very different orders of magnitude, so fixed cards avoid crowded labels and
make the README stable on GitHub without third-party plotting dependencies.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ASSET_DIR = ROOT / "docs" / "performance" / "assets"

FONT = "Microsoft YaHei, Noto Sans CJK SC, PingFang SC, Arial, sans-serif"
BG = "#f8fafc"
INK = "#172033"
MUTED = "#64748b"
BORDER = "#dbe3ef"
BLUE = "#2563eb"
GREEN = "#16a34a"
RED = "#dc2626"
PURPLE = "#7c3aed"


@dataclass(frozen=True)
class MetricCard:
    title: str
    value: str
    subtitle: str
    accent: str


def esc(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def text(x: int, y: int, content: str, size: int = 16, weight: int = 400,
         fill: str = INK, anchor: str = "start") -> str:
    return (
        f'<text x="{x}" y="{y}" font-family="{FONT}" font-size="{size}" '
        f'font-weight="{weight}" fill="{fill}" text-anchor="{anchor}">'
        f'{esc(content)}</text>'
    )


def panel(x: int, y: int, w: int, h: int, accent: str) -> list[str]:
    return [
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="#ffffff" stroke="{BORDER}"/>',
        f'<rect x="{x}" y="{y}" width="6" height="{h}" rx="3" fill="{accent}"/>',
    ]


def render_metric_grid(title: str, subtitle: str, cards: list[MetricCard],
                       output: Path, width: int = 960) -> None:
    cols = 2
    card_w = 420
    card_h = 146
    gap_x = 40
    gap_y = 30
    left = 50
    top = 126
    rows = (len(cards) + cols - 1) // cols
    height = top + rows * card_h + (rows - 1) * gap_y + 56

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="100%" height="100%" fill="{BG}"/>',
        text(50, 52, title, 30, 800),
        text(50, 84, subtitle, 15, 400, MUTED),
    ]

    for idx, card in enumerate(cards):
        col = idx % cols
        row = idx // cols
        x = left + col * (card_w + gap_x)
        y = top + row * (card_h + gap_y)
        parts.extend(panel(x, y, card_w, card_h, card.accent))
        parts.extend([
            text(x + 28, y + 42, card.title, 17, 700),
            text(x + 28, y + 94, card.value, 34, 800, card.accent),
            text(x + 28, y + 124, card.subtitle, 13, 400, MUTED),
        ])

    parts.append(text(width - 50, height - 24, "数据来源：智枢阶段三性能证据与独立校验记录", 13, 400, "#94a3b8", "end"))
    parts.append("</svg>")
    output.write_text("\n".join(parts), encoding="utf-8")


def render_comparison(title: str, subtitle: str, cards: list[MetricCard],
                      output: Path, width: int = 960) -> None:
    card_w = 273
    card_h = 164
    gap = 20
    left = 50
    top = 128
    height = top + card_h + 72

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        f'<rect width="100%" height="100%" fill="{BG}"/>',
        text(50, 52, title, 30, 800),
        text(50, 84, subtitle, 15, 400, MUTED),
    ]

    for idx, card in enumerate(cards):
        x = left + idx * (card_w + gap)
        y = top
        parts.extend(panel(x, y, card_w, card_h, card.accent))
        parts.extend([
            text(x + 28, y + 42, card.title, 16, 700),
            text(x + 24, y + 98, card.value, 28, 800, card.accent),
            text(x + 24, y + 130, card.subtitle, 12, 400, MUTED),
        ])

    parts.append(text(width - 50, height - 24, "baseline 与 after 使用同一实验协议对比", 13, 400, "#94a3b8", "end"))
    parts.append("</svg>")
    output.write_text("\n".join(parts), encoding="utf-8")


def main() -> None:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)

    render_metric_grid(
        "智枢性能优化总览",
        "三项优化均绑定复现脚本、统计脚本、独立校验和公开证据索引",
        [
            MetricCard("后台用户列表查询下推", "P95 降 53.59%", "100k 用户数据集，892ms -> 414ms", BLUE),
            MetricCard("Redis 流式写放大优化", "命令数降 93.92%", "M 档 commands/answer，11070.08 -> 673.08", GREEN),
            MetricCard("分片上传有界并发", "耗时降 54.81%", "M 档 64MiB/13 chunks，1855.1ms -> 838.3ms", RED),
            MetricCard("上传吞吐提升", "吞吐升 121.08%", "M 档 34.54 -> 76.36 MiB/s", PURPLE),
        ],
        ASSET_DIR / "overview.svg",
    )

    render_comparison(
        "ZH-F07 后台用户列表查询下推",
        "数据库过滤、排序、分页和 DTO 投影替代 findAll 内存处理",
        [
            MetricCard("P95 延迟", "下降 53.59%", "892ms -> 414ms", BLUE),
            MetricCard("SQL 扫描行数", "下降 99.96%", "98950 -> 40", GREEN),
            MetricCard("峰值堆占用", "下降 88.96%", "1.36GB -> 150MB", PURPLE),
        ],
        ASSET_DIR / "zh-f07-admin-query.svg",
    )

    render_comparison(
        "ZH-F05 Redis 流式会话写放大优化",
        "每 chunk 重写 session 改为 APPEND buffer，最终合并保持消息等价",
        [
            MetricCard("commands / answer", "下降 93.92%", "11070.08 -> 673.08", GREEN),
            MetricCard("bytes / answer", "下降 96.97%", "4756894 -> 144111", BLUE),
            MetricCard("P95 延迟", "139.6ms -> 5.8ms", "after / baseline = 0.0413", PURPLE),
        ],
        ASSET_DIR / "zh-f05-redis-stream.svg",
    )

    render_comparison(
        "ZH-F02 知识库分片上传有界并发",
        "前端串行上传改为 concurrency=4 的 worker pool，后端保持零改动",
        [
            MetricCard("端到端 median", "下降 54.81%", "1855.1ms -> 838.3ms", RED),
            MetricCard("median 吞吐", "提升 121.08%", "34.54 -> 76.36 MiB/s", PURPLE),
            MetricCard("恢复与完整性", "3/3 + SHA256", "恢复、合并、隔离护栏通过", BLUE),
        ],
        ASSET_DIR / "zh-f02-upload.svg",
    )

    for path in sorted(ASSET_DIR.glob("*.svg")):
        print(path.relative_to(ROOT).as_posix())


if __name__ == "__main__":
    main()
