// =====================================================================
// 个人消费分析助手 (Personal Expense Analyzer) - C++ / Win32 版本
// =====================================================================
// 用纯 Win32 API + GDI+ 实现，无需任何第三方库。
// 功能与 Python 版完全一致：
//   1. 录入消费/收入记录（日期、类型、分类、金额、收款方、用途备注）
//   2. 分析收支情况与消费结构，绘制柱状图/饼图/每日对比/雷达图
//   3. 基于消费数据生成"消费人格画像"及两条针对性建议
//   4. 数据自动保存为 JSON，支持载入示例数据、导出 CSV
//
// 编译方式（MSVC，x64）：
//   vcvarsall.bat x64 之后：
//   cl /EHsc /std:c++17 /O2 expense_analyzer.cpp /Fe:expense_analyzer.exe ^
//      user32.lib gdi32.lib gdiplus.lib comdlg32.lib comctl32.lib shell32.lib
// =====================================================================

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
// 不定义 WIN32_LEAN_AND_MEAN，避免移除 GDI+ 需要的 COM/objidl 定义
#define NOMINMAX
#include <windows.h>
#include <windowsx.h>
#include <commctrl.h>
#include <commdlg.h>
#include <richedit.h>
#include <gdiplus.h>
#include <shellapi.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <string>
#include <vector>
#include <map>
#include <set>
#include <algorithm>
#include <numeric>
#include <cmath>
#include <fstream>
#include <sstream>
#include <functional>
#include <cwctype>

namespace {
const double PI = 3.14159265358979323846;
}  // namespace

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "gdiplus.lib")
#pragma comment(lib, "comdlg32.lib")
#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "shell32.lib")

using namespace Gdiplus;

// ===================== 工具函数 =====================

static std::wstring s2ws(const std::string& s) {
    if (s.empty()) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), nullptr, 0);
    std::wstring w(len, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.c_str(), (int)s.size(), &w[0], len);
    return w;
}

static std::string ws2s(const std::wstring& w) {
    if (w.empty()) return "";
    int len = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
    std::string s(len, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), &s[0], len, nullptr, nullptr);
    return s;
}

static std::wstring trim(const std::wstring& s) {
    size_t a = s.find_first_not_of(L" \t\r\n");
    if (a == std::wstring::npos) return L"";
    size_t b = s.find_last_not_of(L" \t\r\n");
    return s.substr(a, b - a + 1);
}

static double clampd(double v, double lo = 0.0, double hi = 100.0) {
    return std::max(lo, std::min(hi, v));
}

static std::wstring fmtMoney(double v) {
    wchar_t buf[64];
    swprintf(buf, 64, L"%.2f", v);
    return std::wstring(buf);
}

static std::wstring fmtInt(double v) {
    wchar_t buf[32];
    swprintf(buf, 32, L"%.0f", v);
    return std::wstring(buf);
}

// ===================== 数据模型 =====================

struct Record {
    int id = 0;
    std::wstring date;      // YYYY-MM-DD
    std::wstring type;      // 支出 / 收入
    std::wstring category;  // 分类
    double amount = 0.0;
    std::wstring payee;
    std::wstring note;
};

// 分类颜色表
struct CatColor { const wchar_t* name; DWORD color; };
static const CatColor kExpenseColors[] = {
    {L"餐饮", 0xFF7043}, {L"交通", 0x4FC3F7}, {L"购物", 0xEC407A}, {L"娱乐", 0xAB47BC},
    {L"居住", 0x8D6E63}, {L"医疗", 0xEF5350}, {L"教育", 0x5C6BC0}, {L"通讯", 0x26A69A},
    {L"社交人情", 0xFFA726}, {L"旅行", 0x42A5F5}, {L"其他", 0x90A4AE},
    {L"工资", 0x66BB6A}, {L"奖金", 0x9CCC65}, {L"兼职", 0x7CB342}, {L"理财", 0x43A047},
    {L"红包", 0xFFCA28}, {L"报销", 0x78909C},
};
static DWORD colorForCategory(const std::wstring& cat) {
    for (auto& c : kExpenseColors)
        if (cat == c.name) return c.color;
    return 0x90A4AE;
}

static const wchar_t* kExpenseCategories[] = {
    L"餐饮", L"交通", L"购物", L"娱乐", L"居住", L"医疗",
    L"教育", L"通讯", L"社交人情", L"旅行", L"其他", nullptr
};
static const wchar_t* kIncomeCategories[] = {
    L"工资", L"奖金", L"兼职", L"理财", L"红包", L"报销", L"其他", nullptr
};

// ===================== JSON 极简序列化 =====================

static std::string jsonEscape(const std::wstring& s) {
    std::string out;
    for (wchar_t c : s) {
        if (c == L'"') out += "\\\"";
        else if (c == L'\\') out += "\\\\";
        else if (c == L'\n') out += "\\n";
        else if (c == L'\r') out += "\\r";
        else if (c == L'\t') out += "\\t";
        else if (c < 0x20) { char buf[8]; snprintf(buf, 8, "\\u%04x", c); out += buf; }
        else if (c >= 0x80) {
            char buf[8];
            int n = WideCharToMultiByte(CP_UTF8, 0, &c, 1, buf, 8, nullptr, nullptr);
            out.append(buf, n);
        } else out += (char)c;
    }
    return out;
}

static std::string recordsToJson(const std::vector<Record>& recs) {
    std::string j = "[";
    for (size_t i = 0; i < recs.size(); ++i) {
        const Record& r = recs[i];
        j += "{";
        j += "\"id\":" + std::to_string(r.id) + ",";
        j += "\"date\":\"" + jsonEscape(r.date) + "\",";
        j += "\"type\":\"" + jsonEscape(r.type) + "\",";
        j += "\"category\":\"" + jsonEscape(r.category) + "\",";
        char amt[64]; snprintf(amt, 64, "%.4f", r.amount);
        j += "\"amount\":" + std::string(amt) + ",";
        j += "\"payee\":\"" + jsonEscape(r.payee) + "\",";
        j += "\"note\":\"" + jsonEscape(r.note) + "\"";
        j += "}";
        if (i + 1 < recs.size()) j += ",";
    }
    j += "]";
    return j;
}

// 极简 JSON 数组解析（从字符串提取各字段值）
static std::string extractStrField(const std::string& body, const std::string& key) {
    std::string pat = "\"" + key + "\":\"";
    size_t p = body.find(pat);
    if (p == std::string::npos) return "";
    p += pat.size();
    std::string out;
    while (p < body.size() && body[p] != '"') {
        if (body[p] == '\\' && p + 1 < body.size()) {
            if (body[p + 1] == 'n') out += '\n';
            else if (body[p + 1] == 'r') out += '\r';
            else if (body[p + 1] == 't') out += '\t';
            else out += body[p + 1];
            p += 2;
        } else out += body[p++];
    }
    return out;
}

static std::string extractNumField(const std::string& body, const std::string& key) {
    std::string pat = "\"" + key + "\":";
    size_t p = body.find(pat);
    if (p == std::string::npos) return "";
    p += pat.size();
    size_t q = p;
    while (q < body.size() && (isdigit((unsigned char)body[q]) || body[q] == '.' || body[q] == '-' || body[q] == 'e' || body[q] == 'E'))
        q++;
    return body.substr(p, q - p);
}

static std::vector<Record> jsonToRecords(const std::string& text) {
    std::vector<Record> recs;
    std::string t = text;
    size_t pos = 0;
    while ((pos = t.find('{', pos)) != std::string::npos) {
        size_t end = t.find('}', pos);
        if (end == std::string::npos) break;
        std::string body = t.substr(pos + 1, end - pos - 1);
        Record r;
        r.id = atoi(extractNumField(body, "id").c_str());
        r.date = s2ws(extractStrField(body, "date"));
        r.type = s2ws(extractStrField(body, "type"));
        r.category = s2ws(extractStrField(body, "category"));
        r.amount = atof(extractNumField(body, "amount").c_str());
        r.payee = s2ws(extractStrField(body, "payee"));
        r.note = s2ws(extractStrField(body, "note"));
        if (r.amount > 0) recs.push_back(r);
        pos = end + 1;
    }
    return recs;
}

static void saveRecords(const std::vector<Record>& recs) {
    std::ofstream f(L"expense_data.json", std::ios::out | std::ios::trunc);
    if (f) f << recordsToJson(recs);
}

static std::vector<Record> loadRecords() {
    std::ifstream f(L"expense_data.json", std::ios::in | std::ios::binary);
    if (!f) return {};
    std::stringstream ss;
    ss << f.rdbuf();
    return jsonToRecords(ss.str());
}

// ===================== 分析引擎 =====================

struct Summary {
    double totalIncome = 0, totalExpense = 0, balance = 0, savingsRate = -1.0, avg = 0, max = 0;
    int count = 0, expCount = 0, incCount = 0;
    std::wstring savingsText;
};

struct Scores {
    double planning, frugality, indulgence, foodie, social, impulse, balance;
};

static std::wstring todayStr() {
    SYSTEMTIME st; GetLocalTime(&st);
    wchar_t buf[16];
    swprintf(buf, 16, L"%04d-%02d-%02d", st.wYear, st.wMonth, st.wDay);
    return std::wstring(buf);
}

static std::wstring dateShift(const std::wstring& iso, int days) {
    // 解析 YYYY-MM-DD，减去 days 天
    int y = _wtoi(iso.substr(0, 4).c_str());
    int m = _wtoi(iso.substr(5, 2).c_str());
    int d = _wtoi(iso.substr(8, 2).c_str());
    if (y < 1) y = 2026; if (m < 1) m = 1; if (d < 1) d = 1;
    SYSTEMTIME st{};
    st.wYear = (WORD)y; st.wMonth = (WORD)m; st.wDay = (WORD)d;
    FILETIME ft; SystemTimeToFileTime(&st, &ft);
    ULARGE_INTEGER ui; ui.LowPart = ft.dwLowDateTime; ui.HighPart = ft.dwHighDateTime;
    // 减去 days * 86400 秒
    ui.QuadPart -= (ULONGLONG)days * 864000000000ULL;
    FILETIME ft2; ft2.dwLowDateTime = ui.LowPart; ft2.dwHighDateTime = ui.HighPart;
    SYSTEMTIME st2; FileTimeToSystemTime(&ft2, &st2);
    wchar_t buf[16];
    swprintf(buf, 16, L"%04d-%02d-%02d", st2.wYear, st2.wMonth, st2.wDay);
    return std::wstring(buf);
}

static Summary analyzeSummary(const std::vector<Record>& recs) {
    Summary s;
    double inc = 0, exp = 0;
    for (auto& r : recs) {
        s.count++;
        if (r.type == L"收入") { inc += r.amount; s.incCount++; }
        else { exp += r.amount; s.expCount++; s.avg += r.amount; s.max = std::max(s.max, r.amount); }
    }
    s.totalIncome = inc; s.totalExpense = exp; s.balance = inc - exp;
    if (inc > 0) { s.savingsRate = (inc - exp) / inc; s.savingsText = L"（储蓄率 " + fmtMoney((inc - exp) / inc * 100.0) + L" %）"; }
    else s.savingsText = L"—（暂无收入）";
    if (s.expCount > 0) s.avg = exp / s.expCount;
    return s;
}

static Scores computeScores(const std::vector<Record>& recs) {
    std::vector<const Record*> exps;
    double totalInc = 0, totalExp = 0;
    for (auto& r : recs) {
        if (r.type == L"收入") totalInc += r.amount;
        else { exps.push_back(&r); totalExp += r.amount; }
    }
    int n = (int)exps.size();
    double savingsRate = totalInc > 0 ? (totalInc - totalExp) / totalInc : -1.0;

    std::map<std::wstring, double> catAmt;
    std::vector<double> amounts;
    for (auto* r : exps) { catAmt[r->category] += r->amount; amounts.push_back(r->amount); }
    double total = 0; for (auto& kv : catAmt) total += kv.second;
    if (total <= 0) total = 1.0;

    double foodPct = catAmt[L"餐饮"] / total;
    double socialPct = (catAmt[L"社交人情"] + catAmt[L"娱乐"] + catAmt[L"旅行"]) / total;
    double luxuryPct = (catAmt[L"购物"] + catAmt[L"旅行"] + catAmt[L"娱乐"]) / total;

    double hhi = 0; for (auto& kv : catAmt) hhi += (kv.second / total) * (kv.second / total);
    double balance = clampd((1 - hhi) * 115);

    // 消费频率（笔/天）
    double freq = 0;
    if (n >= 2) {
        std::vector<long long> ticks;
        for (auto* r : exps) {
            std::wstring d = r->date;
            int y = _wtoi(d.substr(0, 4).c_str()), m = _wtoi(d.substr(5, 2).c_str()), dd = _wtoi(d.substr(8, 2).c_str());
            SYSTEMTIME st{}; st.wYear = (WORD)y; st.wMonth = (WORD)m; st.wDay = (WORD)dd;
            FILETIME ft; SystemTimeToFileTime(&st, &ft);
            ULARGE_INTEGER ui; ui.LowPart = ft.dwLowDateTime; ui.HighPart = ft.dwHighDateTime;
            ticks.push_back((long long)(ui.QuadPart / 864000000000ULL));
        }
        auto mn = *std::min_element(ticks.begin(), ticks.end());
        auto mx = *std::max_element(ticks.begin(), ticks.end());
        long long span = std::max<long long>(mx - mn, 1);
        freq = (double)n / (double)span;
    }
    double freqScore = clampd(freq * 35);

    // 金额结构
    std::sort(amounts.begin(), amounts.end());
    double med = n ? amounts[n / 2] : 0.0;
    double avg = n ? totalExp / n : 0.0;
    double sizeRatio = med > 0 ? avg / med : 1.0;
    double ratioPenalty = clampd(sizeRatio - 1, 0.0, 1.5) * 18;
    double bigShare = 0, smallShare = 0;
    if (n) {
        int b = 0, s2 = 0;
        for (double a : amounts) { if (a >= med * 2) b++; if (a <= med) s2++; }
        bigShare = (double)b / n; smallShare = (double)s2 / n;
    }

    double savingsBonus = savingsRate >= 0 ? savingsRate * 40 : -12;
    Scores sc;
    sc.planning = clampd(38 + (savingsRate >= 0 ? 25 : 0) + savingsBonus + balance * 0.2 + (n >= 3 ? 10 : 0));
    sc.frugality = clampd(50 - ratioPenalty + (savingsRate >= 0 ? savingsRate * 25 : -12) + (n >= 3 ? 8 : 0));
    sc.indulgence = clampd(luxuryPct * 130 + (sizeRatio > 1.3 ? 18 : 0) + bigShare * 15);
    sc.foodie = clampd(foodPct * 170 + (foodPct > 0.12 ? 8 : 0));
    sc.social = clampd(socialPct * 190 + freqScore * 0.2);
    sc.impulse = clampd(freqScore * 0.5 + smallShare * 25 + (sizeRatio > 1.5 ? 18 : 0) + (n >= 6 ? 10 : 0) - sc.planning * 0.2);
    sc.balance = balance;
    return sc;
}

// ===================== 人格画像 =====================

struct Archetype {
    const wchar_t* name;
    const wchar_t* desc;
    std::vector<const wchar_t*> strengths;
    std::vector<const wchar_t*> risks;
    std::vector<const wchar_t*> advice;
};

static Archetype makeArch(const wchar_t* name, const wchar_t* desc,
                          std::vector<const wchar_t*> s, std::vector<const wchar_t*> r,
                          std::vector<const wchar_t*> a) {
    Archetype x; x.name = name; x.desc = desc; x.strengths = s; x.risks = r; x.advice = a; return x;
}

static Archetype pickArchetype(const std::vector<Record>& recs, const Scores& sc, double savingsRate) {
    int n = 0;
    for (auto& r : recs) if (r.type != L"收入") n++;
    if (n == 0) {
        return makeArch(L"待观察型",
            L"目前支出样本较少（或只有收入记录），画像暂不成熟，先积累几笔消费再回来看看吧。",
            {L"数据积累阶段，一切皆有可能", L"你已经开始记账，这本身就是很好的起点"},
            {L"样本不足时，任何结论都仅供参考"},
            {L"坚持记录至少一周的每一笔支出，样本越全画像越准",
             L"同时录入收入记录，才能算出真实的储蓄率与规划空间"});
    }

    // 找最高维度
    std::vector<std::pair<std::string, double>> dims = {
        {"frugality", sc.frugality}, {"indulgence", sc.indulgence},
        {"foodie", sc.foodie}, {"social", sc.social}, {"impulse", sc.impulse}};
    auto top = std::max_element(dims.begin(), dims.end(),
                                [](auto& a, auto& b) { return a.second < b.second; });
    std::string topName = top->first;

    if (sc.impulse >= 68 && topName == "impulse" && sc.planning < 55) {
        return makeArch(L"随心消费型",
            L"你的消费跟随心情走，小额高频、说买就买，享受即时的快乐，但也容易在深夜后悔。",
            {L"行动力强，敢于取悦当下的自己", L"心态开放，对新事物保持好奇"},
            {L"碎片化支出累积可观，容易「小钱不断、大钱不见」", L"冲动购买的商品闲置率通常偏高"},
            {L"采用「24 小时冷静期」：想买的东西先加入购物车，隔天再决定",
             L"把常用支付工具的免密/自动扣款关掉，让每笔支出多一次「确认」的机会"});
    }
    if (sc.frugality >= 62 && (topName == "frugality" || (savingsRate >= 0 && savingsRate >= 0.3))) {
        return makeArch(L"精打细算型",
            L"每一分钱在你手里都能发挥最大价值，你擅长克制冲动、货比三家，是朋友眼中的省钱顾问。",
            {L"消费性价比极高，单位金额换取的效用最大", L"自控力强，几乎不为情绪买单"},
            {L"可能因过度在意价格而牺牲时间与体验", L"偶尔的「舍不得」会降低生活幸福感"},
            {L"为「体验类消费」（旅行、课程、健康）单独设一笔预算，告诉自己这些也值得投入",
             L"每周给自己一次小额「无理由奖励」，让克制与享受形成良性循环"});
    }
    if (savingsRate >= 0 && savingsRate >= 0.25 && sc.planning >= 55 && sc.balance >= 55) {
        return makeArch(L"稳健规划型",
            L"你像一位自带预算表的理财师，收支安排井井有条，储蓄意识强，消费决策从容不迫。",
            {L"储蓄率可观，财务安全垫正在逐步变厚", L"消费有规划、节奏稳，很少被临时种草打乱"},
            {L"可能过度克制，偶尔错过提升生活品质的机会", L"对突发大额支出缺少弹性预算"},
            {L"每月将收入的 10%-20% 自动转入储蓄或理财账户，让存钱在不知不觉中完成",
             L"在预算中预留 5% 的「快乐基金」，给品质体验留一点从容的余地"});
    }
    if (sc.indulgence >= 55 && topName == "indulgence") {
        return makeArch(L"品质生活家",
            L"你相信「贵有贵的道理」，愿意为好体验、好品质买单，追求生活的精致与愉悦感。",
            {L"审美与品味在线，消费选择往往经得起推敲", L"愿意投资自己，注重长期价值"},
            {L"大额享受型支出占比偏高，易挤占储蓄空间", L"价格敏感度低，容易忽略比价机会"},
            {L"为品质类支出设定月度上限，超出部分延迟 7 天再决定是否购买",
             L"每次大额消费后问自己一句：这是「我想要」还是「我真正需要」？"});
    }
    if (sc.foodie >= 55 && topName == "foodie") {
        return makeArch(L"美食探索家",
            L"你的快乐很大一部分来自味蕾，愿意为美食投入，是一枚资深吃货与生活体验派。",
            {L"懂得用美食治愈生活，幸福感知力强", L"社交常以美食为纽带，人缘通常不错"},
            {L"餐饮支出占比偏高，是减肥与钱包的双重隐患", L"外卖/外食频次高，健康与支出都需留意"},
            {L"每周设置 2-3 顿「家庭厨房日」，既控支出又更健康",
             L"把每月餐饮预算分成「日常」与「犒赏」两笔，犒赏时好好享受、日常时守住底线"});
    }
    if (sc.social >= 55 && topName == "social") {
        return makeArch(L"社交达人型",
            L"聚会、礼尚往来、娱乐社交是你生活的重要部分，你重情义、人气旺、生活热闹。",
            {L"人际关系投入积极，情感账户很充盈", L"乐于分享，朋友愿意与你往来"},
            {L"人情与社交支出波动大，可能影响月度预算", L"容易被氛围带动消费，出现「面子消费」"},
            {L"给社交支出设月度上限，超过后本月改为「低预算聚会」模式",
             L"尝试把部分社交从「花钱场所」转移到家里、公园等低成本场景，情谊不减"});
    }
    if (sc.balance >= 65 && sc.planning >= 45) {
        return makeArch(L"均衡生活家",
            L"你的消费结构多元均衡，没有明显偏科，收支节奏稳定，是典型的稳妥型消费者。",
            {L"生活维度丰富，消费面广而不失分寸", L"风险承受与抗波动能力较强"},
            {L"各分类占比平均，可能在某一关键领域投入不足", L"缺乏重点规划，长期目标感偏弱"},
            {L"为未来 6-12 个月的明确目标（如旅行、换机、学习）单列一笔专项储蓄",
             L"每月复盘时挑一个「最想加强」的领域，定向倾斜 5% 的预算"});
    }
    if (sc.balance < 38 && n >= 3) {
        return makeArch(L"单点专注型",
            L"你的钱高度集中在少数领域，生活重心明确，是一根筋的「实力派」。",
            {L"核心需求保障有力，舍得在关键处投入", L"目标感强，不轻易被分散注意力"},
            {L"消费结构单一，其他维度的体验容易被忽视", L"集中度过高，一旦重心变化支出压力会骤增"},
            {L"从主要支出中每月挪出 5%-10%，用于拓展一两个新领域的小体验",
             L"为集中度最高的分类设置「刚性上限」，超出部分必须二次确认"});
    }
    return makeArch(L"自由随性型",
        L"你的消费风格松弛自由，不设框架、走一步看一步，生活随性而有弹性。",
        {L"心态轻松，不给自己过多压力", L"可塑性强，调整空间大"},
        {L"缺少预算框架，月末容易「钱去哪儿了」", L"收入与支出缺少对应关系，储蓄较被动"},
        {L"用「三账户法」：发薪日把生活费、储蓄、娱乐各划一个账户，专款专用",
         L"每周日花 2 分钟快速回顾本周支出，只需看一眼分类汇总即可"});
}

static std::wstring makeReport(const std::vector<Record>& recs) {
    Summary s = analyzeSummary(recs);
    if (recs.empty()) return L"暂无任何记录。\n\n请先在左侧录入最近的消费与收入，再生成画像。";

    // 分类汇总
    std::map<std::wstring, double> catAmt;
    for (auto& r : recs) if (r.type != L"收入") catAmt[r.category] += r.amount;
    double total = 0; for (auto& kv : catAmt) total += kv.second;
    if (total <= 0) total = 1.0;
    std::vector<std::pair<std::wstring, double>> top(catAmt.begin(), catAmt.end());
    std::sort(top.begin(), top.end(), [](auto& a, auto& b) { return a.second > b.second; });

    Scores sc = computeScores(recs);
    Archetype arch = pickArchetype(recs, sc, s.savingsRate);

    std::wostringstream os;
    os << L"================================================\n"
       << L"          个 人 消 费 人 格 报 告\n"
       << L"================================================\n"
       << L"生成时间：" << todayStr() << L"\n"
       << L"统计样本：" << s.count << L" 笔记录（支出 " << s.expCount << L" 笔 / 收入 " << s.incCount << L" 笔）\n\n"
       << L"【一、收支总览】\n"
       << L"  总收入：" << fmtMoney(s.totalIncome) << L" 元\n"
       << L"  总支出：" << fmtMoney(s.totalExpense) << L" 元\n"
       << L"  结余：" << fmtMoney(s.balance) << L" 元" << (s.balance < 0 ? L"　（入不敷出，注意！）" : L"") << L"\n"
       << L"  储蓄率：" << (s.savingsRate >= 0 ? fmtMoney(s.savingsRate * 100.0) + L"%" : L"暂无收入数据，无法计算") << L"\n";
    if (s.expCount > 0) {
        os << L"  支出笔均：" << fmtMoney(s.avg) << L" 元\n";
        os << L"  最大单笔：" << fmtMoney(s.max) << L" 元\n";
    }
    os << L"\n【二、消费结构】\n";
    if (!top.empty()) {
        for (int i = 0; i < (int)top.size() && i < 3; ++i)
            os << L"  " << top[i].first << L"：" << fmtMoney(top[i].second) << L" 元（占 " << fmtMoney(top[i].second / total * 100.0) << L"%）\n";
    } else os << L"  暂无支出数据\n";

    os << L"\n【三、消费人格画像】\n"
       << L"  人格类型：" << arch.name << L"\n"
       << L"  一句话画像：" << arch.desc << L"\n\n"
       << L"  优势特质：\n";
    for (auto& s_ : arch.strengths) os << L"    + " << s_ << L"\n";
    os << L"\n  潜在风险：\n";
    for (auto& r_ : arch.risks) os << L"    - " << r_ << L"\n";
    os << L"\n【四、给您的两条建议】\n";
    for (int i = 0; i < (int)arch.advice.size(); ++i)
        os << L"  建议" << i + 1 << L"：" << arch.advice[i] << L"\n";
    os << L"\n（说明：画像基于您录入的数据做规则化分析，样本越多、覆盖天数越长，结论越接近真实。）\n";
    return os.str();
}

// ===================== 示例数据 =====================

static std::vector<Record> loadDemoData() {
    // 覆盖最近约 4 个自然月（当月 + 前 3 月），11 个支出分类全部有数据，
    // 当月「购物/娱乐/医疗/社交人情」明显超历史月均 → 载入后即可看到消费预警；
    // 5 月含五一出行 → 旅行分类集中支出，可切换月视图对比。
    std::wstring t = todayStr();
    std::vector<Record> v;
    auto add = [&](int offset, const wchar_t* type, const wchar_t* cat, double amt, const wchar_t* payee, const wchar_t* note) {
        Record r;
        r.date = dateShift(t, offset);
        r.type = type; r.category = cat; r.amount = amt; r.payee = payee; r.note = note;
        v.push_back(r);
    };

    // ============ 当月（offset 1~26）============
    // 收入
    add(17, L"收入", L"工资", 12000.00, L"某某科技有限公司", L"本月工资");
    add(7,  L"收入", L"理财", 130.50,  L"余额宝", L"理财收益");
    // 居住
    add(25, L"支出", L"居住", 2800.00, L"安居公寓", L"本月房租");
    // 餐饮（与历史月均接近，不触发预警）
    add(1,  L"支出", L"餐饮", 12.00,   L"麦当劳", L"早餐");
    add(2,  L"支出", L"餐饮", 32.50,   L"老乡鸡", L"午餐");
    add(4,  L"支出", L"餐饮", 228.00,  L"海底捞", L"朋友聚餐");
    add(6,  L"支出", L"餐饮", 22.00,   L"瑞幸咖啡", L"拿铁");
    add(11, L"支出", L"餐饮", 85.00,   L"盒马鲜生", L"买菜做饭");
    add(15, L"支出", L"餐饮", 48.00,   L"美团外卖", L"晚餐外卖");
    add(18, L"支出", L"餐饮", 33.00,   L"肯德基", L"午餐");
    add(21, L"支出", L"餐饮", 120.00,  L"永辉超市", L"食材日用");
    // 交通
    add(1,  L"支出", L"交通", 12.00,   L"地铁", L"通勤");
    add(3,  L"支出", L"交通", 41.50,   L"滴滴出行", L"打车");
    add(5,  L"支出", L"交通", 12.00,   L"地铁", L"通勤");
    add(8,  L"支出", L"交通", 6.00,    L"哈啰单车", L"骑行");
    add(14, L"支出", L"交通", 12.00,   L"地铁", L"通勤");
    add(20, L"支出", L"交通", 12.00,   L"地铁", L"通勤");
    // 购物（超历史月均 → 预警）
    add(3,  L"支出", L"购物", 499.00,  L"京东自营", L"蓝牙耳机");
    add(10, L"支出", L"购物", 399.00,  L"优衣库", L"夏装");
    add(16, L"支出", L"购物", 68.50,   L"名创优品", L"日用品");
    // 娱乐（超历史月均 → 预警）
    add(13, L"支出", L"娱乐", 88.00,   L"万达影城", L"电影");
    add(19, L"支出", L"娱乐", 198.00,  L"Steam", L"游戏充值");
    // 医疗（超历史月均 → 预警）
    add(12, L"支出", L"医疗", 380.00,  L"美年大健康", L"年度体检");
    add(23, L"支出", L"医疗", 45.00,   L"康民大药房", L"感冒药");
    // 通讯
    add(22, L"支出", L"通讯", 58.00,   L"中国移动", L"本月话费");
    // 社交人情（超历史月均 → 预警）
    add(9,  L"支出", L"社交人情", 300.00, L"微信红包", L"朋友生日礼金");
    add(14, L"支出", L"社交人情", 500.00, L"微信转账", L"婚礼随礼");
    add(26, L"支出", L"社交人情", 60.00,  L"微信群收款", L"聚餐AA");
    // 其他
    add(8,  L"支出", L"其他", 15.00,   L"菜鸟驿站", L"快递代收");

    // ============ 上月（offset 27~57）：常规月度模板 ============
    add(47, L"收入", L"工资", 12000.00, L"某某科技有限公司", L"上月工资");
    add(37, L"收入", L"理财", 98.20,   L"余额宝", L"理财收益");
    add(55, L"支出", L"居住", 2800.00, L"安居公寓", L"上月房租");
    add(56, L"支出", L"餐饮", 12.00,   L"麦当劳", L"早餐");
    add(54, L"支出", L"餐饮", 35.00,   L"老乡鸡", L"午餐");
    add(53, L"支出", L"餐饮", 268.00,  L"海底捞", L"朋友聚餐");
    add(51, L"支出", L"餐饮", 22.00,   L"瑞幸咖啡", L"咖啡");
    add(48, L"支出", L"餐饮", 56.00,   L"西贝莜面村", L"晚餐");
    add(45, L"支出", L"餐饮", 31.00,   L"美团外卖", L"午餐外卖");
    add(42, L"支出", L"餐饮", 45.00,   L"美团外卖", L"晚餐外卖");
    add(39, L"支出", L"餐饮", 142.00,  L"永辉超市", L"食材");
    add(33, L"支出", L"餐饮", 15.00,   L"巴比馒头", L"早餐");
    add(57, L"支出", L"交通", 12.00,   L"地铁", L"通勤");
    add(52, L"支出", L"交通", 38.00,   L"滴滴出行", L"打车");
    add(46, L"支出", L"交通", 12.00,   L"地铁", L"通勤");
    add(44, L"支出", L"交通", 4.00,    L"公交", L"通勤");
    add(40, L"支出", L"购物", 149.00,  L"优衣库", L"夏装短袖");
    add(34, L"支出", L"购物", 69.00,   L"屈臣氏", L"日用品");
    add(50, L"支出", L"娱乐", 65.00,   L"万达影城", L"电影");
    add(49, L"支出", L"医疗", 45.00,   L"社区诊所", L"中医理疗");
    add(43, L"支出", L"通讯", 58.00,   L"中国移动", L"上月话费");
    add(38, L"支出", L"社交人情", 200.00, L"微信红包", L"乔迁红包");
    add(41, L"支出", L"其他", 12.00,   L"菜鸟驿站", L"快递超时费");

    // ============ 前两月（offset 58~88）============
    add(78, L"收入", L"工资", 12000.00, L"某某科技有限公司", L"6月工资");
    add(68, L"收入", L"理财", 76.80,   L"余额宝", L"理财收益");
    add(86, L"支出", L"居住", 2800.00, L"安居公寓", L"6月房租");
    add(87, L"支出", L"餐饮", 12.00,   L"麦当劳", L"早餐");
    add(85, L"支出", L"餐饮", 38.00,   L"太二酸菜鱼", L"午餐");
    add(84, L"支出", L"餐饮", 228.00,  L"海底捞", L"团建聚餐");
    add(82, L"支出", L"餐饮", 22.00,   L"瑞幸咖啡", L"咖啡");
    add(79, L"支出", L"餐饮", 62.00,   L"外婆家", L"晚餐");
    add(76, L"支出", L"餐饮", 29.00,   L"兰州拉面", L"午餐");
    add(73, L"支出", L"餐饮", 41.00,   L"美团外卖", L"晚餐外卖");
    add(70, L"支出", L"餐饮", 138.00,  L"盒马鲜生", L"食材");
    add(67, L"支出", L"餐饮", 35.00,   L"老乡鸡", L"午餐");
    add(64, L"支出", L"餐饮", 15.00,   L"巴比馒头", L"早餐");
    add(61, L"支出", L"交通", 12.00,   L"地铁", L"通勤");
    add(83, L"支出", L"交通", 28.50,   L"滴滴出行", L"打车");
    add(77, L"支出", L"交通", 12.00,   L"地铁", L"通勤");
    add(71, L"支出", L"交通", 4.00,    L"公交", L"通勤");
    add(80, L"支出", L"购物", 189.00,  L"京东自营", L"运动鞋");
    add(66, L"支出", L"娱乐", 88.00,   L"万达影城", L"电影");
    add(75, L"支出", L"教育", 450.00,  L"网易云课堂", L"职场技能培训");
    add(74, L"支出", L"通讯", 58.00,   L"中国移动", L"6月话费");
    add(69, L"支出", L"社交人情", 260.00, L"微信转账", L"朋友生日礼金");
    add(65, L"支出", L"医疗", 60.00,   L"康民大药房", L"肠胃药");
    add(63, L"支出", L"其他", 30.00,   L"洗衣房", L"干洗");

    // ============ 前三月（offset 89~118）：含五一出行 ============
    add(109, L"收入", L"工资", 11800.00, L"某某科技有限公司", L"5月工资");
    add(99,  L"收入", L"理财", 55.30,   L"余额宝", L"理财收益");
    add(117, L"支出", L"居住", 2800.00, L"安居公寓", L"5月房租");
    add(118, L"支出", L"餐饮", 12.00,   L"麦当劳", L"早餐");
    add(116, L"支出", L"餐饮", 32.00,   L"沙县小吃", L"午餐");
    add(115, L"支出", L"餐饮", 245.00,  L"海底捞", L"五一聚餐");
    add(113, L"支出", L"餐饮", 22.00,   L"瑞幸咖啡", L"咖啡");
    add(110, L"支出", L"餐饮", 58.00,   L"绿茶餐厅", L"晚餐");
    add(107, L"支出", L"餐饮", 28.00,   L"美团外卖", L"午餐外卖");
    add(104, L"支出", L"餐饮", 52.00,   L"美团外卖", L"晚餐外卖");
    add(101, L"支出", L"餐饮", 132.00,  L"永辉超市", L"食材");
    add(98,  L"支出", L"餐饮", 36.00,   L"肯德基", L"午餐");
    add(95,  L"支出", L"餐饮", 15.00,   L"巴比馒头", L"早餐");
    add(114, L"支出", L"交通", 12.00,   L"地铁", L"通勤");
    add(112, L"支出", L"交通", 356.00,  L"滴滴出行", L"五一打车");
    add(106, L"支出", L"交通", 12.00,   L"地铁", L"通勤");
    add(102, L"支出", L"交通", 4.00,    L"公交", L"通勤");
    add(105, L"支出", L"购物", 228.00,  L"京东自营", L"五一出行装备");
    add(96,  L"支出", L"娱乐", 75.00,   L"万达影城", L"电影");
    add(92,  L"支出", L"通讯", 58.00,   L"中国移动", L"5月话费");
    add(100, L"支出", L"社交人情", 300.00, L"微信红包", L"婚礼随礼");
    add(93,  L"支出", L"医疗", 120.00,  L"社区医院", L"拔牙");
    add(90,  L"支出", L"其他", 20.00,   L"菜鸟驿站", L"快递代收");
    // 五一旅行（旅行分类集中支出）
    add(111, L"支出", L"旅行", 768.00,  L"铁路12306", L"五一往返高铁票");
    add(108, L"支出", L"旅行", 1088.00, L"汉庭酒店", L"五一住宿");
    add(103, L"支出", L"旅行", 360.00,  L"携程旅行", L"景区门票");

    // 分配 id
    for (int i = 0; i < (int)v.size(); ++i) v[i].id = i + 1;
    return v;
}

// ===================== 预算规划模块（合并） =====================
// 将预算规划模块（budget_planner.cpp）与主程序合并：
//   - 算法核心（computeBudget / defaultRatiosForRegion / 分类占比表）为全局纯函数；
//   - UI 位于 namespace budgetui，通过 budgetui::ShowBudgetPlanner() 打开规划窗口；
//   - 点击"应用此方案到主程序"时回调 budgetui::OnApplyPlan()（本文件末尾实现），
//     将各分类月预算写入 g.hasBudget / g.budgetTotal / g.budgetByCat，
//     并持久化到 budget_plan.json，用于"消费超预期预警"。
#include "budget_planner.cpp"

// ===================== 应用状态 =====================

struct App {
    HWND hwnd = nullptr;
    std::vector<Record> records;
    int nextId = 1;
    int editingId = -1;

    // 视图 / 搜索 / 筛选 / 排序
    int viewMode = 0;             // 0=全部 1=日视图 2=月视图 3=年视图
    int viewYear = 0, viewMonth = 0;
    std::wstring viewDateStr;     // 日视图当前日期 YYYY-MM-DD
    std::wstring searchText;      // 关键词搜索
    std::wstring filterType;      // L"" = 全部类型
    std::wstring filterCat;       // L"" = 全部分类
    int sortCol = -1;             // -1 = 按录入顺序；0-5 对应各列
    bool sortAsc = true;
    int ifSortCol = -1;           // if 线列表排序：-1 = 按录入顺序
    bool ifSortAsc = true;

    // 控件句柄
    HWND hDate, hTypeCombo, hCatCombo, hAmount, hPayee, hNote, hAddBtn;
    HWND hList, hStatus;
    HWND hInc, hExp, hBal, hRate, hCnt, hAvg, hMax;
    HWND hDaily, hScope;          // 收支概览第二行：日均支出 / 统计范围
    HWND hViewCombo, hViewDate, hViewPrev, hViewNext;
    HWND hSearch, hFilterType, hFilterCat;
    HWND hWarn;

    // 预算（来自预算规划模块，用于"消费超预期预警"）
    bool hasBudget = false;
    double budgetTotal = 0;
    double budgetByCat[kCategoryCount] = {0};

    // if 线模拟（历史记录的可修改副本）
    std::vector<Record> ifline;
    HWND hIfList, hIfAmount, hIfDiff;
    // if 线增删查改：查询框 / 范围显示 / 提示行 / 录入表单 / 按钮
    HWND hIfSearch = nullptr, hIfScope = nullptr, hIfHint = nullptr;
    HWND hIfDate = nullptr, hIfTypeCombo = nullptr, hIfCatCombo = nullptr;
    HWND hIfAmountForm = nullptr, hIfPayee = nullptr, hIfNote = nullptr;
    HWND hIfAddBtn = nullptr, hIfCancelBtn = nullptr;
    std::wstring ifSearchText;   // if 线关键词过滤
    int ifLineNextId = -1;       // if 线新增记录使用负 id，避免与既有线冲突
    int ifEditingId = -1;        // 正在编辑的 if 线记录 id，-1 表示非编辑态
    bool ifFormUpdating = false; // if 表单回填时抑制 CBN_SELCHANGE 联动
    HWND hIfLine = nullptr;      // if 线顶层窗口句柄（视图变化时通知刷新）
    // 图表数据（按当前视图快照）
    std::vector<Record> chartRecords;

    bool formUpdating = false;  // 编辑回填时抑制 CBN_SELCHANGE 联动

    void Normalize() {
        std::set<int> ids;
        for (auto& r : records) if (r.id) ids.insert(r.id);
        for (auto& r : records) {
            if (!r.id) { while (ids.count(nextId)) nextId++; r.id = nextId; ids.insert(nextId); nextId++; }
        }
        nextId = (nextId > (ids.empty() ? 1 : *ids.rbegin() + 1)) ? nextId : (*ids.rbegin() + 1);
        for (auto& r : records) {
            if (r.date.empty()) r.date = todayStr();
            if (r.type.empty()) r.type = L"支出";
            if (r.category.empty()) r.category = L"其他";
        }
    }
};

static App g;

// 当前视图范围的文字描述（全部 / 日 / 月 / 年）
static std::wstring ViewScopeLabel() {
    if (g.viewMode == 0) return L"全部记录";
    if (g.viewMode == 1) return L"日视图 " + g.viewDateStr;
    if (g.viewMode == 2) {
        wchar_t b[16]; swprintf(b, 16, L"月视图 %04d-%02d", g.viewYear, g.viewMonth); return b;
    }
    wchar_t b[16]; swprintf(b, 16, L"年视图 %04d", g.viewYear); return b;
}

// ===================== 控件工具 =====================

static HWND CreateLabel(HWND parent, const wchar_t* text, int x, int y, int w, int h) {
    return CreateWindowW(L"STATIC", text, WS_CHILD | WS_VISIBLE | SS_LEFT,
                         x, y, w, h, parent, nullptr, GetModuleHandleW(nullptr), nullptr);
}

static HWND CreateEdit(HWND parent, int x, int y, int w, int h) {
    return CreateWindowW(L"EDIT", L"", WS_CHILD | WS_VISIBLE | WS_BORDER | WS_TABSTOP | ES_AUTOHSCROLL,
                         x, y, w, h, parent, nullptr, GetModuleHandleW(nullptr), nullptr);
}

static HWND CreateCombo(HWND parent, int x, int y, int w, int h) {
    return CreateWindowW(L"COMBOBOX", L"", WS_CHILD | WS_VISIBLE | WS_TABSTOP |
                         CBS_DROPDOWNLIST | CBS_HASSTRINGS,
                         x, y, w, h, parent, nullptr, GetModuleHandleW(nullptr), nullptr);
}

static void ComboSet(HWND cbo, const wchar_t* const* items, const std::wstring& sel) {
    SendMessageW(cbo, CB_RESETCONTENT, 0, 0);
    int si = 0;
    for (int i = 0; items[i]; ++i) {
        SendMessageW(cbo, CB_ADDSTRING, 0, (LPARAM)items[i]);
        if (sel == items[i]) si = i;
    }
    SendMessageW(cbo, CB_SETCURSEL, si, 0);
}

static std::wstring ComboText(HWND cbo) {
    wchar_t buf[64];
    SendMessageW(cbo, WM_GETTEXT, 64, (LPARAM)buf);
    return std::wstring(buf);
}

// ===================== 图标/资源（简单画一个图标） =====================

// ===================== 窗口过程声明 =====================
LRESULT CALLBACK MainProc(HWND, UINT, WPARAM, LPARAM);
LRESULT CALLBACK ReportProc(HWND, UINT, WPARAM, LPARAM);
LRESULT CALLBACK ChartProc(HWND, UINT, WPARAM, LPARAM);

// ===================== 报告窗口（RichEdit 富文本） =====================

static std::wstring g_reportText;

// 报告配色方案
static const DWORD kColorTitle = 0x1A237E;   // 深蓝：大标题
static const DWORD kColorSection = 0x00695C; // 青蓝：小节标题
static const DWORD kColorKey = 0x1565C0;     // 蓝：关键词
static const DWORD kColorName = 0x6A1B9A;    // 紫：人格类型
static const DWORD kColorNum = 0xC62828;     // 红：金额/数字
static const DWORD kColorPlus = 0x2E7D32;    // 绿：优势
static const DWORD kColorMinus = 0xB71C1C;   // 红：风险
static const DWORD kColorAdvice = 0xB4530A;  // 棕：建议
static const DWORD kColorBody = 0x37474F;    // 深灰：正文
static const DWORD kColorMuted = 0x90A4AE;   // 浅灰：说明
static const DWORD kColorWarn = 0xE65100;    // 橙：警告(入不敷出)

// 行内关键词（蓝色加粗）
static const wchar_t* kReportKeywords[] = {
    L"总收入", L"总支出", L"结余", L"储蓄率", L"记录数", L"支出笔均",
    L"最大单笔", L"生成时间", L"统计样本", L"人格类型", L"一句话画像",
    L"优势特质", L"潜在风险", nullptr};

// 对 RichEdit 指定字符区间 [start, end) 应用格式
static void SetRangeFormat(HWND hRich, long start, long end,
                           DWORD color, bool bold, int sizePt) {
    CHARFORMAT2W cf{};
    cf.cbSize = sizeof(cf);
    cf.dwMask = CFM_COLOR | CFM_BOLD | CFM_SIZE | CFM_FACE;
    cf.dwEffects = bold ? CFE_BOLD : 0;
    cf.yHeight = (LONG)sizePt * 20;  // 单位：1/20 磅
    cf.crTextColor = color;
    wcscpy_s(cf.szFaceName, L"Microsoft YaHei UI");
    SendMessageW(hRich, EM_SETSEL, start, end);
    SendMessageW(hRich, EM_SETCHARFORMAT, SCF_SELECTION, (LPARAM)&cf);
}

// 将一行文本中的连续数字/百分比/金额标红
static void ColorNumbers(HWND hRich, long base, const std::wstring& line, int sizePt) {
    long i = 0, n = (long)line.size();
    while (i < n) {
        wchar_t c = line[i];
        bool isNumStart = iswdigit(c);
        if (!isNumStart) { i++; continue; }
        long start = i;
        while (i < n) {
            wchar_t d = line[i];
            if (iswdigit(d)) { i++; continue; }
            if ((d == L'.' || d == L',') && i + 1 < n && iswdigit(line[i + 1])) { i++; continue; }
            if (d == L'%') { i++; break; }
            break;
        }
        SetRangeFormat(hRich, base + start, base + i, kColorNum, true, sizePt);
    }
}

// 行内关键词标蓝加粗
static void ColorKeywords(HWND hRich, long base, const std::wstring& line, int sizePt) {
    for (int k = 0; kReportKeywords[k]; ++k) {
        std::wstring kw = kReportKeywords[k];
        size_t pos = 0;
        while ((pos = line.find(kw, pos)) != std::wstring::npos) {
            SetRangeFormat(hRich, base + (long)pos, base + (long)pos + (long)kw.size(),
                           kColorKey, true, sizePt);
            pos += kw.size();
        }
    }
}

// 判断行类型并填充富文本
static void FillRichReport(HWND hRich, const std::wstring& text) {
    SetWindowTextW(hRich, L"");
    SendMessageW(hRich, EM_SETBKGNDCOLOR, 0, (LPARAM)RGB(253, 251, 244));  // 米色背景

    std::vector<std::wstring> lines;
    std::wstring cur;
    for (wchar_t ch : text) {
        if (ch == L'\n') { lines.push_back(cur); cur.clear(); }
        else if (ch != L'\r') cur += ch;
    }
    if (!cur.empty()) lines.push_back(cur);

    long pos = 0;
    for (auto& raw : lines) {
        std::wstring line = raw;
        if (pos > 0) {
            SendMessageW(hRich, EM_SETSEL, pos, pos);
            SendMessageW(hRich, EM_REPLACESEL, FALSE, (LPARAM)L"\r\n");
            pos += 1;  // CRLF 在 RichEdit 中计 1 个字符
        }
        SendMessageW(hRich, EM_SETSEL, pos, pos);
        SendMessageW(hRich, EM_REPLACESEL, FALSE, (LPARAM)line.c_str());
        long lineStart = pos;
        long lineEnd = pos + (long)line.size();
        pos = lineEnd;

        // —— 行级样式 ——
        if (line.find(L"个人消费人格报告") != std::wstring::npos) {
            // 大标题：深蓝加粗 16 磅，居中
            SetRangeFormat(hRich, lineStart, lineEnd, kColorTitle, true, 16);
            PARAFORMAT2 pf{};
            pf.cbSize = sizeof(pf);
            pf.dwMask = PFM_ALIGNMENT;
            pf.wAlignment = PFA_CENTER;
            SendMessageW(hRich, EM_SETSEL, lineStart, lineEnd);
            SendMessageW(hRich, EM_SETPARAFORMAT, 0, (LPARAM)&pf);
        } else if (line.find(L"====") != std::wstring::npos) {
            SetRangeFormat(hRich, lineStart, lineEnd, kColorMuted, false, 11);
        } else if (!line.empty() && line[0] == L'【') {
            SetRangeFormat(hRich, lineStart, lineEnd, kColorSection, true, 13);
        } else if (line.find(L"入不敷出") != std::wstring::npos) {
            SetRangeFormat(hRich, lineStart, lineEnd, kColorWarn, true, 11);
            ColorNumbers(hRich, lineStart, line, 11);
        } else if (line.find(L"  建议") == 0) {
            SetRangeFormat(hRich, lineStart, lineEnd, kColorAdvice, true, 12);
        } else if (line.find(L"    + ") == 0) {
            SetRangeFormat(hRich, lineStart, lineEnd, kColorPlus, true, 11);
        } else if (line.find(L"    - ") == 0) {
            SetRangeFormat(hRich, lineStart, lineEnd, kColorMinus, true, 11);
        } else if (line.find(L"（说明：") != std::wstring::npos) {
            SetRangeFormat(hRich, lineStart, lineEnd, kColorMuted, false, 10);
        } else if (line.find(L"人格类型：") != std::wstring::npos) {
            SetRangeFormat(hRich, lineStart, lineEnd, kColorBody, false, 12);
            ColorKeywords(hRich, lineStart, line, 12);
            // 人格名称紫色加粗
            size_t p = line.find(L"人格类型：");
            std::wstring rest = line.substr(p + 5);
            size_t namePos = p + 5;
            SetRangeFormat(hRich, lineStart + (long)namePos, lineEnd,
                           kColorName, true, 13);
            (void)rest;
        } else if (line.find(L"  储蓄率：") == 0 ||
                   line.find(L"  结余：") == 0) {
            SetRangeFormat(hRich, lineStart, lineEnd, kColorBody, false, 11);
            ColorKeywords(hRich, lineStart, line, 11);
            ColorNumbers(hRich, lineStart, line, 11);
        } else {
            SetRangeFormat(hRich, lineStart, lineEnd, kColorBody, false, 12);
            ColorKeywords(hRich, lineStart, line, 12);
            ColorNumbers(hRich, lineStart, line, 12);
        }
    }
    SendMessageW(hRich, EM_SETSEL, 0, 0);
}

LRESULT CALLBACK ReportProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    static HWND hRich = nullptr;
    switch (msg) {
    case WM_CREATE: {
        // 加载微软 RichEdit 5.0（Msftedit.dll）
        LoadLibraryW(L"Msftedit.dll");
        hRich = CreateWindowExW(WS_EX_CLIENTEDGE, L"RICHEDIT50W", L"",
                                WS_CHILD | WS_VISIBLE | WS_VSCROLL |
                                ES_MULTILINE | ES_AUTOVSCROLL | ES_READONLY,
                                0, 0, 0, 0, hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        FillRichReport(hRich, g_reportText);
        return 0;
    }
    case WM_SIZE:
        SetWindowPos(hRich, nullptr, 0, 0, LOWORD(lParam), HIWORD(lParam), SWP_NOZORDER);
        return 0;
    case WM_CLOSE:
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

// ===================== 图表窗口（GDI+ 绘制） =====================

static void DrawChart(HWND hwnd) {
    PAINTSTRUCT ps;
    HDC hdc = BeginPaint(hwnd, &ps);
    RECT rc; GetClientRect(hwnd, &rc);
    int W = rc.right - rc.left, H = rc.bottom - rc.top;
    if (W < 10 || H < 10) { EndPaint(hwnd, &ps); return; }

    Graphics graphics(hdc);
    graphics.SetSmoothingMode(SmoothingModeAntiAlias);
    graphics.Clear(Color(255, 250, 250, 250));

    // 标题（跟随当前视图：全部 / 日 / 月 / 年）
    Font titleFont(L"Microsoft YaHei UI", 18, FontStyleBold);
    SolidBrush titleBrush(Color(30, 33, 50));
    std::wstring title = L"个人收支分析报告（" + ViewScopeLabel() + L"）";
    graphics.DrawString(title.c_str(), -1, &titleFont, PointF(20, 12), &titleBrush);

    // 收集数据（仅当前视图范围）
    const auto& recs = g.chartRecords.empty() ? g.records : g.chartRecords;
    std::map<std::wstring, double> catAmt;
    for (auto& r : recs) if (r.type != L"收入") catAmt[r.category] += r.amount;
    std::vector<std::pair<std::wstring, double>> cats(catAmt.begin(), catAmt.end());
    std::sort(cats.begin(), cats.end(), [](auto& a, auto& b) { return a.second > b.second; });

    // 每日收支
    std::map<std::wstring, double> incByDate, expByDate;
    for (auto& r : recs) {
        if (r.type == L"收入") incByDate[r.date] += r.amount;
        else expByDate[r.date] += r.amount;
    }
    std::vector<std::wstring> dates;
    for (auto& kv : incByDate) dates.push_back(kv.first);
    for (auto& kv : expByDate) if (std::find(dates.begin(), dates.end(), kv.first) == dates.end()) dates.push_back(kv.first);
    std::sort(dates.begin(), dates.end());
    if (dates.size() > 14) dates.erase(dates.begin(), dates.end() - 14);

    // 雷达图
    Scores sc = computeScores(recs);
    const wchar_t* dimLabels[6] = {L"计划性", L"节俭度", L"品质追求", L"美食偏好", L"社交活跃", L"均衡性"};
    double dimVals[6] = {sc.planning, sc.frugality, sc.indulgence, sc.foodie, sc.social, sc.balance};

    // 四个子图区域
    int M = 40;                 // 边距
    int gapX = 20, gapY = 30;
    int tw = (W - 2 * M - gapX) / 2;
    int th = (H - M - 60 - gapY) / 2;
    RECT r1 = {M, M + 10, M + tw, M + 10 + th};
    RECT r2 = {M + tw + gapX, M + 10, M + tw + gapX + tw, M + 10 + th};
    RECT r3 = {M, M + 10 + th + gapY, M + tw, M + 10 + th + gapY + th};
    RECT r4 = {M + tw + gapX, M + 10 + th + gapY, M + tw + gapX + tw, M + 10 + th + gapY + th};

    Font subFont(L"Microsoft YaHei UI", 12, FontStyleBold);
    SolidBrush subBrush(Color(30, 60, 90));
    Font axisFont(L"Microsoft YaHei UI", 9);
    SolidBrush axisBrush(Color(80, 80, 80));
    Font numFont(L"Microsoft YaHei UI", 9);
    SolidBrush numBrush(Color(60, 60, 60));
    Pen linePen(Color(90, 90, 90), 1);

    // ---- 1. 分类柱状图 ----
    {
        const wchar_t* title = L"各分类支出金额（元）";
        graphics.DrawString(title, -1, &subFont, PointF((REAL)r1.left, (REAL)r1.top - 22), &subBrush);
        if (cats.empty()) {
            SolidBrush gb(Color(180, 180, 180));
            Font f(L"Microsoft YaHei UI", 11);
            graphics.DrawString(L"暂无支出数据", -1, &f, PointF((REAL)(r1.left + tw / 2 - 40), (REAL)(r1.top + th / 2)), &gb);
        } else {
            int padL = 40, padR = 10, padT = 25, padB = 40;
            int plotW = tw - padL - padR, plotH = th - padT - padB;
            double maxV = 0; for (auto& c : cats) maxV = std::max(maxV, c.second);
            if (maxV <= 0) maxV = 1;
            int n = (int)cats.size();
            double barW = (double)plotW / n * 0.6;
            double step = (double)plotW / n;
            for (int i = 0; i < n; ++i) {
                double h = cats[i].second / maxV * plotH;
                int x = r1.left + padL + (int)(i * step + step * 0.2);
                int y = r1.bottom - padB - (int)h;
                Color c; c.SetFromCOLORREF(colorForCategory(cats[i].first));
                SolidBrush b(c);
                graphics.FillRectangle(&b, x, y, (int)barW, (int)h);
                // 数值
                graphics.DrawString(fmtInt(cats[i].second).c_str(), -1, &numFont,
                                    PointF((REAL)x, (REAL)(y - 16)), &numBrush);
                // 分类标签（竖排过多时用斜）
                graphics.DrawString(cats[i].first.c_str(), -1, &axisFont,
                                    PointF((REAL)x, (REAL)(r1.bottom - padB + 5)), &axisBrush);
            }
            // 横纵网格线
            for (int g2 = 0; g2 <= 4; ++g2) {
                int yy = r1.bottom - padB - plotH * g2 / 4;
                Pen gp(Color(200, 200, 200), 1);
                graphics.DrawLine(&gp, r1.left + padL, yy, r1.left + padL + plotW, yy);
            }
            // 坐标轴
            graphics.DrawLine(&linePen, (INT)(r1.left + padL), (INT)(r1.bottom - padB), (INT)(r1.left + padL + plotW), (INT)(r1.bottom - padB));
            graphics.DrawLine(&linePen, (INT)(r1.left + padL), (INT)(r1.bottom - padB), (INT)(r1.left + padL), (INT)(r1.bottom - padB - plotH));
        }
    }

    // ---- 2. 饼图 ----
    {
        const wchar_t* title = L"消费类型占比";
        graphics.DrawString(title, -1, &subFont, PointF((REAL)r2.left, (REAL)r2.top - 22), &subBrush);
        if (cats.empty()) {
            SolidBrush gb(Color(180, 180, 180));
            Font f(L"Microsoft YaHei UI", 11);
            graphics.DrawString(L"暂无支出数据", -1, &f, PointF((REAL)(r2.left + tw / 2 - 40), (REAL)(r2.top + th / 2)), &gb);
        } else {
            double total = 0; for (auto& c : cats) total += c.second;
            int cx = r2.left + tw / 2;
            int cy = r2.top + th / 2;
            int rad = std::min(tw, th) / 2 - 20;
            float start = -90.0f;
            for (auto& c : cats) {
                float sweep = (float)(c.second / total * 360.0);
                Color col; col.SetFromCOLORREF(colorForCategory(c.first));
                SolidBrush b(col);
                graphics.FillPie(&b, cx - rad, cy - rad, 2 * rad, 2 * rad, start, sweep);
                start += sweep;
            }
            // 图例
            int lx = r2.left + 12, ly = r2.top + 30;
            int lw = tw - 24;
            int perCol = lw / 130;
            if (perCol < 1) perCol = 1;
            int colIdx = 0, rowIdx = 0;
            for (int i = 0; i < (int)cats.size(); ++i) {
                colIdx = i % perCol; rowIdx = i / perCol;
                int x = lx + colIdx * 130;
                int y = ly + rowIdx * 20;
                Color col; col.SetFromCOLORREF(colorForCategory(cats[i].first));
                SolidBrush b(col);
                graphics.FillRectangle(&b, x, y, 12, 12);
                graphics.DrawString(cats[i].first.c_str(), -1, &axisFont, PointF((REAL)(x + 16), (REAL)y), &axisBrush);
            }
        }
    }

    // ---- 3. 每日收支对比 ----
    {
        const wchar_t* title = L"每日收支对比（元）";
        graphics.DrawString(title, -1, &subFont, PointF((REAL)r3.left, (REAL)r3.top - 22), &subBrush);
        if (dates.empty()) {
            SolidBrush gb(Color(180, 180, 180));
            Font f(L"Microsoft YaHei UI", 11);
            graphics.DrawString(L"暂无数据", -1, &f, PointF((REAL)(r3.left + tw / 2 - 30), (REAL)(r3.top + th / 2)), &gb);
        } else {
            int padL = 40, padR = 10, padT = 25, padB = 40;
            int plotW = tw - padL - padR, plotH = th - padT - padB;
            double maxV = 0;
            for (auto& d : dates) {
                maxV = std::max(maxV, incByDate[d]);
                maxV = std::max(maxV, expByDate[d]);
            }
            if (maxV <= 0) maxV = 1;
            int n = (int)dates.size();
            double step = (double)plotW / n;
            double barW = step * 0.32;
            for (int i = 0; i < n; ++i) {
                std::wstring d = dates[i];
                double ex = expByDate[d], inc = incByDate[d];
                int x = r3.left + padL + (int)(i * step);
                int yBase = r3.bottom - padB;
                // 支出（红）
                if (ex > 0) {
                    int h = (int)(ex / maxV * plotH);
                    SolidBrush b(Color(239, 83, 80));
                    graphics.FillRectangle(&b, x, yBase - h, (int)barW, h);
                }
                // 收入（绿）
                if (inc > 0) {
                    int h = (int)(inc / maxV * plotH);
                    SolidBrush b(Color(102, 187, 106));
                    graphics.FillRectangle(&b, x + (int)barW, yBase - h, (int)barW, h);
                }
                // 日期标签
                std::wstring dd = d.substr(5);
                graphics.DrawString(dd.c_str(), -1, &axisFont, PointF((REAL)x, (REAL)(yBase + 5)), &axisBrush);
            }
            for (int g2 = 0; g2 <= 4; ++g2) {
                int yy = r3.bottom - padB - plotH * g2 / 4;
                Pen gp(Color(200, 200, 200), 1);
                graphics.DrawLine(&gp, r3.left + padL, yy, r3.left + padL + plotW, yy);
            }
            graphics.DrawLine(&linePen, (INT)(r3.left + padL), (INT)(r3.bottom - padB), (INT)(r3.left + padL + plotW), (INT)(r3.bottom - padB));
            // 图例
            graphics.FillRectangle(&SolidBrush(Color(239, 83, 80)), r3.right - 70, r3.top + 30, 12, 12);
            graphics.DrawString(L"支出", -1, &axisFont, PointF((REAL)(r3.right - 54), (REAL)(r3.top + 29)), &axisBrush);
            graphics.FillRectangle(&SolidBrush(Color(102, 187, 106)), r3.right - 70, r3.top + 48, 12, 12);
            graphics.DrawString(L"收入", -1, &axisFont, PointF((REAL)(r3.right - 54), (REAL)(r3.top + 47)), &axisBrush);
        }
    }

    // ---- 4. 雷达图 ----
    {
        const wchar_t* title = L"消费人格六维雷达图";
        graphics.DrawString(title, -1, &subFont, PointF((REAL)r4.left, (REAL)r4.top - 22), &subBrush);
        int cx = r4.left + tw / 2;
        int cy = r4.top + th / 2 + 5;
        int rad = std::min(tw, th) / 2 - 35;
        // 网格
        for (int ring = 1; ring <= 4; ++ring) {
            double rr = rad * ring / 4.0;
            std::vector<PointF> pts;
            for (int i = 0; i < 6; ++i) {
                double ang = -PI / 2 + i * 2 * PI / 6;
                pts.push_back(PointF((REAL)(cx + rr * cos(ang)), (REAL)(cy + rr * sin(ang))));
            }
            Pen gp(Color(210, 210, 210), 1);
            for (int i = 0; i < 6; ++i)
                graphics.DrawLine(&gp, pts[i], pts[(i + 1) % 6]);
        }
        // 轴线与标签
        for (int i = 0; i < 6; ++i) {
            double ang = -PI / 2 + i * 2 * PI / 6;
            graphics.DrawLine(&linePen, cx, cy, (int)(cx + rad * cos(ang)), (int)(cy + rad * sin(ang)));
            int lx = (int)(cx + (rad + 14) * cos(ang)) - 20;
            int ly = (int)(cy + (rad + 14) * sin(ang)) - 8;
            graphics.DrawString(dimLabels[i], -1, &axisFont, PointF((REAL)lx, (REAL)ly), &axisBrush);
        }
        // 数值多边形
        std::vector<PointF> vpts;
        for (int i = 0; i < 6; ++i) {
            double ang = -PI / 2 + i * 2 * PI / 6;
            double rr = dimVals[i] / 100.0 * rad;
            vpts.push_back(PointF((REAL)(cx + rr * cos(ang)), (REAL)(cy + rr * sin(ang))));
        }
        std::vector<Point> intPts;
        for (auto& p : vpts) intPts.push_back(Point((int)p.X, (int)p.Y));
        SolidBrush fill(Color(66, 165, 245, 64));
        graphics.FillPolygon(&fill, intPts.data(), 6);
        Pen blue(Color(66, 165, 245), 2);
        for (int i = 0; i < 6; ++i)
            graphics.DrawLine(&blue, vpts[i], vpts[(i + 1) % 6]);
    }

    EndPaint(hwnd, &ps);
}

LRESULT CALLBACK ChartProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_PAINT: DrawChart(hwnd); return 0;
    case WM_ERASEBKGND: return 1;
    case WM_SIZE: InvalidateRect(hwnd, nullptr, TRUE); return 0;
    case WM_CLOSE: DestroyWindow(hwnd); return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

// ===================== 视图 / 搜索 / 筛选 / 排序 =====================

static void RefreshWarn();  // 实现见"消费超预期预警"模块
static void RefreshList();
static void UpdateSummary();
static void RefreshAll();

static std::wstring lowerW(const std::wstring& s) {
    std::wstring r = s;
    for (auto& c : r) c = towlower(c);
    return r;
}

// 记录日期是否落在当前视图范围内（全部 / 日 / 月 / 年）
static bool dateInView(const std::wstring& date) {
    if (g.viewMode == 0) return true;
    if (date.size() < 10) return true;
    int y = _wtoi(date.substr(0, 4).c_str());
    int m = _wtoi(date.substr(5, 2).c_str());
    int d = _wtoi(date.substr(8, 2).c_str());
    if (g.viewMode == 3) return y == g.viewYear;
    if (g.viewMode == 2) return y == g.viewYear && m == g.viewMonth;
    return date == g.viewDateStr;
}

// 当前视图对应的"表单默认日期"：日=视图日期，月=当月1号，年=当年1号，全部=今天
// 保证新增记录默认落在当前分析视图内，增改后立即反映到列表与差异分析中
static std::wstring viewDefaultDate() {
    if (g.viewMode == 1 && g.viewDateStr.size() == 10) return g.viewDateStr;
    if (g.viewMode == 2) { wchar_t b[16]; swprintf(b, 16, L"%04d-%02d-01", g.viewYear, g.viewMonth); return std::wstring(b); }
    if (g.viewMode == 3) { wchar_t b[16]; swprintf(b, 16, L"%04d-01-01", g.viewYear); return std::wstring(b); }
    return todayStr();
}

static bool isLeapYear(int y) { return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0; }

static int daysInMonthY(int y, int m) {
    static const int mdays[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    if (m == 2 && isLeapYear(y)) return 29;
    return (m >= 1 && m <= 12) ? mdays[m - 1] : 30;
}

// 日期字符串 "YYYY-MM-DD" → 绝对天数（用于跨度计算）
static int dateToDay(const std::wstring& d) {
    if (d.size() < 10) return 0;
    int y = _wtoi(d.substr(0, 4).c_str());
    int m = _wtoi(d.substr(5, 2).c_str());
    int day = _wtoi(d.substr(8, 2).c_str());
    static const int mdays[] = {31,28,31,30,31,30,31,31,30,31,30,31};
    int days = day;
    for (int i = 0; i < m - 1 && i < 12; ++i) days += mdays[i];
    if (m > 2 && isLeapYear(y)) ++days;
    days += (y - 1) * 365 + (y - 1) / 4 - (y - 1) / 100 + (y - 1) / 400;
    return days;
}

// 当前视图的统计跨度天数：日=1 月=当月天数 年=当年天数 全部=首末记录天数
static int viewSpanDays() {
    if (g.viewMode == 1) return 1;
    if (g.viewMode == 2) return daysInMonthY(g.viewYear, g.viewMonth);
    if (g.viewMode == 3) return isLeapYear(g.viewYear) ? 366 : 365;
    int mn = 99999999, mx = 0;
    for (auto& r : g.records) {
        int d = dateToDay(r.date);
        if (d < mn) mn = d;
        if (d > mx) mx = d;
    }
    if (mx == 0) return 1;
    return std::max(1, mx - mn + 1);
}

// 当前视图的日期范围描述（用于概览"统计范围"）
static std::wstring viewRangeStr() {
    if (g.viewMode == 0) {
        if (g.records.empty()) return L"无记录";
        std::wstring lo = g.records[0].date, hi = g.records[0].date;
        for (auto& r : g.records) {
            if (r.date < lo) lo = r.date;
            if (r.date > hi) hi = r.date;
        }
        return lo + L" ~ " + hi;
    }
    if (g.viewMode == 1) return g.viewDateStr;
    if (g.viewMode == 2) {
        wchar_t b[48];
        swprintf(b, 48, L"%04d-%02d-01 ~ %04d-%02d-%02d", g.viewYear, g.viewMonth,
                 g.viewYear, g.viewMonth, daysInMonthY(g.viewYear, g.viewMonth));
        return b;
    }
    wchar_t b[48];
    swprintf(b, 48, L"%04d-01-01 ~ %04d-12-31", g.viewYear, g.viewYear);
    return b;
}

// 关键词 / 类型 / 分类筛选
static bool matchSearch(const Record& r) {
    if (!g.filterType.empty() && r.type != g.filterType) return false;
    if (!g.filterCat.empty() && r.category != g.filterCat) return false;
    if (g.searchText.empty()) return true;
    std::wstring q = lowerW(g.searchText);
    return lowerW(r.date).find(q) != std::wstring::npos ||
           lowerW(r.type).find(q) != std::wstring::npos ||
           lowerW(r.category).find(q) != std::wstring::npos ||
           lowerW(r.payee).find(q) != std::wstring::npos ||
           lowerW(r.note).find(q) != std::wstring::npos;
}

static bool recordInView(const Record& r) {
    return dateInView(r.date) && matchSearch(r);
}

// 当前视图范围内的记录副本（不含关键词筛选，供分析与图表使用）
static std::vector<Record> RecordsInView() {
    std::vector<Record> v;
    for (auto& r : g.records)
        if (dateInView(r.date)) v.push_back(r);
    return v;
}

// 过滤 + 排序，返回记录指针（顺序即列表显示顺序）
static std::vector<const Record*> FilterAndSort() {
    std::vector<const Record*> v;
    for (auto& r : g.records)
        if (recordInView(r)) v.push_back(&r);
    if (g.sortCol >= 0) {
        std::stable_sort(v.begin(), v.end(), [](const Record* a, const Record* b) {
            int c = 0;
            switch (g.sortCol) {
            case 0: c = a->date.compare(b->date); break;
            case 1: c = a->type.compare(b->type); break;
            case 2: c = a->category.compare(b->category); break;
            case 3: c = (a->amount < b->amount) ? -1 : (a->amount > b->amount ? 1 : 0); break;
            case 4: c = a->payee.compare(b->payee); break;
            default: c = a->note.compare(b->note); break;
            }
            return g.sortAsc ? c < 0 : c > 0;
        });
    }
    return v;
}

// 更新视图日期显示与导航按钮状态
static void UpdateViewUI() {
    if (!g.hViewDate) return;
    if (g.viewMode == 0) {
        SetWindowTextW(g.hViewDate, L"全部记录");
        EnableWindow(g.hViewDate, FALSE);
        EnableWindow(g.hViewPrev, FALSE);
        EnableWindow(g.hViewNext, FALSE);
    } else {
        wchar_t buf[16];
        if (g.viewMode == 1) wcscpy_s(buf, g.viewDateStr.c_str());
        else if (g.viewMode == 2) swprintf(buf, 16, L"%04d-%02d", g.viewYear, g.viewMonth);
        else swprintf(buf, 16, L"%04d", g.viewYear);
        SetWindowTextW(g.hViewDate, buf);
        EnableWindow(g.hViewDate, TRUE);
        EnableWindow(g.hViewPrev, TRUE);
        EnableWindow(g.hViewNext, TRUE);
    }
}

// 视图模式切换：自动把日期归位到当前时间
static void OnViewModeChanged() {
    g.viewMode = (int)SendMessageW(g.hViewCombo, CB_GETCURSEL, 0, 0);
    if (g.viewMode < 0 || g.viewMode > 3) g.viewMode = 0;
    SYSTEMTIME st; GetLocalTime(&st);
    if (g.viewYear == 0) g.viewYear = st.wYear;
    if (g.viewMonth == 0) g.viewMonth = st.wMonth;
    if (g.viewDateStr.empty()) g.viewDateStr = todayStr();
    if (g.viewMode == 1) { g.viewDateStr = todayStr(); g.viewYear = st.wYear; g.viewMonth = st.wMonth; }
    if (g.viewMode == 2) { g.viewYear = st.wYear; g.viewMonth = st.wMonth; }
    if (g.viewMode == 3) { g.viewYear = st.wYear; }
    UpdateViewUI();
    RefreshAll();
    if (g.hIfLine) PostMessageW(g.hIfLine, WM_APP + 1, 0, 0);  // 通知 if 线按新视图刷新分析
}

// 上一 / 下一 导航（日视图 ±1 天，月视图 ±1 月，年视图 ±1 年）
static void ShiftView(int dir) {
    if (g.viewMode == 1) {
        g.viewDateStr = dateShift(g.viewDateStr, -dir);
    } else if (g.viewMode == 2) {
        g.viewMonth += dir;
        if (g.viewMonth < 1) { g.viewMonth += 12; g.viewYear--; }
        if (g.viewMonth > 12) { g.viewMonth -= 12; g.viewYear++; }
    } else if (g.viewMode == 3) {
        g.viewYear += dir;
    }
    UpdateViewUI();
    RefreshAll();
    if (g.hIfLine) PostMessageW(g.hIfLine, WM_APP + 1, 0, 0);  // 通知 if 线按新视图刷新分析
}

// 读取搜索 / 筛选控件
static void ReadSearch() {
    wchar_t buf[128];
    GetWindowTextW(g.hSearch, buf, 128);
    g.searchText = std::wstring(buf);
    RefreshAll();
}
static void ReadFilterType() {
    g.filterType = ComboText(g.hFilterType);
    if (g.filterType == L"全部类型") g.filterType.clear();
    RefreshAll();
}
static void ReadFilterCat() {
    g.filterCat = ComboText(g.hFilterCat);
    if (g.filterCat == L"全部分类") g.filterCat.clear();
    RefreshAll();
}

// 录入区类型切换：同步刷新分类下拉（编辑回填时不触发）
static void OnFormTypeChanged() {
    if (g.formUpdating) return;
    std::wstring type = ComboText(g.hTypeCombo);
    ComboSet(g.hCatCombo, type == L"收入" ? kIncomeCategories : kExpenseCategories,
             type == L"收入" ? L"工资" : L"餐饮");
}

// 一键刷新（列表 + 概览 + 预警）
static void RefreshAll() {
    RefreshList();
    UpdateSummary();
    RefreshWarn();
}

// ===================== 主窗口逻辑 =====================

static void RefreshList() {
    if (!g.hList) return;
    SendMessageW(g.hList, LVM_DELETEALLITEMS, 0, 0);
    auto v = FilterAndSort();
    for (auto* r : v) {
        int row = (int)SendMessageW(g.hList, LVM_GETITEMCOUNT, 0, 0);
        auto setCol = [&](int c, const std::wstring& text) {
            LVITEMW it{};
            it.mask = (c == 0) ? (LVIF_TEXT | LVIF_PARAM) : LVIF_TEXT;
            it.iItem = row;
            it.iSubItem = c;
            it.pszText = (LPWSTR)text.c_str();
            if (c == 0) it.lParam = r->id;  // 用 id 关联记录：删除/编辑/双击均按 id 定位
            SendMessageW(g.hList, (c == 0) ? LVM_INSERTITEMW : LVM_SETITEMW,
                         0, (LPARAM)&it);
        };
        setCol(0, r->date);
        setCol(1, r->type);
        setCol(2, r->category);
        setCol(3, fmtMoney(r->amount));
        setCol(4, r->payee);
        setCol(5, r->note);
    }
}

static void UpdateSummary() {
    auto v = FilterAndSort();
    std::vector<Record> view;
    for (auto* p : v) view.push_back(*p);
    Summary s = analyzeSummary(view);
    SetWindowTextW(g.hInc, fmtMoney(s.totalIncome).c_str());
    SetWindowTextW(g.hExp, fmtMoney(s.totalExpense).c_str());
    SetWindowTextW(g.hBal, fmtMoney(s.balance).c_str());
    SetWindowTextW(g.hRate, (s.savingsRate >= 0 ? fmtMoney(s.savingsRate * 100.0) + L" %" : L"—").c_str());
    SetWindowTextW(g.hCnt, (std::to_wstring(s.count) + L" 笔").c_str());
    SetWindowTextW(g.hAvg, fmtMoney(s.avg).c_str());
    SetWindowTextW(g.hMax, fmtMoney(s.max).c_str());
    if (g.hDaily) {
        int days = viewSpanDays();
        SetWindowTextW(g.hDaily, (fmtMoney(s.totalExpense / days) + L" / 天").c_str());
    }
    if (g.hScope) SetWindowTextW(g.hScope, viewRangeStr().c_str());
}

static void StatusMsg(const std::wstring& s) {
    SetWindowTextW(g.hStatus, s.c_str());
}

static void ClearForm() {
    SetWindowTextW(g.hDate, viewDefaultDate().c_str());
    SetWindowTextW(g.hAmount, L"");
    SetWindowTextW(g.hPayee, L"");
    SetWindowTextW(g.hNote, L"");
}

static void RefreshAddBtn() {
    SetWindowTextW(g.hAddBtn, g.editingId > 0 ? L"✓ 保存修改" : L"＋ 添加记录");
}

static void CheckOverspendAfterAdd(const Record& r);  // 实现见"消费超预期预警"模块

static void AddRecord() {
    wchar_t buf[512];
    GetWindowTextW(g.hDate, buf, 512); std::wstring date = trim(buf);
    std::wstring type = ComboText(g.hTypeCombo);
    std::wstring cat = ComboText(g.hCatCombo);
    GetWindowTextW(g.hAmount, buf, 512); std::wstring amtStr = trim(buf);
    GetWindowTextW(g.hPayee, buf, 512); std::wstring payee = trim(buf);
    GetWindowTextW(g.hNote, buf, 512); std::wstring note = trim(buf);

    if (date.empty()) { MessageBoxW(g.hwnd, L"请填写日期。", L"信息不完整", MB_OK | MB_ICONWARNING); return; }
    // 校验日期格式
    if (date.size() == 10 && date[4] == L'-' && date[7] == L'-') {
        // ok
    } else { MessageBoxW(g.hwnd, L"日期请使用 YYYY-MM-DD 格式，例如 2026-08-23。", L"日期格式错误", MB_OK | MB_ICONWARNING); return; }

    double amount = 0;
    try { amount = std::stod(ws2s(amtStr)); } catch (...) { amount = -1; }
    if (amount <= 0) { MessageBoxW(g.hwnd, L"金额必须是大于 0 的数字。", L"金额格式错误", MB_OK | MB_ICONWARNING); return; }
    if (cat.empty()) cat = L"其他";

    if (g.editingId > 0) {
        for (auto& r : g.records) {
            if (r.id == g.editingId) {
                r.date = date; r.type = type; r.category = cat;
                r.amount = amount; r.payee = payee; r.note = note;
                break;
            }
        }
        g.editingId = -1;
        StatusMsg(L"已保存修改。");
    } else {
        Record r;
        r.id = g.nextId++;
        r.date = date; r.type = type; r.category = cat;
        r.amount = amount; r.payee = payee; r.note = note;
        g.records.push_back(r);
        StatusMsg(L"已添加记录 #" + std::to_wstring(r.id));
        if (type == L"支出") CheckOverspendAfterAdd(r);  // 消费超预期预警
    }
    saveRecords(g.records);
    RefreshAll();
    ClearForm();
    RefreshAddBtn();
}

static void DeleteSelected() {
    int sel = ListView_GetNextItem(g.hList, -1, LVNI_SELECTED);
    if (sel < 0) { MessageBoxW(g.hwnd, L"请先在表格中选中要删除的记录。", L"未选择", MB_OK | MB_ICONINFORMATION); return; }
    if (MessageBoxW(g.hwnd, L"确定删除选中的记录吗？", L"确认删除", MB_YESNO | MB_ICONQUESTION) != IDYES) return;
    // 从列表 lParam 收集选中记录的 id（与排序 / 视图过滤 / 搜索无关，精确定位）
    std::vector<int> delIds;
    for (int i = 0; i < ListView_GetItemCount(g.hList); ++i) {
        if (ListView_GetItemState(g.hList, i, LVIS_SELECTED)) {
            LVITEMW it{};
            it.mask = LVIF_PARAM;
            it.iItem = i;
            if (ListView_GetItem(g.hList, &it)) delIds.push_back((int)it.lParam);
        }
    }
    if (delIds.empty()) return;
    std::vector<Record> kept;
    for (auto& r : g.records)
        if (std::find(delIds.begin(), delIds.end(), r.id) == delIds.end()) kept.push_back(r);
    size_t removed = g.records.size() - kept.size();
    g.records = kept;
    if (g.editingId > 0) { g.editingId = -1; RefreshAddBtn(); }
    saveRecords(g.records);
    RefreshAll();
    StatusMsg(L"已删除 " + std::to_wstring(removed) + L" 条记录。");
}

static void EditSelected() {
    int sel = ListView_GetNextItem(g.hList, -1, LVNI_SELECTED);
    if (sel < 0) { StatusMsg(L"请先选中一条记录。"); return; }
    LVITEMW it{};
    it.mask = LVIF_PARAM;
    it.iItem = sel;
    if (!ListView_GetItem(g.hList, &it)) return;
    int id = (int)it.lParam;
    for (auto& r : g.records) {
        if (r.id == id) {
            g.editingId = r.id;
            g.formUpdating = true;
            SetWindowTextW(g.hDate, r.date.c_str());
            SendMessageW(g.hTypeCombo, CB_RESETCONTENT, 0, 0);
            SendMessageW(g.hTypeCombo, CB_ADDSTRING, 0, (LPARAM)L"支出");
            SendMessageW(g.hTypeCombo, CB_ADDSTRING, 0, (LPARAM)L"收入");
            SendMessageW(g.hTypeCombo, CB_SETCURSEL, r.type == L"收入" ? 1 : 0, 0);
            ComboSet(g.hCatCombo, r.type == L"收入" ? kIncomeCategories : kExpenseCategories, r.category);
            SetWindowTextW(g.hAmount, fmtMoney(r.amount).c_str());
            SetWindowTextW(g.hPayee, r.payee.c_str());
            SetWindowTextW(g.hNote, r.note.c_str());
            g.formUpdating = false;
            RefreshAddBtn();
            StatusMsg(L"正在编辑记录 #" + std::to_wstring(r.id) + L"，修改后点击「保存修改」，或点击「取消编辑」。");
            return;
        }
    }
}

static void CancelEdit() {
    if (g.editingId <= 0) { StatusMsg(L"当前没有正在编辑的记录。"); return; }
    g.editingId = -1;
    ClearForm();
    RefreshAddBtn();
    StatusMsg(L"已取消编辑。");
}

static void LoadDemo() {
    if (!g.records.empty() &&
        MessageBoxW(g.hwnd, L"当前已有数据，载入示例将替换现有记录，是否继续？", L"载入示例",
                    MB_YESNO | MB_ICONQUESTION) != IDYES) return;
    g.records = loadDemoData();
    g.nextId = (int)g.records.size() + 1;
    g.editingId = -1;
    saveRecords(g.records);
    RefreshAll();
    RefreshAddBtn();
    wchar_t buf[128];
    swprintf(buf, 128, L"已载入 %d 条示例记录（覆盖近 4 个自然月）。\n"
                       L"可切换日/月/年视图、生成图表与人格报告，\n"
                       L"底部预警区已对比「历史月均」标出超支分类。",
             (int)g.records.size());
    MessageBoxW(g.hwnd, buf, L"载入完成", MB_OK | MB_ICONINFORMATION);
}

static void ClearData() {
    if (g.records.empty()) return;
    if (MessageBoxW(g.hwnd, L"确定清空全部记录吗？此操作不可撤销。", L"确认清空", MB_YESNO | MB_ICONQUESTION) != IDYES) return;
    g.records.clear();
    g.editingId = -1;
    g.nextId = 1;
    saveRecords(g.records);
    RefreshAll();
    RefreshAddBtn();
}

static void ExportCsv() {
    if (g.records.empty()) { MessageBoxW(g.hwnd, L"当前没有可导出的记录。", L"暂无数据", MB_OK | MB_ICONINFORMATION); return; }
    OPENFILENAMEW ofn{};
    wchar_t file[MAX_PATH] = L"消费记录.csv";
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = g.hwnd;
    ofn.lpstrFilter = L"CSV 文件 (*.csv)\0*.csv\0\0";
    ofn.lpstrFile = file;
    ofn.nMaxFile = MAX_PATH;
    ofn.Flags = OFN_OVERWRITEPROMPT;
    ofn.lpstrDefExt = L"csv";
    if (!GetSaveFileNameW(&ofn)) return;
    std::ofstream f(ofn.lpstrFile, std::ios::out | std::ios::trunc);
    if (!f) { MessageBoxW(g.hwnd, L"导出失败。", L"错误", MB_OK | MB_ICONERROR); return; }
    // 写 UTF-8 BOM
    f << "\xEF\xBB\xBF";
    f << "日期,类型,分类,金额,收款方,用途/备注\n";
    for (auto& r : g.records) {
        f << ws2s(r.date) << "," << ws2s(r.type) << "," << ws2s(r.category) << ","
          << r.amount << "," << ws2s(r.payee) << "," << ws2s(r.note) << "\n";
    }
    MessageBoxW(g.hwnd, (std::wstring(L"已导出 ") + std::to_wstring(g.records.size()) + L" 条记录。").c_str(), L"导出成功", MB_OK | MB_ICONINFORMATION);
}

static void ShowCharts() {
    g.chartRecords = RecordsInView();   // 按当前视图（全部/日/月/年）生成图表分析
    if (g.chartRecords.empty()) { MessageBoxW(g.hwnd, L"当前视图范围内没有记录，请先添加消费记录或切换视图。", L"暂无数据", MB_OK | MB_ICONINFORMATION); return; }
    static bool registered = false;
    if (!registered) {
        WNDCLASSEXW wc{};
        wc.cbSize = sizeof(wc);
        wc.lpfnWndProc = ChartProc;
        wc.hInstance = GetModuleHandleW(nullptr);
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.lpszClassName = L"ExpenseChartWindow";
        wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
        RegisterClassExW(&wc);
        registered = true;
    }
    HWND w = CreateWindowExW(0, L"ExpenseChartWindow",
                             (L"收支分析图表（" + ViewScopeLabel() + L"）").c_str(),
                             WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 1180, 880,
                             g.hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
    ShowWindow(w, SW_SHOW);
    UpdateWindow(w);
}

static void ShowReport() {
    std::vector<Record> view = RecordsInView();  // 按当前视图（全部/日/月/年）生成人格报告
    if (view.empty()) { MessageBoxW(g.hwnd, L"当前视图范围内没有记录，请先添加消费记录或切换视图。", L"暂无数据", MB_OK | MB_ICONINFORMATION); return; }
    g_reportText = makeReport(view);
    static bool registered = false;
    if (!registered) {
        WNDCLASSEXW wc{};
        wc.cbSize = sizeof(wc);
        wc.lpfnWndProc = ReportProc;
        wc.hInstance = GetModuleHandleW(nullptr);
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.lpszClassName = L"ExpenseReportWindow";
        wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
        RegisterClassExW(&wc);
        registered = true;
    }
    HWND w = CreateWindowExW(0, L"ExpenseReportWindow", L"消费人格报告",
                             WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 720, 780,
                             g.hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
    ShowWindow(w, SW_SHOW);
    UpdateWindow(w);
}

// ===================== 消费超预期预警 =====================
// 预期值来源优先级：
//   1. 已规划的计划值：预算规划模块"应用此方案"写入的 g.budgetByCat；
//   2. 历史记录平均值：历史月份中该分类月均支出（无预算方案时）。
// 预警维度：当月各分类累计支出 vs 分类预期；当月总支出 vs 总预算。

static int budgetCatIndex(const std::wstring& cat) {
    for (int i = 0; i < kCategoryCount; ++i)
        if (kDefaultRatios[i].name == cat) return i;
    return -1;
}

// 某分类的"预期值"（元/月）
static double expectedCatMonthly(const std::wstring& cat) {
    int idx = budgetCatIndex(cat);
    if (g.hasBudget && idx >= 0 && g.budgetByCat[idx] > 0) return g.budgetByCat[idx];
    // 无预算方案：取历史月份中该分类的月均支出（排除当月，避免自比）
    SYSTEMTIME st; GetLocalTime(&st);
    wchar_t mbuf[8]; swprintf(mbuf, 8, L"%04d-%02d", st.wYear, st.wMonth);
    std::wstring curMonth = mbuf;
    std::map<std::wstring, double> byMonth;
    for (auto& r : g.records)
        if (r.type == L"支出" && r.category == cat &&
            r.date.size() >= 7 && r.date.substr(0, 7) != curMonth)
            byMonth[r.date.substr(0, 7)] += r.amount;
    if (byMonth.empty()) return 0;
    double sum = 0;
    for (auto& kv : byMonth) sum += kv.second;
    return sum / (double)byMonth.size();
}

static std::vector<std::wstring> BuildWarnTexts() {
    std::vector<std::wstring> out;
    if (g.records.empty()) return out;
    SYSTEMTIME st; GetLocalTime(&st);
    wchar_t mbuf[8]; swprintf(mbuf, 8, L"%04d-%02d", st.wYear, st.wMonth);
    std::wstring month = mbuf;
    std::map<std::wstring, double> cur;
    double monthExp = 0;
    for (auto& r : g.records)
        if (r.type == L"支出" && r.date.size() >= 7 && r.date.substr(0, 7) == month) {
            cur[r.category] += r.amount;
            monthExp += r.amount;
        }
    if (monthExp <= 0) return out;
    // 分类级预警
    for (auto& kv : cur) {
        double expected = expectedCatMonthly(kv.first);
        if (expected <= 0) continue;
        if (kv.second > expected) {
            double over = (kv.second - expected) / expected * 100.0;
            out.push_back(L"⚠ " + kv.first + L" 本月支出 " + fmtMoney(kv.second) +
                          L" 元，超出预期值 " + fmtMoney(expected) + L" 元（" +
                          fmtMoney(over) + L"%）。");
        }
    }
    // 总预算预警
    if (g.hasBudget && g.budgetTotal > 0 && monthExp > g.budgetTotal) {
        double over = (monthExp - g.budgetTotal) / g.budgetTotal * 100.0;
        out.push_back(L"⚠ 本月总支出 " + fmtMoney(monthExp) + L" 元，超出月预算 " +
                      fmtMoney(g.budgetTotal) + L" 元（" + fmtMoney(over) + L"%）。");
    }
    return out;
}

static void RefreshWarn() {
    if (!g.hWarn) return;
    auto warns = BuildWarnTexts();
    if (warns.empty()) {
        if (g.records.empty())
            SetWindowTextW(g.hWarn, L"💡 添加记录后，将自动对比「预算计划值 / 历史月均」进行消费预警");
        else
            SetWindowTextW(g.hWarn, L"✅ 本月消费均在预期范围内");
        return;
    }
    std::wstring msg;
    for (size_t i = 0; i < warns.size(); ++i) {
        if (i) msg += L"\r\n";
        msg += warns[i];
    }
    SetWindowTextW(g.hWarn, msg.c_str());
}

// 添加支出记录后：若该分类当月累计超过预期，弹窗预警
static void CheckOverspendAfterAdd(const Record& r) {
    if (r.type != L"支出") return;
    if (r.date.size() < 7) return;
    SYSTEMTIME st; GetLocalTime(&st);
    wchar_t mbuf[8]; swprintf(mbuf, 8, L"%04d-%02d", st.wYear, st.wMonth);
    if (r.date.substr(0, 7) != mbuf) return;  // 仅对当月记录预警
    auto warns = BuildWarnTexts();
    for (auto& w : warns) {
        if (w.find(r.category) != std::wstring::npos) {
            MessageBoxW(g.hwnd, (w + L"\n\n提示：可在「预算规划」中调整预期值，" +
                                 L"或使用「if 线模拟」探索省钱方案。").c_str(),
                        L"⚠ 消费超预期预警", MB_OK | MB_ICONWARNING);
            return;
        }
    }
}

// ===================== 预算规划集成 =====================

// 保存预算方案到 budget_plan.json（供下次启动恢复"已规划的计划值"）
static void saveBudgetPlanFile() {
    std::ofstream f(L"budget_plan.json", std::ios::out | std::ios::trunc);
    if (!f) return;
    f << "{\"period\":\"month\",\"totalBudget\":" << g.budgetTotal
      << ",\"budgetPerMonth\":" << g.budgetTotal << ",\"categories\":[";
    for (int i = 0; i < kCategoryCount; ++i) {
        if (i) f << ",";
        f << "{\"name\":\"" << ws2s(kDefaultRatios[i].name) << "\",\"amount\":" << g.budgetByCat[i] << "}";
    }
    f << "]}";
}

// 启动时恢复已应用的预算方案（用于超支预警）
static void loadBudgetPlan() {
    std::ifstream f(L"budget_plan.json", std::ios::in | std::ios::binary);
    if (!f) return;
    std::stringstream ss;
    ss << f.rdbuf();
    std::string body = ss.str();
    std::string period = extractStrField(body, "period");
    double scale = (period == "year") ? 12.0 : 1.0;
    std::string totalStr = extractNumField(body, "budgetPerMonth");
    if (totalStr.empty()) totalStr = extractNumField(body, "totalBudget");
    g.budgetTotal = atof(totalStr.c_str());
    bool any = false;
    for (int i = 0; i < kCategoryCount; ++i) {
        std::string pat = "\"name\":\"" + ws2s(kDefaultRatios[i].name) + "\",\"amount\":";
        size_t p = body.find(pat);
        if (p == std::string::npos) continue;
        p += pat.size();
        size_t q = p;
        while (q < body.size() &&
               (isdigit((unsigned char)body[q]) || body[q] == '.' || body[q] == '-'))
            q++;
        double amt = atof(body.substr(p, q - p).c_str());
        g.budgetByCat[i] = amt / scale;
        any = true;
    }
    g.hasBudget = (any || g.budgetTotal > 0);
}

static void ShowBudgetPlannerUI() {
    budgetui::ShowBudgetPlanner(g.hwnd, nullptr);
}

// ===================== if 线模拟 =====================
// if 线：把当前历史记录复制为一份"可修改的副本"，
// 用户可修改其中金额 / 删除记录，实时对比"if 线"与"既有线"的差异
// （如总支出省下多少钱），并将省钱金额映射为可购买的愿望清单，
// 最后把 if 线消费结构生成为现实的预算规划。

struct WishItem {
    double min;
    const wchar_t* desc;
};
static const WishItem kWishList[] = {
    {5000, L"一趟出国旅行或年度大件数码升级"},
    {2000, L"一次为期 3~5 天的国内旅行（食宿交通全包）"},
    {1500, L"一台旗舰级智能手机"},
    {800,  L"一台电子书阅读器 / 入门平板"},
    {400,  L"一次周末城市周边游"},
    {200,  L"一套精装版《莎士比亚全集》"},
    {100,  L"一个月的视频 + 音乐双会员套餐"},
    {60,   L"两张电影票 + 一顿双人下午茶"},
    {30,   L"一杯精品手冲咖啡或一本畅销书"},
    {0,    L"把零钱存进「愿望罐」，积少成多"},
};
static std::wstring wishFor(double money) {
    for (auto& w : kWishList)
        if (money >= w.min) return w.desc;
    return kWishList[sizeof(kWishList) / sizeof(kWishList[0]) - 1].desc;
}

// 计算 if 线与既有线的差异（仅统计当前视图范围：全部 / 日 / 月 / 年）
static void ComputeIfDiff(double& origInc, double& origExp, double& ifInc, double& ifExp,
                          std::map<std::wstring, double>& origCat,
                          std::map<std::wstring, double>& ifCat) {
    origInc = origExp = ifInc = ifExp = 0;
    origCat.clear(); ifCat.clear();
    for (auto& r : g.records) {
        if (!dateInView(r.date)) continue;
        if (r.type == L"支出") { origExp += r.amount; origCat[r.category] += r.amount; }
        else origInc += r.amount;
    }
    for (auto& r : g.ifline) {
        if (!dateInView(r.date)) continue;
        if (r.type == L"支出") { ifExp += r.amount; ifCat[r.category] += r.amount; }
        else ifInc += r.amount;
    }
}

// if 线记录是否匹配查询关键词（日期/类型/分类/收款方/备注）
static bool ifMatchSearch(const Record& r) {
    if (g.ifSearchText.empty()) return true;
    std::wstring q = lowerW(g.ifSearchText);
    return lowerW(r.date).find(q) != std::wstring::npos ||
           lowerW(r.type).find(q) != std::wstring::npos ||
           lowerW(r.category).find(q) != std::wstring::npos ||
           lowerW(r.payee).find(q) != std::wstring::npos ||
           lowerW(r.note).find(q) != std::wstring::npos;
}

static void RefreshIfList() {
    if (!g.hIfList) return;
    SendMessageW(g.hIfList, LVM_DELETEALLITEMS, 0, 0);
    // 过滤（与主窗口视图范围一致）+ 排序
    std::vector<const Record*> rows;
    for (auto& r : g.ifline)
        if (dateInView(r.date) && ifMatchSearch(r)) rows.push_back(&r);
    if (g.ifSortCol >= 0) {
        std::stable_sort(rows.begin(), rows.end(), [](const Record* a, const Record* b) {
            int c = 0;
            switch (g.ifSortCol) {
            case 0: c = a->date.compare(b->date); break;
            case 1: c = a->type.compare(b->type); break;
            case 2: c = a->category.compare(b->category); break;
            case 3: c = (a->amount < b->amount) ? -1 : (a->amount > b->amount ? 1 : 0); break;
            case 4: c = a->payee.compare(b->payee); break;
            default: c = a->note.compare(b->note); break;
            }
            return g.ifSortAsc ? c < 0 : c > 0;
        });
    }
    for (auto* r : rows) {
        int row = (int)SendMessageW(g.hIfList, LVM_GETITEMCOUNT, 0, 0);
        auto setCol = [&](int c, const std::wstring& text) {
            LVITEMW it{};
            it.mask = (c == 0) ? (LVIF_TEXT | LVIF_PARAM) : LVIF_TEXT;
            it.iItem = row;
            it.iSubItem = c;
            it.pszText = (LPWSTR)text.c_str();
            if (c == 0) it.lParam = r->id;
            SendMessageW(g.hIfList, (c == 0) ? LVM_INSERTITEMW : LVM_SETITEMW,
                         0, (LPARAM)&it);
        };
        setCol(0, r->date);
        setCol(1, r->type);
        setCol(2, r->category);
        setCol(3, fmtMoney(r->amount));
        setCol(4, r->payee);
        setCol(5, r->note);
    }
}

// 把选中记录的当前金额填入"新金额"编辑框
static void FillIfAmountEdit() {
    int sel = ListView_GetNextItem(g.hIfList, -1, LVNI_SELECTED);
    if (sel < 0) return;
    LVITEMW it{};
    it.mask = LVIF_PARAM;
    it.iItem = sel;
    if (!ListView_GetItem(g.hIfList, &it)) return;
    for (auto& r : g.ifline)
        if (r.id == (int)it.lParam) {
            SetWindowTextW(g.hIfAmount, fmtMoney(r.amount).c_str());
            return;
        }
}

// 修改 if 线中选中记录的金额
static void ApplyIfAmount() {
    int sel = ListView_GetNextItem(g.hIfList, -1, LVNI_SELECTED);
    if (sel < 0) { MessageBoxW(g.hwnd, L"请先在 if 线列表中选择一条记录。", L"未选择", MB_OK | MB_ICONINFORMATION); return; }
    LVITEMW it{}; it.mask = LVIF_PARAM; it.iItem = sel;
    if (!ListView_GetItem(g.hIfList, &it)) return;
    wchar_t buf[64];
    GetWindowTextW(g.hIfAmount, buf, 64);
    double amt = 0;
    try { amt = std::stod(ws2s(trim(buf))); } catch (...) { amt = -1; }
    if (amt < 0) { MessageBoxW(g.hwnd, L"金额必须是大于等于 0 的数字。", L"金额格式错误", MB_OK | MB_ICONWARNING); return; }
    for (auto& r : g.ifline)
        if (r.id == (int)it.lParam) {
            r.amount = amt;
            break;
        }
    RefreshIfList();
    InvalidateRect(g.hIfDiff, nullptr, TRUE);
    StatusMsg(L"if 线金额已修改。");
}

// 删除 if 线中选中的记录
static void DeleteIfSelected() {
    int sel = ListView_GetNextItem(g.hIfList, -1, LVNI_SELECTED);
    if (sel < 0) { MessageBoxW(g.hwnd, L"请先在 if 线列表中选择一条记录。", L"未选择", MB_OK | MB_ICONINFORMATION); return; }
    LVITEMW it{}; it.mask = LVIF_PARAM; it.iItem = sel;
    if (!ListView_GetItem(g.hIfList, &it)) return;
    int id = (int)it.lParam;
    g.ifline.erase(std::remove_if(g.ifline.begin(), g.ifline.end(),
                                  [id](const Record& r) { return r.id == id; }),
                   g.ifline.end());
    RefreshIfList();
    InvalidateRect(g.hIfDiff, nullptr, TRUE);
    StatusMsg(L"if 线记录已删除（不影响原数据）。");
}

// 把 if 线恢复到与既有线一致（重新复制）
static void RecopyIfFromMain() {
    if (g.records.empty()) return;
    g.ifline = g.records;
    RefreshIfList();
    InvalidateRect(g.hIfDiff, nullptr, TRUE);
    StatusMsg(L"if 线已重置为既有线副本。");
}

// 恢复选中记录：从既有线还原金额 / 存在性
static void RestoreIfSelected() {
    int sel = ListView_GetNextItem(g.hIfList, -1, LVNI_SELECTED);
    if (sel < 0) { MessageBoxW(g.hwnd, L"请先在 if 线列表中选择一条记录。", L"未选择", MB_OK | MB_ICONINFORMATION); return; }
    LVITEMW it{}; it.mask = LVIF_PARAM; it.iItem = sel;
    if (!ListView_GetItem(g.hIfList, &it)) return;
    int id = (int)it.lParam;
    const Record* src = nullptr;
    for (auto& r : g.records)
        if (r.id == id) { src = &r; break; }
    if (!src) { MessageBoxW(g.hwnd, L"既有线中不存在该记录。", L"无法恢复", MB_OK | MB_ICONWARNING); return; }
    bool found = false;
    for (auto& r : g.ifline)
        if (r.id == id) { r = *src; found = true; break; }
    if (!found) g.ifline.push_back(*src);
    RefreshIfList();
    InvalidateRect(g.hIfDiff, nullptr, TRUE);
    StatusMsg(L"已从既有线恢复该记录。");
}

// 把 if 线变为现实的规划：按 if 线消费结构预填预算规划窗口（按当前视图范围统计）
static void IfLineToPlan() {
    if (g.ifline.empty()) { MessageBoxW(g.hwnd, L"if 线为空，无法生成规划。", L"提示", MB_OK | MB_ICONINFORMATION); return; }
    std::map<std::wstring, double> catAmt;
    double totalExp = 0, totalInc = 0;
    for (auto& r : g.ifline) {
        if (!dateInView(r.date)) continue;
        if (r.type == L"支出") { catAmt[r.category] += r.amount; totalExp += r.amount; }
        else totalInc += r.amount;
    }
    if (totalExp <= 0 && totalInc <= 0) {
        MessageBoxW(g.hwnd, L"当前视图范围内没有 if 线记录，无法生成规划。", L"提示", MB_OK | MB_ICONINFORMATION); return;
    }
    double weights[kCategoryCount] = {0};
    if (totalExp > 0)
        for (int i = 0; i < kCategoryCount; ++i)
            weights[i] = catAmt[kDefaultRatios[i].name] / totalExp * 100.0;
    double save = totalInc - totalExp;
    double rate = totalInc > 0 ? save / totalInc * 100.0 : 0.0;
    std::wstring msg = L"已将 if 线的消费结构预填到预算规划窗口。\n\n如果能把 if 线变为现实：\n";
    msg += L"  · 每期结余 " + fmtMoney(save) + L" 元";
    if (totalInc > 0) msg += L"（储蓄率 " + fmtMoney(rate) + L"% ）";
    msg += L"\n  · 坚持 " + std::to_wstring((int)std::ceil((save > 0 ? 1000.0 / save : 0.0))) +
           L" 个月即可攒下 1000 元应急金\n";
    msg += L"  · 这笔钱足够：" + wishFor(save) + L"\n\n";
    msg += L"请在下个窗口中确认省份 / 收入 / 占比，然后点击「应用此方案到主程序」，即可把 if 线落地为现实规划并用于超支预警。";
    budgetui::ShowBudgetPlanner(g.hwnd, weights);
    MessageBoxW(g.hwnd, msg.c_str(), L"🎯 把 if 线变为现实的规划", MB_OK | MB_ICONINFORMATION);
}

// ===================== if 线：增删查改（表单） =====================

// 重置 if 线录入表单（退出编辑态）
static void IfFormReset() {
    g.ifEditingId = -1;
    if (g.hIfAddBtn) SetWindowTextW(g.hIfAddBtn, L"＋ 添加记录");
    if (g.hIfCancelBtn) EnableWindow(g.hIfCancelBtn, FALSE);
    if (g.hIfDate) SetWindowTextW(g.hIfDate, viewDefaultDate().c_str());
    if (g.hIfTypeCombo) SendMessageW(g.hIfTypeCombo, CB_SETCURSEL, 0, 0);
    if (g.hIfCatCombo) ComboSet(g.hIfCatCombo, kExpenseCategories, L"餐饮");
    if (g.hIfAmountForm) SetWindowTextW(g.hIfAmountForm, L"");
    if (g.hIfPayee) SetWindowTextW(g.hIfPayee, L"");
    if (g.hIfNote) SetWindowTextW(g.hIfNote, L"");
}

// if 表单：类型切换时联动重置分类下拉
static void OnIfFormTypeChanged() {
    if (g.ifFormUpdating) return;
    std::wstring type = ComboText(g.hIfTypeCombo);
    ComboSet(g.hIfCatCombo, type == L"收入" ? kIncomeCategories : kExpenseCategories,
             type == L"收入" ? L"工资" : L"餐饮");
}

// if 表单 -> 记录（含校验）
static bool IfFormToRecord(Record& r, std::wstring& err) {
    wchar_t buf[256];
    GetWindowTextW(g.hIfDate, buf, 256); r.date = trim(buf);
    if (r.date.size() != 10 || r.date[4] != L'-' || r.date[7] != L'-') {
        err = L"日期请使用 YYYY-MM-DD 格式，例如 2026-08-23。"; return false;
    }
    r.type = ComboText(g.hIfTypeCombo);
    if (r.type.empty()) r.type = L"支出";
    r.category = ComboText(g.hIfCatCombo);
    if (r.category.empty()) r.category = L"其他";
    GetWindowTextW(g.hIfAmountForm, buf, 256);
    double amt = 0;
    try { amt = std::stod(ws2s(trim(buf))); } catch (...) { amt = -1; }
    if (amt < 0) { err = L"金额必须是大于等于 0 的数字。"; return false; }
    r.amount = amt;
    GetWindowTextW(g.hIfPayee, buf, 256); r.payee = trim(buf);
    GetWindowTextW(g.hIfNote, buf, 256); r.note = trim(buf);
    return true;
}

// 新增 if 线记录；编辑态下保存对选中记录的修改
static void AddOrSaveIfRecord() {
    Record r;
    std::wstring err;
    if (!IfFormToRecord(r, err)) { MessageBoxW(g.hwnd, err.c_str(), L"输入有误", MB_OK | MB_ICONWARNING); return; }
    if (g.ifEditingId > 0) {
        bool found = false;
        for (auto& x : g.ifline)
            if (x.id == g.ifEditingId) { r.id = x.id; x = r; found = true; break; }
        if (!found) { MessageBoxW(g.hwnd, L"要编辑的记录不存在。", L"编辑失败", MB_OK | MB_ICONWARNING); return; }
        IfFormReset();
        RefreshIfList();
        InvalidateRect(g.hIfDiff, nullptr, TRUE);
        StatusMsg(L"if 线记录已保存修改。");
    } else {
        r.id = g.ifLineNextId--;   // 新增记录使用负 id，避免与既有线冲突
        g.ifline.push_back(r);
        IfFormReset();
        RefreshIfList();
        InvalidateRect(g.hIfDiff, nullptr, TRUE);
        StatusMsg(L"已向 if 线新增一条记录。");
    }
}

// 把选中记录的完整字段回填到表单，进入编辑态
static void EditIfSelected() {
    int sel = ListView_GetNextItem(g.hIfList, -1, LVNI_SELECTED);
    if (sel < 0) { MessageBoxW(g.hwnd, L"请先在 if 线列表中选择一条记录。", L"未选择", MB_OK | MB_ICONINFORMATION); return; }
    LVITEMW it{}; it.mask = LVIF_PARAM; it.iItem = sel;
    if (!ListView_GetItem(g.hIfList, &it)) return;
    for (auto& r : g.ifline)
        if (r.id == (int)it.lParam) {
            g.ifEditingId = r.id;
            g.ifFormUpdating = true;
            SetWindowTextW(g.hIfDate, r.date.c_str());
            SendMessageW(g.hIfTypeCombo, CB_SETCURSEL, 0, r.type == L"收入" ? 1 : 0);
            ComboSet(g.hIfCatCombo, r.type == L"收入" ? kIncomeCategories : kExpenseCategories, r.category);
            SetWindowTextW(g.hIfAmountForm, fmtMoney(r.amount).c_str());
            SetWindowTextW(g.hIfPayee, r.payee.c_str());
            SetWindowTextW(g.hIfNote, r.note.c_str());
            g.ifFormUpdating = false;
            SetWindowTextW(g.hIfAddBtn, L"✓ 保存修改");
            EnableWindow(g.hIfCancelBtn, TRUE);
            SetFocus(g.hIfAmountForm);
            return;
        }
    MessageBoxW(g.hwnd, L"记录不存在。", L"编辑失败", MB_OK | MB_ICONWARNING);
}

// 取消编辑态
static void CancelIfEdit() {
    IfFormReset();
}

// 读取 if 线查询关键词（实时过滤列表）
static void ReadIfSearch() {
    wchar_t buf[128];
    GetWindowTextW(g.hIfSearch, buf, 128);
    g.ifSearchText = trim(buf);
    RefreshIfList();
}

// ===================== 消费习惯建议 =====================

// 基于当前视图内的 if 线 vs 既有线，生成消费习惯建议报告
static std::wstring MakeHabitAdvice() {
    double origInc, origExp, ifInc, ifExp;
    std::map<std::wstring, double> origCat, ifCat;
    ComputeIfDiff(origInc, origExp, ifInc, ifExp, origCat, ifCat);
    std::wstring s = L"【分析范围】" + ViewScopeLabel() + L"　（if 线 vs 既有线）\n\n";

    // 1. 收支与储蓄
    double save = ifInc - ifExp;
    if (ifInc > 0) {
        double rate = save / ifInc * 100.0;
        if (rate >= 20) s += L"✅ 储蓄率 " + fmtMoney(rate) + L"% ，财务健康，建议坚持「先储蓄、后消费」。\n";
        else if (rate >= 10) s += L"🟡 储蓄率 " + fmtMoney(rate) + L"% ，中等偏下，建议先把应急金攒到 3 个月开销。\n";
        else if (rate >= 0) s += L"⚠️ 储蓄率仅 " + fmtMoney(rate) + L"% ，结余接近零，建议从娱乐 / 购物等弹性支出中挤出储蓄。\n";
        else s += L"🚨 已入不敷出（结余为负），建议立即削减非必要支出或增加副业收入。\n";
    } else {
        s += L"⚠️ 该范围内没有收入记录，无法评估储蓄率，请确认收入是否已录入。\n";
    }

    // 2. 支出集中度
    double totalExp = 0; std::wstring topCat; double topAmt = 0;
    for (auto& kv : ifCat) { totalExp += kv.second; if (kv.second > topAmt) { topAmt = kv.second; topCat = kv.first; } }
    if (totalExp > 0 && !topCat.empty()) {
        double pct = topAmt / totalExp * 100.0;
        if (pct >= 40) s += L"📌 支出高度集中在「" + topCat + L"」，占 " + fmtMoney(pct) + L"% ，建议为该分类设置月度上限并寻找平价替代。\n";
        else if (pct >= 25) s += L"📌 最大支出分类「" + topCat + L"」占 " + fmtMoney(pct) + L"% ，略高，可留意冲动消费。\n";
    }

    // 3. 与既有线各分类的省 / 超对比
    std::vector<std::pair<std::wstring, double>> deltas;
    for (auto& kv : origCat) deltas.push_back({kv.first, kv.second - ifCat[kv.first]});
    for (auto& kv : ifCat)
        if (origCat.find(kv.first) == origCat.end()) deltas.push_back({kv.first, -kv.second});
    std::sort(deltas.begin(), deltas.end(), [](auto& a, auto& b) { return a.second > b.second; });
    if (!deltas.empty()) {
        if (deltas[0].second >= 1.0)
            s += L"💪 在「" + deltas[0].first + L"」上比原消费省下 " + fmtMoney(deltas[0].second) + L" 元，这是最成功的调整，继续保持。\n";
        if (deltas.back().second <= -1.0)
            s += L"📈 「" + deltas.back().first + L"」多花 " + fmtMoney(-deltas.back().second) + L" 元，请检查该笔是否必要。\n";
    }

    // 4. 相对既有线的整体结余变化
    double origSave = origInc - origExp;
    double saveDelta = save - origSave;
    if (saveDelta >= 1.0)
        s += L"🏆 相对既有线整体省下 " + fmtMoney(saveDelta) + L" 元，约合 " + wishFor(saveDelta) + L" ，可以把它设为下一阶段的奖励目标。\n";
    else if (saveDelta <= -1.0)
        s += L"💡 相对既有线多支出 " + fmtMoney(-saveDelta) + L" 元，试着把 if 线里的好习惯坚持下来，就能变回省钱模式。\n";

    if (ifInc == 0 && ifExp == 0)
        s += L"📋 当前视图范围内暂无 if 线记录，请在列表中加入记录后再分析。\n";
    return s;
}

// 弹窗展示完整消费习惯建议
static void ShowHabitAdvice() {
    MessageBoxW(g.hwnd, MakeHabitAdvice().c_str(), L"💡 消费习惯建议报告", MB_OK | MB_ICONINFORMATION);
}

// if 线差异面板（自绘）
static void DrawIfLineResult(HWND hwnd) {
    PAINTSTRUCT ps;
    HDC hdc = BeginPaint(hwnd, &ps);
    RECT rc; GetClientRect(hwnd, &rc);
    FillRect(hdc, &rc, (HBRUSH)GetStockObject(WHITE_BRUSH));

    double origInc, origExp, ifInc, ifExp;
    std::map<std::wstring, double> origCat, ifCat;
    ComputeIfDiff(origInc, origExp, ifInc, ifExp, origCat, ifCat);

    double origSave = origInc - origExp;
    double ifSave = ifInc - ifExp;
    double saveDelta = ifSave - origSave;  // >0 即"省下"

    HFONT titleFont = CreateFontW(20, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET,
                                  0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
    HFONT bodyFont = CreateFontW(18, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                                 0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
    HFONT old = (HFONT)SelectObject(hdc, bodyFont);
    SetBkMode(hdc, TRANSPARENT);

    int y = 10;
    RECT rtt = {14, y, rc.right - 14, y + 28};
    SelectObject(hdc, titleFont);
    SetTextColor(hdc, RGB(0x1A, 0x23, 0x7E));
    DrawTextW(hdc, (L"if 线 vs 既有线 差异对比（" + ViewScopeLabel() + L"）").c_str(),
              -1, &rtt, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    y += 30;

    SelectObject(hdc, bodyFont);
    SetTextColor(hdc, RGB(0x37, 0x47, 0x4F));
    RECT r1 = {14, y, rc.right - 14, y + 26};
    DrawTextW(hdc, (L"既有线：总收入 " + fmtMoney(origInc) + L" 元　总支出 " +
                    fmtMoney(origExp) + L" 元　结余 " + fmtMoney(origSave) + L" 元").c_str(),
              -1, &r1, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    y += 26;
    RECT r2 = {14, y, rc.right - 14, y + 26};
    DrawTextW(hdc, (L"if 线：总收入 " + fmtMoney(ifInc) + L" 元　总支出 " +
                    fmtMoney(ifExp) + L" 元　结余 " + fmtMoney(ifSave) + L" 元").c_str(),
              -1, &r2, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    y += 30;

    // 差异行
    if (saveDelta >= 0.0005) {
        SetTextColor(hdc, RGB(0x2E, 0x7D, 0x32));
        RECT r3 = {14, y, rc.right - 14, y + 28};
        DrawTextW(hdc, (L"💰 在 if 线中，您已省下 " + fmtMoney(saveDelta) +
                        L" 元，可购买：" + wishFor(saveDelta)).c_str(),
                  -1, &r3, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
        y += 28;
        SetTextColor(hdc, RGB(0x55, 0x8B, 0x2F));
        RECT r4 = {14, y, rc.right - 14, y + 26};
        double months = saveDelta > 0 ? 1000.0 / saveDelta : 0;
        DrawTextW(hdc, (L"坚持该消费水平 " + std::to_wstring((int)std::ceil(months)) +
                        L" 个月即可攒下 1000 元应急金").c_str(),
                  -1, &r4, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    } else {
        SetTextColor(hdc, RGB(0xC6, 0x28, 0x28));
        RECT r3 = {14, y, rc.right - 14, y + 28};
        DrawTextW(hdc, (L"⚠ if 线比既有线多支出 " + fmtMoney(-saveDelta) +
                        L" 元，当前副本更花费，请尝试削减可压缩分类。").c_str(),
                  -1, &r3, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    }
    y += 32;

    // 分类支出对比（节省最多的前 3 个分类）
    SetTextColor(hdc, RGB(0x21, 0x21, 0x21));
    RECT r5 = {14, y, rc.right - 14, y + 24};
    DrawTextW(hdc, L"分类支出变化：", -1, &r5, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    y += 24;
    std::vector<std::pair<std::wstring, double>> deltas;
    for (auto& kv : origCat)
        deltas.push_back({kv.first, kv.second - ifCat[kv.first]});
    for (auto& kv : ifCat)
        if (origCat.find(kv.first) == origCat.end()) deltas.push_back({kv.first, -kv.second});
    std::sort(deltas.begin(), deltas.end(),
              [](auto& a, auto& b) { return a.second > b.second; });
    int shown = 0;
    for (auto& d : deltas) {
        if (shown >= 3) break;
        if (std::fabs(d.second) < 0.005) continue;
        SetTextColor(hdc, d.second >= 0 ? RGB(0x2E, 0x7D, 0x32) : RGB(0xC6, 0x28, 0x28));
        RECT rline = {28, y, rc.right - 14, y + 22};
        DrawTextW(hdc, (d.first + L"  " + (d.second >= 0 ? L"省下 " : L"多花 ") +
                        fmtMoney(std::fabs(d.second)) + L" 元").c_str(),
                  -1, &rline, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
        y += 22;
        shown++;
    }
    if (shown == 0) {
        RECT rempty = {28, y, rc.right - 14, y + 22};
        DrawTextW(hdc, L"（无显著变化）", -1, &rempty, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    }
    y += 26;

    // 消费习惯建议（跟随当前视图自动生成）
    SetTextColor(hdc, RGB(0x1A, 0x23, 0x7E));
    RECT r6 = {14, y, rc.right - 14, y + 24};
    DrawTextW(hdc, L"💡 消费习惯建议", -1, &r6, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    y += 24;
    std::wstring advice = MakeHabitAdvice();
    std::vector<std::wstring> alines;
    std::wstring cur;
    for (wchar_t c : advice) {
        if (c == L'\n') { if (!cur.empty()) alines.push_back(cur); cur.clear(); }
        else cur += c;
    }
    if (!cur.empty()) alines.push_back(cur);
    int aShown = 0;
    for (size_t i = 1; i < alines.size() && aShown < 3; ++i) {  // 跳过第 1 行（范围行）
        SetTextColor(hdc, RGB(0x2E, 0x7D, 0x32));
        RECT rline = {28, y, rc.right - 14, y + 20};
        DrawTextW(hdc, alines[i].c_str(), -1, &rline, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
        y += 20;
        aShown++;
    }
    if (alines.size() > 4) {
        SetTextColor(hdc, RGB(0x90, 0x6C, 0x3C));
        RECT rmore = {28, y, rc.right - 14, y + 20};
        DrawTextW(hdc, L"… 更多建议请点击「💡 建议报告」查看全部", -1, &rmore,
                  DT_LEFT | DT_SINGLELINE | DT_VCENTER);
        y += 20;
    }
    y += 6;

    // 提示
    SetTextColor(hdc, RGB(0x90, 0x6C, 0x3C));
    RECT rtip = {14, y, rc.right - 14, rc.bottom - 8};
    DrawTextW(hdc, L"💡 点击「生成现实规划」即可把 if 线的消费结构落地为预算方案，指导真实消费。",
              -1, &rtip, DT_LEFT | DT_SINGLELINE | DT_VCENTER);

    SelectObject(hdc, old);
    DeleteObject(titleFont);
    DeleteObject(bodyFont);
    EndPaint(hwnd, &ps);
}

LRESULT CALLBACK IfLineResultProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_PAINT: DrawIfLineResult(hwnd); return 0;
    case WM_ERASEBKGND: return 1;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

// if 线主窗口
LRESULT CALLBACK IfLineProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_CREATE: {
        HFONT font = (HFONT)GetStockObject(DEFAULT_GUI_FONT);
        HWND hTitle = CreateWindowW(L"STATIC", L"if 线模拟 —— 历史记录的可修改副本",
                                    WS_CHILD | WS_VISIBLE | SS_LEFT,
                                    14, 8, 700, 26, hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(hTitle, WM_SETFONT, (WPARAM)font, TRUE);
        g.hIfHint = CreateWindowW(L"STATIC",
                                  L"提示：增删查改均不影响既有数据；点击表头可排序；双击或点「✏ 编辑选中」可回填表单修改；差异分析与建议跟随主窗口视图（全部 / 日 / 月 / 年）自动生成。",
                                  WS_CHILD | WS_VISIBLE | SS_LEFT,
                                  14, 34, 1020, 20, hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(g.hIfHint, WM_SETFONT, (WPARAM)font, TRUE);

        // 查询行（查）
        CreateLabel(hwnd, L"搜索", 14, 62, 40, 20);
        g.hIfSearch = CreateEdit(hwnd, 50, 58, 200, 26);
        SetWindowLongPtrW(g.hIfSearch, GWLP_ID, 303);  // 供 EN_CHANGE 识别
        SendMessageW(g.hIfSearch, WM_SETFONT, (WPARAM)font, TRUE);
        g.hIfScope = CreateLabel(hwnd, (L"分析范围（跟随主窗口视图）：" + ViewScopeLabel()).c_str(),
                                 320, 62, 650, 20);
        SendMessageW(g.hIfScope, WM_SETFONT, (WPARAM)font, TRUE);

        // 录入表单 第 1 行（增 / 改）
        CreateLabel(hwnd, L"日期", 14, 94, 40, 20);
        g.hIfDate = CreateEdit(hwnd, 50, 90, 100, 24);
        SetWindowLongPtrW(g.hIfDate, GWLP_ID, 310);
        SetWindowTextW(g.hIfDate, viewDefaultDate().c_str());
        SendMessageW(g.hIfDate, WM_SETFONT, (WPARAM)font, TRUE);
        CreateLabel(hwnd, L"类型", 156, 94, 40, 20);
        g.hIfTypeCombo = CreateCombo(hwnd, 190, 90, 70, 200);
        SendMessageW(g.hIfTypeCombo, CB_ADDSTRING, 0, (LPARAM)L"支出");
        SendMessageW(g.hIfTypeCombo, CB_ADDSTRING, 0, (LPARAM)L"收入");
        SendMessageW(g.hIfTypeCombo, CB_SETCURSEL, 0, 0);
        SetWindowLongPtrW(g.hIfTypeCombo, GWLP_ID, 304);  // 供 CBN_SELCHANGE 识别
        SendMessageW(g.hIfTypeCombo, WM_SETFONT, (WPARAM)font, TRUE);
        CreateLabel(hwnd, L"分类", 266, 94, 40, 20);
        g.hIfCatCombo = CreateCombo(hwnd, 300, 90, 110, 200);
        ComboSet(g.hIfCatCombo, kExpenseCategories, L"餐饮");
        SendMessageW(g.hIfCatCombo, WM_SETFONT, (WPARAM)font, TRUE);
        CreateLabel(hwnd, L"金额(元)", 416, 94, 60, 20);
        g.hIfAmountForm = CreateEdit(hwnd, 472, 90, 100, 24);
        SetWindowLongPtrW(g.hIfAmountForm, GWLP_ID, 311);
        SendMessageW(g.hIfAmountForm, WM_SETFONT, (WPARAM)font, TRUE);

        // 录入表单 第 2 行
        CreateLabel(hwnd, L"收款方", 14, 120, 50, 20);
        g.hIfPayee = CreateEdit(hwnd, 60, 116, 150, 24);
        SetWindowLongPtrW(g.hIfPayee, GWLP_ID, 312);
        SendMessageW(g.hIfPayee, WM_SETFONT, (WPARAM)font, TRUE);
        CreateLabel(hwnd, L"用途/备注", 216, 120, 70, 20);
        g.hIfNote = CreateEdit(hwnd, 282, 116, 240, 24);
        SetWindowLongPtrW(g.hIfNote, GWLP_ID, 313);
        SendMessageW(g.hIfNote, WM_SETFONT, (WPARAM)font, TRUE);
        g.hIfAddBtn = CreateWindowW(L"BUTTON", L"＋ 添加记录", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                    540, 114, 110, 26, hwnd, (HMENU)301, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(g.hIfAddBtn, WM_SETFONT, (WPARAM)font, TRUE);
        g.hIfCancelBtn = CreateWindowW(L"BUTTON", L"取消编辑", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                       656, 114, 90, 26, hwnd, (HMENU)302, GetModuleHandleW(nullptr), nullptr);
        EnableWindow(g.hIfCancelBtn, FALSE);
        SendMessageW(g.hIfCancelBtn, WM_SETFONT, (WPARAM)font, TRUE);
        HWND bEdit = CreateWindowW(L"BUTTON", L"✏ 编辑选中", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                   752, 114, 100, 26, hwnd, (HMENU)308, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(bEdit, WM_SETFONT, (WPARAM)font, TRUE);

        // 列表
        g.hIfList = CreateWindowW(WC_LISTVIEWW, L"", WS_CHILD | WS_VISIBLE | WS_BORDER |
                                  LVS_REPORT | LVS_SINGLESEL, 14, 150, 972, 200,
                                  hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(g.hIfList, WM_SETFONT, (WPARAM)font, TRUE);
        ListView_SetExtendedListViewStyle(g.hIfList, LVS_EX_FULLROWSELECT | LVS_EX_GRIDLINES);
        const wchar_t* heads[] = {L"日期", L"类型", L"分类", L"金额(元)", L"收款方", L"用途/备注"};
        int widths[] = {100, 60, 90, 100, 220, 280};
        for (int i = 0; i < 6; ++i) {
            LVCOLUMNW colw{};
            colw.mask = LVCF_TEXT | LVCF_WIDTH | LVCF_SUBITEM;
            colw.pszText = (LPWSTR)heads[i];
            colw.cx = widths[i];
            colw.iSubItem = i;
            ListView_InsertColumn(g.hIfList, i, &colw);
        }

        // 操作区：快速改金额 + 记录操作 + 分析
        CreateLabel(hwnd, L"新金额(元)", 14, 360, 90, 20);
        g.hIfAmount = CreateEdit(hwnd, 100, 356, 110, 24);
        SendMessageW(g.hIfAmount, WM_SETFONT, (WPARAM)font, TRUE);
        HWND b1 = CreateWindowW(L"BUTTON", L"应用修改", WS_CHILD | WS_VISIBLE, 218, 356, 90, 26,
                                hwnd, (HMENU)201, GetModuleHandleW(nullptr), nullptr);
        HWND b2 = CreateWindowW(L"BUTTON", L"删除选中", WS_CHILD | WS_VISIBLE, 314, 356, 90, 26,
                                hwnd, (HMENU)202, GetModuleHandleW(nullptr), nullptr);
        HWND b3 = CreateWindowW(L"BUTTON", L"重新复制", WS_CHILD | WS_VISIBLE, 410, 356, 90, 26,
                                hwnd, (HMENU)203, GetModuleHandleW(nullptr), nullptr);
        HWND b4 = CreateWindowW(L"BUTTON", L"恢复选中", WS_CHILD | WS_VISIBLE, 506, 356, 90, 26,
                                hwnd, (HMENU)204, GetModuleHandleW(nullptr), nullptr);
        HWND b5 = CreateWindowW(L"BUTTON", L"🎯 生成现实规划", WS_CHILD | WS_VISIBLE, 602, 356, 130, 26,
                                hwnd, (HMENU)205, GetModuleHandleW(nullptr), nullptr);
        HWND b7 = CreateWindowW(L"BUTTON", L"💡 建议报告", WS_CHILD | WS_VISIBLE, 738, 356, 110, 26,
                                hwnd, (HMENU)207, GetModuleHandleW(nullptr), nullptr);
        HWND b6 = CreateWindowW(L"BUTTON", L"关闭", WS_CHILD | WS_VISIBLE, 854, 356, 70, 26,
                                hwnd, (HMENU)206, GetModuleHandleW(nullptr), nullptr);
        for (HWND b : {b1, b2, b3, b4, b5, b7, b6}) SendMessageW(b, WM_SETFONT, (WPARAM)font, TRUE);

        // 差异面板（含消费习惯建议）
        g.hIfDiff = CreateWindowExW(WS_EX_CLIENTEDGE, L"IfLineResultWindow", L"",
                                    WS_CHILD | WS_VISIBLE, 14, 390, 972, 400,
                                    hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);

        RefreshIfList();
        return 0;
    }
    case WM_COMMAND: {
        int id = LOWORD(wParam);
        if (HIWORD(wParam) == BN_CLICKED) {
            if (id == 201) ApplyIfAmount();
            else if (id == 202) DeleteIfSelected();
            else if (id == 203) RecopyIfFromMain();
            else if (id == 204) RestoreIfSelected();
            else if (id == 205) IfLineToPlan();
            else if (id == 206) DestroyWindow(hwnd);
            else if (id == 207) ShowHabitAdvice();
            else if (id == 301) AddOrSaveIfRecord();
            else if (id == 302) CancelIfEdit();
            else if (id == 308) EditIfSelected();
        } else if (HIWORD(wParam) == EN_CHANGE && id == 303) {
            ReadIfSearch();
        } else if (HIWORD(wParam) == CBN_SELCHANGE && id == 304) {
            OnIfFormTypeChanged();
        }
        return 0;
    }
    case WM_NOTIFY: {
        NMHDR* nm = (NMHDR*)lParam;
        if (nm->hwndFrom != g.hIfList) return 0;
        if (nm->code == NM_DBLCLK) {
            EditIfSelected();
        } else if (nm->code == LVN_COLUMNCLICK) {
            // 点击表头排序（与主窗口一致的交互）
            NMLISTVIEW* plv = (NMLISTVIEW*)lParam;
            int col = plv->iSubItem;
            if (col < 0 || col > 5) return 0;
            if (g.ifSortCol == col) g.ifSortAsc = !g.ifSortAsc;
            else { g.ifSortCol = col; g.ifSortAsc = true; }
            RefreshIfList();
            static const wchar_t* colNames[] = {L"日期", L"类型", L"分类", L"金额", L"收款方", L"备注"};
            if (g.hIfHint)
                SetWindowTextW(g.hIfHint,
                               (std::wstring(L"提示：已按「") + colNames[col] + L"」" +
                                (g.ifSortAsc ? L"升序" : L"降序") +
                                L"排序；双击或点「✏ 编辑选中」可回填表单修改。").c_str());
        }
        return 0;
    }
    case WM_APP + 1: {  // 主窗口视图变化 → 按新视图刷新分析
        if (g.hIfScope)
            SetWindowTextW(g.hIfScope, (L"分析范围（跟随主窗口视图）：" + ViewScopeLabel()).c_str());
        RefreshIfList();
        if (g.hIfDiff) InvalidateRect(g.hIfDiff, nullptr, TRUE);
        return 0;
    }
    case WM_DESTROY:
        if (g.hIfLine == hwnd) g.hIfLine = nullptr;
        return 0;
    case WM_CLOSE:
        DestroyWindow(hwnd);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

static void ShowIfLine() {
    if (g.records.empty()) {
        MessageBoxW(g.hwnd, L"请先添加消费记录，或点击「载入示例数据」再开始 if 线模拟。",
                    L"暂无数据", MB_OK | MB_ICONINFORMATION);
        return;
    }
    g.ifline = g.records;  // 复制一份可修改的副本
    static bool registered = false;
    if (!registered) {
        WNDCLASSEXW wc{};
        wc.cbSize = sizeof(wc);
        wc.lpfnWndProc = IfLineResultProc;
        wc.hInstance = GetModuleHandleW(nullptr);
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
        wc.lpszClassName = L"IfLineResultWindow";
        RegisterClassExW(&wc);
        WNDCLASSEXW wc2{};
        wc2.cbSize = sizeof(wc2);
        wc2.lpfnWndProc = IfLineProc;
        wc2.hInstance = GetModuleHandleW(nullptr);
        wc2.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc2.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
        wc2.lpszClassName = L"IfLineWindow";
        RegisterClassExW(&wc2);
        registered = true;
    }
    HWND w = CreateWindowExW(0, L"IfLineWindow", L"if 线模拟 —— 历史记录的可修改副本",
                             WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 1010, 880,
                             g.hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
    g.hIfLine = w;
    ShowWindow(w, SW_SHOW);
    UpdateWindow(w);
}

// ===================== 主窗口过程 =====================

LRESULT CALLBACK MainProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_CREATE: {
        g.hwnd = hwnd;
        HFONT font = (HFONT)GetStockObject(DEFAULT_GUI_FONT);
        int y = 10;

        // 标题
        HWND hTitle = CreateWindowW(L"STATIC", L"个人消费分析助手",
                                    WS_CHILD | WS_VISIBLE | SS_LEFT,
                                    14, y, 500, 28, hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        HFONT titleFont = CreateFontW(22, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET,
                                      0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
        SendMessageW(hTitle, WM_SETFONT, (WPARAM)titleFont, TRUE);
        y += 36;

        // 录入区
        HWND hForm = CreateWindowW(L"BUTTON", L"录入一笔消费 / 收入",
                                   WS_CHILD | WS_VISIBLE | BS_GROUPBOX,
                                   14, y, 1150, 88, hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(hForm, WM_SETFONT, (WPARAM)font, TRUE);
        int fy = y + 24;
        int col = 14;
        CreateLabel(hwnd, L"日期", col, fy, 40, 20); col += 44;
        g.hDate = CreateEdit(hwnd, col, fy, 100, 24); SendMessageW(g.hDate, WM_SETFONT, (WPARAM)font, TRUE);
        SetWindowTextW(g.hDate, viewDefaultDate().c_str()); col += 110;
        CreateLabel(hwnd, L"类型", col, fy, 40, 20); col += 44;
        g.hTypeCombo = CreateCombo(hwnd, col, fy, 70, 200);
        SendMessageW(g.hTypeCombo, CB_ADDSTRING, 0, (LPARAM)L"支出");
        SendMessageW(g.hTypeCombo, CB_ADDSTRING, 0, (LPARAM)L"收入");
        SendMessageW(g.hTypeCombo, CB_SETCURSEL, 0, 0);
        SetWindowLongPtrW(g.hTypeCombo, GWLP_ID, 1100);  // 供 CBN_SELCHANGE 识别
        SendMessageW(g.hTypeCombo, WM_SETFONT, (WPARAM)font, TRUE); col += 80;
        CreateLabel(hwnd, L"分类", col, fy, 40, 20); col += 44;
        g.hCatCombo = CreateCombo(hwnd, col, fy, 110, 200);
        ComboSet(g.hCatCombo, kExpenseCategories, L"餐饮");
        SendMessageW(g.hCatCombo, WM_SETFONT, (WPARAM)font, TRUE); col += 120;
        CreateLabel(hwnd, L"金额(元)", col, fy, 60, 20); col += 64;
        g.hAmount = CreateEdit(hwnd, col, fy, 100, 24); SendMessageW(g.hAmount, WM_SETFONT, (WPARAM)font, TRUE);
        col += 110;
        CreateLabel(hwnd, L"收款方", col, fy, 50, 20); col += 54;
        g.hPayee = CreateEdit(hwnd, col, fy, 140, 24); SendMessageW(g.hPayee, WM_SETFONT, (WPARAM)font, TRUE);
        col += 150;
        g.hAddBtn = CreateWindowW(L"BUTTON", L"＋ 添加记录", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                  col, fy, 100, 28, hwnd, (HMENU)1001, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(g.hAddBtn, WM_SETFONT, (WPARAM)font, TRUE);
        // 第二行：备注
        fy += 32;
        CreateLabel(hwnd, L"用途/备注", 14, fy, 60, 20);
        g.hNote = CreateEdit(hwnd, 78, fy, 300, 24); SendMessageW(g.hNote, WM_SETFONT, (WPARAM)font, TRUE);
        y += 100;

        // ---- 工具行 1：视图切换 + 搜索筛选 ----
        int ty1 = y;
        CreateLabel(hwnd, L"视图", 14, ty1 + 5, 36, 20);
        g.hViewCombo = CreateCombo(hwnd, 46, ty1, 90, 200);
        SendMessageW(g.hViewCombo, CB_ADDSTRING, 0, (LPARAM)L"全部记录");
        SendMessageW(g.hViewCombo, CB_ADDSTRING, 0, (LPARAM)L"日视图");
        SendMessageW(g.hViewCombo, CB_ADDSTRING, 0, (LPARAM)L"月视图");
        SendMessageW(g.hViewCombo, CB_ADDSTRING, 0, (LPARAM)L"年视图");
        SendMessageW(g.hViewCombo, CB_SETCURSEL, 0, 0);
        SetWindowLongPtrW(g.hViewCombo, GWLP_ID, 1201);  // 供 CBN_SELCHANGE 识别
        SendMessageW(g.hViewCombo, WM_SETFONT, (WPARAM)font, TRUE);
        g.hViewPrev = CreateWindowW(L"BUTTON", L"◀", WS_CHILD | WS_VISIBLE, 144, ty1, 34, 26,
                                    hwnd, (HMENU)1203, GetModuleHandleW(nullptr), nullptr);
        g.hViewDate = CreateEdit(hwnd, 182, ty1, 120, 26);
        SendMessageW(g.hViewDate, WM_SETFONT, (WPARAM)font, TRUE);
        SendMessageW(g.hViewDate, EM_SETREADONLY, TRUE, 0);
        g.hViewNext = CreateWindowW(L"BUTTON", L"▶", WS_CHILD | WS_VISIBLE, 306, ty1, 34, 26,
                                    hwnd, (HMENU)1204, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(g.hViewPrev, WM_SETFONT, (WPARAM)font, TRUE);
        SendMessageW(g.hViewNext, WM_SETFONT, (WPARAM)font, TRUE);
        CreateLabel(hwnd, L"搜索", 352, ty1 + 5, 40, 20);
        g.hSearch = CreateEdit(hwnd, 390, ty1, 180, 26);
        SetWindowLongPtrW(g.hSearch, GWLP_ID, 1200);  // 供 EN_CHANGE 识别
        SendMessageW(g.hSearch, WM_SETFONT, (WPARAM)font, TRUE);
        CreateLabel(hwnd, L"类型", 582, ty1 + 5, 40, 20);
        g.hFilterType = CreateCombo(hwnd, 618, ty1, 100, 200);
        SendMessageW(g.hFilterType, CB_ADDSTRING, 0, (LPARAM)L"全部类型");
        SendMessageW(g.hFilterType, CB_ADDSTRING, 0, (LPARAM)L"支出");
        SendMessageW(g.hFilterType, CB_ADDSTRING, 0, (LPARAM)L"收入");
        SendMessageW(g.hFilterType, CB_SETCURSEL, 0, 0);
        SetWindowLongPtrW(g.hFilterType, GWLP_ID, 1205);  // 供 CBN_SELCHANGE 识别
        SendMessageW(g.hFilterType, WM_SETFONT, (WPARAM)font, TRUE);
        CreateLabel(hwnd, L"分类", 728, ty1 + 5, 40, 20);
        g.hFilterCat = CreateCombo(hwnd, 764, ty1, 180, 200);
        SendMessageW(g.hFilterCat, CB_ADDSTRING, 0, (LPARAM)L"全部分类");
        for (int i = 0; kExpenseCategories[i]; ++i)
            SendMessageW(g.hFilterCat, CB_ADDSTRING, 0, (LPARAM)kExpenseCategories[i]);
        for (int i = 0; kIncomeCategories[i]; ++i)
            SendMessageW(g.hFilterCat, CB_ADDSTRING, 0, (LPARAM)kIncomeCategories[i]);
        SendMessageW(g.hFilterCat, CB_SETCURSEL, 0, 0);
        SetWindowLongPtrW(g.hFilterCat, GWLP_ID, 1206);  // 供 CBN_SELCHANGE 识别
        SendMessageW(g.hFilterCat, WM_SETFONT, (WPARAM)font, TRUE);
        CreateLabel(hwnd, L"提示：点击列表表头可排序；双击行可编辑。", 960, ty1 + 5, 320, 18);
        y += 38;

        // ---- 工具行 2：功能按钮 ----
        int ty2 = y;
        HWND hDel = CreateWindowW(L"BUTTON", L"删除选中", WS_CHILD | WS_VISIBLE,
                                  14, ty2, 88, 26, hwnd, (HMENU)1002, GetModuleHandleW(nullptr), nullptr);
        HWND hEdit = CreateWindowW(L"BUTTON", L"编辑选中", WS_CHILD | WS_VISIBLE,
                                   106, ty2, 88, 26, hwnd, (HMENU)1008, GetModuleHandleW(nullptr), nullptr);
        HWND hCancel = CreateWindowW(L"BUTTON", L"取消编辑", WS_CHILD | WS_VISIBLE,
                                     198, ty2, 88, 26, hwnd, (HMENU)1009, GetModuleHandleW(nullptr), nullptr);
        HWND hDemo = CreateWindowW(L"BUTTON", L"载入示例数据", WS_CHILD | WS_VISIBLE,
                                   290, ty2, 110, 26, hwnd, (HMENU)1003, GetModuleHandleW(nullptr), nullptr);
        HWND hClear = CreateWindowW(L"BUTTON", L"清空数据", WS_CHILD | WS_VISIBLE,
                                    404, ty2, 90, 26, hwnd, (HMENU)1004, GetModuleHandleW(nullptr), nullptr);
        HWND hExport = CreateWindowW(L"BUTTON", L"导出 CSV", WS_CHILD | WS_VISIBLE,
                                     498, ty2, 90, 26, hwnd, (HMENU)1005, GetModuleHandleW(nullptr), nullptr);
        HWND hBudget = CreateWindowW(L"BUTTON", L"🧠 预算规划", WS_CHILD | WS_VISIBLE,
                                     592, ty2, 120, 26, hwnd, (HMENU)1010, GetModuleHandleW(nullptr), nullptr);
        HWND hIfLine = CreateWindowW(L"BUTTON", L"🎭 if 线模拟", WS_CHILD | WS_VISIBLE,
                                     716, ty2, 110, 26, hwnd, (HMENU)1011, GetModuleHandleW(nullptr), nullptr);
        for (HWND b : {hDel, hEdit, hCancel, hDemo, hClear, hExport, hBudget, hIfLine})
            SendMessageW(b, WM_SETFONT, (WPARAM)font, TRUE);
        y += 38;

        // 列表视图
        g.hList = CreateWindowW(WC_LISTVIEWW, L"", WS_CHILD | WS_VISIBLE | WS_BORDER |
                               LVS_REPORT | LVS_SINGLESEL, 14, y, 1150, 260,
                               hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(g.hList, WM_SETFONT, (WPARAM)font, TRUE);
        ListView_SetExtendedListViewStyle(g.hList, LVS_EX_FULLROWSELECT | LVS_EX_GRIDLINES);
        const wchar_t* heads[] = {L"日期", L"类型", L"分类", L"金额(元)", L"收款方", L"用途/备注"};
        int widths[] = {100, 60, 90, 100, 360, 420};
        for (int i = 0; i < 6; ++i) {
            LVCOLUMNW colw{};
            colw.mask = LVCF_TEXT | LVCF_WIDTH | LVCF_SUBITEM;
            colw.pszText = (LPWSTR)heads[i];
            colw.cx = widths[i];
            colw.iSubItem = i;
            ListView_InsertColumn(g.hList, i, &colw);
        }
        y += 266;

        // 收支概览（两行：汇总指标 + 日均支出/统计范围）
        HWND hSum = CreateWindowW(L"BUTTON", L"收支概览（当前视图）", WS_CHILD | WS_VISIBLE | BS_GROUPBOX,
                                  14, y, 1150, 84, hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(hSum, WM_SETFONT, (WPARAM)font, TRUE);
        // 注意：label 以 groupbox 为父窗口，坐标相对 groupbox 而非主窗口
        int sy = 22;
        struct { const wchar_t* label; HWND* h; } sumItems[] = {
            {L"总收入", &g.hInc}, {L"总支出", &g.hExp}, {L"结余", &g.hBal},
            {L"储蓄率", &g.hRate}, {L"记录数", &g.hCnt}, {L"笔均支出", &g.hAvg}, {L"最大单笔", &g.hMax}};
        int sx = 20;
        for (int i = 0; i < 7; ++i) {
            CreateLabel(hSum, sumItems[i].label, sx, sy, 60, 18);
            sx += 64;
            *sumItems[i].h = CreateLabel(hSum, L"0.00", sx, sy, 80, 18);
            SendMessageW(*sumItems[i].h, WM_SETFONT, (WPARAM)font, TRUE);
            sx += 84;
        }
        // 第二行：日均支出 + 统计范围
        int sy2 = sy + 26;
        CreateLabel(hSum, L"日均支出", 20, sy2, 60, 18);
        g.hDaily = CreateLabel(hSum, L"0.00", 84, sy2, 80, 18);
        SendMessageW(g.hDaily, WM_SETFONT, (WPARAM)font, TRUE);
        CreateLabel(hSum, L"统计范围", 200, sy2, 60, 18);
        g.hScope = CreateLabel(hSum, L"", 264, sy2, 880, 18);
        SendMessageW(g.hScope, WM_SETFONT, (WPARAM)font, TRUE);
        y += 92;

        // 预警行（多行，完整显示所有预警，不使用省略号）
        g.hWarn = CreateLabel(hwnd, L"", 14, y, 1150, 110);
        SendMessageW(g.hWarn, WM_SETFONT, (WPARAM)font, TRUE);
        y += 120;

        // 分析按钮
        HWND hChart = CreateWindowW(L"BUTTON", L"📊 查看收支图表", WS_CHILD | WS_VISIBLE,
                                    14, y, 160, 32, hwnd, (HMENU)1006, GetModuleHandleW(nullptr), nullptr);
        HWND hReport = CreateWindowW(L"BUTTON", L"🧬 生成消费人格报告", WS_CHILD | WS_VISIBLE,
                                     180, y, 180, 32, hwnd, (HMENU)1007, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(hChart, WM_SETFONT, (WPARAM)font, TRUE);
        SendMessageW(hReport, WM_SETFONT, (WPARAM)font, TRUE);
        y += 42;

        // 状态栏
        g.hStatus = CreateLabel(hwnd, L"数据自动保存至 expense_data.json", 14, y, 900, 18);

        // 载入数据
        g.records = loadRecords();
        g.Normalize();
        g.nextId = 1;
        for (auto& r : g.records) g.nextId = std::max(g.nextId, r.id + 1);
        loadBudgetPlan();     // 恢复已应用的预算方案（用于超支预警）
        OnViewModeChanged();  // 初始化视图（全部记录）
        RefreshList();
        UpdateSummary();
        RefreshWarn();
        return 0;
    }

    case WM_COMMAND: {
        int id = LOWORD(wParam);
        int code = HIWORD(wParam);
        if (code == BN_CLICKED) {
            if (id == 1001) AddRecord();
            else if (id == 1002) DeleteSelected();
            else if (id == 1003) LoadDemo();
            else if (id == 1004) ClearData();
            else if (id == 1005) ExportCsv();
            else if (id == 1006) ShowCharts();
            else if (id == 1007) ShowReport();
            else if (id == 1008) EditSelected();
            else if (id == 1009) CancelEdit();
            else if (id == 1010) ShowBudgetPlannerUI();
            else if (id == 1011) ShowIfLine();
            else if (id == 1203) ShiftView(-1);
            else if (id == 1204) ShiftView(1);
            return 0;
        }
        if (code == EN_CHANGE && id == 1200) ReadSearch();
        if (code == CBN_SELCHANGE) {
            if (id == 1201) OnViewModeChanged();
            else if (id == 1205) ReadFilterType();
            else if (id == 1206) ReadFilterCat();
            else if (id == 1100) OnFormTypeChanged();
        }
        return 0;
    }

    case WM_NOTIFY: {
        NMHDR* nm = (NMHDR*)lParam;
        if (nm->hwndFrom == g.hList) {
            if (nm->code == NM_DBLCLK) EditSelected();
            else if (nm->code == LVN_COLUMNCLICK) {
                // 点击表头排序
                NMLISTVIEW* plv = (NMLISTVIEW*)lParam;
                int col = plv->iSubItem;
                if (col < 0 || col > 5) return 0;
                if (g.sortCol == col) g.sortAsc = !g.sortAsc;
                else { g.sortCol = col; g.sortAsc = true; }
                RefreshList();
                static const wchar_t* colNames[] = {L"日期", L"类型", L"分类", L"金额", L"收款方", L"备注"};
                StatusMsg(L"已按「" + std::wstring(colNames[col]) + L"」" +
                          (g.sortAsc ? L"升序" : L"降序") + L"排序。");
            }
        }
        return 0;
    }

    case WM_SIZE: {
        // 简单自适应：拉伸列表
        RECT rc; GetClientRect(hwnd, &rc);
        return 0;
    }

    case WM_CLOSE:
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

// ===================== 入口 =====================

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE, PWSTR, int nCmdShow) {
    // 初始化 GDI+
    GdiplusStartupInput gsi;
    ULONG_PTR token;
    GdiplusStartup(&token, &gsi, nullptr);

    INITCOMMONCONTROLSEX icc{};
    icc.dwSize = sizeof(icc);
    icc.dwICC = ICC_LISTVIEW_CLASSES | ICC_STANDARD_CLASSES;
    InitCommonControlsEx(&icc);

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = MainProc;
    wc.hInstance = hInstance;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    wc.lpszClassName = L"ExpenseAnalyzerMain";
    wc.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    wc.hIconSm = LoadIconW(nullptr, IDI_APPLICATION);
    RegisterClassExW(&wc);

    g.hwnd = CreateWindowExW(0, L"ExpenseAnalyzerMain", L"个人消费分析助手",
                             WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT,
                             1210, 860, nullptr, nullptr, hInstance, nullptr);
    if (!g.hwnd) return 1;
    ShowWindow(g.hwnd, nCmdShow);
    UpdateWindow(g.hwnd);

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0)) {
        if (!IsDialogMessageW(g.hwnd, &msg)) {
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    }
    GdiplusShutdown(token);
    return 0;
}

// ===================== 预算方案应用回调（主程序实现） =====================
// 预算规划窗口点击"应用此方案到主程序"时回调，
// 将各分类月预算写入 g.hasBudget / g.budgetTotal / g.budgetByCat
// （用于超支预警），并持久化到 budget_plan.json。
namespace budgetui {
void OnApplyPlan(const BudgetPlan& plan) {
    g.hasBudget = true;
    g.budgetTotal = plan.budgetPerMonth;
    double scale = (plan.period == L"year") ? 12.0 : 1.0;
    for (int i = 0; i < kCategoryCount; ++i)
        g.budgetByCat[i] = plan.categories[i].amount / scale;
    saveBudgetPlanFile();
    RefreshWarn();
    MessageBoxW(g.hwnd, L"预算方案已应用：将用于「消费超预期预警」。\n可在「预算规划」中随时重新规划。",
                L"预算已应用", MB_OK | MB_ICONINFORMATION);
}
}
