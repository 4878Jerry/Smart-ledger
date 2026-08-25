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
    std::wstring t = todayStr();
    std::vector<Record> v;
    auto add = [&](int offset, const wchar_t* type, const wchar_t* cat, double amt, const wchar_t* payee, const wchar_t* note) {
        Record r;
        r.date = dateShift(t, offset);
        r.type = type; r.category = cat; r.amount = amt; r.payee = payee; r.note = note;
        v.push_back(r);
    };
    add(1, L"收入", L"工资", 12000.00, L"某某科技有限公司", L"8月工资");
    add(1, L"支出", L"居住", 2800.00, L"安居公寓", L"房租");
    add(1, L"支出", L"餐饮", 35.50, L"老乡鸡", L"午餐");
    add(2, L"支出", L"交通", 12.00, L"地铁", L"通勤");
    add(2, L"支出", L"餐饮", 128.00, L"海底捞", L"朋友聚餐");
    add(3, L"支出", L"购物", 499.00, L"京东自营", L"蓝牙耳机");
    add(3, L"支出", L"娱乐", 88.00, L"万达影城", L"电影");
    add(4, L"支出", L"餐饮", 22.00, L"瑞幸咖啡", L"拿铁");
    add(5, L"支出", L"社交人情", 300.00, L"微信红包", L"朋友生日礼金");
    add(6, L"支出", L"通讯", 58.00, L"中国移动", L"话费");
    add(7, L"收入", L"理财", 120.50, L"余额宝", L"理财收益");
    add(7, L"支出", L"医疗", 45.00, L"康民大药房", L"感冒药");
    // 分配 id
    for (int i = 0; i < (int)v.size(); ++i) v[i].id = i + 1;
    return v;
}

// ===================== 应用状态 =====================

struct App {
    HWND hwnd = nullptr;
    std::vector<Record> records;
    int nextId = 1;
    int editingId = -1;

    // 控件句柄
    HWND hDate, hTypeCombo, hCatCombo, hAmount, hPayee, hNote, hAddBtn;
    HWND hList, hStatus;
    HWND hInc, hExp, hBal, hRate, hCnt, hAvg, hMax;

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

    // 标题
    Font titleFont(L"Microsoft YaHei UI", 18, FontStyleBold);
    SolidBrush titleBrush(Color(30, 33, 50));
    graphics.DrawString(L"个人收支分析报告", -1, &titleFont, PointF(20, 12), &titleBrush);

    // 收集数据
    std::map<std::wstring, double> catAmt;
    for (auto& r : g.records) if (r.type != L"收入") catAmt[r.category] += r.amount;
    std::vector<std::pair<std::wstring, double>> cats(catAmt.begin(), catAmt.end());
    std::sort(cats.begin(), cats.end(), [](auto& a, auto& b) { return a.second > b.second; });

    // 每日收支
    std::map<std::wstring, double> incByDate, expByDate;
    for (auto& r : g.records) {
        if (r.type == L"收入") incByDate[r.date] += r.amount;
        else expByDate[r.date] += r.amount;
    }
    std::vector<std::wstring> dates;
    for (auto& kv : incByDate) dates.push_back(kv.first);
    for (auto& kv : expByDate) if (std::find(dates.begin(), dates.end(), kv.first) == dates.end()) dates.push_back(kv.first);
    std::sort(dates.begin(), dates.end());
    if (dates.size() > 14) dates.erase(dates.begin(), dates.end() - 14);

    // 雷达图
    Scores sc = computeScores(g.records);
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
            graphics.DrawLine(&linePen, r1.left + padL, r1.bottom - padB, r1.left + padL + plotW, r1.bottom - padB);
            graphics.DrawLine(&linePen, r1.left + padL, r1.bottom - padB, r1.left + padL, r1.bottom - padB - plotH);
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
            graphics.DrawLine(&linePen, r3.left + padL, r3.bottom - padB, r3.left + padL + plotW, r3.bottom - padB);
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

// ===================== 主窗口逻辑 =====================

static void RefreshList() {
    if (!g.hList) return;
    SendMessageW(g.hList, LVM_DELETEALLITEMS, 0, 0);
    for (auto& r : g.records) {
        LVITEMW it{};
        it.mask = LVIF_TEXT;
        std::vector<std::wstring> cols = {
            r.date, r.type, r.category, fmtMoney(r.amount), r.payee, r.note};
        for (int c = 0; c < 6; ++c) {
            it.iItem = (int)SendMessageW(g.hList, LVM_GETITEMCOUNT, 0, 0);
            it.iSubItem = c;
            it.pszText = (LPWSTR)cols[c].c_str();
            if (c == 0) SendMessageW(g.hList, LVM_INSERTITEMW, 0, (LPARAM)&it);
            else SendMessageW(g.hList, LVM_SETITEMW, 0, (LPARAM)&it);
        }
    }
}

static void UpdateSummary() {
    Summary s = analyzeSummary(g.records);
    SetWindowTextW(g.hInc, fmtMoney(s.totalIncome).c_str());
    SetWindowTextW(g.hExp, fmtMoney(s.totalExpense).c_str());
    SetWindowTextW(g.hBal, fmtMoney(s.balance).c_str());
    SetWindowTextW(g.hRate, (s.savingsRate >= 0 ? fmtMoney(s.savingsRate * 100.0) + L" %" : L"—").c_str());
    SetWindowTextW(g.hCnt, (std::to_wstring(s.count) + L" 笔").c_str());
    SetWindowTextW(g.hAvg, fmtMoney(s.avg).c_str());
    SetWindowTextW(g.hMax, fmtMoney(s.max).c_str());
}

static void StatusMsg(const std::wstring& s) {
    SetWindowTextW(g.hStatus, s.c_str());
}

static void ClearForm() {
    SetWindowTextW(g.hDate, todayStr().c_str());
    SetWindowTextW(g.hAmount, L"");
    SetWindowTextW(g.hPayee, L"");
    SetWindowTextW(g.hNote, L"");
}

static void RefreshAddBtn() {
    SetWindowTextW(g.hAddBtn, g.editingId > 0 ? L"✓ 保存修改" : L"＋ 添加记录");
}

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
    }
    saveRecords(g.records);
    RefreshList();
    UpdateSummary();
    ClearForm();
    RefreshAddBtn();
}

static void DeleteSelected() {
    int sel = ListView_GetNextItem(g.hList, -1, LVNI_SELECTED);
    if (sel < 0) { MessageBoxW(g.hwnd, L"请先在表格中选中要删除的记录。", L"未选择", MB_OK | MB_ICONINFORMATION); return; }
    if (MessageBoxW(g.hwnd, L"确定删除选中的记录吗？", L"确认删除", MB_YESNO | MB_ICONQUESTION) != IDYES) return;
    std::vector<int> ids;
    for (int i = 0; i < ListView_GetItemCount(g.hList); ++i) {
        if (ListView_GetItemState(g.hList, i, LVIS_SELECTED)) ids.push_back(i);
    }
    // 按行号记录 id 需要从列表中取；这里简化：按当前显示顺序删除
    // 收集要删除的 id
    std::vector<int> delIds;
    LVITEMW it{}; it.mask = LVIF_PARAM;
    for (int i = 0; i < ListView_GetItemCount(g.hList); ++i) {
        if (ListView_GetItemState(g.hList, i, LVIS_SELECTED)) {
            // 记录在 vector 中与列表顺序一致，但删除需按值
        }
    }
    // 简化实现：把选中行号对应的记录删除
    std::vector<Record> kept;
    int idx = 0;
    for (auto& r : g.records) {
        if (ListView_GetItemState(g.hList, idx, LVIS_SELECTED)) { idx++; continue; }
        kept.push_back(r); idx++;
    }
    g.records = kept;
    if (g.editingId > 0) { g.editingId = -1; RefreshAddBtn(); }
    saveRecords(g.records);
    RefreshList();
    UpdateSummary();
}

static void EditSelected() {
    int sel = ListView_GetNextItem(g.hList, -1, LVNI_SELECTED);
    if (sel < 0) return;
    // 找到对应记录
    int idx = 0;
    for (auto& r : g.records) {
        if (idx == sel) {
            g.editingId = r.id;
            SetWindowTextW(g.hDate, r.date.c_str());
            // 设置类型
            ComboSet(g.hTypeCombo, r.type == L"收入" ? kIncomeCategories : kExpenseCategories, r.category);
            // 设置类型选择
            {
                HWND cbo = g.hTypeCombo;
                SendMessageW(cbo, CB_RESETCONTENT, 0, 0);
                SendMessageW(cbo, CB_ADDSTRING, 0, (LPARAM)L"支出");
                SendMessageW(cbo, CB_ADDSTRING, 0, (LPARAM)L"收入");
                SendMessageW(cbo, CB_SETCURSEL, r.type == L"收入" ? 1 : 0, 0);
            }
            ComboSet(g.hCatCombo, r.type == L"收入" ? kIncomeCategories : kExpenseCategories, r.category);
            SetWindowTextW(g.hAmount, (fmtMoney(r.amount)).c_str());
            SetWindowTextW(g.hPayee, r.payee.c_str());
            SetWindowTextW(g.hNote, r.note.c_str());
            RefreshAddBtn();
            StatusMsg(L"正在编辑记录 #" + std::to_wstring(r.id) + L"，修改后点击「保存修改」。");
            break;
        }
        idx++;
    }
}

static void LoadDemo() {
    if (!g.records.empty() &&
        MessageBoxW(g.hwnd, L"当前已有数据，载入示例将替换现有记录，是否继续？", L"载入示例",
                    MB_YESNO | MB_ICONQUESTION) != IDYES) return;
    g.records = loadDemoData();
    g.nextId = (int)g.records.size() + 1;
    g.editingId = -1;
    saveRecords(g.records);
    RefreshList();
    UpdateSummary();
    RefreshAddBtn();
    MessageBoxW(g.hwnd, L"已载入 12 条示例记录，可直接生成图表与人格报告。", L"载入完成", MB_OK | MB_ICONINFORMATION);
}

static void ClearData() {
    if (g.records.empty()) return;
    if (MessageBoxW(g.hwnd, L"确定清空全部记录吗？此操作不可撤销。", L"确认清空", MB_YESNO | MB_ICONQUESTION) != IDYES) return;
    g.records.clear();
    g.editingId = -1;
    g.nextId = 1;
    saveRecords(g.records);
    RefreshList();
    UpdateSummary();
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
    if (g.records.empty()) { MessageBoxW(g.hwnd, L"请先添加消费记录，或点击「载入示例数据」。", L"暂无数据", MB_OK | MB_ICONINFORMATION); return; }
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
    HWND w = CreateWindowExW(0, L"ExpenseChartWindow", L"收支分析图表",
                             WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT, 1180, 880,
                             g.hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
    ShowWindow(w, SW_SHOW);
    UpdateWindow(w);
}

static void ShowReport() {
    if (g.records.empty()) { MessageBoxW(g.hwnd, L"请先添加消费记录，或点击「载入示例数据」。", L"暂无数据", MB_OK | MB_ICONINFORMATION); return; }
    g_reportText = makeReport(g.records);
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
                                   14, y, 960, 88, hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(hForm, WM_SETFONT, (WPARAM)font, TRUE);
        int fy = y + 24;
        int col = 14;
        CreateLabel(hForm, L"日期", col, fy, 40, 20); col += 44;
        g.hDate = CreateEdit(hForm, col, fy, 100, 24); SendMessageW(g.hDate, WM_SETFONT, (WPARAM)font, TRUE);
        SetWindowTextW(g.hDate, todayStr().c_str()); col += 110;
        CreateLabel(hForm, L"类型", col, fy, 40, 20); col += 44;
        g.hTypeCombo = CreateCombo(hForm, col, fy, 70, 200);
        SendMessageW(g.hTypeCombo, CB_ADDSTRING, 0, (LPARAM)L"支出");
        SendMessageW(g.hTypeCombo, CB_ADDSTRING, 0, (LPARAM)L"收入");
        SendMessageW(g.hTypeCombo, CB_SETCURSEL, 0, 0);
        SendMessageW(g.hTypeCombo, WM_SETFONT, (WPARAM)font, TRUE); col += 80;
        CreateLabel(hForm, L"分类", col, fy, 40, 20); col += 44;
        g.hCatCombo = CreateCombo(hForm, col, fy, 110, 200);
        ComboSet(g.hCatCombo, kExpenseCategories, L"餐饮");
        SendMessageW(g.hCatCombo, WM_SETFONT, (WPARAM)font, TRUE); col += 120;
        CreateLabel(hForm, L"金额(元)", col, fy, 60, 20); col += 64;
        g.hAmount = CreateEdit(hForm, col, fy, 100, 24); SendMessageW(g.hAmount, WM_SETFONT, (WPARAM)font, TRUE);
        col += 110;
        CreateLabel(hForm, L"收款方", col, fy, 50, 20); col += 54;
        g.hPayee = CreateEdit(hForm, col, fy, 140, 24); SendMessageW(g.hPayee, WM_SETFONT, (WPARAM)font, TRUE);
        col += 150;
        g.hAddBtn = CreateWindowW(L"BUTTON", L"＋ 添加记录", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                  col, fy, 100, 28, hForm, (HMENU)1001, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(g.hAddBtn, WM_SETFONT, (WPARAM)font, TRUE);
        // 第二行：备注
        fy += 32;
        CreateLabel(hForm, L"用途/备注", 14, fy, 60, 20);
        g.hNote = CreateEdit(hForm, 78, fy, 300, 24); SendMessageW(g.hNote, WM_SETFONT, (WPARAM)font, TRUE);
        y += 100;

        // 工具栏
        int ty = y;
        HWND hDel = CreateWindowW(L"BUTTON", L"删除选中", WS_CHILD | WS_VISIBLE,
                                  14, ty, 80, 26, hwnd, (HMENU)1002, GetModuleHandleW(nullptr), nullptr);
        HWND hDemo = CreateWindowW(L"BUTTON", L"载入示例数据", WS_CHILD | WS_VISIBLE,
                                   100, ty, 100, 26, hwnd, (HMENU)1003, GetModuleHandleW(nullptr), nullptr);
        HWND hClear = CreateWindowW(L"BUTTON", L"清空数据", WS_CHILD | WS_VISIBLE,
                                    206, ty, 80, 26, hwnd, (HMENU)1004, GetModuleHandleW(nullptr), nullptr);
        HWND hExport = CreateWindowW(L"BUTTON", L"导出 CSV", WS_CHILD | WS_VISIBLE,
                                     292, ty, 80, 26, hwnd, (HMENU)1005, GetModuleHandleW(nullptr), nullptr);
        CreateLabel(hwnd, L"提示：单击行后点「删除选中」删除；点工具栏按钮查看图表/报告。", 400, ty + 5, 500, 18);
        for (HWND b : {hDel, hDemo, hClear, hExport}) SendMessageW(b, WM_SETFONT, (WPARAM)font, TRUE);
        y += 36;

        // 列表视图
        g.hList = CreateWindowW(WC_LISTVIEWW, L"", WS_CHILD | WS_VISIBLE | WS_BORDER |
                               LVS_REPORT | LVS_SINGLESEL, 14, y, 960, 300,
                               hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(g.hList, WM_SETFONT, (WPARAM)font, TRUE);
        ListView_SetExtendedListViewStyle(g.hList, LVS_EX_FULLROWSELECT | LVS_EX_GRIDLINES);
        const wchar_t* heads[] = {L"日期", L"类型", L"分类", L"金额(元)", L"收款方", L"用途/备注"};
        int widths[] = {100, 60, 90, 100, 190, 220};
        for (int i = 0; i < 6; ++i) {
            LVCOLUMNW colw{};
            colw.mask = LVCF_TEXT | LVCF_WIDTH | LVCF_SUBITEM;
            colw.pszText = (LPWSTR)heads[i];
            colw.cx = widths[i];
            colw.iSubItem = i;
            ListView_InsertColumn(g.hList, i, &colw);
        }
        y += 306;

        // 收支概览
        HWND hSum = CreateWindowW(L"BUTTON", L"收支概览", WS_CHILD | WS_VISIBLE | BS_GROUPBOX,
                                  14, y, 960, 60, hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(hSum, WM_SETFONT, (WPARAM)font, TRUE);
        int sy = y + 22;
        struct { const wchar_t* label; HWND* h; } sumItems[] = {
            {L"总收入", &g.hInc}, {L"总支出", &g.hExp}, {L"结余", &g.hBal},
            {L"储蓄率", &g.hRate}, {L"记录数", &g.hCnt}, {L"笔均支出", &g.hAvg}, {L"最大单笔", &g.hMax}};
        int sx = 20;
        for (int i = 0; i < 7; ++i) {
            CreateLabel(hSum, sumItems[i].label, sx, sy, 60, 18);
            sx += 64;
            *sumItems[i].h = CreateLabel(hSum, L"0.00", sx, sy, 70, 18);
            SendMessageW(*sumItems[i].h, WM_SETFONT, (WPARAM)font, TRUE);
            sx += 74;
        }
        y += 68;

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
        RefreshList();
        UpdateSummary();
        return 0;
    }

    case WM_COMMAND: {
        int id = LOWORD(wParam);
        if (id == 1001) AddRecord();
        else if (id == 1002) DeleteSelected();
        else if (id == 1003) LoadDemo();
        else if (id == 1004) ClearData();
        else if (id == 1005) ExportCsv();
        else if (id == 1006) ShowCharts();
        else if (id == 1007) ShowReport();
        return 0;
    }

    case WM_NOTIFY: {
        // 双击列表行回填修改
        NMHDR* nm = (NMHDR*)lParam;
        if (nm->hwndFrom == g.hList && nm->code == NM_DBLCLK) EditSelected();
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
                             1010, 660, nullptr, nullptr, hInstance, nullptr);
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
