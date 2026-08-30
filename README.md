# 鸥算AI · 智能消费管家

## 项目简介

一款融合 AI 技术的个人消费管理助手，支持语音/OCR 记账、离线模式、社区分享。

## 技术架构

- **Android 端**：Kotlin + Room + Retrofit + Vosk + ML Kit
- **后端**：Python FastAPI + SQLite + JWT
- **桌面端（参考原型）**：C++ + Win32 + GDI+

## 功能列表

- 语音记账（Vosk 离线识别 + 本地解析）
- 图片 OCR 记账（ML Kit 中文识别）
- 手动记账 + 消费统计图表
- 月度预算规划（省份差异 + 偏好滑块）
- 余额预警 + IF 线模拟 + 年月日视图
- 社区（发布公开数据 + 点赞/评论）
- 账号系统（注册/登录 + 离线降级 + 切换账号）
- 在线/离线双模式 + 云同步

## 环境要求

- **Android**：JDK 17、Android Studio
- **后端**：Python 3.10+

## 快速开始

### 1. 启动后端服务器

```bash
cd backend
pip install -r requirements.txt
python import_data.py  # 导入测试数据
run.bat  # 或 start_server.bat
```

### 2. 运行 Android App

- Android Studio 打开 SmartButler 项目
- 在「我的」→「服务器设置」中输入服务器地址
- 用 test1 / 123456 登录

### 3. 测试账号

| 用户名 | 密码 |
| --- | --- |
| test1 | 123456 |
| test2 | 123456 |

## 团队成员

| 成员 | 负责模块 |
| --- | --- |
| 冀颢天 | 基础记账 + 图表统计 |
| 黄颖伦 | 月度预算规划 + PPT |
| 胡腾飞 | 预警 / IF线 / 年月日视图 |
| 裴振羽 | 语音 + OCR + 账号 + 社区 + 后端 + PPT |
| 符维磊 | UI美化 + 吉祥物 |
