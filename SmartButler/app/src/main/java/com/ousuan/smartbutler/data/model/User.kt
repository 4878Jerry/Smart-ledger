package com.ousuan.smartbutler.data.model

import java.util.UUID

/**
 * 本地用户账号（本地模拟版，无真实后端）。
 * 密码为明文存储，仅用于演示，切勿用于生产环境。
 */
data class User(
    /** 用户唯一 ID，注册时自动生成 UUID */
    val userId: String = UUID.randomUUID().toString(),
    /** 登录用户名（唯一） */
    val username: String,
    /** 密码（明文，本地模拟用） */
    val password: String,
    /** 昵称（默认取用户名） */
    val nickname: String? = null,
    /** 头像（预留，可存资源名或 base64） */
    val avatar: String? = null,
    /** 注册时间（毫秒时间戳） */
    val createdAt: Long = System.currentTimeMillis()
)
