# AGENTS.md — SmartButler（智能管家）

Android 记账应用 + FastAPI 本地后端：手动/语音/OCR 记账、图表统计、预算与预警、账号体系、云端同步、社区（发帖/评论/点赞）。

## 技术栈

### Android（`SmartButler/`）

- **语言**: Kotlin 1.9.24（`org.jetbrains.kotlin.android` / `kapt`）
- **构建**: Gradle 8.7（Groovy DSL），AGP 8.2.2；已有 `gradlew` / `gradlew.bat` wrapper
- **SDK**: `compileSdk 34` / `targetSdk 34` / `minSdk 26`，Java 17，`viewBinding` 开启
- **架构**: 传统 Activity/Fragment + View Binding + Room + Repository，非 Compose / 非 Hilt；无 ViewModel 层，数据操作直接走协程；另含 Retrofit 网络层 + 离线缓存/同步层
- **关键依赖**:
  - Room 2.6.1（runtime / ktx / compiler via kapt，当前库版本 v9）
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
- **表**: `users` / `transactions` / `posts` / `comments` / `likes`；扩展列见下
  - `users`: 含 `budget_json`（TEXT，分类→金额 JSON，默认 `{}`）、`is_data_public`（INTEGER）
  - `posts`: 含帖子级可见度 `visibility` / `data_visibility` / `budget_visibility`（String(20)，默认 `public`）
- **API**:
  - 认证（免 token）: `POST /api/register`、`POST /api/login`
  - 用户设置（需 Bearer token）: `PUT /api/users/settings`；预算 `GET /api/users/budget`（返回 `{"budget": {...}}`）、`PUT /api/users/budget`（整体覆盖保存，body `{"budget": {...}}`）
  - 交易（需 Bearer token）: `GET/POST /api/transactions`、`POST /api/transactions/sync`（按 localId 幂等增量同步）、`DELETE /api/transactions/{id}`
  - 社区: `GET /api/stats/public`（公开帖子流，免 token）、`GET /api/posts/mine`、`POST /api/posts`（按 用户+月份 幂等，更新同月旧帖）、`PUT /api/posts/{post_id}`（更新帖子/可见度，仅本人）、`POST /api/comments`、`GET /api/comments/{post_id}`、`POST /api/like/{post_id}`（点赞/取消切换）

## 目录结构

```
app/src/main/java/com/ousuan/smartbutler/
├── MainActivity.kt            # 入口，底部导航 + Fragment 容器
├── SmartButlerApp.kt          # Application：TokenManager/ApiConfig/NetworkMonitor/
│                              #   UserRepository/BudgetRepository/CommunityRepository 初始化、
│                              #   登录后下载数据、网络恢复自动同步
├── data/                      # 数据层
│   ├── Transaction.kt / TransactionDao.kt / AppDatabase.kt / TransactionRepository.kt
│   ├── BudgetEntity.kt / BudgetDao.kt   # Room budgets 表（(userId, category) 联合主键，按账号隔离）
│   ├── BudgetPrefs.kt         # 预算 SharedPreferences（未登录遗留/兜底，登录后迁移到账号）
│   ├── DataPublicPrefs.kt     # 全局数据公开开关（按用户 userId 独立存储）
│   ├── ProvinceFactors.kt     # 各省份生活成本系数（预算建议）
│   ├── CachedPost.kt / CachedPostDao.kt   # 帖子本地缓存（离线发布排队）
│   ├── PendingPost.kt / PendingPostDao.kt # 待同步帖子队列
│   ├── model/                 # 网络 DTO：User / CommunityPost / PostRequest / PublicStats /
│   │                          #   PublicStatsResponse / SyncRequest / TransactionRequest /
│   │                          #   CommentRequest / ApiResponse / Login/RegisterRequest /
│   │                          #   BudgetRequest(BudgetUpdateRequest/BudgetData) / UserSettingsRequest
│   ├── network/               # ApiConfig(服务器地址/接口路径) / ApiClient / ApiService /
│   │                          #   AuthInterceptor / TokenManager / NetworkChecker / NetworkMonitor /
│   │                          #   BaiduNlpCorrector
│   ├── repository/            # UserRepository（登录/注册/用户态）、BudgetRepository（预算云同步）、
│   │                          #   CommunityRepository（社区）
│   └── sync/SyncManager.kt    # 云同步：离线缓存 → 服务器，网络恢复自动增量同步（记录+帖子+预算）
├── ui/
│   ├── auth/                  # 登录 / 注册界面
│   ├── home/                  # 首页：收支列表 + 折线/饼图（ViewPager2）
│   ├── budget/BudgetFragment.kt    # 预算设置：打开从本地 Room 回填，保存先写本地再推服务器
│   ├── alert/AlertFragment.kt      # 超支/异常预警（读 BudgetPrefs）
│   ├── community/             # CommunityFragment（社区流+发帖，含帖子级可见度选择）/
│   │                          #   CommunityAdapter（卡片 + 我的帖子可见度开关）/
│   │                          #   MyPostsActivity（我的帖子，可改可见度）
│   ├── profile/ProfileFragment.kt  # 个人设置（含全局数据公开开关、我的帖子入口）
│   ├── settings/              # 设置页
│   ├── voice/                 # VoiceInputActivity + VoskSpeechRecognizer（离线语音记账）
│   ├── ocr/OcrInputActivity.kt     # OCR 记账（ML Kit 中文识别 + 金额/分类解析）
│   ├── widget/BarChartView.kt      # 自定义柱状图 View（预算分布/分类展示用）
│   └── mascot/                # 吉祥物相关资源/组件
└── util/
    ├── ParseUtils.kt          # 文本 → 金额/分类/备注 解析（语音与 OCR 共用）
    ├── Categories.kt          # 分类定义与默认图标/颜色（含 BUDGET 预算分类列表）
    ├── ExpenseAnalyzer.kt     # 支出统计分析逻辑
    ├── DateUtils.kt           # 日期工具
    └── Common.kt              # 通用常量/工具
```

资源：`app/src/main/res/layout/` 下 21 个布局（activity_main/login/register/settings/my_posts/voice/ocr、fragment_home/list/line/pie/budget/alert/profile/community、dialog_add_transaction、item_record/comment/community_post/budget_row/category_bar），图标 `@drawable/ic_mascot`。

## 构建与运行

- Android 构建：`.\gradlew assembleDebug`（命令行）；或 Android Studio 打开 `SmartButler/` 构建运行。
- 静态检查：`.\gradlew lint`（AGP 默认任务，扫描 UI/资源/权限问题）；IDE 内可看即时 lint 诊断。
- 测试：工程未配置 JUnit/仪器测试依赖（`app/build.gradle` 无 `testImplementation`），无单测任务；功能验证靠编译通过 + 后端回归脚本 + 真机 logcat 日志（按类名/标签过滤）。后端回归脚本 `backend/_verify_budget.py`（urllib 直测预算接口与迁移，无需 venv 依赖）。
- 后端启动见「技术栈 → 后端」；首次可 `python import_data.py` 导入测试数据（预置 test1/test2，密码 123456）。
- 服务器地址在 Android 端 `ApiConfig` 集中配置（可在设置页修改，默认指向局域网/Tailscale IP）；HTTP 明文依赖 `AndroidManifest.xml` 的 `usesCleartextTraffic="true"`。
- 权限：`RECORD_AUDIO`（语音）、`INTERNET`（网络/同步/社区）。

## 业务要点

- **记账字段**: 收入/支出类型、金额、分类（Categories 预定义）、备注、日期；新记录插入后立即触发云同步。
- **文本解析流程**: 语音（Vosk 离线）/ OCR（ML Kit）得到原始文本 → `ParseUtils` 提取金额与分类 → 用户确认后入库。
- **云同步**: 记录带 `local_id` 幂等上传；离线时帖子进入 PendingPost/CachedPost 队列，网络恢复自动补传；登录成功后自动下载服务器数据（记录 + 我的帖子 + 预算），支持换机/重装恢复。
- **预算（云端存储 + 本地优先）**: Room `budgets` 表（按 userId 隔离）为本地真源，服务器 `users.budget_json` 为云端备份。
  - 保存：`BudgetRepository.saveBudget()` **先无条件写 Room + BudgetPrefs（本地立即生效），再异步 `pushToServer`**（离线仅标记待同步，网络恢复 `syncPending` 补推）。
  - 加载：`BudgetFragment` 打开时 `loadSavedBudget()` 从 **Room 优先**读取回填（总额 + 滑块占比 + 方案展示），Room 空或未登录回退 `BudgetPrefs`，全程不依赖网络。
  - 调和：登录后 `fetchFromServer()` —— 服务器有则覆盖本地；服务器空本地有则保留并补传；两者均空但 BudgetPrefs 有遗留 → 迁移到账号并上传。
- **社区**: 帖子 = 用户 + 月份（幂等，同月重发会更新旧帖并保留首次创建时间）；每个帖子含两个独立模块 —— `category_breakdown`（消费数据）与 `budget_breakdown`（预算方案），两者皆空不能发布；支持评论、点赞、我的帖子管理。
- **公开可见度（已实现）**: 全局开关（`DataPublicPrefs`，按用户独立存储）+ 帖子级可见度（`visibility` / `data_visibility` / `budget_visibility`）。发布时在 `CommunityFragment` 选择，`MyPostsActivity` / `CommunityAdapter` 可随时编辑（`PUT /api/posts/{id}`）。服务器对非公开模块脱敏后再下发；模块私有则 Android 端隐藏对应卡片。
- **统计**: `ExpenseAnalyzer` 提供按月汇总、分类占比、趋势等，图表数据由 DAO 聚合查询提供。

## 网络与连通性判断（易踩坑）

- **接口能力判断 vs 真实连通性**: `NetworkMonitor.hasInternet()` 只检查 `NET_CAPABILITY_INTERNET`（**不要加 `NET_CAPABILITY_VALIDATED`**，局域网/Tailscale 无外网验证会被误判离线，导致云同步全链路短路）。真实连通性用 `NetworkChecker.checkServerAvailable()`（3s 探测服务器）兜底。
- **离线提示条**: 社区离线提示条**只由 `NetworkMonitor.isConnected()` 控制**，严禁混入公开开关状态或服务器探测结果（探测在弱网下抖动会污染提示条显隐）。
- **本地优先原则**: 任何保存（记账/预算/帖子）必须先在本地落库生效，网络失败仅标记待同步，绝不在网络判断处短路本地写入。

## 关联项目（不在本工程内）

父目录 `d:/lbt/smartledger/20260823090311/` 为独立的 Windows C++ 命令行工具（`expense_analyzer.cpp`、`budget_planner.cpp` 及其 Python 版本、`expense_data.json`），与 Android 应用共用记账业务思想，但无代码依赖，勿混用。

## 约定

- 界面字符串用中文硬编码于布局/代码中（未走 strings.xml 国际化，`app_name` 除外）。
- 后端 `smartbutler.db` 在 `backend/` 下，删除可重置数据；老库表结构变更优先仿照 `main.py` 的 `migrate_*` 做 ALTER 迁移（如 `migrate_posts_visibility` / `migrate_users_budget_json`），而非直接删库。
- 新功能优先复用 `util/` 已有解析逻辑与 `data/` 网络/同步层；数据库变更需同步更新 DAO 查询与 `AppDatabase` 版本（当前 v9）。
- 修改后保持 Java 17 + Kotlin 1.9 兼容，勿引入超出现有依赖范围的库。
