# -*- coding: utf-8 -*-
"""
第二轮: 来源交叉核验
对保留条目匹配到的 VNDB 角色, 拉取其 traits 与登场作品(vns, 含各语言标题),
与 wife 列表的"作品名"做模糊匹配, 揪出同名异角的误匹配。
输出:
  vndb_chars_verified.json - 各 VNDB 角色的 traits + vns
  verify_report.txt        - 核验报告 (含来源匹配评分)
"""
import json
import os
import re
import time
import urllib.request
from opencc import OpenCC

BASE = os.path.dirname(os.path.abspath(__file__))
KEPT_JSON = os.path.join(BASE, "kept_entries.json")
OUT_JSON = os.path.join(BASE, "vndb_chars_verified.json")
OUT_REPORT = os.path.join(BASE, "verify_report.txt")

UA = "User-Agent: meguri-wife-filter/0.1 (research)"
API = "https://api.vndb.org/kana/character"

cc_s2t = OpenCC("s2t")
cc_t2jp = OpenCC("t2jp")


def norm_jp(s):
    return cc_t2jp.convert(cc_s2t.convert(re.sub(r"[\s\u3000]+", "", s)))


def norm_latin(s):
    return re.sub(r"[\s\-_.!?']+", "", s.lower())


def fetch_char(cid):
    body = json.dumps({
        "filters": ["id", "=", cid],
        "fields": "id,name,original,aliases,traits{id,name},vns{id,title,alttitle,titles{title,latin,lang}}",
    })
    req = urllib.request.Request(API, data=body.encode(),
                                 headers={"Content-Type": "application/json", "User-Agent": UA})
    with urllib.request.urlopen(req, timeout=40) as r:
        return json.loads(r.read())


def title_forms(vn):
    forms = set()
    for t in vn.get("titles") or []:
        tt = t.get("title") or ""
        if re.search(r"[\u4e00-\u9fff]", tt):
            forms.add(("zh-ja", norm_jp(tt)))
        else:
            forms.add(("latin", norm_latin(tt)))
    if vn.get("title"):
        forms.add(("latin", norm_latin(vn["title"])))
    if vn.get("alttitle"):
        forms.add(("zh-ja", norm_jp(vn["alttitle"])))
    return forms


def src_forms(src):
    forms = []
    if re.search(r"[\u4e00-\u9fff]", src):
        forms.append(("zh-ja", norm_jp(src)))
    else:
        forms.append(("latin", norm_latin(src)))
    return forms


def main():
    with open(KEPT_JSON, encoding="utf-8") as f:
        kept = json.load(f)

    ids = sorted({i for e in kept for i in e["vndb_ids"]})
    print(f"保留条目: {len(kept)}, 唯一 VNDB 角色id: {len(ids)}")

    cached = {}
    if os.path.exists(OUT_JSON):
        with open(OUT_JSON, encoding="utf-8") as f:
            cached = {c["id"]: c for c in json.load(f)}

    chars = []
    for i, cid in enumerate(ids):
        if cid in cached:
            chars.append(cached[cid])
            continue
        try:
            d = fetch_char(cid)
            res = (d.get("results") or [])[0]
            if res:
                chars.append(res)
                print(f"[{i+1}/{len(ids)}] {cid} {res.get('name')}", flush=True)
        except Exception as e:
            print(f"[{i+1}/{len(ids)}] {cid} 获取失败: {e}", flush=True)
        time.sleep(0.8)

    with open(OUT_JSON, "w", encoding="utf-8") as f:
        json.dump(chars, f, ensure_ascii=False, indent=1)

    id2char = {c["id"]: c for c in chars}

    report = []
    report.append("=" * 72)
    report.append("第二轮: 来源交叉核验报告")
    report.append("=" * 72)
    report.append("说明: 同名异角误匹配会被【来源不匹配】标出; traits 列出该 VNDB 角色实际标注")
    report.append("")

    KEEP_TRAITS = {"i958": "Younger Sister", "i3206": "Imouto Act", "i296": "Twin Sister",
                   "i413": "Non-blood-related Sister", "i295": "Step Sister", "i1136": "Half-sister"}
    EXCL_TRAITS = {"i959": "Older Sister", "i1914": "Middle Sister"}

    for e in kept:
        src, ch, ids = e["source"], e["char"], e["vndb_ids"]
        report.append(f"◆ {ch}  <来源:{src}>  [行: {e['line']}]")
        any_source_ok = False
        for cid in ids:
            c = id2char.get(cid)
            if not c:
                report.append(f"    VNDB {cid}: (未取到)")
                continue
            ts = [f"{t['name']}({t['id']})" for t in c.get("traits") or []]
            keep_ts = [t["name"] for t in c.get("traits") or [] if t["id"] in KEEP_TRAITS]
            excl_ts = [t["name"] for t in c.get("traits") or [] if t["id"] in EXCL_TRAITS]
            vn_titles = []
            for vn in c.get("vns") or []:
                tf = [t for _, t in title_forms(vn)]
                vn_titles.append(" / ".join(sorted(set(tf))[:3]))
            # 来源匹配
            src_f = src_forms(src)
            matched_vn = []
            for vn in c.get("vns") or []:
                for kind, tf in title_forms(vn):
                    for skind, sf in src_f:
                        if kind == skind:
                            if tf == sf or (len(sf) >= 2 and (sf in tf or tf in sf)):
                                matched_vn.append(vn.get("title") or tf)
            status = "✅来源匹配" if matched_vn else ("⚠️来源未确认" if not any_source_ok else "↘︎(同名字已确认)")
            if matched_vn:
                any_source_ok = True
            report.append(f"    VNDB {cid} {c.get('name')} ({c.get('original') or ''})")
            report.append(f"      妹系trait: {keep_ts or '无'} | 排除trait: {excl_ts or '无'}")
            report.append(f"      作品标题: {vn_titles[:4]}")
            report.append(f"      => {status} {matched_vn[:3]}")
        report.append("")

    with open(OUT_REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(report))
    print(f"完成, 报告: {OUT_REPORT}")


if __name__ == "__main__":
    main()
