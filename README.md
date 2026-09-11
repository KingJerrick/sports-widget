# 赛事日历 · Android 桌面小组件

一个 4×2 的安卓桌面小组件，显示**未来七天**的 F1、MotoGP、CS2 猎鹰、皇马、IG 赛程。
每场比赛是一张卡片：**左边是队伍/赛事的标，右边是「哪一站什么比赛」，往下滑看更多。**

```
┌──────────────────────────────────────────────────────┐
│ ▍今 六 日 一 二 三 四              9/11 09:38    ↻  │
├──────────────────────────────────────────────────────┤
│ ╭────╮ F1 · 西班牙站                今天 19:30      │
│ │ F1 │ FP1 · FP2 · FP3 · 排位 · 正赛                 │
│ ╰────╯                                               │
│ ╭────╮ MotoGP · 圣马力诺站          今天 21:00      │
│ │ GP │ FP1 · 练习 · 排位 · 冲刺 · 正赛               │
│ ╰────╯                                               │
│ ╭────╮ 皇马 · LaLiga                周日 03:00      │
│ │皇马│ vs 巴列卡诺                                   │
│ ╰────╯          ↓ 往下滑看更多                       │
└──────────────────────────────────────────────────────┘
```

**顶栏那一排是七天迷你条**：有比赛的日子底下有一层浅浅的底色，今天最重。
卡片流回答「比什么赛」，这条回答「哪天有比赛」—— 两个不同的问题。

**一个比赛周末合成一张卡**，不是一天一张。F1 西班牙站的 FP1/FP2/FP3/排位/正赛
是同一张卡，第二行把它们按时间列出来；队伍类一场比赛就是一张卡，第二行是对手。

| 标记 | 项目 | 卡片标题 | 第二行 |
|---|---|---|---|
| 🔴 `F1` | F1 | `F1 · 西班牙站` | 各场次：`FP1 · FP2 · 排位 · 正赛` |
| 🟠 `GP` | MotoGP | `MotoGP · 圣马力诺站` | 各场次（Moto2/Moto3 已滤掉） |
| 🟣 `猎鹰` | CS2 · Team Falcons | `猎鹰 · BLAST Open` | 对手 |
| 🟢 `皇马` | 足球 · 皇家马德里 | `皇马 · LaLiga` | 对手 |
| 🔵 `IG` | 英雄联盟 · Invictus Gaming | `IG · LPL 淘汰赛` | 对手 |

卡片左侧的图标**由你自己在 App 里上传**（见下面「四、配置」的第 ④ 段），
没上传就显示上表里的字母块。

---

## ⚠️ 先读这一节：数据是从哪来的

**五个源全部免注册**，不需要任何 API Key。但代价要说清楚：

| 项目 | 源 | 性质 |
|---|---|---|
| F1 | [Jolpica](https://api.jolpi.ca/ergast/f1/2026/races.json?limit=100) | 🟢 Ergast 的社区继任者，结构稳定 |
| MotoGP | `api.motogp.pulselive.com` | 🔴 **网页私有接口**，无文档无承诺 |
| CS2 · 猎鹰 | `api.sofascore.com` | 🔴 **网页私有接口** |
| 足球 · 皇马 | `api.sofascore.com` | 🔴 同上（一个源覆盖两类） |
| 英雄联盟 · IG | `esports-api.lolesports.com` | 🟡 Riot 官方数据，但用的是网页公开 key，可能轮换 |

**五个源里三个是网页私有接口**，哪天某个源改了结构或者开始封爬虫，那一类就会没数据。这是选「免注册」付出的代价 —— 用注册账号换来的官方 API 会稳得多。

好消息是这个风险**被架构吸收掉了**：抓取跑在 GitHub Actions 上，源变了只需要改 `tools/fetch_calendar.py` 里对应的那一个函数、手动触发一次 workflow，**手机上的 App 完全不用动**。

### 抓取是云端做的，手机只请求一个文件

```
GitHub Actions（每 6 小时 + 手动触发）
  └─ tools/fetch_calendar.py ──抓──▶ 五个源
        └─ 归一化 → data/calendar.json ──commit──▶ 仓库
                                                      │
手机 ──GET──▶ ① 自定义地址（如果填了）
              ② cdn.jsdelivr.net      ← 逐个试，第一个成功就停
              ③ raw.gitmirror.com
              ④ raw.githubusercontent.com
   ├─ 成功 → 裁出窗口存进本地缓存
   ├─ 全失败 → 用上次的缓存
   └─ 再失败 → 用 APK 内置的快照（assets/calendar.json）
```

这么分有三个好处：**改解析逻辑不用重装 APK**、手机只发一个请求省电、以及抓取永远发生在 GitHub 的服务器上，你的手机 IP 不会因为轮询被赛事站点风控。

---

## 一、准备环境（二选一）

这个工程需要编译成 APK 才能装到手机上。

### 路线 A：用 Android Studio（推荐，能实时调试）

1. 下载安装 [Android Studio](https://developer.android.com/studio)，安装时勾选 **Android SDK**。
2. 首次启动让它自动下载 SDK（约 2–3 GB）。
3. `File → Open`，选择本工程根目录 `sports-widget`（**不是** `app` 子目录）。
4. 右下角会提示 Gradle Sync，等它跑完。首次会下载 Gradle 8.7 和依赖，需要能访问 `dl.google.com` 和 `repo.maven.apache.org`，国内网络可能需要代理。

### 路线 B：没有 Android Studio，用 GitHub Actions 免费打包

1. 在 GitHub 上新建一个**公开**仓库 `sports-widget`（私有仓库的话 jsDelivr 取不到数据）。
2. 把本目录所有文件推上去（`.github/workflows/` 两个文件要一起推，别被 `.gitignore` 排除）。
3. 推上去后自动触发构建；也可以到 **Actions → Build APK → Run workflow** 手动触发。
4. 构建完成后在该次运行的 **Artifacts** 里下载 `sports-widget-debug`，解压就是 APK。

> 用的是 debug 签名，可以直接安装，但不能上架应用商店 —— 自用完全够。

---

## 二、编译出 APK

**Android Studio：** 菜单 `Build → Build Bundle(s) / APK(s) → Build APK(s)`。
APK 在 `app/build/outputs/apk/debug/app-debug.apk`。

**命令行：** 工程里带了 `gradle-wrapper.properties`，但没有 wrapper 的可执行 jar（二进制没法直接生成）。二选一：

```bash
gradle assembleDebug                                   # 方式 1：本机装了 Gradle 8.7+
gradle wrapper --gradle-version 8.7 && ./gradlew assembleDebug   # 方式 2：先生成 wrapper
```

---

## 三、安装到手机

1. 把 `app-debug.apk` 传到手机（微信文件传输 / USB / 网盘都行）。
2. 手机上点开 APK，按提示给文件管理器或浏览器**允许安装未知应用**的权限。
3. 装完桌面会出现「赛事日历」图标。

> 部分手机会有「纯净模式」/「外部来源应用检测」拦截（小米、华为常见），需要在设置里临时关闭或选「仍要安装」。

---

## 四、把它加到桌面

长按桌面空白处 → 小部件 / 小组件 → 找到「赛事日历」→ 拖到桌面。

**第一次加到桌面时可能是空的**，同时顶栏显示「尚未获取数据」—— 这是正常的，后台任务正在拉数据，等十几秒或点一下顶栏上的 ↻ 就会出来。

### 交互

| 点哪里 | 做什么 |
|---|---|
| 整条**顶栏**（含 ↻ 图标） | 立刻刷新，顶栏先变成「刷新中…」 |
| **任意一张卡片** | 打开 App 的完整赛程页 |
| 卡片列表区**上下滑** | 看后面几天的比赛 |

> 刷新是挂在**整条顶栏**上的，不是那个 18dp 的小图标 —— 18dp 低于安卓建议的 48dp 最小触控区，单独挂上去的话边缘点击会被启动器的缩放手柄吃掉。

### 尺寸

目标是 4×2。**宽度锁死了不允许缩**（`minResizeWidth=250dp`）：卡片那一行要塞下
「图标 + 标题 + 时间」，再窄就只能显示省略号了。

高度可以在 110–180dp 之间调。**拖得越高，一屏能看到的卡片越多**：

| 高度 | 可见卡片 |
|---|---|
| 110dp（4×2） | 约 1.7 张，其余往下滑 |
| 150dp | 约 2.7 张 |
| 180dp（上限） | 约 3.4 张 |

列表本身是可滚动的，所以卡片的数量不受高度限制 —— 高度只影响「不用滑能看到几张」。

---

## 五、配置

打开「赛事日历」App，从上到下四段：

- **① 未来七天赛程** —— 按天分组的完整列表，每场都平铺开（小组件那边会把一个比赛周末合成一张卡）
- **② 颜色图例** —— 五个颜色分别对应什么，以及当前用的是图标还是字母块
- **③ 数据源状态** —— 每个源这次抓到没有、抓了多少场、失败原因。哪一类突然不显示了，先看这里
- **④ 卡片图标** —— 给每个分组传一张图（见下）
- **⑤ 设置** —— 数据地址和刷新间隔，加一个「测试连接」自检

### 给卡片传图标

小组件卡片左侧那块图，**是你自己传的，程序不去网上找**。在 App 的 ④ 里，每个分组一行，
点「选图」从相册挑一张，点「清除」退回字母块。

- 建议用**正方形、图案清晰**的图，透明底 PNG 最好（白底的图放在卡片上会有一个白方块）
- 选中后会立刻缩到 96×96 存进 App 私有目录，**不占你相册、也不需要存储权限**
  （系统只把挑中的那一个文件临时授权给 App，不是打开整个相册）
- 换一张随时可以，小组件会立刻更新

**为什么不自动去网上拉队标**：试过，不行。拉回来的图明暗不一定配卡片底色 ——
F1 和 MotoGP 的标是白色/浅色的，本来就是给深色背景用的，放到浅色卡片上就看不清；
皇马队徽的白底又和卡片融在一起。各家给的尺寸、留白、比例也都不一样，排成一列参差不齐。
来源本身还不稳（TLS 指纹风控、403、给的还是 Android 解不了的 SVG）。
自己放一张满意的图，放什么就是什么。

### 刷新间隔

默认 **6 小时**，跟后端抓取的节奏一致。**刷得更勤并不会更新鲜** —— 后端每 6 小时才重新生成一次 `calendar.json`，中间刷多少次拿到的都是同一份。

### 数据超过 24 小时没更新会怎样

顶栏那行会变成琥珀色的 `⚠ 9/08 09:38 更新`。

注意顶栏显示的是**数据生成时间**（后端写进 JSON 的那个时间），不是手机最后拉取的时间。这个区别很关键：Actions 挂掉之后，手机每 6 小时照样能**成功**拉到同一份旧数据（CDN 有缓存，HTTP 200，一切正常）—— 如果显示「最后拉取时间」，界面上永远看着是新鲜的，「后端停了」这件事会被完全掩盖。

### 自定义数据地址

`cdn.jsdelivr.net` 在国内时不时会抽风。如果你挂了代理、或者自己搭了镜像，把地址填进「自定义数据地址」框里，它会**优先于**三个默认地址被使用，不用改代码重新打包。

**填哪种地址？** 三种都行：

```
✅ https://raw.githubusercontent.com/<用户名>/<仓库>/main/data/calendar.json
✅ https://cdn.jsdelivr.net/gh/<用户名>/<仓库>@main/data/calendar.json
⚠️ https://github.com/<用户名>/<仓库>/blob/main/data/calendar.json
```

第三种是**浏览器地址栏里那个**，看着最像但**是错的** —— 它返回的是 GitHub 的 HTML 页面（约 78 万字节），不是数据。

不过这个坑太容易踩（在浏览器里打开文件、复制地址栏，是最自然的操作），所以 App **会自动把它转成 raw 地址**，App 里点「测试连接」会显示转换前后的对照。真要填别的网页地址，会得到一句明确的提示，而不是一句「解析失败」。

### 「测试连接」会告诉你什么

真的拉一次，然后打出来：试了哪些地址、哪个成功了、多少字节、各类别多少场、**未来七天会显示成什么**（按 3 条算），以及返回 JSON 的结构清单。数据出问题时先点它。

---

## 六、常见问题

| 现象 | 原因 / 怎么办 |
|---|---|
| **卡片左侧是彩色字母块，不是图标** | 你还没给这个分组传图。打开 App → ④ 卡片图标 → 给对应分组点「选图」。**这不是故障**，不传也能用 |
| 列表滑不动 | 部分国产启动器对「可滚动小组件」支持不好（这是安卓自己的老问题）。拖高一点能多看到几张卡，或者打开 App 看完整列表 |
| 桌面显示「载入小组件时出现问题」 | 布局 inflate 失败。多半是有人把 `widget_card.xml` 里的框架控件换成了 AppCompat 的 —— 那种控件的方法不带 `@RemotableViewMethod`，RemoteViews 反射调用会直接抛异常 |
| 加到桌面后一直空白 | 点一下顶栏的 ↻；还不行就打开 App 看 ③ 数据源状态 |
| 某一类突然没有卡片了 | 大概率是那个源挂了。看 App 的 ③，失败原因会写在那儿 |
| **从来没有猎鹰的卡片** | **正常现象**。猎鹰 CS2 分队的比赛本来就稀疏（2026 年 9 月 BLAST 打完、FISSURE 和 StarLadder 都没参加）。已经做了对照验证：同样调接口，Legacy、MIBR 都能返回比赛，只有猎鹰是 0 场 |
| 卡片上的时间一直不往前滚 | 跨零点时「今天/明天」要靠系统广播重算。看顶栏的时间有没有往前走（没走说明后端停了）|
| 数据不刷新 | ① 看顶栏的时间有没有往前走 ② 看 App 里 ③ 的状态 ③ 试着在设置里填个自定义地址 |
| 切了深色模式，小组件还是浅色 | 已知问题。小组件重施的是缓存的视图树，很多启动器（尤其 MIUI）缓存得很凶。**没有干净的即时钩子** —— `ACTION_CONFIGURATION_CHANGED` 官方明确不能通过 manifest receiver 接收。打开一次 App 就会重画，或者等下一次周期刷新 |
| 一整天没收到数据 / 后台不跑 | 国产 ROM 的「自启动」「后台运行」权限没给。设置 → 应用管理 → 赛事日历 → 允许自启动 |
| Android 11 及以下，「已结束」的场次不压暗 | 已知限制。`View.setAlpha` 到 **API 31** 才支持在 RemoteViews 里调用，低版本调了会让整个小组件崩掉，所以做了版本判断。App 里的赛程列表两种版本都会标「已结束」 |

---

## 七、工程结构

```
sports-widget/
├── .github/workflows/
│   ├── build-apk.yml            # 打包 APK（失败时把错误写进 build-error.md）
│   └── update-calendar.yml      # 每 6 小时抓赛程并提交
├── build-error.md               # 只在构建失败时出现，记录当时的编译错误
├── tools/fetch_calendar.py      # 聚合脚本（只用标准库 + curl）
├── data/
│   ├── config.json              # 追哪些队伍 —— 改这个换队，不用碰 App
│   └── calendar.json            # 抓取产物
└── app/src/main/
    ├── assets/calendar.json     # 打进 APK 的兜底快照
    ├── java/com/dailywork/sportswidget/
    │   ├── Model.kt             # Event / Card / CalendarData / Cat 枚举 + 时间工具
    │   ├── CalendarParser.kt    # JSON -> 模型，卡片怎么归并，自检的结构清单
    │   ├── CalendarClient.kt    # 多端点兜底链 + 三级降级
    │   ├── LogoStore.kt         # 用户上传的卡片图标：缩放 / 存盘 / 内存缓存
    │   ├── Prefs.kt             # object Prefs + SharedPreferences
    │   ├── RefreshWorker.kt     # Worker + Scheduler + 刷新编排
    │   ├── WidgetProvider.kt    # WidgetProvider + WidgetRenderer（顶栏 + 七天条 + 接列表）
    │   ├── WidgetService.kt     # RemoteViewsService + 卡片工厂（可滚动的关键）
    │   └── MainActivity.kt      # 赛程页 + 图例 + 数据源状态 + 设置
    └── res/
        ├── layout/widget_sports.xml          # 主布局：顶栏 + 七天条 + ListView（顶部有尺寸账）
        ├── layout/widget_card.xml            # 一张卡片（含「为什么放两个控件叠着」的说明）
        ├── layout/widget_sports_preview.xml  # 选择器预览用的静态假数据版
        └── values/ values-night/             # 配色两套，逐项对应
```

### 改关注对象：`data/config.json`

想换队伍（比如把皇马换成巴萨）**不用碰代码、不用重装 App**：

1. 在 GitHub 网页上编辑 `data/config.json`
2. 改 `football.sofascoreId`（用 `https://api.sofascore.com/api/v1/search/all?q=Barcelona` 查 id，免注册）
3. 等下一次定时抓取，或者去 Actions 手动触发 `update-calendar`

> ⚠️ 猎鹰有两个 id：`409766` 是 **CS2** 分队，`498383` 是 **Dota 2** 分队。填错会拿回一堆 Dota 比赛。

### 改配色

颜色全在 `values/colors.xml` 和 `values-night/colors.xml`，两边名字一一对应。布局和代码里只写 `@color/xxx`，**不写深浅色分支逻辑** —— 由资源系统按系统模式自动选。

改类别色时注意 4.5:1 的对比度下限（7dp 的小字必须过这条线）。现在这套是浅色模式「深色块 + 白字」、深色模式「亮色块 + 近黑字」，两组都验过。

---

## 八、技术选型备忘

**RemoteViews 而不是 Jetpack Glance。** Glance 要引入 Compose 全家桶，而这个小组件只需要文本和色块，用 RemoteViews 编译更快、在国产 ROM 上兼容性也更好。

**零第三方依赖。** 网络用 `HttpURLConnection`，JSON 用 `org.json`，后台任务用 WorkManager（AndroidX 的一部分）。整个 APK 除了三个 AndroidX 库没有别的。

**字号用 `dp` 而不是 `sp`。** `sp` 是在**启动器的 Configuration** 里解析的（布局由启动器 inflate），所以系统的「字体大小」设置百分之百会影响小组件。Android 14 又把非线性字体缩放的上限拉到 200%，且小于 20sp 的文字按满额放大 —— 7sp 在 fontScale 1.5 下会变成 10.5sp。而小组件**不会滚动、也不会自己长高，溢出就是被裁掉**。用 `dp`（乘 density、不乘 scaledDensity）可以完全免疫。代价是不再跟随无障碍字号设置，对固定高度的小组件这是必要取舍。

**21 个 chip 全部写死在布局里，不用 `addView` 动态挂。** `addView` 会让每次更新产生 21 次嵌套 inflate，全部发生在启动器主线程上（MIUI / EMUI 的启动器本来就重），而且嵌套根布局的 `layout_margin` 在部分启动器上会被丢掉。写死之后渲染只剩 `setTextViewText` / `setViewVisibility` / `setInt` 这些一等公民方法。

**chip 换色用 5 个预置 drawable 切 `setBackgroundResource`，不用运行时 tint。** `RemoteViews.setColorStateList` 是 **API 31 才公开**的，之前只是 `@hide`；`View.setBackgroundTintList` 也没有 `@RemotableViewMethod` 注解。`compileSdk = 34` 所以**编译一定过，死在运行期** —— 轻则这次 update 根本没执行、桌面停在旧布局，重则启动器反射失败直接显示「载入小组件时出现问题」。详见 `drawable/bg_pill_f1.xml` 的注释。

**深色模式用 `values-night` 而不是在代码里判断。** 代价是切换后要等桌面重新加载布局才变色。

**一个比赛周末合成一张卡，不是一天一张。** F1 西班牙站的 FP1/FP2/FP3/排位/正赛是**一张**卡（标题「F1 · 西班牙站」，第二行列各场次），而不是五张各说各话的卡 —— 一个周末本来就是一回事，拆开会把列表撑得很长、还看不出它们的关系。队伍类没有这个层级，一场比赛一张卡。分组在后端做（`tools/fetch_calendar.py` 的 `group` 字段）。

**卡片第二行放不下时会按重要性取舍，不是简单截断。** MotoGP 一个周末有 FP1/练习/FP2/Q1/Q2/冲刺/热身/正赛八节，全列出来一行放不下。规则是：先去重（Q1、Q2 都叫「排位」），还超就保正赛和排位、让练习赛让位（正赛 3 > 排位 2 > 练习 1）。直接截断的话会把最重要的那几节切掉，而且不报错、不崩，只是看不见。

**可滚动用 ListView + RemoteViewsService 实现。** 安卓的小组件只有 `ListView`/`GridView` 这类「集合控件」能滚动，而且行内容不能直接塞进 RemoteViews，得由一个 `RemoteViewsService` 逐行提供。见 `WidgetService.kt`。顶栏（品牌条 / 七天迷你条 / 状态 / 刷新）是 ListView 外面的普通控件，这在这个模式里是合法的。

**卡片图标由用户自己上传，程序不去网上找。** 试过自动拉队标，三个问题凑在一起没法看：拉回来的图**明暗不一定配卡片底色**（F1 和 MotoGP 的标是白色/浅色的，本来就是给深色背景用的，放到浅色卡片上就看不清，皇马队徽的白底又和卡片融在一起）、各家给的尺寸留白比例都不一样排成一列参差不齐、来源本身还不稳（TLS 指纹风控、403、给的还是 Android 解不了的 SVG）。所以改成：App 里有个「卡片图标」区，每个分组传一张，存本机 `filesDir/logos/`，传了用图、没传用字母块。

上传的图会先缩到 96px 再存。**这一步不能省**：`setImageViewBitmap` 会把整张位图塞进 Binder 事务（全进程共享约 1MB），用户随手挑一张几 MB 的照片，解码后好几 MB，直接就是 `TransactionTooLargeException` —— 表现是小组件不更新，而且报错信息完全指不到这里。

**Sofascore 单独走 curl 子进程。** 它对客户端 **TLS 指纹**做风控，不看 UA 也不看 IP。实测（同一台机器、同一时刻、同一个 URL）：`curl` 返回 200、`curl --http1.1` 返回 200、`curl -A "Python-urllib/3.12"` 也返回 200，但 Python 的 urllib 换什么请求头都是 403。所以只能让这一个源走 curl。

---

## 参考

- [Jolpica F1 API](https://api.jolpi.ca/ergast/)（Ergast 的社区继任者）
- [Sofascore](https://www.sofascore.com/)（接口为站点前端私有 API，无公开文档）
- [LoL Esports](https://lolesports.com/schedule)
