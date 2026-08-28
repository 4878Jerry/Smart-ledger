# SmartButler 后端服务

「智能管家」Android 应用（`com.ousuan.smartbutler`）的本地后端，提供在线数据服务：用户注册/登录、交易记录云同步、社区发帖/评论/点赞。

- **框架**：FastAPI + uvicorn（Python 3.10+）
- **数据库**：SQLite（aiosqlite 异步驱动），首次启动自动建表
- **认证**：JWT（有效期 7 天），密码 bcrypt 哈希存储
- **文档**：启动后访问 `http://localhost:8000/docs` 自动生成 Swagger 文档

## 目录结构

```
backend/
├── main.py              # 主程序：注册路由、CORS、启动建表 + 预置账号
├── models.py            # SQLAlchemy ORM 数据模型（users/transactions/posts/comments/likes）
├── database.py          # 数据库连接与会话管理
├── auth.py              # JWT 生成与验证、bcrypt 密码哈希、get_current_user 依赖
├── schemas.py           # Pydantic 请求/响应模型，统一返回格式
├── routers/
│   ├── auth.py          # POST /api/register, POST /api/login
│   ├── transactions.py  # GET/POST /api/transactions(, /sync), DELETE /api/transactions/{id}
│   └── community.py     # 公开统计、发帖、评论、点赞
├── requirements.txt     # 依赖清单
├── run.bat              # Windows 一键启动脚本
└── README.md
```

## 快速启动（Windows）

```bat
cd D:\lbt\smartledger\backend
run.bat
```

脚本会自动创建 `venv` 虚拟环境、安装依赖，并启动服务：

```
本机访问:      http://localhost:8000
Swagger 文档:  http://localhost:8000/docs
```

手动启动方式：

```bat
cd D:\lbt\smartledger\backend
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

> 绑定 `0.0.0.0` 表示监听所有网卡，同一局域网 / Tailscale 网络内的设备均可访问。

## 预置测试账号

| 用户名 | 密码 | 说明 |
| ------ | ---- | ---- |
| test1  | 123456 | 测试账号 1 |
| test2  | 123456 | 测试账号 2 |

首次启动自动创建，已存在则跳过。

## API 一览

统一返回格式：成功 `{"code": 0, "data": ..., "msg": "success"}`；错误时 `code` 非 0，`msg` 为错误信息。

### 认证（无需 token）

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| POST | `/api/register` | 注册 `{username, password, nickname?}` |
| POST | `/api/login` | 登录，返回 `{token, user}` |

### 交易记录（需 `Authorization: Bearer {token}`）

| 方法 | 路径 | 说明 |
| ---- | ---- | ---- |
| GET | `/api/transactions` | 获取当前用户所有记录 |
| POST | `/api/transactions/sync` | 批量增量同步 `{transactions: [{localId, amount, category, type, note, timestamp, is_public}]}`，返回 `{synced_count, failed_ids}` |
| DELETE | `/api/transactions/{id}` | 删除记录（校验归属） |

### 社区

| 方法 | 路径 | 需 token | 说明 |
| ---- | ---- | ---- | ---- |
| GET | `/api/stats/public` | 否 | 所有用户公开统计（含帖子作者、评论列表、点赞数） |
| POST | `/api/posts` | 是 | 发布月度统计 `{month: "2026-08", total_expense, category_breakdown, top_category, saving_tip}` |
| POST | `/api/comments` | 是 | 发表评论 `{post_id, content}` |
| GET | `/api/comments/{post_id}` | 否 | 获取评论列表 |
| POST | `/api/like/{post_id}` | 是 | 点赞 / 取消点赞，返回最新 `{likes}` |

## Tailscale 虚拟网络访问

Tailscale 会把不同设备组进一个虚拟局域网，即使不在同一 WiFi 也能互相访问，无需暴露公网端口。

### 1. 安装并登录 Tailscale

- 后端电脑（Windows）：到 [tailscale.com/download](https://tailscale.com/download) 下载安装，登录账号。
- Android 手机：在应用商店搜索「Tailscale」安装，登录**同一个账号**（或同一 Tailnet）。

### 2. 获取本机 Tailscale 虚拟 IP

命令行执行：

```bat
tailscale ip -4
```

输出形如 `100.101.102.103` 即本机 Tailscale IP。也可在系统托盘 Tailscale 图标 → 查看「100.x.x.x」。

两台设备都显示在线后，后端电脑上验证：

```
http://100.101.102.103:8000/docs
```

手机浏览器能打开该地址即可。

### 3. Android 端 BASE_URL 配置

在 Android 工程中找到后端地址常量（建议集中定义，例如 `SmartButlerApp` 或 `Common.kt` 中），设为：

```
http://100.101.102.103:8000
```

要点：

- **必须用 HTTP**：Android 9+ 默认禁止明文 HTTP。调试期可在 `AndroidManifest.xml` 的 `<application>` 加 `android:usesCleartextTraffic="true"`，或配置 `network_security_config.xml` 仅放行 Tailscale 网段。
- 手机与后端必须在同一个 Tailnet，且两台设备在线。
- 若手机与后端在同一局域网，也可直接用局域网 IP（`ipconfig` 查看）访问，速度更快。

## 常见问题

**端口被占用**：改端口 `uvicorn main:app --host 0.0.0.0 --port 8001 --reload`，Android 端 BASE_URL 同步修改。

**数据库位置**：`backend/smartbutler.db`（首次启动自动生成）。删除该文件可重置数据，重启服务自动重建表并重新预置 test1/test2。

**密码加密**：数据库存储的是 bcrypt 哈希，不是明文。

**生产部署注意**：`auth.py` 中 `SECRET_KEY` 为开发默认值，部署到公网前务必通过环境变量覆盖。
