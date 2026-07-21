#!/usr/bin/env python3
"""Render a safe Qianji aggregate digest into clickable desktop report artifacts.

This script intentionally accepts the post-sync aggregate digest only. It never
opens Qianji credentials, token state, or a raw transaction export.
"""

from __future__ import annotations

import argparse
import json
from decimal import Decimal, InvalidOperation
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4


FONT_PATH = Path(r"C:\Windows\Fonts\Deng.ttf")


def money(value: object) -> str:
    try:
        return f"{Decimal(str(value)).quantize(Decimal('0.01')):,.2f}"
    except (InvalidOperation, ValueError):
        return "0.00"


def load_digest(source: Path) -> dict:
    digest = json.loads(source.read_text(encoding="utf-8"))
    if digest.get("status") != "success":
        raise ValueError("daily digest status is not success")
    if not isinstance(digest.get("analytics"), dict):
        raise ValueError("daily digest has no analytics aggregate")
    return digest


def report_lines(digest: dict) -> list[str]:
    analytics = digest["analytics"]
    currency = str(analytics.get("base_currency") or "CNY")
    categories = analytics.get("top_expense_categories") or []
    lines = [
        f"{digest.get('target_date', 'unknown')} 账单日报",
        "数据范围：钱迹同步后的安全聚合数据（不含令牌、完整原始账单）",
        f"收入：{currency} {money(analytics.get('income_total'))}",
        f"支出：{currency} {money(analytics.get('expense_total'))}",
        f"净流：{currency} {money(analytics.get('net_flow'))}",
        f"交易笔数：{int(analytics.get('tx_count') or 0)}",
        f"净资产：{currency} {money(analytics.get('net_asset'))}",
        "主要支出分类：",
    ]
    if categories:
        for item in categories[:5]:
            lines.append(f"- {item.get('category') or '未分类'}：{currency} {money(item.get('amount'))}")
    else:
        lines.append("- 暂无分类统计")
    return lines


def write_markdown(target: Path, digest: dict, lines: list[str]) -> None:
    analytics = digest["analytics"]
    sync = digest.get("sync") or {}
    currency = str(analytics.get("base_currency") or "CNY")
    categories = analytics.get("top_expense_categories") or []
    content = [
        f"# {digest.get('target_date', 'unknown')} 账单日报",
        "",
        "> 数据来自钱迹同步后的安全聚合摘要；本报告不包含登录凭据或完整原始交易载荷。",
        "",
        "## 汇总",
        "",
        f"- 收入：{currency} {money(analytics.get('income_total'))}",
        f"- 支出：{currency} {money(analytics.get('expense_total'))}",
        f"- 净流：{currency} {money(analytics.get('net_flow'))}",
        f"- 交易笔数：{int(analytics.get('tx_count') or 0)}",
        f"- 当前净资产：{currency} {money(analytics.get('net_asset'))}",
        "",
        "## 主要支出分类",
        "",
    ]
    content.extend(
        f"- {item.get('category') or '未分类'}：{currency} {money(item.get('amount'))}"
        for item in categories[:5]
    )
    if not categories:
        content.append("- 暂无分类统计")
    content.extend([
        "",
        "## 同步说明",
        "",
        f"- 生成时间：{digest.get('generated_at', 'unknown')}",
        f"- 导入：{int(sync.get('imported_count') or 0)}；重复：{int(sync.get('duplicate_count') or 0)}；跳过：{int(sync.get('skipped_count') or 0)}",
        "",
    ])
    target.write_text("\n".join(content), encoding="utf-8")


def register_font() -> str:
    if not FONT_PATH.is_file():
        raise FileNotFoundError(f"Chinese report font missing: {FONT_PATH}")
    font_name = "MeguriDeng"
    try:
        pdfmetrics.registerFont(TTFont(font_name, str(FONT_PATH)))
    except KeyError:
        pass
    return font_name


def write_pdf(target: Path, lines: list[str]) -> None:
    font_name = register_font()
    page_width, page_height = A4
    pdf = canvas.Canvas(str(target), pagesize=A4)
    pdf.setTitle(lines[0])
    pdf.setFillColorRGB(0.10, 0.07, 0.16)
    pdf.rect(0, 0, page_width, page_height, fill=1, stroke=0)
    y = page_height - 58
    for index, line in enumerate(lines):
        pdf.setFillColorRGB(0.95, 0.91, 0.98) if index == 0 else pdf.setFillColorRGB(0.82, 0.77, 0.88)
        pdf.setFont(font_name, 20 if index == 0 else 11)
        pdf.drawString(46, y, line)
        y -= 36 if index == 0 else 26
    pdf.setFillColorRGB(0.52, 0.77, 1.0)
    pdf.setFont(font_name, 9)
    pdf.drawString(46, 34, "Meguri daily report - safe aggregate digest")
    pdf.showPage()
    pdf.save()


def write_jpg(target: Path, lines: list[str]) -> None:
    font_path = str(FONT_PATH)
    image = Image.new("RGB", (1440, 960), "#171323")
    draw = ImageDraw.Draw(image)
    title_font = ImageFont.truetype(font_path, 44)
    body_font = ImageFont.truetype(font_path, 28)
    y = 70
    for index, line in enumerate(lines):
        draw.text((78, y), line, font=title_font if index == 0 else body_font,
                  fill="#f7edff" if index == 0 else "#d8c7e3")
        y += 76 if index == 0 else 52
    draw.text((78, 900), "Meguri daily report - safe aggregate digest", font=body_font, fill="#87c7ff")
    image.save(target, format="JPEG", quality=94, optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--project-root", required=True, type=Path)
    args = parser.parse_args()
    digest = load_digest(args.input)
    date = str(digest.get("target_date") or "unknown")
    reports_dir = args.project_root / "reports" / "daily"
    pdf_dir = args.project_root / "output" / "pdf"
    reports_dir.mkdir(parents=True, exist_ok=True)
    pdf_dir.mkdir(parents=True, exist_ok=True)
    lines = report_lines(digest)
    write_markdown(reports_dir / f"billing-{date}.md", digest, lines)
    write_pdf(pdf_dir / f"billing-{date}.pdf", lines)
    write_jpg(reports_dir / f"billing-{date}.jpg", lines)
    print(f"billing report generated for {date}")


if __name__ == "__main__":
    main()
