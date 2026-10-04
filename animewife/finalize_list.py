# -*- coding: utf-8 -*-
"""
第三轮: 最终名单清洗
基于 verify_report.txt 的人工研判结论, 剔除"同名异角"误匹配与 VNDB 数据异常项。
输出: imouto_list.txt (最终部署名单), final_report.txt
"""
import json
import os
import re

BASE = os.path.dirname(os.path.abspath(__file__))
WIFE_LIST = os.path.join(BASE, "wife_list.txt")
KEPT_JSON = os.path.join(BASE, "kept_entries.json")
OUT_LIST = os.path.join(BASE, "imouto_list.txt")
OUT_REPORT = os.path.join(BASE, "final_report.txt")

# 决策表: 角色名 -> 保留/剔除 + 理由
# keep_reason / drop_reason
DECISIONS = {
    # ---------- 保留 (VNDB 来源确认 / 名字唯一且身份可信) ----------
    "ALyCE":        ("keep", "多娜多娜 ✓ VNDB 来源匹配; Twin Sister+Imouto Act"),
    "三好夏凛":      ("keep", "结城友奈 ✓ VNDB 来源匹配 (结城友奈游戏)"),
    "东云希":       ("keep", "初雪樱 ✓ VNDB 来源匹配"),
    "中野五月":      ("keep", "五等分 ✓ VNDB 来源匹配; 五姐妹中最小的妹妹"),
    "五河琴里":      ("keep", "约会大作战 ✓ Date A Live VN 系列; 义妹(收养)"),
    "伊吹风子":      ("keep", "CLANNAD ✓ 来源匹配; 伊吹公子之妹"),
    "北条沙都子":    ("keep", "寒蝉 ✓ 来源匹配; 北条悟史之妹"),
    "南千秋":       ("keep", "南家三姐妹 ✓ 来源匹配; 三姐妹中最小的妹妹"),
    "古手川唯":      ("keep", "出包王女 ✓ ToLOVEる VN 来源匹配 (VNDB 标注 Younger Sister, 存疑但按权威保留)"),
    "和泉妃爱":      ("keep", "常轨脱离Creative ✓ 来源匹配"),
    "园崎诗音":      ("keep", "寒蝉角色 園崎詩音 名字唯一; 魅音之双胞胎妹妹"),
    "土间埋":       ("keep", "干物妹！小埋 ✓ 干物妹育成计划 VN; 埋是哥哥的妹妹"),
    "在原七海":      ("keep", "RIDDLE JOKER ✓ 来源匹配"),
    "大藏里想奈":    ("keep", "近月少女的礼仪 ✓ 来源匹配"),
    "天马咲希":      ("keep", "世界计划 ✓ Project Sekai 来源匹配; 天马司之妹"),
    "宫永咲":       ("keep", "天才麻将少女 ✓ Saki 游戏; 宫永照之妹 (百科确认咲是妹妹)"),
    "小鸟游亚子":    ("keep", "突然＊恋人 ✓ Making*Lovers 来源匹配"),
    "川神一子":      ("keep", "认真和我恋爱 ✓ Majikoi 来源匹配 (VNDB 标注义妹)"),
    "御津井芭华":    ("keep", "作品名即'笨蛋妹妹', 妹系确认"),
    "新海天":       ("keep", "9-nine ✓ 来源匹配; 新海翔之妹"),
    "新见雪月":      ("keep", "银色，遥远 ✓ 同一 VN (Giniro, Haruka)"),
    "日野森志步":    ("keep", "世界计划 ✓ Project Sekai; 日野森雫之妹"),
    "春原芽衣":      ("keep", "CLANNAD ✓ 来源匹配 (c15865); 春原阳平之妹"),
    "春日野穹":      ("keep", "缘之空 ✓ 来源匹配; 春日野悠之双胞胎妹妹"),
    "月社妃":       ("keep", "纸上魔法使 ✓ 同一 VN"),
    "月见里水雪":    ("keep", "未来广播与人工鸽 ✓ 来源匹配"),
    "朝雾麻衣":      ("keep", "更胜黎明前的琉璃色 ✓ 来源匹配"),
    "朱雀院椿":      ("keep", "牵绊闪耀的恋之伊吕波 ✓ 来源匹配"),
    "枣铃":         ("keep", "Little Busters ✓ 来源匹配; 枣恭介之妹"),
    "椎名立希":      ("keep", "BanG Dream; 椎名立希名字唯一, 家有姐姐"),
    "橘美也":       ("keep", "圣诞之吻 ✓ 来源匹配; 橘纯一之妹"),
    "橘麻沙音":      ("keep", "拔作岛 ✓ 同一 VN (Nukitashi); 橘淳之介之妹"),
    "水无月时雨":    ("keep", "猫娘乐园 ✓ NEKOPARA 来源匹配 (VNDB 权威)"),
    "照桥心美":      ("keep", "齐木楠雄 ✓ 恋爱游戏来源相关; 照桥信之妹"),
    "瓜生樱乃":      ("keep", "纯白交响曲 ✓ 来源匹配; 瓜生新吾之妹"),
    "秋月爱莉":      ("keep", "鬼父 ✓ 来源匹配; 秋月真理奈之妹"),
    "筒隐月子":      ("keep", "变态王子与不笑猫 ✓ 来源匹配; 筒隐筑紫之妹"),
    "结城美柑":      ("keep", "出包王女 ✓ ToLOVEる 来源匹配; 结城梨斗之妹"),
    "绪方理奈":      ("keep", "白色相簿 ✓ 来源匹配; 绪方英二之妹"),
    "绫里真宵":      ("keep", "逆转裁判 ✓ 来源匹配; 绫里千寻之妹"),
    "羽濑川小鸠":    ("keep", "我的朋友很少 ✓ 仆少 Portable; 羽濑川小鹰之妹"),
    "翠星石":       ("keep", "蔷薇少女 ✓ Rozen Maiden 来源匹配; 苍星石之双胞胎妹妹"),
    "藤林椋":       ("keep", "CLANNAD ✓ 来源匹配 (c6089); 藤林杏之双胞胎妹妹"),
    "赤锖美果子":    ("keep", "青鸟 ✓ 来源匹配"),
    "远野秋叶":      ("keep", "月姬 ✓ 来源匹配; 远野志贵之妹"),
    "速川六花":      ("keep", "anemoi ✓ 来源匹配"),
    "间桐樱":       ("keep", "Fate ✓ FSN 来源; 远坂凛之妹(过继间桐家)"),
    "鞍马小春":      ("keep", "千恋万花 ✓ 同一 VN (Senren*Banka)"),
    "高坂桐乃":      ("keep", "我的妹妹哪有这么可爱 ✓ Oreimo Portable; 头号妹系"),
    "鸨田奏海":      ("keep", "千之刃涛 ✓ 来源匹配"),
    "鹰仓杏璃":      ("keep", "幸运草的约定 ✓ Clover Day's; 义妹双胞胎"),
    "鹰仓杏铃":      ("keep", "幸运草的约定 ✓ Clover Day's; 义妹双胞胎"),
    "鹰见泽菜月":    ("keep", "更胜黎明前的琉璃色 ✓ 来源匹配"),
    "黑崎小夜":      ("keep", "青鸟 ✓ 来源匹配"),
    "黑川濑良":      ("keep", "LOVELY×CATION ✓ 来源匹配"),

    # ---------- 剔除 (同名异角: VNDB 匹配到的是别的作品同名角色) ----------
    "沙耶":    ("drop", "同名异角: 匹配到游戏《Doop》的 Saya, 非沙耶之歌的沙耶"),
    "八重樱":  ("drop", "同名异角(匹配到Junkseeker的八重桜) 且崩坏设定中是八重凛的姐姐"),
    "藤林杏":  ("drop", "同名异角: 仅匹配到寒蝉命手游的藤林杏; CLANNAD的杏是姐姐(双胞胎中的年长)"),
    "薰":      ("drop", "同名异角: 匹配到妹ペット/戦国恋姫的薫, 均非MoonFall角色"),
    "玛瑙":    ("drop", "同名异角: 匹配到『璃瑠・瑪瑠姉妹』的瑪瑙, 非异度之刃角色"),
    "白":      ("drop", "同名异角: 匹配到《天気雨》的Shiro, 非游戏人生的白"),
    "结衣":    ("drop", "同名异角: 匹配到4个Gal角色Yui, 均非刀剑神域的结衣(女儿系)"),
    "芽衣":    ("drop", "同名异角: 匹配到君と一緒に/まいしすっ的Mei, 非宝可梦的芽衣"),
    "椿":      ("drop", "同名异角: 匹配到緋の月的椿, 非鸣潮角色"),
    "I.R.I.S": ("drop", "同名异角: 匹配到picturalstory/MONSTERPARK的Iris, 非鸣潮角色"),
    "Jill":    ("drop", "同名异角: 匹配到Inside the Box的Jill, 非VA-11 Hall-A的Jill"),
    "Luce":    ("drop", "同名异角: 匹配到Dreamlink等作的Lucy, 非梵蒂冈的Luce"),
    "May":     ("drop", "同名异角: 匹配到リトルモニカ物語的May, 非罪恶装备的May"),
    "初音":    ("drop", "同名异角(匹配到明日へのおくりもの的初音) 且公主连结的初音是栞的姐姐"),
    "优奈":    ("drop", "同名异角: 匹配到想いのかけら的Yuuna, 非熊熊勇闯异世界的优奈"),
    "兰":      ("drop", "同名异角: 匹配到売約済的蘭, 非初雪樱角色"),
    "希":      ("drop", "同名异角: 匹配到蜜柑的希, 非漂流少年角色"),
    "雪":      ("drop", "同名异角: 匹配到Dream Club的Setsu, 非深夜冲击角色"),
    "宫下爱":  ("drop", "同名异角: 匹配到放課後シンデレラ2的宮下愛, 非LoveLive角色"),
    "乙姬":    ("drop", "同名异角: 匹配到鬼ごっこ的西園寺乙女(乙女≠乙姫), 非maimai角色"),
    "佐仓双叶": ("drop", "同名异角: 匹配到夢現の境界等作的佐倉双葉, 非女神异闻录的佐仓双叶(养女系)"),
    "奈美":    ("drop", "同名异角: 匹配到自慰★すぽっと等作的奈美, 非Arcaea角色"),

    # ---------- 剔除 (VNDB trait 标注与作品常识冲突) ----------
    "水濑秋子": ("drop", "Kanon来源确认, 但水濑秋子是名雪的母亲, 非任何人的妹妹; VNDB标注Younger Sister疑似数据错误"),
    "秋穰子":   ("drop", "东方Project的秋穰子是秋静叶的姐姐; VNDB经同人游戏标注Younger Sister与常识冲突"),
    "佐天泪子": ("drop", "超电磁炮来源确认, 但佐天泪子无兄弟姐妹设定; VNDB标注Younger Sister存疑"),
}


def main():
    # 基础 = 第一轮名字匹配出的保留条目 (kept_entries.json), 不是全量 wife 列表
    with open(KEPT_JSON, encoding="utf-8") as f:
        kept_entries = json.load(f)

    kept_lines, drop_rows = [], []
    stats = {"keep": set(), "drop": set()}
    for e in kept_entries:
        char = e["char"]
        decision = DECISIONS.get(char)
        if decision and decision[0] == "drop":
            drop_rows.append((char, e["line"], decision[1]))
            stats["drop"].add(char)
        else:
            kept_lines.append(e["line"])
            stats["keep"].add(char)

    with open(OUT_LIST, "w", encoding="utf-8") as f:
        f.write("\n".join(kept_lines) + "\n")

    rep = []
    rep.append("=" * 72)
    rep.append("最终妹系名单 (VNDB traits 白名单 + 人工研判)")
    rep.append("=" * 72)
    rep.append(f"第一轮匹配保留: {len(kept_entries)} 行 / {len({e['char'] for e in kept_entries})} 角色")
    rep.append(f"最终保留: {len(kept_lines)} 行 / {len(stats['keep'])} 角色")
    rep.append(f"剔除: {len(drop_rows)} 行 / {len(stats['drop'])} 角色")
    rep.append("")
    rep.append("-" * 72)
    rep.append("【保留角色】(按出现顺序)")
    rep.append("-" * 72)
    for ln in kept_lines:
        rep.append(f"  {ln}")
    rep.append("")
    rep.append("-" * 72)
    rep.append("【剔除明细】")
    rep.append("-" * 72)
    for char, ln, why in sorted(drop_rows):
        rep.append(f"  ✗ {char}  [{ln}]")
        rep.append(f"      原因: {why}")

    with open(OUT_REPORT, "w", encoding="utf-8") as f:
        f.write("\n".join(rep))

    print(f"保留: {len(kept_lines)} 行 / {len(stats['keep'])} 角色 | 剔除: {len(drop_rows)} 行 / {len(stats['drop'])} 角色")
    print(f"名单: {OUT_LIST}")
    print(f"报告: {OUT_REPORT}")


if __name__ == "__main__":
    main()
