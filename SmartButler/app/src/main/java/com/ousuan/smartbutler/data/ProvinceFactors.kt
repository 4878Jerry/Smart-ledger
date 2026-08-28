package com.ousuan.smartbutler.data

/**
 * 地域消费水平系数：完全参照 C++ budget_planner.cpp 中的 kProvinceTable / kCityTable。
 * 基准：全国平均 = 1.00，系数越大代表该省居民人均消费支出越高。
 * 综合地域系数 = 省份系数 × 城市等级系数。
 */
object ProvinceFactors {

    /** 省份消费水平系数（31 个省级行政区） */
    val provinces: List<Pair<String, Double>> = listOf(
        "北京" to 1.12, "天津" to 1.06, "河北" to 0.94, "山西" to 0.92, "内蒙古" to 0.96,
        "辽宁" to 0.95, "吉林" to 0.92, "黑龙江" to 0.90,
        "上海" to 1.12, "江苏" to 1.02, "浙江" to 1.05, "安徽" to 0.94, "福建" to 1.01,
        "江西" to 0.93, "山东" to 0.99,
        "河南" to 0.93, "湖北" to 0.99, "湖南" to 0.97, "广东" to 1.04, "广西" to 0.89,
        "海南" to 0.97,
        "重庆" to 0.98, "四川" to 0.97, "贵州" to 0.86, "云南" to 0.88, "西藏" to 0.85,
        "陕西" to 0.93, "甘肃" to 0.85, "青海" to 0.87, "宁夏" to 0.88, "新疆" to 0.89
    )

    /** 城市等级系数（5 档） */
    val cityLevels: List<Pair<String, Double>> = listOf(
        "一线城市" to 1.00,
        "新一线城市" to 0.90,
        "二线城市" to 0.82,
        "三线城市" to 0.72,
        "四线及以下" to 0.62
    )

    /** 未收录省份的默认回退系数（对应 C++ 的 kDefaultFactor=0.82） */
    const val DEFAULT_FACTOR: Double = 0.82
}
