#!/usr/bin/env python3
"""Render public performance charts for Zhishu.

The script intentionally uses only Python's standard library so the charts can
be regenerated on a clean machine without installing plotting dependencies.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


ROOT = Path(__file__).resolve().parents[2]
ASSET_DIR = ROOT / "docs" / "performance" / "assets"


@dataclass(frozen=True)
class Bar:
    label: str
    value: float
    color: str


def esc(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
    )


def svg_text(x: float, y: float, text: str, size: int = 14, weight: str = "400",
             anchor: str = "start", fill: str = "#1f2937") -> str:
    return (
        f'<text x="{x:.1f}" y="{y:.1f}" font-family="Arial, sans-serif" '
        f'font-size="{size}" font-weight="{weight}" text-anchor="{anchor}" '
        f'fill="{fill}">{esc(text)}</text>'
    )


def render_bar_chart(title: str, subtitle: str, bars: Iterable[Bar], output: Path,
                     width: int = 960, height: int = 520, unit: str = "") -> None:
    bars = list(bars)
    margin_left = 170
    margin_right = 60
    margin_top = 110
    row_h = 72
    bar_h = 28
    chart_w = width - margin_left - margin_right
    max_value = max(bar.value for bar in bars) or 1

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#ffffff"/>',
        svg_text(34, 44, title, 26, "700"),
        svg_text(34, 76, subtitle, 14, "400", fill="#64748b"),
        f'<line x1="{margin_left}" y1="{margin_top - 20}" x2="{width - margin_right}" y2="{margin_top - 20}" stroke="#e5e7eb"/>',
    ]

    for idx, bar in enumerate(bars):
        y = margin_top + idx * row_h
        w = chart_w * (bar.value / max_value)
        parts.extend([
            svg_text(34, y + 22, bar.label, 15, "600"),
            f'<rect x="{margin_left}" y="{y}" width="{chart_w}" height="{bar_h}" rx="6" fill="#f1f5f9"/>',
            f'<rect x="{margin_left}" y="{y}" width="{w:.1f}" height="{bar_h}" rx="6" fill="{bar.color}"/>',
            svg_text(margin_left + w + 12, y + 20, f"{bar.value:g}{unit}", 14, "700", fill=bar.color),
        ])

    parts.append(svg_text(width - margin_right, height - 28, "Source: reviewed Zhishu performance evidence", 12, "400", "end", "#94a3b8"))
    parts.append("</svg>")
    output.write_text("\n".join(parts), encoding="utf-8")


def render_grouped_chart(title: str, subtitle: str, groups: list[tuple[str, float, float, str]],
                         output: Path, width: int = 980, height: int = 560) -> None:
    margin_left = 210
    margin_right = 150
    margin_top = 118
    row_h = 92
    bar_h = 24
    gap = 8
    chart_w = width - margin_left - margin_right
    max_value = max(max(base, after) for _, base, after, _ in groups) or 1

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#ffffff"/>',
        svg_text(34, 44, title, 26, "700"),
        svg_text(34, 76, subtitle, 14, "400", fill="#64748b"),
        '<rect x="34" y="92" width="14" height="14" rx="3" fill="#94a3b8"/>',
        svg_text(56, 104, "baseline", 12, "600", fill="#475569"),
        '<rect x="136" y="92" width="14" height="14" rx="3" fill="#2563eb"/>',
        svg_text(158, 104, "after", 12, "600", fill="#475569"),
    ]

    for idx, (label, baseline, after, note) in enumerate(groups):
        y = margin_top + idx * row_h
        base_w = chart_w * (baseline / max_value)
        after_w = chart_w * (after / max_value)
        parts.extend([
            svg_text(34, y + 31, label, 15, "700"),
            svg_text(34, y + 53, note, 12, "400", fill="#64748b"),
            f'<rect x="{margin_left}" y="{y}" width="{chart_w}" height="{bar_h}" rx="5" fill="#f1f5f9"/>',
            f'<rect x="{margin_left}" y="{y}" width="{base_w:.1f}" height="{bar_h}" rx="5" fill="#94a3b8"/>',
            svg_text(margin_left + base_w + 10, y + 18, f"{baseline:g}", 12, "600", fill="#475569"),
            f'<rect x="{margin_left}" y="{y + bar_h + gap}" width="{chart_w}" height="{bar_h}" rx="5" fill="#f1f5f9"/>',
            f'<rect x="{margin_left}" y="{y + bar_h + gap}" width="{after_w:.1f}" height="{bar_h}" rx="5" fill="#2563eb"/>',
            svg_text(margin_left + after_w + 10, y + bar_h + gap + 18, f"{after:g}", 12, "700", fill="#2563eb"),
        ])

    parts.append(svg_text(width - margin_right, height - 28, "Lower is better unless noted in label", 12, "400", "end", "#94a3b8"))
    parts.append("</svg>")
    output.write_text("\n".join(parts), encoding="utf-8")


def main() -> None:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)

    render_bar_chart(
        "Zhishu performance optimization overview",
        "Public headline improvements from reviewed phase-three evidence",
        [
            Bar("ZH-F07 P95 latency reduction", 53.59, "#2563eb"),
            Bar("ZH-F05 Redis commands reduction", 93.92, "#16a34a"),
            Bar("ZH-F02 upload median reduction", 54.81, "#dc2626"),
            Bar("ZH-F02 throughput increase", 121.08, "#9333ea"),
        ],
        ASSET_DIR / "overview.svg",
        unit="%",
    )

    render_grouped_chart(
        "ZH-F07 admin user list pushdown",
        "100k synthetic users, MySQL 8.0, n=200/arm",
        [
            ("P95 latency (ms)", 892, 414, "53.59% lower"),
            ("SQL scanned rows", 98950, 40, "99.96% lower"),
            ("Peak heap (MB)", 1393, 150, "88.96% lower"),
        ],
        ASSET_DIR / "zh-f07-admin-query.svg",
    )

    render_grouped_chart(
        "ZH-F05 Redis streaming write amplification",
        "M scale, REAL Redis commandstats, n=25/arm",
        [
            ("commands / answer", 11070.08, 673.08, "93.92% lower"),
            ("bytes / answer", 4756894, 144111, "96.97% lower"),
            ("P95 latency (ms)", 139.6, 5.8, "after/baseline=0.0413"),
        ],
        ASSET_DIR / "zh-f05-redis-stream.svg",
    )

    render_grouped_chart(
        "ZH-F02 bounded-concurrency chunk upload",
        "M scale 64MiB/13 chunks, REAL backend HTTP",
        [
            ("median end-to-end (ms)", 1855.1, 838.3, "54.81% lower"),
            ("P90 ratio marker", 100, 52.76, "after/baseline=0.5276"),
            ("throughput marker", 34.54, 76.36, "121.08% higher"),
        ],
        ASSET_DIR / "zh-f02-upload.svg",
    )

    for path in sorted(ASSET_DIR.glob("*.svg")):
        print(path.relative_to(ROOT).as_posix())


if __name__ == "__main__":
    main()
