package com.ousuan.smartbutler.util

/**
 * 文本解析工具：从语音/OCR 识别文本中提取金额与消费分类（正则 + 关键词）。
 */
object ParseUtils {

    /**
     * 金额正则：匹配 "35元" "35.5块钱" "35块" "¥35" "￥35.5" 等。
     * 支持两种写法：¥ 符号前缀（¥35）与 数字+单位后缀（35元/35块钱/35块）。
     */
    private val AMOUNT_RE = Regex("""(?:¥|￥)\s*(\d+(?:\.\d+)?)|(\d+(?:\.\d+)?)\s*(?:元|块钱|块)""")

    /** 千分位逗号：用于清理 "1,299.00" 这类带逗号金额 */
    private val THOUSAND_SEP_RE = Regex("""(\d),(?=\d{3}\b)""")

    /** 裸数字：OCR 小票中无单位符号的金额候选 */
    private val BARE_AMOUNT_RE = Regex("""\d+(?:\.\d+)?""")

    /** 中文数字 → 阿拉伯数字映射（「两」也按 2 处理，支持「两百」） */
    private val CHINESE_NUM_MAP = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        '十' to 10, '百' to 100, '千' to 1000, '万' to 10000
    )

    /** 同音字纠错映射：Vosk 中文模型易把「百」识别成「白」、「零」识别成「领」等 */
    private val HOMOPHONE_MAP = mapOf(
        '白' to '百', '摆' to '百', '佰' to '百',
        '领' to '零', '令' to '零'
    )

    /**
     * 中文金额正则：匹配「三十八块」「十五块」「五十」「三点五」「十块五」等。
     * 组1=中文数字整数部分，组2=可选小数位数字，「点/块」为小数分隔符。
     */
    private val CHINESE_AMOUNT_RE =
        Regex("""([零一二三四五六七八九十百千万]+)(?:点|块)?([零一二三四五六七八九]?)??""")

    /** 合理金额上限，过滤日期/数量等明显非金额的数字 */
    private const val MAX_AMOUNT = 1_000_000.0

    /**
     * 对识别文本做同音字纠正（白→百、领→零 等），返回纠正后的文本。
     * 用于「一百零五」被 Vosk 识别成「一白领五」时仍能正确转换为 105。
     */
    fun correctHomophones(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) sb.append(HOMOPHONE_MAP[c] ?: c)
        return sb.toString()
    }

    /**
     * 中文数字 → 阿拉伯数字（Double）。
     * 整数：十五→15、三十八→38、一百二十三→123、十→10、二十→20、两百→200；
     * 小数：三点五→3.5、十块五→10.5、十二块三→12.3。
     * 输入可含同音错字（「一白领五」→ 105）。
     * 无法解析返回 null。
     */
    fun chineseToNumber(chinese: String): Double? {
        if (chinese.isBlank()) return null
        // 先做同音字纠正，兼容「一白领五」这类识别错误
        val cleaned = correctHomophones(chinese)
        // 以「点」「块」为小数分隔符，拆出整数部分与小数部分
        val sep = cleaned.indexOfAny(charArrayOf('点', '块'))
        val intPart = if (sep >= 0) cleaned.substring(0, sep) else cleaned
        val decPart = if (sep >= 0) cleaned.substring(sep + 1) else ""
        val intVal = chineseIntegerToNumber(intPart) ?: return null
        val decVal = if (decPart.isEmpty()) 0.0 else chineseDecimalToNumber(decPart)
        return intVal + decVal
    }

    /** 中文整数 → Int（经典分段算法，支持 十/百/千/万 进制） */
    private fun chineseIntegerToNumber(s: String): Int? {
        if (s.isEmpty()) return null
        var total = 0
        var section = 0
        var num = 0
        for (c in s) {
            val v = CHINESE_NUM_MAP[c] ?: return null
            when {
                v >= 10000 -> { section = (section + num) * v; total += section; section = 0; num = 0 }
                v >= 100 -> { num = if (num == 0) 1 else num; section += num * v; num = 0 }
                v >= 10 -> { num = if (num == 0) 1 else num; section += num * v; num = 0 }
                else -> num = v
            }
        }
        val result = total + section + num
        return if (result > 0) result else null
    }

    /** 中文小数位 → Double（「五」→0.5，「三五」→0.35） */
    private fun chineseDecimalToNumber(s: String): Double {
        var result = 0.0
        var factor = 0.1
        for (c in s) {
            val d = CHINESE_NUM_MAP[c] ?: continue
            result += d * factor
            factor /= 10
        }
        return result
    }

    /**
     * 分类关键词表（linkedMapOf 按书写顺序匹配，先命中先返回）。
     * 注意：教育/学习 组（书/学/课/文具）排在 购物 之前，保证「买书」命中学习类而非购物。
     */
    private val KEYWORDS = linkedMapOf(
        "餐饮" to listOf("食堂", "堂", "外卖", "早餐", "午餐", "晚餐", "夜宵", "奶茶", "咖啡", "火锅", "吃", "饭", "餐", "喝", "聚餐", "超市", "菜", "水果", "零食", "面包"),
        "交通" to listOf("公交", "地铁", "打车", "打的", "滴滴", "滴", "出租", "高铁", "火车", "加油", "停车", "单车", "骑行", "通勤", "车费", "车", "打", "的"),
        "娱乐" to listOf("电影", "影", "歌", "游戏", "玩", "乐", "KTV", "唱歌", "演唱会", "网吧", "会员", "Steam", "演出", "桌游"),
        "教育" to listOf("书", "学", "课", "文具", "课程", "培训", "学费", "网课", "考试", "报班"),
        "购物" to listOf("淘宝", "京东", "拼多多", "拼", "买", "购", "药", "店", "衣服", "鞋", "耳机", "数码", "口红", "包", "日用品"),
        "居住" to listOf("房租", "房贷", "物业", "水费", "电费", "燃气", "宽带", "暖气"),
        "医疗" to listOf("医院", "挂号", "体检", "诊所", "牙", "感冒", "医保"),
        "通讯" to listOf("话费", "流量", "手机", "宽带", "充值"),
        "社交人情" to listOf("红包", "礼金", "随礼", "份子", "请客", "AA", "生日", "聚餐"),
        "旅行" to listOf("酒店", "民宿", "机票", "门票", "旅行", "旅游", "景区", "高铁票", "旅行团"),
        "其他" to emptyList()
    )

    /**
     * 提取金额，找不到返回 null。匹配优先级：
     * 0. 先做同音字纠正（白→百、领→零），兼容 Vosk 识别误差；
     * 1. 阿拉伯数字（"35元" "35.5块钱" "35块" "¥35" 及无单位 "50"）；
     * 2. 中文数字（「三十八块钱」→ 38、"十五块"→ 15、"一百零五"→ 105、"三点五"→ 3.5）；
     * 3. 无单位阿拉伯数字兜底：取第一个非日期数字（如「买书花了50」→ 50）。
     */
    fun extractAmount(text: String): Double? {
        // 0) 同音字纠正，保证「一白领五」能按「一百零五」匹配
        val corrected = correctHomophones(text)
        // 1) 阿拉伯数字：带单位/¥ 符号形式优先
        AMOUNT_RE.find(corrected)?.let { m ->
            val v = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
            v.toDoubleOrNull()?.let { return it }
        }
        // 2) 中文数字：Vosk 中文模型输出「三十八」「十五」「一百零五」等
        CHINESE_AMOUNT_RE.find(corrected)?.let { m ->
            chineseToNumber(m.value)?.let { return it }
        }
        // 3) 无单位阿拉伯数字兜底：取第一个非日期数字，避免误提「8月28日」中的 8 / 28
        return BARE_AMOUNT_RE.findAll(corrected)
            .firstOrNull { m ->
                val before = corrected.getOrNull(m.range.first - 1)
                val after = corrected.getOrNull(m.range.last + 1)
                before != '年' && before != '月' && after != '月' && after != '日'
            }
            ?.value?.toDoubleOrNull()
    }

    /**
     * 提取识别文本中的所有金额候选（按原文出现顺序、去重）。
     * 优先级：带单位/符号的（"35元" "¥35.5"）与裸数字（"35.5"）都会收集，
     * 由用户点击确认哪个才是真正的总金额（最大数字可能是折扣前/小计等）。
     */
    fun extractAmounts(text: String): List<Double> {
        // 先清理千分位逗号，避免 "1,299.00" 被拆成 1 和 299
        val cleaned = text.replace(THOUSAND_SEP_RE, "$1")
        val found = mutableListOf<Pair<Int, Double>>()
        AMOUNT_RE.findAll(cleaned).forEach { m ->
            val v = if (m.groupValues[1].isNotEmpty()) m.groupValues[1] else m.groupValues[2]
            v.toDoubleOrNull()?.let { found += m.range.first to it }
        }
        BARE_AMOUNT_RE.findAll(cleaned).forEach { m ->
            m.value.toDoubleOrNull()?.let { found += m.range.first to it }
        }
        return found
            .filter { it.second > 0 && it.second < MAX_AMOUNT }
            .distinctBy { it.second }
            .sortedBy { it.first }
            .map { it.second }
    }

    /** 提取分类，未命中任何关键词返回「其他」 */
    fun extractCategory(text: String): String {
        for ((cat, words) in KEYWORDS) {
            if (cat == "其他") continue
            if (words.any { text.contains(it) }) return cat
        }
        return "其他"
    }
}
