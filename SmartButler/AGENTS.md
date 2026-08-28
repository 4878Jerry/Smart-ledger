# AGENTS.md — SmartButler（智能管家）

Android 记账应用 + FastAPI 本地后端：手动/语音/OCR 记账、图表统计、预算与预警、账号体系、云端同步、社区（发帖/评论/点赞）。

## 技术栈

### Android（`SmartButler/`）

- **语言**: Kotlin 1.9.24（`org.jetbrains.kotlin.android` / `kapt`）
- **构建**: Gradle 8.7（Groovy DSL），AGP 8.2.2；已有 `gradlew` / `gradlew.bat` wrapper
- **SDK**: `compileSdk 34` / `targetSdk 34` / `minSdk 26`，Java 17，`viewBinding` 开启
- **架构**: 传统 Activity/Fragment + View Binding + Room + Repository，非 Compose / 非 Hilt；无 ViewModel 层，数据操作直接走协程；另含 Retrofit 网络层 + 离线缓存/同步层
- **关键依赖**:
  - Room 2.6.1（runtime / ktx / compiler via kapt）
  - MPAndroidChart v3.1.0（折线图、饼图）
  - ML Kit `text-recognition-chinese:16.0.0`（离线中文 OCR）
  - Retrofit 2.11 + OkHttp 4.12 + Gson 2.11（后端通信，含 AuthInterceptor 自动带 token）
  - Vosk `vosk-android:0.3.45` + JNA `5.14.0@aar`（离线语音识别，模型在 `assets/model`；JNA 必须用 @aar 变体，否则 libjnidispatch.so 缺失崩溃；vosk 需排除传递的 jna JAR）
  - AndroidX（appcompat、material、constraintlayout、recyclerview、viewpager2、fragment-ktx、lifecycle、coroutines）

### 后端（`d:/lbt/smartledger/backend/`，与 Android 工程同仓库的兄弟目录）

- **框架**: FastAPI + uvicorn（Python 3.10+），SQLAlchemy 2.0 异步 ORM + aiosqlite
- **认证**: JWT（7 天有效期）+ bcrypt 密码哈希；`auth.py` 的 `SECRET_KEY` 为开发默认值
- **数据库**: SQLite 文件 `backend/smartbutler.db`，启动时自动建表 + 轻量迁移（见 `main.py` 的 `migrate_*`，老库缺列时 ALTER 补列）+ 预置账号 test1/test2（密码 123456）
- **启动**: `cd backend && run.bat`（自动建 venv 装依赖）或 `uvicorn main:app --host 0.0.0.0 --port 8000 --reload`；Swagger 在 `/docs`
- **统一返回**: 成功 `{"code": 0, "data": ..., "msg": "success"}`；错误 code 非 0
- **表**: `users` / `transactions` / `posts` / `comments` / `likes`
- **API**:
  - 认证（免 token）: `POST /api/register`、`POST /api/login`
  - 交易（需 Bearer token）: `GET/POST /api/transactions`、`POST /api/transactions/sync`（按 localId 幂等增量同步）、`DELETE /api/transactions/{id}`
  - 社区: `GET /api/stats/public`（公开帖子流，免 token）、`GET /api/posts/mine`、`POST /api/posts`（按 用户+月份 幂等，更新同月旧帖）、`POST /api/comments`、`GET /api/comments/{post_id}`、`POST /api/like/{post_id}`（点赞/取消切换）

## 目录结构

```
app/src/main/java/com/ousuan/smartbutler/
├── MainActivity.kt            # 入口，底部导航 + Fragment 容器
├── SmartButlerApp.kt          # Application：TokenManager/ApiConfig/NetworkMonitor/
│                              #   CommunityRepository 初始化、登录后下载数据、网络恢复自动同步
├── data/                      # 数据层
│   ├── Transaction.kt / TransactionDao.kt / AppDatabase.kt / TransactionRepository.kt
│   ├── BudgetPrefs.kt         # 预算设置（SharedPreferences）
│   ├── DataPublicPrefs.kt     # 全局数据公开开关（按用户 userId 独立存储）
│   ├── ProvinceFactors.kt     # 各省份生活成本系数（预算建议）
│   ├── CachedPost.kt / CachedPostDao.kt   # 帖子本地缓存（离线发布排队）
│   ├── PendingPost.kt / PendingPostDao.kt # 待同步帖子队列
│   ├── model/                 # 网络 DTO：User / CommunityPost / PostRequest / PublicStats /
│   │                          #   PublicStatsResponse / SyncRequest / TransactionRequest /
│   │                          #   CommentRequest / ApiResponse / Login/RegisterRequest
│   ├── network/               # ApiConfig(服务器地址) / ApiClient / ApiService / AuthInterceptor /
│   │                          #   TokenManager / NetworkChecker / NetworkMonitor / BaiduNlpCorrector
│   ├── repository/            # UserRepository（登录/注册/用户态）、CommunityRepository（社区）
│   └── sync/SyncManager.kt    # 云同步：离线缓存 → 服务器，网络恢复自动增量同步
├── ui/
│   ├── auth/                  # 登录 / 注册界面
│   ├── home/                  # 首页：收支列表 + 折线/饼图（ViewPager2）
│   ├── budget/BudgetFragment.kt    # 预算设置与使用进度
│   ├── alert/AlertFragment.kt      # 超支/异常预警
│   ├── community/             # CommunityFragment（社区流+发帖）/ CommunityAdapter /
│   │                          #   MyPostsActivity（我的帖子，可改可见度）
│   ├── profile/ProfileFragment.kt  # 个人设置（含全局数据公开开关）
│   ├── settings/              # 设置页
│   ├── voice/                 # VoiceInputActivity + VoskSpeechRecognizer（离线语音记账）
│   └── ocr/OcrInputActivity.kt     # OCR 记账（ML Kit 中文识别 + 金额/分类解析）
└── util/
    ├── ParseUtils.kt          # 文本 → 金额/分类/备注 解析（语音与 OCR 共用）
    ├── Categories.kt          # 分类定义与默认图标/颜色
    ├── ExpenseAnalyzer.kt     # 支出统计分析逻辑
    ├── DateUtils.kt           # 日期工具
    └── Common.kt              # 通用常量/工具
```

资源：`app/src/main/res/layout/` 下 21 个布局（activity_main/login/register/settings/my_posts/voice/ocr、fragment_home/list/line/pie/budget/alert/profile/community、dialog_add_transaction、item_record/comment/community_post/budget_row/category_bar），图标 `@drawable/ic_mascot`。

## 构建与运行

- 推荐 Android Studio 打开 `SmartButler/` 构建运行；命令行 `.\gradlew assembleDebug`。
- 后端：`cd d:\lbt\smartledger\backend && run.bat`，默认 `http://localhost:8000`。
- 服务器地址在 Android 端 `ApiConfig` 集中配置（可在设置页修改，默认指向局域网/Tailscale IP）；HTTP 明文依赖 `AndroidManifest.xml` 的 `usesCleartextTraffic="true"`。
- 权限：`RECORD_AUDIO`（语音）、`INTERNET`（网络/同步/社区）。

## 业务要点

- **记账字段**: 收入/支出类型、金额、分类（Categories 预定义）、备注、日期；新记录插入后立即触发云同步。
- **文本解析流程**: 语音（Vosk 离线）/ OCR（ML Kit）得到原始文本 → `ParseUtils` 提取金额与分类 → 用户确认后入库。
- **云同步**: 记录带 `local_id` 幂等上传；离线时帖子进入 PendingPost/CachedPost 队列，网络恢复自动补传；登录成功后自动下载服务器数据（记录 + 我的帖子），支持换机/重装恢复。
- **社区**: 帖子 = 用户 + 月份（幂等，同月重发会更新旧帖并保留首次创建时间）；每个帖子含两个独立模块 —— `category_breakdown`（消费数据）与 `budget_breakdown`（预算方案），两者皆空不能发布；支持评论、点赞、我的帖子管理。
- **公开可见度（进行中）**: 已存在全局开关（`DataPublicPrefs`，ProfileFragment 中按用户独立存储）。帖子级可见度（`visibility` / `data_visibility` / `budget_visibility`）后端字段与接口、Android 端发布/编辑 UI **尚未实现**，属待办事项。
- **预算**: `BudgetPrefs` 存预算，`ProvinceFactors` 按省份给出建议预算系数。
- **统计**: `ExpenseAnalyzer` 提供按月汇总、分类占比、趋势等，图表数据由 DAO 聚合查询提供。

## 关联项目（不在本工程内）

父目录 `d:/lbt/smartledger/20260823090311/` 为独立的 Windows C++ 命令行工具（`expense_analyzer.cpp`、`budget_planner.cpp` 及其 Python 版本、`expense_data.json`），与 Android 应用共用记账业务思想，但无代码依赖，勿混用。

## 约定

- 界面字符串用中文硬编码于布局/代码中（未走 strings.xml 国际化，`app_name` 除外）。
- 后端 `smartbutler.db` 在 `backend/` 下，删除可重置数据；老库表结构变更优先仿照 `main.py` 的 `migrate_*` 做 ALTER 迁移，而非直接删库。
- 新功能优先复用 `util/` 已有解析逻辑与 `data/` 网络/同步层；数据库变更需同步更新 DAO 查询与 `AppDatabase` 版本。
- 修改后保持 Java 17 + Kotlin 1.9 兼容，勿引入超出现有依赖范围的库。
