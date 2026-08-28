package com.ousuan.smartbutler.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 日期工具（minSdk 26 起可用 java.time） */
object DateUtils {

    private val MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM")

    /** 今天 YYYY-MM-DD */
    fun today(): String = LocalDate.now().toString()

    /** 当前月份前缀 yyyy-MM */
    fun nowMonthPrefix(): String = LocalDate.now().format(MONTH_FMT)

    /** 当前年份 yyyy */
    fun nowYear(): String = LocalDate.now().year.toString()
}
