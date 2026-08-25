# -*- coding: utf-8 -*-
"""
个人消费分析助手 (Personal Expense Analyzer)
=============================================
功能：
  1. 录入最近的消费/收入记录：日期、类型、金额、分类、收款方、用途备注等
  2. 分析收支情况与消费结构，绘制柱状图、饼状图、收支对比、人格雷达图
  3. 基于消费数据生成完整的"消费人格画像"及两条针对性建议
  4. 数据自动保存为 JSON，支持一键载入示例数据、导出 CSV

运行环境：
  - Python 3.8+
  - matplotlib  (pip install matplotlib)
运行方式：
  python expense_analyzer.py
"""

import os
import csv
import json
import math
import datetime as dt
from statistics import median

import tkinter as tk
from tkinter import ttk, messagebox, filedialog

import matplotlib
matplotlib.use("TkAgg")
import matplotlib.pyplot as plt
from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg, NavigationToolbar2Tk

# ---------------------------- 全局配置 ----------------------------

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "SimSun", "Arial Unicode MS"]
plt.rcParams["axes.unicode_minus"] = False

DATA_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "expense_data.json")

EXPENSE_CATEGORIES = ["餐饮", "交通", "购物", "娱乐", "居住", "医疗", "教育",
                      "通讯", "社交人情", "旅行", "其他"]
INCOME_CATEGORIES = ["工资", "奖金", "兼职", "理财", "红包", "报销", "其他"]

CATEGORY_COLORS = {
    "餐饮": "#FF7043", "交通": "#4FC3F7", "购物": "#EC407A", "娱乐": "#AB47BC",
    "居住": "#8D6E63", "医疗": "#EF5350", "教育": "#5C6BC0", "通讯": "#26A69A",
    "社交人情": "#FFA726", "旅行": "#42A5F5", "其他": "#90A4AE",
    "工资": "#66BB6A", "奖金": "#9CCC65", "兼职": "#7CB342", "理财": "#43A047",
    "红包": "#FFCA28", "报销": "#78909C",
}


# ---------------------------- 分析引擎 ----------------------------

def _clamp(value, low=0.0, high=100.0):
    """把数值限制在 [low, high] 区间。"""
    return max(low, min(high, value))


def analyze_summary(records):
    """汇总收支情况，返回统计字典。"""
    exps = [r for r in records if r["type"] == "支出"]
    inss = [r for r in records if r["type"] == "收入"]
    total_expense = sum(r["amount"] for r in exps)
    total_income = sum(r["amount"] for r in inss)
    balance = total_income - total_expense
    if total_income > 0:
        rate = balance / total_income
        savings_text = f"{rate * 100:.1f}%"
    else:
        rate = None
        savings_text = "—（暂无收入）"
    avg = total_expense / len(exps) if exps else 0.0
    max_exp = max((r["amount"] for r in exps), default=0.0)
    return {
        "total_income": total_income,
        "total_expense": total_expense,
        "balance": balance,
        "savings_rate": rate,
        "savings_text": savings_text,
        "count": len(records),
        "expense_count": len(exps),
        "income_count": len(inss),
        "avg": avg,
        "max": max_exp,
    }


def compute_scores(records):
    """计算消费人格六大维度得分（0-100）。

    维度：计划性 / 节俭度 / 品质追求 / 美食偏好 / 社交活跃 / 冲动指数 / 均衡性
    """
    exps = [r for r in records if r["type"] == "支出"]
    inss = [r for r in records if r["type"] == "收入"]
    n = len(exps)
    total_exp = sum(r["amount"] for r in exps)
    total_inc = sum(r["amount"] for r in inss)
    savings_rate = (total_inc - total_exp) / total_inc if total_inc > 0 else None

    amounts = sorted(r["amount"] for r in exps)
    avg = total_exp / n if n else 0.0
    med = median(amounts) if amounts else 0.0

    # 分类金额分布
    cat_amt = {}
    for r in exps:
        cat_amt[r["category"]] = cat_amt.get(r["category"], 0.0) + r["amount"]
    total = sum(cat_amt.values()) or 1.0

    food_pct = cat_amt.get("餐饮", 0.0) / total
    social_pct = sum(cat_amt.get(c, 0.0) for c in ("社交人情", "娱乐", "旅行")) / total
    luxury_pct = sum(cat_amt.get(c, 0.0) for c in ("购物", "旅行", "娱乐")) / total

    # 集中度 HHI：值越高说明消费越集中在少数分类
    hhi = sum((v / total) ** 2 for v in cat_amt.values())
    balance = _clamp((1 - hhi) * 115)

    # 消费频率（笔/天）
    if n >= 2:
        dates = [dt.date.fromisoformat(r["date"]) for r in exps]
        span = max((max(dates) - min(dates)).days, 1)
        freq = n / span
    else:
        freq = 0.0
    freq_score = _clamp(freq * 35)

    # 金额结构（用中位数做基准，避免房租等刚需大额扭曲判断）
    size_ratio = avg / med if med > 0 else 1.0
    ratio_penalty = _clamp(size_ratio - 1, 0.0, 1.5) * 18
    big_share = sum(1 for a in amounts if a >= med * 2) / n if n else 0.0
    small_share = sum(1 for a in amounts if a <= med) / n if n else 0.0

    savings_bonus = (savings_rate * 40) if savings_rate is not None else -12
    planning = _clamp(38 + (25 if savings_rate is not None else 0)
                      + savings_bonus + balance * 0.2 + (10 if n >= 3 else 0))
    frugality = _clamp(50 - ratio_penalty
                       + (savings_rate * 25 if savings_rate is not None else -12)
                       + (8 if n >= 3 else 0))
    indulgence = _clamp(luxury_pct * 130 + (18 if size_ratio > 1.3 else 0) + big_share * 15)
    foodie = _clamp(food_pct * 170 + (8 if food_pct > 0.12 else 0))
    social = _clamp(social_pct * 190 + freq_score * 0.2)
    impulse = _clamp(freq_score * 0.5 + small_share * 25
                     + (18 if size_ratio > 1.5 else 0) + (10 if n >= 6 else 0)
                     - planning * 0.2)

    return {
        "planning": planning,
        "frugality": frugality,
        "indulgence": indulgence,
        "foodie": foodie,
        "social": social,
        "impulse": impulse,
        "balance": balance,
    }


# ------------------------- 人格画像数据库 -------------------------

ARCHETYPES = {
    "planner": {
        "name": "稳健规划型",
        "desc": "你像一位自带预算表的理财师，收支安排井井有条，储蓄意识强，消费决策从容不迫。",
        "strengths": ["储蓄率可观，财务安全垫正在逐步变厚", "消费有规划、节奏稳，很少被临时种草打乱"],
        "risks": ["可能过度克制，偶尔错过提升生活品质的机会", "对突发大额支出缺少弹性预算"],
        "advice": ["每月将收入的 10%-20% 自动转入储蓄或理财账户，让存钱在不知不觉中完成",
                   "在预算中预留 5% 的「快乐基金」，给品质体验留一点从容的余地"],
    },
    "thrifty": {
        "name": "精打细算型",
        "desc": "每一分钱在你手里都能发挥最大价值，你擅长克制冲动、货比三家，是朋友眼中的省钱顾问。",
        "strengths": ["消费性价比极高，单位金额换取的效用最大", "自控力强，几乎不为情绪买单"],
        "risks": ["可能因过度在意价格而牺牲时间与体验", "偶尔的「舍不得」会降低生活幸福感"],
        "advice": ["为「体验类消费」（旅行、课程、健康）单独设一笔预算，告诉自己这些也值得投入",
                   "每周给自己一次小额「无理由奖励」，让克制与享受形成良性循环"],
    },
    "quality": {
        "name": "品质生活家",
        "desc": "你相信「贵有贵的道理」，愿意为好体验、好品质买单，追求生活的精致与愉悦感。",
        "strengths": ["审美与品味在线，消费选择往往经得起推敲", "愿意投资自己，注重长期价值"],
        "risks": ["大额享受型支出占比偏高，易挤占储蓄空间", "价格敏感度低，容易忽略比价机会"],
        "advice": ["为品质类支出设定月度上限，超出部分延迟 7 天再决定是否购买",
                   "每次大额消费后问自己一句：这是「我想要」还是「我真正需要」？"],
    },
    "foodie": {
        "name": "美食探索家",
        "desc": "你的快乐很大一部分来自味蕾，愿意为美食投入，是一枚资深吃货与生活体验派。",
        "strengths": ["懂得用美食治愈生活，幸福感知力强", "社交常以美食为纽带，人缘通常不错"],
        "risks": ["餐饮支出占比偏高，是减肥与钱包的双重隐患", "外卖/外食频次高，健康与支出都需留意"],
        "advice": ["每周设置 2-3 顿「家庭厨房日」，既控支出又更健康",
                   "把每月餐饮预算分成「日常」与「犒赏」两笔，犒赏时好好享受、日常时守住底线"],
    },
    "social": {
        "name": "社交达人型",
        "desc": "聚会、礼尚往来、娱乐社交是你生活的重要部分，你重情义、人气旺、生活热闹。",
        "strengths": ["人际关系投入积极，情感账户很充盈", "乐于分享，朋友愿意与你往来"],
        "risks": ["人情与社交支出波动大，可能影响月度预算", "容易被氛围带动消费，出现「面子消费」"],
        "advice": ["给社交支出设月度上限，超过后本月改为「低预算聚会」模式",
                   "尝试把部分社交从「花钱场所」转移到家里、公园等低成本场景，情谊不减"],
    },
    "impulsive": {
        "name": "随心消费型",
        "desc": "你的消费跟随心情走，小额高频、说买就买，享受即时的快乐，但也容易在深夜后悔。",
        "strengths": ["行动力强，敢于取悦当下的自己", "心态开放，对新事物保持好奇"],
        "risks": ["碎片化支出累积可观，容易「小钱不断、大钱不见」", "冲动购买的商品闲置率通常偏高"],
        "advice": ["采用「24 小时冷静期」：想买的东西先加入购物车，隔天再决定",
                   "把常用支付工具的免密/自动扣款关掉，让每笔支出多一次「确认」的机会"],
    },
    "balanced": {
        "name": "均衡生活家",
        "desc": "你的消费结构多元均衡，没有明显偏科，收支节奏稳定，是典型的稳妥型消费者。",
        "strengths": ["生活维度丰富，消费面广而不失分寸", "风险承受与抗波动能力较强"],
        "risks": ["各分类占比平均，可能在某一关键领域投入不足", "缺乏重点规划，长期目标感偏弱"],
        "advice": ["为未来 6-12 个月的明确目标（如旅行、换机、学习）单列一笔专项储蓄",
                   "每月复盘时挑一个「最想加强」的领域，定向倾斜 5% 的预算"],
    },
    "focused": {
        "name": "单点专注型",
        "desc": "你的钱高度集中在少数领域，生活重心明确，是一根筋的「实力派」。",
        "strengths": ["核心需求保障有力，舍得在关键处投入", "目标感强，不轻易被分散注意力"],
        "risks": ["消费结构单一，其他维度的体验容易被忽视", "集中度过高，一旦重心变化支出压力会骤增"],
        "advice": ["从主要支出中每月挪出 5%-10%，用于拓展一两个新领域的小体验",
                   "为集中度最高的分类设置「刚性上限」，超出部分必须二次确认"],
    },
    "casual": {
        "name": "自由随性型",
        "desc": "你的消费风格松弛自由，不设框架、走一步看一步，生活随性而有弹性。",
        "strengths": ["心态轻松，不给自己过多压力", "可塑性强，调整空间大"],
        "risks": ["缺少预算框架，月末容易「钱去哪儿了」", "收入与支出缺少对应关系，储蓄较被动"],
        "advice": ["用「三账户法」：发薪日把生活费、储蓄、娱乐各划一个账户，专款专用",
                   "每周日花 2 分钟快速回顾本周支出，只需看一眼分类汇总即可"],
    },
    "observer": {
        "name": "待观察型",
        "desc": "目前支出样本较少（或只有收入记录），画像暂不成熟，先积累几笔消费再回来看看吧。",
        "strengths": ["数据积累阶段，一切皆有可能", "你已经开始记账，这本身就是很好的起点"],
        "risks": ["样本不足时，任何结论都仅供参考"],
        "advice": ["坚持记录至少一周的每一笔支出，样本越全画像越准",
                   "同时录入收入记录，才能算出真实的储蓄率与规划空间"],
    },
}


def pick_archetype(records, scores, savings_rate):
    """根据得分挑选最匹配的人格画像。"""
    exps = [r for r in records if r["type"] == "支出"]
    n = len(exps)
    if n == 0:
        return ARCHETYPES["observer"]

    dims = {
        "frugality": scores["frugality"],
        "indulgence": scores["indulgence"],
        "foodie": scores["foodie"],
        "social": scores["social"],
        "impulse": scores["impulse"],
    }
    top_name = max(dims, key=dims.get)

    if scores["impulse"] >= 68 and top_name == "impulse" and scores["planning"] < 55:
        arch_id = "impulsive"
    elif scores["frugality"] >= 62 and (top_name == "frugality" or (savings_rate or 0) >= 0.3):
        arch_id = "thrifty"
    elif (savings_rate or 0) >= 0.25 and scores["planning"] >= 55 and scores["balance"] >= 55:
        arch_id = "planner"
    elif scores["indulgence"] >= 55 and top_name == "indulgence":
        arch_id = "quality"
    elif scores["foodie"] >= 55 and top_name == "foodie":
        arch_id = "foodie"
    elif scores["social"] >= 55 and top_name == "social":
        arch_id = "social"
    elif scores["balance"] >= 65 and scores["planning"] >= 45:
        arch_id = "balanced"
    elif scores["balance"] < 38 and n >= 3:
        arch_id = "focused"
    else:
        arch_id = "casual"
    return ARCHETYPES[arch_id]


def make_report(records):
    """生成完整的人格报告文本。"""
    exps = [r for r in records if r["type"] == "支出"]
    inss = [r for r in records if r["type"] == "收入"]
    if not records:
        return "暂无任何记录。\n\n请先在左侧录入最近的消费与收入，再生成画像。"

    total_exp = sum(r["amount"] for r in exps)
    total_inc = sum(r["amount"] for r in inss)
    balance = total_inc - total_exp
    savings_rate = (total_inc - total_exp) / total_inc if total_inc > 0 else None

    cat_amt = {}
    for r in exps:
        cat_amt[r["category"]] = cat_amt.get(r["category"], 0.0) + r["amount"]
    total = sum(cat_amt.values()) or 1.0
    top = sorted(cat_amt.items(), key=lambda x: x[1], reverse=True)[:3]

    scores = compute_scores(records)
    arch = pick_archetype(records, scores, savings_rate)

    lines = []
    lines.append("=" * 48)
    lines.append("          个 人 消 费 人 格 报 告")
    lines.append("=" * 48)
    lines.append(f"生成时间：{dt.datetime.now():%Y-%m-%d %H:%M}")
    lines.append(f"统计样本：{len(records)} 笔记录（支出 {len(exps)} 笔 / 收入 {len(inss)} 笔）")
    lines.append("")

    lines.append("【一、收支总览】")
    lines.append(f"  总收入：{total_inc:,.2f} 元")
    lines.append(f"  总支出：{total_exp:,.2f} 元")
    lines.append(f"  结余：{balance:,.2f} 元" + ("　（入不敷出，注意！）" if balance < 0 else ""))
    if savings_rate is not None:
        lines.append(f"  储蓄率：{savings_rate * 100:.1f}%")
    else:
        lines.append("  储蓄率：暂无收入数据，无法计算")
    if exps:
        avg = total_exp / len(exps)
        mx = max(r["amount"] for r in exps)
        lines.append(f"  支出笔均：{avg:.2f} 元　/　最大单笔：{mx:,.2f} 元")
    lines.append("")

    lines.append("【二、消费结构】")
    if top:
        for name, amt in top:
            lines.append(f"  {name}：{amt:,.2f} 元（占 {amt / total * 100:.1f}%）")
    else:
        lines.append("  暂无支出数据")
    lines.append("")

    lines.append("【三、消费人格画像】")
    lines.append(f"  人格类型：{arch['name']}")
    lines.append(f"  一句话画像：{arch['desc']}")
    lines.append("")

    lines.append("  优势特质：")
    for s in arch["strengths"]:
        lines.append(f"    + {s}")
    lines.append("")
    lines.append("  潜在风险：")
    for r_ in arch["risks"]:
        lines.append(f"    - {r_}")
    lines.append("")

    lines.append("【四、给您的两条建议】")
    for i, adv in enumerate(arch["advice"], 1):
        lines.append(f"  建议{i}：{adv}")
    lines.append("")
    lines.append("（说明：画像基于您录入的数据做规则化分析，样本越多、覆盖天数越长，结论越接近真实。）")

    return "\n".join(lines)


# ------------------------------ 图表 ------------------------------

def build_figure(records):
    """构建 2x2 分析图表：分类柱状图 / 占比饼图 / 每日收支 / 人格雷达。"""
    exps = [r for r in records if r["type"] == "支出"]

    fig = plt.Figure(figsize=(12, 9), dpi=100)
    fig.suptitle("个人收支分析报告", fontsize=16, fontweight="bold")

    cat_amt = {}
    for r in exps:
        cat_amt[r["category"]] = cat_amt.get(r["category"], 0.0) + r["amount"]

    # 1) 分类支出柱状图
    ax1 = fig.add_subplot(221)
    if cat_amt:
        cats = sorted(cat_amt, key=lambda c: cat_amt[c], reverse=True)
        vals = [cat_amt[c] for c in cats]
        colors = [CATEGORY_COLORS.get(c, "#90A4AE") for c in cats]
        bars = ax1.bar(cats, vals, color=colors)
        for bar, v in zip(bars, vals):
            ax1.text(bar.get_x() + bar.get_width() / 2, v, f"{v:,.0f}",
                     ha="center", va="bottom", fontsize=9)
        ax1.set_xticks(range(len(cats)))
        ax1.set_xticklabels(cats, rotation=25, ha="right")
    else:
        ax1.text(0.5, 0.5, "暂无支出数据", ha="center", va="center",
                 transform=ax1.transAxes, color="#888888")
    ax1.set_title("各分类支出金额（元）")
    ax1.grid(axis="y", linestyle="--", alpha=0.4)

    # 2) 消费类型占比饼图
    ax2 = fig.add_subplot(222)
    if cat_amt:
        labels = list(cat_amt.keys())
        sizes = list(cat_amt.values())
        colors = [CATEGORY_COLORS.get(c, "#90A4AE") for c in labels]
        ax2.pie(sizes, labels=labels, colors=colors, autopct="%1.1f%%",
                startangle=90, pctdistance=0.75, textprops={"fontsize": 9})
        ax2.set_title("消费类型占比")
    else:
        ax2.text(0.5, 0.5, "暂无支出数据", ha="center", va="center",
                 transform=ax2.transAxes, color="#888888")
        ax2.set_title("消费类型占比")

    # 3) 每日收支对比（最多展示最近 14 天）
    ax3 = fig.add_subplot(223)
    by_date = {}
    for r in records:
        by_date.setdefault(r["date"], {"支出": 0.0, "收入": 0.0})[r["type"]] += r["amount"]
    dates = sorted(by_date)
    if len(dates) > 14:
        dates = dates[-14:]
    incs = [by_date[d]["收入"] for d in dates]
    exps_ = [by_date[d]["支出"] for d in dates]
    x = list(range(len(dates)))
    width = 0.38
    ax3.bar([i - width / 2 for i in x], exps_, width=width, label="支出", color="#EF5350")
    ax3.bar([i + width / 2 for i in x], incs, width=width, label="收入", color="#66BB6A")
    ax3.set_xticks(x)
    ax3.set_xticklabels(dates, rotation=40, ha="right", fontsize=8)
    ax3.legend(fontsize=9)
    ax3.set_title("每日收支对比（元）")
    ax3.grid(axis="y", linestyle="--", alpha=0.4)

    # 4) 消费人格六维雷达图
    scores = compute_scores(records)
    dims = ["计划性", "节俭度", "品质追求", "美食偏好", "社交活跃", "均衡性"]
    vals = [scores["planning"], scores["frugality"], scores["indulgence"],
            scores["foodie"], scores["social"], scores["balance"]]
    ax4 = fig.add_subplot(224, projection="polar")
    angles = [i / len(dims) * 2 * math.pi for i in range(len(dims))]
    plot_vals = vals + vals[:1]
    plot_angles = angles + angles[:1]
    ax4.plot(plot_angles, plot_vals, color="#42A5F5", linewidth=2)
    ax4.fill(plot_angles, plot_vals, color="#42A5F5", alpha=0.25)
    ax4.set_xticks(angles)
    ax4.set_xticklabels(dims, fontsize=10)
    ax4.set_ylim(0, 100)
    ax4.set_title("消费人格六维雷达图", pad=20)
    ax4.grid(True)

    fig.tight_layout(rect=[0, 0, 1, 0.95])
    return fig


# ---------------------------- 示例数据 ----------------------------

def load_demo_data():
    """生成一组覆盖常见分类的示例记录。"""
    today = dt.date.today()
    days = lambda offset: (today - dt.timedelta(days=offset)).isoformat()
    return [
        {"date": days(1), "type": "收入", "category": "工资", "amount": 12000.00,
         "payee": "某某科技有限公司", "note": "8月工资"},
        {"date": days(1), "type": "支出", "category": "居住", "amount": 2800.00,
         "payee": "安居公寓", "note": "房租"},
        {"date": days(1), "type": "支出", "category": "餐饮", "amount": 35.50,
         "payee": "老乡鸡", "note": "午餐"},
        {"date": days(2), "type": "支出", "category": "交通", "amount": 12.00,
         "payee": "地铁", "note": "通勤"},
        {"date": days(2), "type": "支出", "category": "餐饮", "amount": 128.00,
         "payee": "海底捞", "note": "朋友聚餐"},
        {"date": days(3), "type": "支出", "category": "购物", "amount": 499.00,
         "payee": "京东自营", "note": "蓝牙耳机"},
        {"date": days(3), "type": "支出", "category": "娱乐", "amount": 88.00,
         "payee": "万达影城", "note": "电影"},
        {"date": days(4), "type": "支出", "category": "餐饮", "amount": 22.00,
         "payee": "瑞幸咖啡", "note": "拿铁"},
        {"date": days(5), "type": "支出", "category": "社交人情", "amount": 300.00,
         "payee": "微信红包", "note": "朋友生日礼金"},
        {"date": days(6), "type": "支出", "category": "通讯", "amount": 58.00,
         "payee": "中国移动", "note": "话费"},
        {"date": days(7), "type": "收入", "category": "理财", "amount": 120.50,
         "payee": "余额宝", "note": "理财收益"},
        {"date": days(7), "type": "支出", "category": "医疗", "amount": 45.00,
         "payee": "康民大药房", "note": "感冒药"},
    ]


# ------------------------------ 界面 ------------------------------

class ExpenseApp:
    def __init__(self, root):
        self.root = root
        self.root.title("个人消费分析助手")
        self.root.geometry("1020x760")
        self.root.minsize(920, 680)

        self.records = []
        self._editing_id = None
        self._load_data()
        self._build_ui()
        self._refresh_table()
        self._update_summary()

    # -------------------- 数据持久化 --------------------

    def _load_data(self):
        if os.path.exists(DATA_FILE):
            try:
                with open(DATA_FILE, "r", encoding="utf-8") as f:
                    self.records = json.load(f)
            except Exception:
                self.records = []
        else:
            self.records = []
        self._normalize_records()

    def _normalize_records(self):
        seen = {r["id"] for r in self.records if "id" in r}
        next_id = max(seen, default=0) + 1
        for r in self.records:
            if "id" not in r:
                r["id"] = next_id
                next_id += 1
            r.setdefault("date", dt.date.today().isoformat())
            r.setdefault("type", "支出")
            r.setdefault("category", "其他")
            r.setdefault("amount", 0.0)
            r.setdefault("payee", "")
            r.setdefault("note", "")

    def _save_data(self):
        try:
            with open(DATA_FILE, "w", encoding="utf-8") as f:
                json.dump(self.records, f, ensure_ascii=False, indent=2)
        except Exception as exc:
            messagebox.showwarning("保存失败", f"数据未能写入本地文件：\n{exc}")

    # -------------------- 界面搭建 --------------------

    def _build_ui(self):
        style = ttk.Style(self.root)
        for name in ("TLabel", "TButton", "TEntry", "TCombobox"):
            try:
                style.configure(name, font=("Microsoft YaHei UI", 10))
            except Exception:
                pass
        style.configure("Title.TLabel", font=("Microsoft YaHei UI", 16, "bold"),
                        foreground="#1F6FB2")
        style.configure("Val.TLabel", font=("Microsoft YaHei UI", 11, "bold"),
                        foreground="#0D47A1")
        style.configure("Key.TLabel", font=("Microsoft YaHei UI", 9),
                        foreground="#546E7A")
        style.configure("Accent.TButton", font=("Microsoft YaHei UI", 11, "bold"))
        style.configure("Treeview", rowheight=28)

        # 顶部标题
        header = ttk.Frame(self.root, padding=(14, 10, 14, 4))
        header.pack(fill="x")
        ttk.Label(header, text="个人消费分析助手", style="Title.TLabel").pack(side="left")
        ttk.Label(header, text="录入消费 → 查看图表 → 生成人格画像",
                  style="Key.TLabel").pack(side="right")

        # 录入区
        form = ttk.LabelFrame(self.root, text=" 录入一笔消费 / 收入 ", padding=(12, 8))
        form.pack(fill="x", padx=14, pady=(6, 2))

        self.date_var = tk.StringVar(value=dt.date.today().isoformat())
        self.type_var = tk.StringVar(value="支出")
        self.cat_var = tk.StringVar(value="餐饮")
        self.amount_var = tk.StringVar()
        self.payee_var = tk.StringVar()
        self.note_var = tk.StringVar()

        ttk.Label(form, text="日期").grid(row=0, column=0, padx=(0, 4), pady=4, sticky="e")
        date_entry = ttk.Entry(form, textvariable=self.date_var, width=12)
        date_entry.grid(row=0, column=1, padx=(0, 12), sticky="w")

        ttk.Label(form, text="类型").grid(row=0, column=2, padx=(0, 4), sticky="e")
        type_combo = ttk.Combobox(form, textvariable=self.type_var, state="readonly",
                                  values=["支出", "收入"], width=7)
        type_combo.grid(row=0, column=3, padx=(0, 12), sticky="w")
        type_combo.bind("<<ComboboxSelected>>", self._on_type_change)

        ttk.Label(form, text="分类").grid(row=0, column=4, padx=(0, 4), sticky="e")
        self.cat_combo = ttk.Combobox(form, textvariable=self.cat_var, width=10,
                                      values=EXPENSE_CATEGORIES)
        self.cat_combo.grid(row=0, column=5, padx=(0, 12), sticky="w")

        ttk.Label(form, text="金额(元)").grid(row=0, column=6, padx=(0, 4), sticky="e")
        amount_entry = ttk.Entry(form, textvariable=self.amount_var, width=12)
        amount_entry.grid(row=0, column=7, padx=(0, 12), sticky="w")
        amount_entry.bind("<Return>", lambda e: self._add_record())

        ttk.Label(form, text="收款方").grid(row=0, column=8, padx=(0, 4), sticky="e")
        ttk.Entry(form, textvariable=self.payee_var, width=16).grid(
            row=0, column=9, padx=(0, 12), sticky="w")

        ttk.Label(form, text="用途/备注").grid(row=1, column=0, padx=(0, 4), sticky="e")
        ttk.Entry(form, textvariable=self.note_var, width=30).grid(
            row=1, column=1, columnspan=6, padx=(0, 12), sticky="we")

        self.add_btn = ttk.Button(form, text="＋ 添加记录", style="Accent.TButton",
                                  command=self._add_record)
        self.add_btn.grid(row=1, column=7, columnspan=3, sticky="we", padx=4)

        # 工具栏
        toolbar = ttk.Frame(self.root, padding=(14, 4))
        toolbar.pack(fill="x")
        ttk.Button(toolbar, text="删除选中", command=self._delete_selected).pack(side="left")
        ttk.Button(toolbar, text="载入示例数据", command=self._load_demo).pack(side="left", padx=6)
        ttk.Button(toolbar, text="清空数据", command=self._clear_data).pack(side="left")
        ttk.Button(toolbar, text="导出 CSV", command=self._export_csv).pack(side="left", padx=6)
        ttk.Label(toolbar, text="提示：双击表格行可回填修改",
                  style="Key.TLabel").pack(side="right")

        # 记录表格
        table_frame = ttk.Frame(self.root, padding=(14, 0))
        table_frame.pack(fill="both", expand=True)
        columns = ("date", "type", "category", "amount", "payee", "note")
        self.tree = ttk.Treeview(table_frame, columns=columns, show="headings", selectmode="extended")
        headings = {"date": "日期", "type": "类型", "category": "分类",
                    "amount": "金额(元)", "payee": "收款方", "note": "用途/备注"}
        widths = {"date": 100, "type": 60, "category": 90, "amount": 100,
                  "payee": 190, "note": 220}
        for col in columns:
            self.tree.heading(col, text=headings[col])
            self.tree.column(col, width=widths[col], anchor="center" if col in ("date", "type", "category", "amount") else "w")
        self.tree.tag_configure("expense", foreground="#C62828")
        self.tree.tag_configure("income", foreground="#2E7D32")
        self.tree.tag_configure("editing", background="#FFF9C4")
        self.tree.bind("<Double-1>", self._edit_row)

        vsb = ttk.Scrollbar(table_frame, orient="vertical", command=self.tree.yview)
        hsb = ttk.Scrollbar(table_frame, orient="horizontal", command=self.tree.xview)
        self.tree.configure(yscrollcommand=vsb.set, xscrollcommand=hsb.set)
        self.tree.grid(row=0, column=0, sticky="nsew")
        vsb.grid(row=0, column=1, sticky="ns")
        hsb.grid(row=1, column=0, sticky="ew")
        table_frame.rowconfigure(0, weight=1)
        table_frame.columnconfigure(0, weight=1)

        # 收支概览
        summary = ttk.LabelFrame(self.root, text=" 收支概览 ", padding=(12, 8))
        summary.pack(fill="x", padx=14, pady=8)

        self.sv_income = tk.StringVar()
        self.sv_expense = tk.StringVar()
        self.sv_balance = tk.StringVar()
        self.sv_rate = tk.StringVar()
        self.sv_count = tk.StringVar()
        self.sv_avg = tk.StringVar()
        self.sv_max = tk.StringVar()

        items = [
            ("总收入", self.sv_income), ("总支出", self.sv_expense),
            ("结余", self.sv_balance), ("储蓄率", self.sv_rate),
            ("记录数", self.sv_count), ("笔均支出", self.sv_avg),
            ("最大单笔", self.sv_max),
        ]
        for i, (key, var) in enumerate(items):
            col = i * 2
            ttk.Label(summary, text=key, style="Key.TLabel").grid(
                row=0, column=col, padx=(12 if col else 0, 4), pady=4, sticky="e")
            ttk.Label(summary, textvariable=var, style="Val.TLabel").grid(
                row=0, column=col + 1, sticky="w")

        # 分析操作区
        actions = ttk.Frame(self.root, padding=(14, 0, 14, 12))
        actions.pack(fill="x")
        ttk.Button(actions, text="📊 查看收支图表", style="Accent.TButton",
                   command=self._show_charts).pack(side="left")
        ttk.Button(actions, text="🧬 生成消费人格报告", style="Accent.TButton",
                   command=self._show_report).pack(side="left", padx=10)

        self.status_var = tk.StringVar(
            value=f"数据自动保存至：{DATA_FILE}")
        ttk.Label(self.root, textvariable=self.status_var, style="Key.TLabel",
                  anchor="e", padding=(14, 2, 14, 6)).pack(fill="x")

    # -------------------- 事件处理 --------------------

    def _on_type_change(self, _event=None):
        if self.type_var.get() == "收入":
            self.cat_combo.config(values=INCOME_CATEGORIES)
            if self.cat_var.get() not in INCOME_CATEGORIES:
                self.cat_var.set("工资")
        else:
            self.cat_combo.config(values=EXPENSE_CATEGORIES)
            if self.cat_var.get() not in EXPENSE_CATEGORIES:
                self.cat_var.set("餐饮")

    def _add_record(self):
        date = self.date_var.get().strip()
        rtype = self.type_var.get()
        cat = self.cat_var.get().strip() or "其他"
        amount_text = self.amount_var.get().strip()
        payee = self.payee_var.get().strip()
        note = self.note_var.get().strip()

        if not date:
            messagebox.showwarning("信息不完整", "请填写日期。")
            return
        try:
            dt.date.fromisoformat(date)
        except ValueError:
            messagebox.showwarning("日期格式错误", "日期请使用 YYYY-MM-DD 格式，例如 2026-08-23。")
            return
        try:
            amount = float(amount_text)
            if amount <= 0:
                raise ValueError
        except ValueError:
            messagebox.showwarning("金额格式错误", "金额必须是大于 0 的数字。")
            return

        if self._editing_id is not None:
            for r in self.records:
                if r["id"] == self._editing_id:
                    r.update({"date": date, "type": rtype, "category": cat,
                              "amount": amount, "payee": payee, "note": note})
                    break
            self._editing_id = None
            self.add_btn.config(text="＋ 添加记录")
        else:
            new_id = max((r["id"] for r in self.records), default=0) + 1
            self.records.append({"id": new_id, "date": date, "type": rtype,
                                 "category": cat, "amount": amount,
                                 "payee": payee, "note": note})

        self._save_data()
        self._refresh_table()
        self._update_summary()
        self._clear_form()

    def _clear_form(self):
        self.date_var.set(dt.date.today().isoformat())
        self.amount_var.set("")
        self.payee_var.set("")
        self.note_var.set("")

    def _edit_row(self, _event=None):
        sel = self.tree.selection()
        if not sel:
            return
        rid = int(sel[0])
        rec = next((r for r in self.records if r["id"] == rid), None)
        if not rec:
            return
        self._editing_id = rid
        self.date_var.set(rec["date"])
        self.type_var.set(rec["type"])
        self._on_type_change()
        self.cat_var.set(rec["category"])
        self.amount_var.set(f"{rec['amount']:g}")
        self.payee_var.set(rec["payee"])
        self.note_var.set(rec["note"])
        self.add_btn.config(text="✓ 保存修改")
        self.status_var.set(f"正在编辑记录 #{rid}，修改后点击「保存修改」。")

    def _delete_selected(self):
        sel = self.tree.selection()
        if not sel:
            messagebox.showinfo("未选择", "请先在表格中选中要删除的记录。")
            return
        if not messagebox.askyesno("确认删除", f"确定删除选中的 {len(sel)} 条记录吗？"):
            return
        ids = {int(i) for i in sel}
        self.records = [r for r in self.records if r["id"] not in ids]
        if self._editing_id in ids:
            self._editing_id = None
            self.add_btn.config(text="＋ 添加记录")
        self._save_data()
        self._refresh_table()
        self._update_summary()

    def _load_demo(self):
        if self.records and not messagebox.askyesno(
                "载入示例", "当前已有数据，载入示例将替换现有记录，是否继续？"):
            return
        self.records = load_demo_data()
        self._normalize_records()
        self._save_data()
        self._refresh_table()
        self._update_summary()
        messagebox.showinfo("载入完成", "已载入 12 条示例记录，可直接生成图表与人格报告。")

    def _clear_data(self):
        if not self.records:
            return
        if not messagebox.askyesno("确认清空", "确定清空全部记录吗？此操作不可撤销。"):
            return
        self.records = []
        self._editing_id = None
        self.add_btn.config(text="＋ 添加记录")
        self._save_data()
        self._refresh_table()
        self._update_summary()

    def _export_csv(self):
        if not self.records:
            messagebox.showinfo("暂无数据", "当前没有可导出的记录。")
            return
        path = filedialog.asksaveasfilename(
            defaultextension=".csv", initialfile="消费记录.csv",
            filetypes=[("CSV 文件", "*.csv")])
        if not path:
            return
        try:
            with open(path, "w", newline="", encoding="utf-8-sig") as f:
                writer = csv.writer(f)
                writer.writerow(["日期", "类型", "分类", "金额", "收款方", "用途/备注"])
                for r in self.records:
                    writer.writerow([r["date"], r["type"], r["category"],
                                     r["amount"], r["payee"], r["note"]])
        except Exception as exc:
            messagebox.showerror("导出失败", str(exc))
            return
        messagebox.showinfo("导出成功", f"已导出 {len(self.records)} 条记录到：\n{path}")

    def _show_charts(self):
        if not self.records:
            messagebox.showinfo("暂无数据", "请先添加消费记录，或点击「载入示例数据」。")
            return
        win = tk.Toplevel(self.root)
        win.title("收支分析图表")
        win.geometry("1180x880")
        fig = build_figure(self.records)
        canvas = FigureCanvasTkAgg(fig, master=win)
        canvas.draw()
        canvas.get_tk_widget().pack(fill="both", expand=True)
        NavigationToolbar2Tk(canvas, win).update()

    def _show_report(self):
        if not self.records:
            messagebox.showinfo("暂无数据", "请先添加消费记录，或点击「载入示例数据」。")
            return
        text = make_report(self.records)
        win = tk.Toplevel(self.root)
        win.title("消费人格报告")
        win.geometry("720x780")
        win.configure(bg="#FDFBF4")

        txt = tk.Text(win, wrap="word", font=("Microsoft YaHei UI", 11),
                      bg="#FDFBF4", fg="#263238", padx=20, pady=16,
                      relief="flat", borderwidth=0)
        scroll = ttk.Scrollbar(win, command=txt.yview)
        txt.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        txt.pack(fill="both", expand=True)

        txt.tag_configure("title", font=("Microsoft YaHei UI", 13, "bold"),
                          foreground="#1A237E")
        txt.tag_configure("h", font=("Microsoft YaHei UI", 12, "bold"),
                          foreground="#00695C")
        txt.tag_configure("key", font=("Microsoft YaHei UI", 11, "bold"),
                          foreground="#6A1B9A")
        txt.tag_configure("plus", foreground="#2E7D32")
        txt.tag_configure("minus", foreground="#C62828")
        txt.tag_configure("advice", font=("Microsoft YaHei UI", 11, "bold"),
                          foreground="#B4530A")

        txt.insert("end", text + "\n")
        for tag, pattern in (("title", "=" * 4), ("h", "【"), ("plus", "  + "),
                             ("minus", "  - "), ("advice", "  建议"), ("key", "  人格类型：")):
            start = "1.0"
            while True:
                pos = txt.search(pattern, start, stopindex="end")
                if not pos:
                    break
                line_start = txt.index(f"{pos} linestart")
                line_end = txt.index(f"{pos} lineend")
                txt.tag_add(tag, line_start, line_end)
                start = txt.index(f"{line_end} +1c")

    # -------------------- 刷新 --------------------

    def _refresh_table(self):
        for item in self.tree.get_children():
            self.tree.delete(item)
        for r in self.records:
            tag = "income" if r["type"] == "收入" else "expense"
            self.tree.insert("", "end", iid=str(r["id"]), tags=(tag,),
                             values=(r["date"], r["type"], r["category"],
                                     f"{r['amount']:,.2f}", r["payee"], r["note"]))
        if self._editing_id is not None and str(self._editing_id) in self.tree.get_children():
            self.tree.item(str(self._editing_id), tags=("editing",))

    def _update_summary(self):
        s = analyze_summary(self.records)
        self.sv_income.set(f"{s['total_income']:,.2f}")
        self.sv_expense.set(f"{s['total_expense']:,.2f}")
        self.sv_balance.set(f"{s['balance']:,.2f}")
        self.sv_rate.set(s["savings_text"])
        self.sv_count.set(f"{s['count']} 笔")
        self.sv_avg.set(f"{s['avg']:,.2f}")
        self.sv_max.set(f"{s['max']:,.2f}")


def main():
    root = tk.Tk()
    ExpenseApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
