# UI 资源清单

> 本文档面向负责 **UI 美化 + 吉祥物** 的（第 5 位）同学，列出需要修改的 UI 资源文件及修改要点。

## 1. 颜色（`app/src/main/res/values/colors.xml`）

| 资源名 | 用途 | 修改建议 |
| --- | --- | --- |
| `colorPrimary` | 主色调 | 统一为品牌主色（建议海鸥蓝/青） |
| `background` | 背景色 | 与主色调协调，注意明暗对比 |
| `text_primary` | 文字颜色 | 保证与背景色对比度足够 |
| `income` | 收入颜色 | 建议暖色系（如橙/黄） |
| `expense` | 支出颜色 | 建议冷色系（如红/蓝） |

## 2. 主题（`app/src/main/res/values/themes.xml`）

- 深色主题配置（如 `DayNight` 或自定义深色风格）
- 状态栏颜色：与应用主色调保持一致

## 3. 吉祥物（`app/src/main/res/drawable/ic_mascot.xml`）

- 替换为设计好的小鸥图标（Vector 格式）
- 注意：替换后需检查所有引用该 drawable 的布局与代码，确保可正常加载

## 4. 布局文件（`app/src/main/res/layout/*.xml`）

- 调整间距、圆角、字体大小、边距
- 统一卡片样式（圆角、阴影、内边距保持一致）

## 5. 图标（`app/src/main/res/drawable/ic_*.xml`）

- 底部导航图标
- FAB 图标
- 语音 / OCR / 社区图标

## 6. 字符串（`app/src/main/res/values/strings.xml`）

- 文案润色，统一语气与风格
- 注意：当前界面大部分文案为中文硬编码在布局/代码中，可一并规范化

---

## 修改流程建议

1. `git pull` 拉取最新代码
2. 按上述顺序逐项修改（先颜色/主题 → 再图标/吉祥物 → 后布局与文案）
3. 每项修改后编译运行，确认无白底白字 / 黑底黑字问题
4. 最终 `git add . && git commit -m "UI美化: xxx" && git push`
