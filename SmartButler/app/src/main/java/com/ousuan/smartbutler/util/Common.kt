package com.ousuan.smartbutler.util

import java.util.Locale

/** 金额格式化：保留两位小数 */
fun fmtMoney(value: Double): String = String.format(Locale.US, "%.2f", value)
