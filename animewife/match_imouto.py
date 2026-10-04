# -*- coding: utf-8 -*-
"""
VNDB 妹系角色白名单匹配器
输入:
  wife_list.txt   - 抽老婆插件图包列表 (来源!角色名.扩展名)
  imouto_dump.json - VNDB 妹系候选角色 (6 trait 并集, 含 traits{id})
输出:
  imouto_list.txt  - 过滤后只含妹系角色的完整行 (保持原路径, 图片仍从 CDN 加载)
  match_report.txt - 匹配报告 (保留/排除/歧义统计)
"""
import json
import re
import os
from collections import defaultdict
from opencc import OpenCC

BASE = os.path.dirname(os.path.abspath(__file__))
WIFE_LIST = os.path.join(BASE, "wife_list.txt")
DUMP = os.path.join(BASE, "imouto_dump.json")
OUT_LIST = os.path.join(BASE, "imouto_list.txt")
OUT_REPORT = os.path.join(BASE, "match_report.txt")
OUT_KEPT_JSON = os.path.join(BASE, "kept_entries.json")

# 宽松版: 计入的 traits
INCLUDE_TRAITS = {"i958", "i3206", "i296", "i413", "i295", "i1136"}  # 妹妹/妹系行为/双胞胎妹/义妹/继妹/异母妹
EXCLUDE_TRAITS = {"i959"}  # 姐姐 (排除: 如双胞胎中的姐姐 拉姆)

cc_s2t = OpenCC("s2t")
cc_t2jp = OpenCC("t2jp")


def norm_jp(s: str) -> str:
    """中日汉字统一归一到日文新字体: 去空格 -> 简->繁 -> 繁->日"""
    s = re.sub(r"[\s\u3000]+", "", s)
    return cc_t2jp.convert(cc_s2t.convert(s))


def norm_latin(s: str) -> str:
    s = s.lower().strip()
    return re.sub(r"[\s\-_.]+", "", s)


def char_forms(c: dict) -> set:
    """一个 VNDB 角色对应的所有归一化名字形式"""
    forms = set()
    if c.get("original"):
        forms.add(norm_jp(c["original"]))
        forms.add(norm_jp(c["original"]).lower())
    if c.get("name"):
        forms.add(norm_latin(c["name"]))
    for a in c.get("aliases") or []:
        a = a.strip()
        if not a:
            continue
        if re.search(r"[\u4e00-\u9fff]", a):
            forms.add(norm_jp(a))
        else:
            forms.add(norm_latin(a))
    return forms


def main():
    # 1. 读取 VNDB dump, 构建 名字形式 -> 角色id 索引
    with open(DUMP, encoding="utf-8") as f:
        chars = json.load(f)
    print(f"VNDB 妹系候选角色: {len(chars)}")

    excluded_ids = set()
    for c in chars:
        tids = {t["id"] for t in c.get("traits") or []}
        if tids & EXCLUDE_TRAITS:
            excluded_ids.add(c["id"])
    print(f"排除带姐姐trait的角色: {len(excluded_ids)}")

    form2ids = defaultdict(set)
    id2char = {}
    for c in chars:
        if c["id"] in excluded_ids:
            continue
        id2char[c["id"]] = c
        for f in char_forms(c):
            form2ids[f].add(c["id"])
    print(f"纳入索引的角色: {len(id2char)}, 名字形式: {len(form2ids)}")

    # 2. 读取 wife 列表
    with open(WIFE_LIST, encoding="utf-8") as f:
        lines = [ln.strip() for ln in f if ln.strip()]
    print(f"wife 列表总行数: {len(lines)}")

    pattern = re.compile(r"^(.*?)!(.*?)(\.[A-Za-z0-9]+)$")
    kept_lines = []
    dropped_lines = []
    kept_chars = defaultdict(list)   # 角色名 -> [(源作品, 行, [vndb ids])]
    ambiguous = []

    for ln in lines:
        m = pattern.match(ln)
        if not m:
            dropped_lines.append((ln, "格式无法解析"))
            continue
        src, char, ext = m.group(1).strip(), m.group(2).strip(), m.group(3)
        cands = set()
        for f in (norm_jp(char), norm_jp(char).lower(), norm_latin(char), char):
            if f in form2ids:
                cands |= form2ids[f]
        if cands:
            kept_lines.append(ln)
            kept_chars[char].append((src, ln, sorted(cands)))
            if len(cands) > 1:
                ambiguous.append((src, char, sorted(cands)))
        else:
            dropped_lines.append((ln, "VNDB妹系库中无此角色名"))

    # 3. 写输出
    with open(OUT_LIST, "w", encoding="utf-8") as f:
        f.write("\n".join(kept_lines) + ("\n" if kept_lines else ""))

    # 4. 报告
    lines_out = []
    lines_out.append("=" * 70)
    lines_out.append("妹系角色筛选报告 (VNDB traits 白名单)")
    lines_out.append("=" * 70)
    lines_out.append(f"wife 列表总行数: {len(lines)}")
    lines_out.append(f"保留(妹系): {len(kept_lines)} 行 / {len(kept_chars)} 个角色名")
    lines_out.append(f"排除: {len(dropped_lines)} 行")
    lines_out.append("")
    lines_out.append("-" * 70)
    lines_out.append("【保留角色】角色名 -> 匹配到的VNDB角色id (括号内为匹配id数量)")
    lines_out.append("-" * 70)
    for ch in sorted(kept_chars):
        for src, ln, ids in kept_chars[ch]:
            lines_out.append(f"  {ch}  <{src}>  VNDB:{','.join(ids)}  [{ln}]")
    lines_out.append("")
    lines_out.append("-" * 70)
    lines_out.append("【歧义提醒】一个角色名匹配到多个VNDB角色(可能存在同名异角, 建议人工核对)")
    lines_out.append("-" * 70)
    for src, ch, ids in sorted(ambiguous, key=lambda x: (x[1], x[0])):
        lines_out.append(f"  {ch}  <{src}>  -> {','.join(ids)}")
    lines_out.append("")
    lines_out.append("-" * 70)
    lines_out.append("【排除样例】前50条被排除的行")
    lines_out.append("-" * 70)
    for ln, why in dropped_lines[:50]:
        lines_out.append(f"  [{why}] {ln}")

    with open(OUT_REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines_out))

    # 保留条目 JSON (供第二轮来源核验使用)
    kept_json = []
    for ch in sorted(kept_chars):
        for src, ln, ids in kept_chars[ch]:
            kept_json.append({"char": ch, "source": src, "line": ln, "vndb_ids": ids})
    with open(OUT_KEPT_JSON, "w", encoding="utf-8") as f:
        json.dump(kept_json, f, ensure_ascii=False, indent=1)

    print(f"\n保留: {len(kept_lines)} 行 / {len(kept_chars)} 角色 | 排除: {len(dropped_lines)} 行 | 歧义: {len(ambiguous)}")
    print(f"输出: {OUT_LIST}")
    print(f"报告: {OUT_REPORT}")


if __name__ == "__main__":
    main()
