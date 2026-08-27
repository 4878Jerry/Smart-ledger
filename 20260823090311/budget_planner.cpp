// =====================================================================
// 生活预支出规划模块 (Budget Planner) - C++ / Win32 版本
// =====================================================================
// 功能：
//   1. 选择省份（31 省/自治区/直辖市）与城市等级（一线/新一线/二线/
//      三线/四线及以下），按"综合地域系数 = 城市等级系数 × 省份消费
//      水平系数"估算未来一个月 / 一年的生活费预算
//   2. 11 个支出分类滑块直接分配占比（0-100%）：拖到 0 即该分类不安排
//      支出（如本月不打算看医疗），其余分类自动归一化，总和恒为 100%；
//      每行滑块旁的 "-"/"+" 按钮支持以 1% 步进精调占比
//   3. 一键配置：不知道如何分配时点击，按所选省份 + 城市等级的普通人
//      生活水平自动生成占比模板（居住占比随地区生活成本浮动），
//      滑块打开时也预填该模板
//   4. 预算模式切换（右上角）：推荐预算沿用算法（收入 × 基准比例 ×
//      综合地域系数）；自定义预算可直接输入本月总预算（不超过收入），
//      两种模式下均可拖动滑块 / 微调按钮 / 一键配置调整各分类占比
//   5. 独立 Win32 演示窗口：切换省份 / 城市等级 / 拖动滑块实时刷新预算
//
// 【与主程序（expense_analyzer.cpp）的合并方式】
//   本文件可独立编译为演示 exe，也可被主程序直接 #include 合并：
//     - 独立编译（演示）：
//         cl /DBUDGET_PLANNER_MAIN /EHsc /std:c++17 /O2 budget_planner.cpp ^
//             /Fe:budget_planner.exe user32.lib gdi32.lib comctl32.lib
//     - 并入主程序：在 expense_analyzer.cpp 中 #include "budget_planner.cpp"
//       （此时不定义 BUDGET_PLANNER_MAIN，本文件的 wWinMain 与演示版
//       OnApplyPlan 自动失效，不产生重复入口）
//   - 算法核心（computeBudget / defaultRatiosForRegion / 系数表）为纯函数，
//     放在全局，主程序可直接调用（用于 if 线生成现实规划等）。
//   - UI 全部位于 namespace budgetui 内，避免与主程序符号冲突。
//   - 主程序通过 budgetui::ShowBudgetPlanner(owner, initWeights) 打开规划
//     窗口；点击"应用此方案到主程序"时回调 budgetui::OnApplyPlan()，
//     该回调由主程序实现（用于把分类月预算写入主程序并做超支预警）。
//
// 编译方式（MSVC，x64）：
//   vcvarsall.bat x64 之后：
//   cl /EHsc /std:c++17 /O2 /DBUDGET_PLANNER_MAIN budget_planner.cpp /Fe:budget_planner.exe ^
//      user32.lib gdi32.lib comctl32.lib
//
// 地域购买力系数说明：
//   - 城市等级系数：基于公开生活成本常识数据综合整理（Numbeo 生活成本指数、
//     《中国城市生活成本报告》等公开资料），仅用于课堂演示与概念验证，
//     如需真实数据可替换下方 kCityTable 常量表（或改为从配置文件读取）。
//   - 省份消费水平系数：参考国家统计局公布的各省居民人均消费支出水平
//     （2023 年前后公开数据常识）综合整理，全国平均 = 1.00，范围约 0.85-1.12，
//     如需真实数据可替换下方 kProvinceTable 常量表（或改为从配置文件读取）。
// =====================================================================

#pragma once
#ifndef SMART_LEDGER_BUDGET_PLANNER_CPP_
#define SMART_LEDGER_BUDGET_PLANNER_CPP_

#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define NOMINMAX
#include <windows.h>
#include <windowsx.h>
#include <commctrl.h>

#include <cstdio>
#include <cstdlib>
#include <cwchar>
#include <string>
#include <vector>
#include <algorithm>

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "gdi32.lib")
#pragma comment(lib, "comctl32.lib")

// ===================== 模块内工具函数（UI 用） =====================
// 全部放入 namespace budgetui，避免与主程序同名 static 函数冲突
namespace budgetui {

static std::wstring trim(const std::wstring& s) {
    size_t a = s.find_first_not_of(L" \t\r\n");
    if (a == std::wstring::npos) return L"";
    size_t b = s.find_last_not_of(L" \t\r\n");
    return s.substr(a, b - a + 1);
}

static std::wstring fmtMoney(double v) {
    wchar_t buf[64];
    swprintf(buf, 64, L"%.2f", v);
    return std::wstring(buf);
}

// 在 r 区域内左对齐绘制文字；若超宽则逐级缩小字号（21→14）直至适配，
// 保证金额与单位完整显示。返回文字实际占用的右边界。
static int DrawAutoFitText(HDC hdc, const std::wstring& text, HFONT baseFont,
                           COLORREF color, const RECT* r) {
    int avail = r->right - r->left;
    if (avail <= 0) return r->left;
    HFONT curFont = baseFont;
    HFONT tmpFont = nullptr;
    SIZE sz = {0, 0};
    for (int size = 20; size >= 14; --size) {
        if (tmpFont) { DeleteObject(tmpFont); tmpFont = nullptr; }
        tmpFont = CreateFontW(size, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET,
                              0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
        curFont = tmpFont;
        HGDIOBJ old = SelectObject(hdc, curFont);
        GetTextExtentPoint32W(hdc, text.c_str(), (int)text.size(), &sz);
        SelectObject(hdc, old);
        if (sz.cx <= avail) break;
    }
    HGDIOBJ old = SelectObject(hdc, curFont);
    SetTextColor(hdc, color);
    RECT rc = *r;
    DrawTextW(hdc, text.c_str(), -1, &rc, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    SelectObject(hdc, old);
    if (tmpFont) DeleteObject(tmpFont);
    return r->left + sz.cx;
}

static std::wstring EditText(HWND h) {
    wchar_t buf[128];
    GetWindowTextW(h, buf, 128);
    return std::wstring(buf);
}

static HWND CreateLabel(HWND parent, const wchar_t* text, int x, int y, int w, int h) {
    return CreateWindowW(L"STATIC", text, WS_CHILD | WS_VISIBLE | SS_LEFT,
                         x, y, w, h, parent, nullptr, GetModuleHandleW(nullptr), nullptr);
}

static HWND CreateGroup(HWND parent, const wchar_t* text, int x, int y, int w, int h) {
    return CreateWindowW(L"BUTTON", text, WS_CHILD | WS_VISIBLE | BS_GROUPBOX,
                         x, y, w, h, parent, nullptr, GetModuleHandleW(nullptr), nullptr);
}

// 分类配色（与主程序 kExpenseColors 同款，0xRRGGBB）
static DWORD colorForCategory(const std::wstring& cat) {
    static const struct { const wchar_t* name; DWORD color; } kColors[] = {
        {L"餐饮", 0xFF7043}, {L"交通", 0x4FC3F7}, {L"购物", 0xEC407A}, {L"娱乐", 0xAB47BC},
        {L"居住", 0x8D6E63}, {L"医疗", 0xEF5350}, {L"教育", 0x5C6BC0}, {L"通讯", 0x26A69A},
        {L"社交人情", 0xFFA726}, {L"旅行", 0x42A5F5}, {L"其他", 0x90A4AE},
    };
    for (auto& c : kColors)
        if (cat == c.name) return c.color;
    return 0x90A4AE;
}

}  // namespace budgetui

// ===================== 城市等级购买力系数表 =====================
// 基准：一线城市生活成本 = 1.00；系数越小代表生活成本越低、
// 同样的钱购买力越强。数据为公开资料综合整理的默认参考值。

struct CityFactor {
    const wchar_t* level;
    double factor;
};

static const CityFactor kCityTable[] = {
    {L"一线城市",   1.00},
    {L"新一线城市", 0.90},
    {L"二线城市",   0.82},
    {L"三线城市",   0.72},
    {L"四线及以下", 0.62},
};

static const int kCityCount = (int)(sizeof(kCityTable) / sizeof(kCityTable[0]));
static const double kDefaultFactor = 0.82;  // 未收录城市等级的默认回退值（二线均值）

static const wchar_t* kCityLevels[] = {
    L"一线城市", L"新一线城市", L"二线城市", L"三线城市", L"四线及以下", nullptr};

// 查找城市等级系数；未命中回退默认值并通过 outFound 告知
static double cityFactor(const std::wstring& level, bool* outFound) {
    for (int i = 0; i < kCityCount; ++i) {
        if (level == kCityTable[i].level) {
            if (outFound) *outFound = true;
            return kCityTable[i].factor;
        }
    }
    if (outFound) *outFound = false;
    return kDefaultFactor;
}

// ===================== 省份消费水平系数表 =====================
// 基准：全国平均消费水平 = 1.00；系数越大代表该省居民人均消费支出越高、
// 生活成本越高。数据参考国家统计局公布的各省居民人均消费支出水平
// （2023 年前后公开数据常识）综合整理，仅用于课堂演示与概念验证；
// 如需真实数据可替换下方 kProvinceTable 常量表（或改为从配置文件读取）。
// 综合地域系数 = 城市等级系数 × 省份消费水平系数。

struct ProvinceFactor {
    const wchar_t* name;
    double factor;
};

// 按地理分区排列（华北→东北→华东→中南→西南→西北）
static const ProvinceFactor kProvinceTable[] = {
    // 华北
    {L"北京", 1.12}, {L"天津", 1.06}, {L"河北", 0.94}, {L"山西", 0.92}, {L"内蒙古", 0.96},
    // 东北
    {L"辽宁", 0.95}, {L"吉林", 0.92}, {L"黑龙江", 0.90},
    // 华东
    {L"上海", 1.12}, {L"江苏", 1.02}, {L"浙江", 1.05}, {L"安徽", 0.94}, {L"福建", 1.01},
    {L"江西", 0.93}, {L"山东", 0.99},
    // 中南
    {L"河南", 0.93}, {L"湖北", 0.99}, {L"湖南", 0.97}, {L"广东", 1.04}, {L"广西", 0.89},
    {L"海南", 0.97},
    // 西南
    {L"重庆", 0.98}, {L"四川", 0.97}, {L"贵州", 0.86}, {L"云南", 0.88}, {L"西藏", 0.85},
    // 西北
    {L"陕西", 0.93}, {L"甘肃", 0.85}, {L"青海", 0.87}, {L"宁夏", 0.88}, {L"新疆", 0.89},
};

static const int kProvinceCount = (int)(sizeof(kProvinceTable) / sizeof(kProvinceTable[0]));
static const double kDefaultProvinceFactor = 1.00;  // 未收录省份的默认回退值（全国平均）

// 查找省份消费系数；未命中回退默认值 1.00 并通过 outFound 告知
static double provinceFactor(const std::wstring& name, bool* outFound) {
    for (int i = 0; i < kProvinceCount; ++i) {
        if (name == kProvinceTable[i].name) {
            if (outFound) *outFound = true;
            return kProvinceTable[i].factor;
        }
    }
    if (outFound) *outFound = false;
    return kDefaultProvinceFactor;
}

// ===================== 分类默认占比表 =====================
// 沿用主程序（expense_analyzer.cpp）的 11 个支出分类，总和为 1.00

struct DefaultRatio {
    const wchar_t* name;
    double ratio;
};

static const DefaultRatio kDefaultRatios[] = {
    {L"餐饮",     0.35},
    {L"居住",     0.25},
    {L"交通",     0.08},
    {L"购物",     0.08},
    {L"娱乐",     0.06},
    {L"医疗",     0.04},
    {L"教育",     0.05},
    {L"通讯",     0.03},
    {L"社交人情", 0.04},
    {L"旅行",     0.01},
    {L"其他",     0.01},
};

static const int kCategoryCount = (int)(sizeof(kDefaultRatios) / sizeof(kDefaultRatios[0]));

// ===================== 按地域生成"普通人"占比模板 =====================
// 当用户不知道如何分配比例时，可按所选省份 + 城市等级的生活水平
// 自动生成默认占比模板（纯函数，不依赖 UI，可并入主程序）：
//   - 居住占比随综合地域系数上浮：生活成本越高，房租/房贷占收入比越高
//     housing = clamp(25% + (综合系数-1.0)×15%, 15%, 40%)
//     例：一线×广东≈31%，二线×广东≈23%，四线×甘肃≈18%
//   - 居住占比的增减由其余 10 个分类等比承担，保证总和恒为 1.00
static void defaultRatiosForRegion(double cityFactor, double provinceFactor,
                                   double out[kCategoryCount]) {
    for (int i = 0; i < kCategoryCount; ++i)
        out[i] = kDefaultRatios[i].ratio;
    double housing = 0.25 + (cityFactor * provinceFactor - 1.0) * 0.15;
    if (housing < 0.15) housing = 0.15;
    if (housing > 0.40) housing = 0.40;
    out[1] = housing;  // 居住（kDefaultRatios 第 2 项）
    double rest = 0;
    for (int i = 0; i < kCategoryCount; ++i)
        if (i != 1) rest += out[i];
    if (rest > 0)
        for (int i = 0; i < kCategoryCount; ++i)
            if (i != 1) out[i] *= (1.0 - housing) / rest;
}

// ===================== 数据模型 =====================

struct BudgetCategory {
    std::wstring name;   // 分类名
    double ratio = 0.0;  // 占比（0-1，各分类之和恒为 1）
    double amount = 0.0; // 预算金额
    std::wstring note;   // 说明（占比为 0 时标注"该分类未安排支出"）
};

struct BudgetPlan {
    std::wstring period;        // L"month" / L"year"
    std::wstring province;      // 省份名
    std::wstring cityLevel;     // 城市等级名
    bool provinceFound = true;  // 省份是否命中系数表
    bool cityFound = true;      // 城市等级是否命中系数表
    double provinceFactor = 1.0;// 省份消费水平系数分量
    double cityFactor = 1.0;    // 城市等级购买力系数分量
    double regionFactor = 1.0;  // 综合地域系数 = cityFactor × provinceFactor
    double income = 0.0;        // 收入（月周期为月收入，年周期为年收入）
    bool customMode = false;    // true = 自定义预算（总预算按用户输入，非推荐算法）
    double totalBudget = 0.0;   // 总预算（与周期一致）
    double budgetPerMonth = 0.0;// 折算的月均预算（年周期时为 totalBudget/12）
    std::vector<BudgetCategory> categories;
    std::vector<std::wstring> warnings;
};

// ===================== 核心算法（纯函数） =====================
// 输入：
//   period       : L"month"（一个月）或 L"year"（一年）
//   province     : 省份名，见 kProvinceTable
//   cityLevel    : 城市等级名，见 kCityLevels
//   income       : 收入金额（月周期填月收入；年周期填年收入）
//   prefWeights  : 长度 11 的占比数组，0-100（0 = 该分类不安排支出），
//                  与 kDefaultRatios 顺序一一对应；全 0 时回退默认占比
//   customBudget : 自定义总预算（可选，默认 -1）：>= 0 时进入自定义预算模式，
//                  总预算 = min(输入值, 收入)，超收入/空值给出警告兜底；
//                  < 0 时走推荐模式（见算法步骤 2）
// 输出：
//   完整的 BudgetPlan，包含各分类预算分配与提醒列表
// 算法步骤：
//   1. 收入档位 -> 基准消费比例（仅推荐模式）
//      （档位按"月收入"语义设计；年周期先折算为月均收入再查档，
//        保证月/年周期下同一收入水平的月均预算一致）
//   2. 总预算：
//      推荐模式   = 收入 × 基准比例 × 综合地域系数
//                  （综合地域系数 = 城市等级系数 × 省份消费水平系数）
//      自定义模式 = 用户输入值（clamp 到收入上限）
//   3. 滑块值即占比 -> 归一化（总和 = 100%）；全 0 时回退默认占比
//   4. 软提醒：餐饮/居住占比过低、购物/娱乐占比过高（不强制干预）
//   5. 金额 = 总预算 × 占比，并生成提醒

static double incomeBaseRatio(double income) {
    if (income <= 3000)  return 0.70;  // 低收入：消费占大头
    if (income <= 8000)  return 0.60;  // 中低收入
    if (income <= 20000) return 0.50;  // 中等收入
    return 0.40;                       // 高收入：可储蓄比例更高
}

BudgetPlan computeBudget(const std::wstring& period, const std::wstring& province,
                         const std::wstring& cityLevel,
                         double income, const double prefWeights[kCategoryCount],
                         double customBudget = -1.0) {
    BudgetPlan plan;
    plan.period = (period == L"year") ? L"year" : L"month";
    plan.province = province;
    plan.cityLevel = cityLevel;

    // ---- 1. 地域系数：综合系数 = 城市等级系数 × 省份消费水平系数 ----
    plan.cityFactor = cityFactor(cityLevel, &plan.cityFound);
    if (!plan.cityFound)
        plan.warnings.push_back(L"⚠ 未收录该城市等级，已使用默认系数 0.82");
    plan.provinceFactor = provinceFactor(province, &plan.provinceFound);
    if (!plan.provinceFound)
        plan.warnings.push_back(L"⚠ 未收录该省份，已使用默认系数 1.00");
    plan.regionFactor = plan.cityFactor * plan.provinceFactor;

    // ---- 2. 收入与总预算 ----
    if (income <= 0) {
        plan.warnings.push_back(L"⚠ 收入未填写或无效，按 5000 元/月估算预算");
        income = (plan.period == L"year") ? 60000.0 : 5000.0;  // 兜底 5000 元/月；年周期折算为 60000 元/年
    }
    plan.income = income;
    if (customBudget >= 0) {
        // 自定义预算模式：总预算 = 用户输入值（clamp 到收入上限）
        plan.customMode = true;
        if (customBudget <= 0) {
            plan.warnings.push_back(L"⚠ 总预算未填写或无效，已按收入估算");
            customBudget = income;
        } else if (customBudget > income) {
            plan.warnings.push_back(L"⚠ 总预算超过收入，已按收入上限计算");
            customBudget = income;
        }
        plan.totalBudget = customBudget;
    } else {
        // 推荐预算模式：总预算 = 收入 × 基准比例 × 综合地域系数（现有算法）
        // 收入档位按"月收入"语义设计：年周期先折算为月均收入再查档，
        // 保证月/年周期下同一收入水平的月均预算一致
        double monthlyIncome = (plan.period == L"year") ? income / 12.0 : income;
        double baseRatio = incomeBaseRatio(monthlyIncome);
        plan.totalBudget = income * baseRatio * plan.regionFactor;
        if (monthlyIncome * baseRatio < 1500.0)
            plan.warnings.push_back(L"⚠ 预算低于基本生活线（1500 元/月），建议提高收入或调整预期");
    }
    plan.budgetPerMonth = (plan.period == L"year") ? plan.totalBudget / 12.0 : plan.totalBudget;

    // ---- 3. 滑块值直接作为占比，归一化（总和恒为 100%） ----
    double weights[11] = {0};
    for (int i = 0; i < kCategoryCount; ++i)
        weights[i] = (prefWeights ? std::max(0.0, std::min(100.0, prefWeights[i])) : 0.0);

    double raw[11] = {0};
    double sum = 0;
    for (int i = 0; i < kCategoryCount; ++i) {
        raw[i] = weights[i];  // 滑块值 0-100 即占比（0-100%），0 表示该分类不安排支出
        sum += raw[i];
    }
    if (sum <= 0) {
        // 所有比例均为 0：回退默认占比，保证仍有合理分配
        plan.warnings.push_back(L"⚠ 所有比例均为 0，已按默认占比分配");
        for (int i = 0; i < kCategoryCount; ++i) raw[i] = kDefaultRatios[i].ratio * 100.0;
        sum = 100.0;
    }
    double ratio[11] = {0};
    for (int i = 0; i < kCategoryCount; ++i) ratio[i] = raw[i] / sum;

    // ---- 4. 软提醒（不强制干预用户分配，尊重自由调整） ----
    // 餐饮/居住占比过低（< 15%）时提醒注意基本生活保障
    if (ratio[0] < 0.15 || ratio[1] < 0.15)
        plan.warnings.push_back(L"⚠ 餐饮/居住占比偏低，注意基本生活保障");
    // 购物/娱乐占比过高时提醒理性消费（默认占比：购物 8%、娱乐 6%）
    if (ratio[3] > 0.15)
        plan.warnings.push_back(L"⚠ 购物比例偏高（>15%），请注意理性消费");
    if (ratio[4] > 0.12)
        plan.warnings.push_back(L"⚠ 娱乐比例偏高（>12%），请合理安排休闲支出");

    // ---- 5. 生成分类结果 ----
    for (int i = 0; i < kCategoryCount; ++i) {
        BudgetCategory c;
        c.name = kDefaultRatios[i].name;
        c.ratio = ratio[i];
        c.amount = plan.totalBudget * ratio[i];
        if (ratio[i] <= 0.0)
            c.note = L"该分类未安排支出";  // 用户拖到 0：该分类占比与金额均为 0
        plan.categories.push_back(c);
    }

    // ---- 提醒 ----
    for (int i = 0; i < kCategoryCount; ++i)
        if (ratio[i] >= 0.60) {
            plan.warnings.push_back(L"⚠ 单分类占比过高（≥60%），注意留出弹性空间");
            break;
        }
    if (plan.period == L"year")
        plan.warnings.push_back(L"ℹ 年预算按 12 个月均摊估算，实际请按月留出应急金");

    return plan;
}

// ===================== 规划窗口 UI（namespace budgetui） =====================

namespace budgetui {

namespace {
const int ID_PERIOD_MONTH   = 1001;
const int ID_PERIOD_YEAR    = 1002;
const int ID_CITY_COMBO     = 1003;
const int ID_INCOME_EDIT    = 1004;
const int ID_PROVINCE_COMBO = 1005;
const int ID_AUTO_BUTTON    = 1006;  // 一键配置
const int ID_MODE_RECOMMEND = 1007;  // 推荐预算模式
const int ID_MODE_CUSTOM    = 1008;  // 自定义预算模式
const int ID_BUDGET_EDIT    = 1009;  // 自定义总预算输入
const int ID_SLIDER_BASE    = 1100;  // 第 i 个滑块 = 1100 + i
const int ID_PCT_BASE       = 1200;  // 第 i 个百分比标签 = 1200 + i
const int ID_NAME_BASE      = 1300;  // 第 i 个分类名标签 = 1300 + i
const int ID_DEC_BASE       = 1400;  // 第 i 个占比 "-" 按钮 = 1400 + i
const int ID_INC_BASE       = 1500;  // 第 i 个占比 "+" 按钮 = 1500 + i
const int ID_APPLY          = 1600;  // 应用此方案到主程序
}  // namespace

struct BudgetApp {
    HWND hwnd = nullptr;
    HWND hPeriodMonth, hPeriodYear;
    HWND hProvinceCombo, hCityCombo, hIncome;
    HWND hBudgetLabel, hBudgetEdit, hBudgetHint;  // 自定义总预算第二行
    HWND hModeRecommend, hModeCustom;             // 模式切换 radio
    HWND hSliders[kCategoryCount];
    HWND hPctLabels[kCategoryCount];
    HWND hApply;          // 应用此方案到主程序
    HWND hResult;         // 右侧自绘结果区
    bool updating = false; // 防止初始化时 EN_CHANGE 提前刷新
    BudgetPlan plan;
};

static BudgetApp bg;

static double g_initWeights[kCategoryCount] = {0};  // 由主程序预填的占比（0-100）
static bool g_hasInitWeights = false;
static bool g_classRegistered = false;

void OnApplyPlan(const BudgetPlan& plan);  // 由宿主程序（主程序）实现

static void RefreshPlan();

// ===================== 结果区绘制（GDI） =====================

static void DrawResult(HWND hwnd) {
    PAINTSTRUCT ps;
    HDC hdc = BeginPaint(hwnd, &ps);
    RECT rc; GetClientRect(hwnd, &rc);
    FillRect(hdc, &rc, (HBRUSH)GetStockObject(WHITE_BRUSH));

    HFONT titleFont = CreateFontW(22, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET,
                                  0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
    HFONT bodyFont = CreateFontW(20, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                                 0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
    HFONT numFont = CreateFontW(21, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET,
                                0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
    HFONT old = (HFONT)SelectObject(hdc, bodyFont);

    // 标题
    SelectObject(hdc, titleFont);
    SetBkMode(hdc, TRANSPARENT);
    SetTextColor(hdc, RGB(0x1A, 0x23, 0x7E));
    RECT rt = {12, 8, rc.right - 12, 36};
    DrawTextW(hdc, L"📊 预算方案", -1, &rt, DT_LEFT | DT_SINGLELINE | DT_VCENTER);

    // 汇总行（20 号字下内容较长，拆为两行显示）
    SelectObject(hdc, bodyFont);
    SetTextColor(hdc, RGB(0x37, 0x47, 0x4F));
    std::wstring periodStr = (bg.plan.period == L"year") ? L"一年" : L"一个月";
    std::wstring provStr = bg.plan.province.empty() ? L"—" : bg.plan.province;
    std::wstring cityStr = bg.plan.cityLevel.empty() ? L"—" : bg.plan.cityLevel;
    std::wstring modeStr = bg.plan.customMode ? L"自定义" : L"推荐";
    std::wstring sumLine1 = L"模式：" + modeStr + L"　周期：" + periodStr + L"　省份：" + provStr;
    RECT rs1 = {12, 36, rc.right - 12, 62};
    DrawTextW(hdc, sumLine1.c_str(), -1, &rs1, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
    std::wstring sumLine2 = L"城市等级：" + cityStr;
    RECT rs1b = {12, 62, rc.right - 12, 88};
    DrawTextW(hdc, sumLine2.c_str(), -1, &rs1b, DT_LEFT | DT_SINGLELINE | DT_VCENTER);

    // 汇总行（第 3 行：推荐模式显示系数；自定义模式显示说明）
    std::wstring facLine;
    if (bg.plan.customMode)
        facLine = L"自定义模式：总预算按输入值，地域系数仅供参考";
    else
        facLine = L"综合地域系数 ×" + fmtMoney(bg.plan.regionFactor) +
            L"（城市等级 ×" + fmtMoney(bg.plan.cityFactor) +
            L" × 省份消费 ×" + fmtMoney(bg.plan.provinceFactor) + L"）";
    RECT rs2 = {12, 88, rc.right - 12, 114};
    DrawTextW(hdc, facLine.c_str(), -1, &rs2, DT_LEFT | DT_SINGLELINE | DT_VCENTER);

    // 总预算大字（左）与储蓄大字（右）：按文字实际宽度分配空间，超宽时自动缩小字号
    SelectObject(hdc, numFont);
    std::wstring unit = (bg.plan.period == L"year") ? L" 元/年（月均 " + fmtMoney(bg.plan.budgetPerMonth) + L" 元）" : L" 元/月";
    std::wstring budgetLine = L"总预算： " + fmtMoney(bg.plan.totalBudget) + unit;

    // 储蓄大字：收入 - 总预算（与周期一致；年周期同时显示月均储蓄）
    double saving = bg.plan.income - bg.plan.totalBudget;
    double savingPerMonth = (bg.plan.period == L"year") ? saving / 12.0 : saving;
    std::wstring savingUnit = (bg.plan.period == L"year")
        ? L" 元/年（月均 " + fmtMoney(savingPerMonth) + L" 元）"
        : L" 元/月";
    std::wstring savingLine = L"储蓄： " + fmtMoney(saving) + savingUnit;

    const int lineTop = 114, lineBot = 142;
    const int availRight = rc.right - 12;

    // 总预算最多占左侧一半宽度（按文字实际宽度计算）
    SIZE szb;
    GetTextExtentPoint32W(hdc, budgetLine.c_str(), (int)budgetLine.size(), &szb);
    int budgetRight = 12 + (int)std::min<LONG>(szb.cx, (availRight - 12) / 2);
    RECT rb = {12, lineTop, budgetRight, lineBot};
    int usedRight = DrawAutoFitText(hdc, budgetLine, numFont, RGB(0xC6, 0x28, 0x28), &rb);

    // 储蓄紧随其后占满剩余空间，空间不足时自动缩小字号
    int saveLeft = usedRight + 20;
    if (saveLeft > availRight) saveLeft = 12;
    RECT rsv = {saveLeft, lineTop, availRight, lineBot};
    DrawAutoFitText(hdc, savingLine, numFont, RGB(0x2E, 0x7D, 0x32), &rsv);

    // 分隔线
    HPEN linePen = CreatePen(PS_SOLID, 1, RGB(0xB0, 0xBE, 0xC5));
    HPEN oldPen = (HPEN)SelectObject(hdc, linePen);
    MoveToEx(hdc, 12, 148, nullptr);
    LineTo(hdc, rc.right - 12, 148);
    SelectObject(hdc, oldPen);
    DeleteObject(linePen);

    // 分类占比条
    SelectObject(hdc, bodyFont);
    int y = 158;
    for (auto& c : bg.plan.categories) {
        // 名称
        SetTextColor(hdc, RGB(0x21, 0x21, 0x21));
        RECT rn = {12, y, 92, y + 26};
        DrawTextW(hdc, c.name.c_str(), -1, &rn, DT_LEFT | DT_SINGLELINE | DT_VCENTER);

        // 条背景
        int barX = 96, barY = y + 6, barW = 230, barH = 14;
        RECT rbck = {barX, barY, barX + barW, barY + barH};
        FillRect(hdc, &rbck, (HBRUSH)GetStockObject(GRAY_BRUSH));

        // 条前景（按占比）
        DWORD col = colorForCategory(c.name);
        HBRUSH br = CreateSolidBrush(RGB((col >> 16) & 0xFF, (col >> 8) & 0xFF, col & 0xFF));
        int fw = (int)(barW * c.ratio);
        RECT rfg = {barX, barY, barX + fw, barY + barH};
        FillRect(hdc, &rfg, br);
        DeleteObject(br);

        // 占比
        SetTextColor(hdc, RGB(0xC6, 0x28, 0x28));
        wchar_t buf[32];
        swprintf(buf, 32, L"%4.1f%%", c.ratio * 100.0);
        RECT rp = {334, y, 410, y + 26};
        DrawTextW(hdc, buf, -1, &rp, DT_LEFT | DT_SINGLELINE | DT_VCENTER);

        // 金额
        SetTextColor(hdc, RGB(0x2E, 0x7D, 0x32));
        swprintf(buf, 32, L"%.2f 元", c.amount);
        RECT ra = {416, y, rc.right - 12, y + 26};
        DrawTextW(hdc, buf, -1, &ra, DT_LEFT | DT_SINGLELINE | DT_VCENTER);

        y += 28;
    }

    // 提醒区
    if (!bg.plan.warnings.empty()) {
        y += 4;
        SelectObject(hdc, bodyFont);
        SetTextColor(hdc, RGB(0xFF, 0xA7, 0x26));
        RECT rw = {12, y, rc.right - 12, y + 26};
        DrawTextW(hdc, L"提醒：", -1, &rw, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
        y += 28;
        for (auto& w : bg.plan.warnings) {
            RECT rline = {12, y, rc.right - 12, y + 26};
            DrawTextW(hdc, w.c_str(), -1, &rline, DT_LEFT | DT_SINGLELINE | DT_VCENTER);
            y += 26;
        }
    }

    SelectObject(hdc, old);
    DeleteObject(titleFont);
    DeleteObject(bodyFont);
    DeleteObject(numFont);
    EndPaint(hwnd, &ps);
}

// ===================== 结果窗口过程 =====================

LRESULT CALLBACK BudgetResultProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_PAINT:
        DrawResult(hwnd);
        return 0;
    case WM_ERASEBKGND:
        return 1;  // 已全量绘制背景，避免闪烁
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

// ===================== 刷新方案 =====================

static void RefreshPlan() {
    bool isYear = (SendMessageW(bg.hPeriodYear, BM_GETCHECK, 0, 0) == BST_CHECKED);
    std::wstring period = isYear ? L"year" : L"month";

    int provIdx = (int)SendMessageW(bg.hProvinceCombo, CB_GETCURSEL, 0, 0);
    std::wstring province = (provIdx >= 0 && provIdx < kProvinceCount)
                                ? kProvinceTable[provIdx].name : L"";

    int cityIdx = (int)SendMessageW(bg.hCityCombo, CB_GETCURSEL, 0, 0);
    std::wstring cityLevel = (cityIdx >= 0 && cityIdx < kCityCount)
                                 ? kCityTable[cityIdx].level : L"";

    double income = _wtof(trim(EditText(bg.hIncome)).c_str());

    // 模式：自定义预算时读总预算输入框（空/无效值由 computeBudget 兜底）
    bool customMode = (SendMessageW(bg.hModeCustom, BM_GETCHECK, 0, 0) == BST_CHECKED);
    double customBudget = -1.0;
    if (customMode)
        customBudget = _wtof(trim(EditText(bg.hBudgetEdit)).c_str());

    double weights[kCategoryCount] = {0};
    for (int i = 0; i < kCategoryCount; ++i)
        weights[i] = (double)SendMessageW(bg.hSliders[i], TBM_GETPOS, 0, 0);

    bg.plan = computeBudget(period, province, cityLevel, income, weights, customBudget);

    // 更新滑块旁的百分比标签
    for (int i = 0; i < kCategoryCount; ++i) {
        wchar_t buf[32];
        swprintf(buf, 32, L"%3.0f", weights[i]);
        SetWindowTextW(bg.hPctLabels[i], buf);
    }

    // 结果区重绘
    InvalidateRect(bg.hResult, nullptr, TRUE);
}

// 一键配置：按当前所选省份/城市等级生成"普通人生活水平"占比模板，
// 填入滑块并刷新（TBM_SETPOS 不触发 WM_HSCROLL，故需手动刷新）
static void ApplyDefaultRatios() {
    int provIdx = (int)SendMessageW(bg.hProvinceCombo, CB_GETCURSEL, 0, 0);
    std::wstring province = (provIdx >= 0 && provIdx < kProvinceCount)
                                ? kProvinceTable[provIdx].name : L"";
    int cityIdx = (int)SendMessageW(bg.hCityCombo, CB_GETCURSEL, 0, 0);
    std::wstring cityLevel = (cityIdx >= 0 && cityIdx < kCityCount)
                                 ? kCityTable[cityIdx].level : L"";

    double def[kCategoryCount] = {0};
    defaultRatiosForRegion(cityFactor(cityLevel, nullptr),
                           provinceFactor(province, nullptr), def);
    for (int i = 0; i < kCategoryCount; ++i)
        SendMessageW(bg.hSliders[i], TBM_SETPOS, TRUE, (int)(def[i] * 100.0 + 0.5));
    RefreshPlan();
}

// ===================== 主窗口过程 =====================

LRESULT CALLBACK BudgetMainProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
    case WM_CREATE: {
        bg.hwnd = hwnd;
        bg.updating = true;

        HFONT font = CreateFontW(16, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                                 0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
        HFONT fontLarge = CreateFontW(20, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET,
                                      0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");
        HFONT titleFont = CreateFontW(20, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET,
                                      0, 0, CLEARTYPE_QUALITY, 0, L"Microsoft YaHei UI");

        // 顶部标题
        HWND hTitle = CreateWindowW(L"STATIC", L"🧠 生活预支出规划",
                                    WS_CHILD | WS_VISIBLE | SS_LEFT,
                                    14, 10, 500, 30, hwnd, nullptr, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(hTitle, WM_SETFONT, (WPARAM)titleFont, TRUE);

        // ---- 周期 ----
        HWND hPeriod = CreateGroup(hwnd, L"周期", 14, 48, 350, 60);
        SendMessageW(hPeriod, WM_SETFONT, (WPARAM)font, TRUE);
        bg.hPeriodMonth = CreateWindowW(L"BUTTON", L"一个月", WS_CHILD | WS_VISIBLE |
                                        BS_AUTORADIOBUTTON | WS_GROUP, 28, 72, 80, 22,
                                        hwnd, (HMENU)(INT_PTR)ID_PERIOD_MONTH, GetModuleHandleW(nullptr), nullptr);
        bg.hPeriodYear = CreateWindowW(L"BUTTON", L"一年", WS_CHILD | WS_VISIBLE |
                                       BS_AUTORADIOBUTTON, 118, 72, 70, 22,
                                       hwnd, (HMENU)(INT_PTR)ID_PERIOD_YEAR, GetModuleHandleW(nullptr), nullptr);
        SendMessageW(bg.hPeriodMonth, WM_SETFONT, (WPARAM)font, TRUE);
        SendMessageW(bg.hPeriodYear, WM_SETFONT, (WPARAM)font, TRUE);
        SendMessageW(bg.hPeriodMonth, BM_SETCHECK, BST_CHECKED, 0);

        // ---- 所在省份 ----
        HWND hProv = CreateGroup(hwnd, L"所在省份", 14, 116, 350, 60);
        SendMessageW(hProv, WM_SETFONT, (WPARAM)font, TRUE);
        HWND hProvLabel = CreateLabel(hwnd, L"省份", 28, 138, 84, 26);
        SendMessageW(hProvLabel, WM_SETFONT, (WPARAM)fontLarge, TRUE);
        bg.hProvinceCombo = CreateWindowW(L"COMBOBOX", L"", WS_CHILD | WS_VISIBLE |
                                          CBS_DROPDOWNLIST | WS_VSCROLL,
                                          102, 138, 170, 300, hwnd, (HMENU)(INT_PTR)ID_PROVINCE_COMBO,
                                          GetModuleHandleW(nullptr), nullptr);
        for (int i = 0; i < kProvinceCount; ++i)
            SendMessageW(bg.hProvinceCombo, CB_ADDSTRING, 0, (LPARAM)kProvinceTable[i].name);
        SendMessageW(bg.hProvinceCombo, CB_SETCURSEL, 18, 0);  // 默认广东省
        SendMessageW(bg.hProvinceCombo, WM_SETFONT, (WPARAM)font, TRUE);

        // ---- 城市等级 ----
        HWND hCity = CreateGroup(hwnd, L"所在城市等级", 14, 184, 350, 60);
        SendMessageW(hCity, WM_SETFONT, (WPARAM)font, TRUE);
        HWND hCityLabel = CreateLabel(hwnd, L"城市等级", 28, 206, 84, 26);
        SendMessageW(hCityLabel, WM_SETFONT, (WPARAM)fontLarge, TRUE);
        bg.hCityCombo = CreateWindowW(L"COMBOBOX", L"", WS_CHILD | WS_VISIBLE |
                                      CBS_DROPDOWNLIST | WS_VSCROLL,
                                      102, 206, 130, 200, hwnd, (HMENU)(INT_PTR)ID_CITY_COMBO,
                                      GetModuleHandleW(nullptr), nullptr);
        for (int i = 0; kCityLevels[i]; ++i)
            SendMessageW(bg.hCityCombo, CB_ADDSTRING, 0, (LPARAM)kCityLevels[i]);
        SendMessageW(bg.hCityCombo, CB_SETCURSEL, 2, 0);  // 默认二线城市
        SendMessageW(bg.hCityCombo, WM_SETFONT, (WPARAM)font, TRUE);

        // ---- 预算模式（右上角，单独放置，远离左侧控件） ----
        HWND hModeLabel = CreateLabel(hwnd, L"预算模式", 556, 16, 96, 26);
        SendMessageW(hModeLabel, WM_SETFONT, (WPARAM)fontLarge, TRUE);
        bg.hModeRecommend = CreateWindowW(L"BUTTON", L"推荐预算", WS_CHILD | WS_VISIBLE |
                                          BS_AUTORADIOBUTTON | WS_GROUP | WS_TABSTOP,
                                          650, 16, 100, 24, hwnd, (HMENU)(INT_PTR)ID_MODE_RECOMMEND,
                                          GetModuleHandleW(nullptr), nullptr);
        bg.hModeCustom = CreateWindowW(L"BUTTON", L"自定义预算", WS_CHILD | WS_VISIBLE |
                                       BS_AUTORADIOBUTTON,
                                       770, 16, 110, 24, hwnd, (HMENU)(INT_PTR)ID_MODE_CUSTOM,
                                       GetModuleHandleW(nullptr), nullptr);
        SendMessageW(bg.hModeRecommend, WM_SETFONT, (WPARAM)font, TRUE);
        SendMessageW(bg.hModeCustom, WM_SETFONT, (WPARAM)font, TRUE);
        SendMessageW(bg.hModeRecommend, BM_SETCHECK, BST_CHECKED, 0);

        // ---- 收入 / 自定义总预算 ----
        HWND hInc = CreateGroup(hwnd, L"收入", 14, 252, 350, 132);
        SendMessageW(hInc, WM_SETFONT, (WPARAM)font, TRUE);
        HWND hIncomeLabel = CreateLabel(hwnd, L"收入(元)", 28, 272, 84, 26);
        SendMessageW(hIncomeLabel, WM_SETFONT, (WPARAM)fontLarge, TRUE);
        bg.hIncome = CreateWindowW(L"EDIT", L"8000", WS_CHILD | WS_VISIBLE | WS_BORDER |
                                   WS_TABSTOP | ES_AUTOHSCROLL | ES_NUMBER,
                                   120, 272, 120, 24, hwnd, (HMENU)(INT_PTR)ID_INCOME_EDIT,
                                   GetModuleHandleW(nullptr), nullptr);
        SendMessageW(bg.hIncome, WM_SETFONT, (WPARAM)font, TRUE);
        HWND hIncHint = CreateLabel(hwnd, L"月周期填月收入，年周期填年收入", 28, 302, 322, 26);
        SendMessageW(hIncHint, WM_SETFONT, (WPARAM)fontLarge, TRUE);
        // 自定义预算第二行（默认隐藏，切到自定义模式时显示）
        bg.hBudgetLabel = CreateLabel(hwnd, L"总预算(元)", 28, 330, 84, 26);
        SendMessageW(bg.hBudgetLabel, WM_SETFONT, (WPARAM)fontLarge, TRUE);
        bg.hBudgetEdit = CreateWindowW(L"EDIT", L"", WS_CHILD | WS_BORDER |
                                       WS_TABSTOP | ES_AUTOHSCROLL | ES_NUMBER,
                                       120, 328, 120, 24, hwnd, (HMENU)(INT_PTR)ID_BUDGET_EDIT,
                                       GetModuleHandleW(nullptr), nullptr);
        SendMessageW(bg.hBudgetEdit, WM_SETFONT, (WPARAM)font, TRUE);
        bg.hBudgetHint = CreateLabel(hwnd, L"总预算不能超过收入", 28, 358, 322, 26);
        SendMessageW(bg.hBudgetHint, WM_SETFONT, (WPARAM)fontLarge, TRUE);
        ShowWindow(bg.hBudgetLabel, SW_HIDE);
        ShowWindow(bg.hBudgetEdit, SW_HIDE);
        ShowWindow(bg.hBudgetHint, SW_HIDE);

        // ---- 支出比例滑块 ----
        HWND hPref = CreateGroup(hwnd, L"支出比例", 14, 392, 350, 356);
        SendMessageW(hPref, WM_SETFONT, (WPARAM)font, TRUE);
        // 一键配置：按当前所选省份+城市等级的普通人生活水平恢复默认分配
        HWND hAuto = CreateWindowW(L"BUTTON", L"一键配置（按地区生活水平）",
                                   WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                   96, 394, 252, 24, hwnd, (HMENU)(INT_PTR)ID_AUTO_BUTTON,
                                   GetModuleHandleW(nullptr), nullptr);
        SendMessageW(hAuto, WM_SETFONT, (WPARAM)font, TRUE);
        // 预填默认占比：若主程序通过 ShowBudgetPlanner 传入占比（如 if 线），则优先使用
        double initRatios[kCategoryCount] = {0};
        if (g_hasInitWeights) {
            for (int i = 0; i < kCategoryCount; ++i)
                initRatios[i] = g_initWeights[i];
        } else {
            defaultRatiosForRegion(cityFactor(L"二线城市", nullptr),
                                   provinceFactor(L"广东", nullptr), initRatios);
        }
        for (int i = 0; i < kCategoryCount; ++i) {
            int sy = 420 + i * 30;
            bg.hSliders[i] = CreateWindowW(L"msctls_trackbar32", L"",
                                           WS_CHILD | WS_VISIBLE | TBS_HORZ | TBS_AUTOTICKS,
                                           116, sy - 3, 112, 24, hwnd, (HMENU)(INT_PTR)(ID_SLIDER_BASE + i),
                                           GetModuleHandleW(nullptr), nullptr);
            SendMessageW(bg.hSliders[i], TBM_SETRANGE, TRUE, MAKELPARAM(0, 100));
            SendMessageW(bg.hSliders[i], TBM_SETPOS, TRUE, (int)(initRatios[i] * 100.0 + 0.5));
            SendMessageW(bg.hSliders[i], WM_SETFONT, (WPARAM)font, TRUE);

            bg.hPctLabels[i] = CreateLabel(hwnd, L"0", 234, sy, 40, 22);
            SendMessageW(bg.hPctLabels[i], WM_SETFONT, (WPARAM)font, TRUE);

            // 占比微调按钮："-" 每点一次 -1%，"+" 每点一次 +1%
            HWND hDec = CreateWindowW(L"BUTTON", L"-", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                      278, sy, 24, 22, hwnd, (HMENU)(INT_PTR)(ID_DEC_BASE + i),
                                      GetModuleHandleW(nullptr), nullptr);
            SendMessageW(hDec, WM_SETFONT, (WPARAM)font, TRUE);
            HWND hIncBtn = CreateWindowW(L"BUTTON", L"+", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                         306, sy, 24, 22, hwnd, (HMENU)(INT_PTR)(ID_INC_BASE + i),
                                         GetModuleHandleW(nullptr), nullptr);
            SendMessageW(hIncBtn, WM_SETFONT, (WPARAM)font, TRUE);

            // 分类名
            HWND hName = CreateLabel(hwnd, kDefaultRatios[i].name, 28, sy, 84, 26);
            SendMessageW(hName, WM_SETFONT, (WPARAM)fontLarge, TRUE);
        }

        // ---- 右侧结果区 ----
        bg.hResult = CreateWindowExW(WS_EX_CLIENTEDGE, L"BudgetResultWindow", L"",
                                     WS_CHILD | WS_VISIBLE,
                                     378, 48, 568, 700, hwnd, nullptr,
                                     GetModuleHandleW(nullptr), nullptr);

        // ---- 应用此方案到主程序（用于超支预警） ----
        bg.hApply = CreateWindowW(L"BUTTON", L"✓ 应用此方案到主程序（用于超支预警）",
                                  WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                  14, 756, 350, 30, hwnd, (HMENU)(INT_PTR)ID_APPLY,
                                  GetModuleHandleW(nullptr), nullptr);
        SendMessageW(bg.hApply, WM_SETFONT, (WPARAM)font, TRUE);

        bg.updating = false;
        RefreshPlan();
        return 0;
    }

    case WM_COMMAND: {
        int id = LOWORD(wParam);
        if (HIWORD(wParam) == EN_CHANGE && id == ID_INCOME_EDIT) {
            if (!bg.updating) RefreshPlan();
            return 0;
        }
        if (HIWORD(wParam) == EN_CHANGE && id == ID_BUDGET_EDIT) {
            if (!bg.updating) RefreshPlan();
            return 0;
        }
        if (HIWORD(wParam) == CBN_SELCHANGE &&
            (id == ID_PROVINCE_COMBO || id == ID_CITY_COMBO)) {
            RefreshPlan();
            return 0;
        }
        if (HIWORD(wParam) == BN_CLICKED &&
            (id == ID_PERIOD_MONTH || id == ID_PERIOD_YEAR)) {
            RefreshPlan();
            return 0;
        }
        if (HIWORD(wParam) == BN_CLICKED && id == ID_AUTO_BUTTON) {
            ApplyDefaultRatios();
            return 0;
        }
        // 模式切换：推荐 / 自定义预算
        if (HIWORD(wParam) == BN_CLICKED &&
            (id == ID_MODE_RECOMMEND || id == ID_MODE_CUSTOM)) {
            bool custom = (id == ID_MODE_CUSTOM);
            ShowWindow(bg.hBudgetLabel, custom ? SW_SHOW : SW_HIDE);
            ShowWindow(bg.hBudgetEdit, custom ? SW_SHOW : SW_HIDE);
            ShowWindow(bg.hBudgetHint, custom ? SW_SHOW : SW_HIDE);
            EnableWindow(bg.hIncome, !custom);
            if (custom && trim(EditText(bg.hBudgetEdit)).empty()) {
                // 首次进入自定义模式：预填当前推荐总预算，方便在此基础上调整
                wchar_t buf[32];
                swprintf(buf, 32, L"%.0f", bg.plan.totalBudget);
                SetWindowTextW(bg.hBudgetEdit, buf);
            }
            RefreshPlan();
            return 0;
        }
        // 应用此方案到主程序
        if (HIWORD(wParam) == BN_CLICKED && id == ID_APPLY) {
            OnApplyPlan(bg.plan);
            DestroyWindow(hwnd);
            return 0;
        }
        // 占比微调："-" 每点一次 -1%，"+" 每点一次 +1%（clamp 0-100）
        if (HIWORD(wParam) == BN_CLICKED &&
            id >= ID_DEC_BASE && id < ID_DEC_BASE + kCategoryCount) {
            int i = id - ID_DEC_BASE;
            int pos = (int)SendMessageW(bg.hSliders[i], TBM_GETPOS, 0, 0);
            if (pos > 0) {
                SendMessageW(bg.hSliders[i], TBM_SETPOS, TRUE, pos - 1);
                RefreshPlan();
            }
            return 0;
        }
        if (HIWORD(wParam) == BN_CLICKED &&
            id >= ID_INC_BASE && id < ID_INC_BASE + kCategoryCount) {
            int i = id - ID_INC_BASE;
            int pos = (int)SendMessageW(bg.hSliders[i], TBM_GETPOS, 0, 0);
            if (pos < 100) {
                SendMessageW(bg.hSliders[i], TBM_SETPOS, TRUE, pos + 1);
                RefreshPlan();
            }
            return 0;
        }
        return 0;
    }

    case WM_HSCROLL: {
        // 判断哪个滑块被拖动（lParam 为滑块句柄）
        for (int i = 0; i < kCategoryCount; ++i) {
            if ((HWND)lParam == bg.hSliders[i]) {
                RefreshPlan();
                break;
            }
        }
        return 0;
    }

    case WM_SIZE: {
        RECT rc; GetClientRect(hwnd, &rc);
        SetWindowPos(bg.hResult, nullptr, 378, 48, rc.right - 378 - 14, rc.bottom - 48 - 14,
                     SWP_NOZORDER);
        return 0;
    }

    case WM_CLOSE:
        DestroyWindow(hwnd);
        return 0;
    case WM_DESTROY:
        if (hwnd == bg.hwnd) bg.hwnd = nullptr;
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

// ===================== 注册窗口类 =====================

void RegisterBudgetClasses() {
    if (g_classRegistered) return;

    WNDCLASSEXW wcResult{};
    wcResult.cbSize = sizeof(wcResult);
    wcResult.lpfnWndProc = BudgetResultProc;
    wcResult.hInstance = GetModuleHandleW(nullptr);
    wcResult.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wcResult.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wcResult.lpszClassName = L"BudgetResultWindow";
    RegisterClassExW(&wcResult);

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = BudgetMainProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_BTNFACE + 1);
    wc.lpszClassName = L"BudgetPlannerMain";
    wc.hIcon = LoadIconW(nullptr, IDI_APPLICATION);
    wc.hIconSm = LoadIconW(nullptr, IDI_APPLICATION);
    RegisterClassExW(&wc);

    g_classRegistered = true;
}

// ===================== 打开规划窗口（供主程序调用） =====================
// owner       : 父窗口（可为 nullptr）
// initWeights : 可选，长度 11 的占比数组（0-100），用于预填滑块
//               （如 if 线生成的消费结构）；传 nullptr 表示用默认模板
void ShowBudgetPlanner(HWND owner, const double* initWeights) {
    INITCOMMONCONTROLSEX icc{};
    icc.dwSize = sizeof(icc);
    icc.dwICC = ICC_BAR_CLASSES | ICC_STANDARD_CLASSES;
    InitCommonControlsEx(&icc);

    RegisterBudgetClasses();

    if (initWeights) {
        for (int i = 0; i < kCategoryCount; ++i) {
            double v = initWeights[i];
            if (v < 0) v = 0;
            if (v > 100) v = 100;
            g_initWeights[i] = v;
        }
        g_hasInitWeights = true;
    } else {
        g_hasInitWeights = false;
    }

    HWND w = CreateWindowExW(0, L"BudgetPlannerMain", L"生活预支出规划",
                             WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT,
                             970, 820, owner, nullptr, GetModuleHandleW(nullptr), nullptr);
    if (!w) return;
    ShowWindow(w, SW_SHOW);
    UpdateWindow(w);
}

}  // namespace budgetui

// ===================== 独立演示模式 =====================
// 仅当定义 BUDGET_PLANNER_MAIN 时编译（独立可执行文件）；
// 被主程序 include 时不定义该宏，以下代码自动失效。

#ifdef BUDGET_PLANNER_MAIN

#include <fstream>

namespace budgetui {
// 演示模式下 OnApplyPlan 的默认实现：提示已应用
void OnApplyPlan(const BudgetPlan& plan) {
    std::wstring msg = L"预算方案已生成：\n";
    msg += L"  总预算：" + fmtMoney(plan.totalBudget) + L" 元";
    if (plan.period == L"year") msg += L"（年，月均 " + fmtMoney(plan.budgetPerMonth) + L" 元）";
    msg += L"\n  月储蓄：" + fmtMoney(plan.income - plan.budgetPerMonth) + L" 元\n\n";
    msg += L"（独立演示模式下不保存；并入主程序后点击此按钮，将把方案用于超支预警。）";
    MessageBoxW(bg.hwnd ? bg.hwnd : nullptr, msg.c_str(), L"预算方案", MB_OK | MB_ICONINFORMATION);
}
}  // namespace budgetui

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE, PWSTR, int nCmdShow) {
    budgetui::RegisterBudgetClasses();

    budgetui::bg.hwnd = CreateWindowExW(0, L"BudgetPlannerMain", L"生活预支出规划",
                                        WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT,
                                        970, 820, nullptr, nullptr, hInstance, nullptr);
    if (!budgetui::bg.hwnd) return 1;
    ShowWindow(budgetui::bg.hwnd, nCmdShow);
    UpdateWindow(budgetui::bg.hwnd);

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return 0;
}

#endif  // BUDGET_PLANNER_MAIN

#endif  // SMART_LEDGER_BUDGET_PLANNER_CPP_
