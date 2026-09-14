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

**一个「整体」合成一张卡**，不是一天一张。F1 西班牙站的 FP1/FP2/FP3/排位/正赛
是同一张卡，第二行把它们按时间列出来。

队伍类里**只有棒球要归并**：道奇一周打 6 场，一场一张卡的话 7 天窗口里它一个人
就占 7 张，把 F1、MotoGP 这些一周只有一场的全挤到列表底下（实测过）。
所以 MLB 的一个系列赛（同一对手、同一主客场、连着打 3~4 天）并成一张卡 ——
「一个整体」在这里是系列赛。足球/篮球/CS2/LoL 都是一场一张卡，它们本来就没这么密。

| 标记 | 项目 | 卡片标题 | 第二行 |
|---|---|---|---|
| 🔴 `F1` | F1 | `F1 · 阿塞拜疆站` | 各场次：`FP1 · FP2 · FP3 · 排位 · 正赛` |
| 🟠 `GP` | MotoGP | `MotoGP · AUT站` | 各场次（Moto2/Moto3 已滤掉） |
| 🟢 `皇马` | 足球 · 皇家马德里 | `皇马 · 西甲` | `vs 巴萨` |
| 🔷 `道奇` | 棒球 · 洛杉矶道奇 | `道奇 · @ 红人` | `vs 红人 · 4 连战` |
| 🟡 `勇士` | 篮球 · 金州勇士 | `勇士 · NBA` | `vs 湖人` |
| 🟣 `猎鹰` | CS2 · Team Falcons | `猎鹰 · BLAST Premier` | `vs NAVI` |
| 🔵 `IG` | 英雄联盟 · Invictus Gaming | `IG · LPL 淘汰赛` | `vs AL` |

> 棒球的卡片标题用北美体育的记法：`@ 红人` 是客场，`vs 巨人` 是主场 —— 一眼能看出
> 这组系列赛是在谁家打的。同一对手在主客场的两组系列赛是**两张卡**，不会并在一起。

卡片左侧的图标**由你自己在 App 里上传**（见下面「四、配置」的第 ④ 段），
没上传就显示上表里的字母块。

---

## ⚠️ 先读这一节：数据是从哪来的

**七个类别，六个源是官方或注册制，只剩一个私有接口。**

| 项目 | 源 | 性质 | 要 key 吗 |
|---|---|---|---|
| F1 | [OpenF1](https://openf1.org) `api.openf1.org` | 🟢 有文档，整年全部场次（含练习赛/排位/冲刺） | 不要 |
| MotoGP | `api.motogp.pulselive.com` | 🔴 **网页私有接口**，无文档无承诺 | 不要 |
| 足球 · 皇马 | [football-data.org](https://www.football-data.org) | 🟢 注册制，西甲 + 欧冠都在免费档 | **要** |
| 棒球 · 道奇 | [MLB Stats API](https://statsapi.mlb.com) `statsapi.mlb.com` | 🟢 **MLB 官方接口**，没有比它更权威的 | 不要 |
| 篮球 · 勇士 | [balldontlie](https://balldontlie.io) `api.balldontlie.io` | 🟢 注册制，免费档 5 次/分钟 | **要** |
| CS2 · 猎鹰 | [PandaScore](https://www.pandascore.co) `api.pandascore.co` | 🟢 注册制，免费档 1000 次/小时 | **要** |
| 英雄联盟 · IG | `esports-api.lolesports.com` | 🟡 Riot 官方数据，但用的是网页公开 key，可能轮换 | 不要 |

### 为什么从「全部免注册」改成了「六个注册制」

2026 年 9 月，**足球和 CS2 一起挂了**：Sofascore 的网页私有接口对 GitHub Actions 的出口 IP 返回 `403 Forbidden`。同一份代码、同一个 UA，9 月 11 日那次 CI 还是好的，9 月 12 日就全红。在本机（住宅 IP）上同一个请求是 200 —— 这是按 TLS 指纹 / 出口 IP 做的风控。

这正是「用私有接口换免注册」的代价，所以能换的都换了。换来的是**有文档、有承诺、key 走 header** 的接口。

**为什么 MotoGP 没换**：市面上没有能长期用的注册制 MotoGP 接口 —— Sportradar 有 MotoGP v2，但只有 30 天试用、到期断供，正式接入要走企业销售合同；ESPN 不覆盖 MotoGP；TheSportsDB 免费档搜不到。所以它继续留在 Pulselive，在下面单独标成已知风险源。

好消息是**这个风险被架构吸收掉了**：抓取跑在 GitHub Actions 上，源变了只需要改 `tools/fetch_calendar.py` 里对应的那一个函数、手动触发一次 workflow，**手机上的 App 完全不用动**。

### ⚠️ 已知风险源：MotoGP

`api.motogp.pulselive.com` 是 motogp.com 自己前端在调的接口，无文档无承诺。它在 2026-09 那轮风控里没被封，但那只是运气。

**它挂掉的表现**：主界面「数据源状态」里 MotoGP 那行变红，`sources.motogp.error` 写着 HTTP 状态码或解析错误。

**挂掉怎么办**，按代价从低到高：
1. 到 [OpenF1 的 issue 区](https://github.com/br-g/openf1/issues) / [jolpi.ca](https://api.jolpi.ca) 看看有没有人做 MotoGP 的社区镜像 —— F1 当年从 Ergast 转 Jolpica 就是这么过来的
2. 接口改版了：对着 `tools/fetch_calendar.py` 里 `fetch_motogp` 上面的注释核字段（那里记着两个已经踩过的坑）
3. 实在没有源了：`data/config.json` 里把 `motogp` 的 `enabled` 改成 `false`，其余六个类别不受影响

### 已排除的源（别再试了）

都是实测过的，记在这里免得以后重复踩：

| 源 | 结果 |
|---|---|
| `stats.nba.com` | 带全套请求头返回 **200，但响应体是 NBA.com 的首页 HTML** —— 反爬墙。看状态码会以为成功了 |
| `cdn.nba.com` | 403（住宅 IP 也 403） |
| `data.nba.net` | 证书错误，已废弃 |
| ESPN `site.api.espn.com` | 能用（实测能拿勇士整季 80 场），但它是**无文档的私有接口**，和 Sofascore 同性质，只是暂时没被封 |
| TheSportsDB 免费档 | 几乎是空的：`all_leagues` 只返回 5 条，搜 MotoGP 返回 null |
| Sportradar MotoGP v2 | 30 天试用后断供，正式接入需联系销售，企业定价 |
| api-sports.io 的 F1 | 只给正赛，一个周末的练习赛/排位全没有 |
| Jolpica（原来的 F1 源） | 能用且稳定，但 OpenF1 的场次字段更全，换掉了 |

### 配置 API Key（三个注册制源要用）

**Key 只以 GitHub Actions Secret 的形式存在，仓库里一个都不留。** 手机端更是完全碰不到 key —— 它下载的还是那一个公开的 `calendar.json`，所以**换 key、换源都不用重装 APK**。

**在 GitHub 上配**（CI 用的就是这条路径）：

1. 去三家注册，拿到 key：

   | 变量名 | 去哪注册 | 免费档 |
   |---|---|---|
   | `FOOTBALL_DATA_TOKEN` | [football-data.org](https://www.football-data.org/client/register) | 10 次/分钟 |
   | `PANDASCORE_TOKEN` | [app.pandascore.co](https://app.pandascore.co/signup) | 1000 次/小时 |
   | `BALLDONTLIE_KEY` | [app.balldontlie.io](https://app.balldontlie.io) | 5 次/分钟 |

   本脚本每 6 小时才跑一次、每个源只发 1~2 个请求，所以免费档的余量绰绰有余。

2. 仓库 → **Settings → Secrets and variables → Actions → New repository secret**，名字就用上表那三个，值粘进去。

3. 去 **Actions → Update calendar → Run workflow** 手动跑一次，日志开头会打印哪个配了、哪个没配（**只报配没配，不回显值**）。

**在本机跑**：把 `tools/.env.example` 复制成 `tools/.env` 填进去。`tools/.env` 已经在 `.gitignore` 里 —— **那个文件绝不能提交**。

没配 key 不会让整个流程挂掉：缺哪个 key，对应的那一类失败并在「数据源状态」里写明原因，其余几个免 key 的源（F1 / MotoGP / 棒球 / 英雄联盟）照常出数据。

> ⚠️ 两家的 Authorization 头格式不一样，写反了都会 401 而且报错信息看不出区别：
> **PandaScore 要 `Bearer ` 前缀，balldontlie 不要。**

### 为什么不会把 key 泄漏出去

四条规矩，改 `tools/fetch_calendar.py` 时别破坏：

1. **key 一律走请求 header，绝不拼进 URL。** 这条最要紧 —— 一旦进 URL，key 就会顺着报错信息被写进 `data/calendar.json` 的 `sources.<cat>.error`，而那个文件是要提交到公开仓库的，等于**永久留在 git 历史里**（删掉也还在）。
2. **脚本在写文件之前会扫一遍产物**（`assert_no_secrets`），发现任何密钥明文就直接中止、不写文件。这一条是机器守的，不靠人记得。
3. 异常消息里不带请求头。公开仓库的 Actions 日志任何人都能看。
4. `tools/.env` 在 `.gitignore` 里。

### 抓取是云端做的，手机只请求一个文件

```
GitHub Actions（每 6 小时 + 手动触发）
  └─ tools/fetch_calendar.py ──抓──▶ 七个源（三个要 key，从 Secrets 注入）
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
- **② 颜色图例** —— 七个颜色分别对应什么，以及当前用的是图标还是字母块
- **③ 数据源状态** —— 每个源这次抓到没有、抓了多少场、失败原因。哪一类突然不显示了，先看这里
- **④ 卡片图标** —— 给每个分组传一张图（见下）
- **⑤ 设置** —— 数据地址和刷新间隔，加一个「测试连接」自检

### 给卡片传图标

小组件卡片左侧那块图，**是你自己传的，程序不去网上找**。在 App 的 ④ 里，每个分组一行，
点「选图」从相册挑一张，点「清除」退回字母块。

选完会进一个**正方形取景框**：拖动挪位置、双指缩放，**框里看到的就是卡片上会显示的**，
满意了点「就用这张」。拖歪了点「重置」回到居中。

需要这一步是因为：你拿来当图标的常常是从网上截的图，标根本不在正中间；
而直接等比缩放的话，周围留白一多，缩到 28dp 的卡片上就只剩一团糊。挪一挪比程序去猜怎么裁靠谱。

- 建议用**图案清晰**的图，透明底 PNG 最好（白底的图放在卡片上会有一个白方块）
- 存下来的是 96×96 PNG，放在 App 私有目录，**不占你相册、也不需要存储权限**
  （系统只把挑中的那一个文件临时授权给 App，不是打开整个相册）
- 相册里那张照片如果很大，会先按上限采样到 1024 再进取景框（原图直接解码会吃几十 MB 内存）；
  照片的方向信息（EXIF）会自动读出来转正，不会出现横着的图
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
| **点卡片没反应** | 不该发生。可滚动小组件的列表项要**同时**设 `setPendingIntentTemplate`（在 `WidgetRenderer`）和 `setOnClickFillInIntent`（在 `WidgetFactory.getViewAt`），少一个模板就永远不触发 —— 不报错、不崩，只是点了没动静。改这块时两个都要在 |
| **传完图标还是显示字母块** | 图标是存在 App 私有目录的，卸载重装会清掉，需要重新传 |
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
├── tools/
│   ├── fetch_calendar.py        # 聚合脚本（只用标准库）
│   └── .env.example             # 本地跑要填的 key，复制成 .env（.env 已 gitignore）
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

**换队伍不用碰代码、不用重装 App**（前提是新队伍在同一个源里 —— 换源就要改 `fetch_xxx` 函数了）：

1. 在 GitHub 网页上编辑 `data/config.json`
2. 改对应的 id，去哪查写在文件开头的 `_comment` 里：

   | 类别 | 改哪个字段 | 去哪查 |
   |---|---|---|
   | 足球 | `footballDataTeamId` | `api.football-data.org/v4/competitions/PD/teams` |
   | 棒球 | `mlbTeamId` | `statsapi.mlb.com/api/v1/teams?sportId=1` |
   | 篮球 | `balldontlieTeamId` | balldontlie 的 `/v1/teams` |
   | CS2 | `pandascoreTeamId` | `api.pandascore.co/csgo/teams?search[name]=队伍名`（留空则按队名匹配，也能用） |
   | LoL | `leagueId` | 文件开头的 `_comment` 里列了 LPL/LCK/LEC/LCS/MSI/Worlds |

3. 顺手改 `teamLabel`（卡片标题和 chip 上用）和 `mark`（没传图标时显示的字母块，**最多 3 个汉字**）
4. 换足球/棒球/篮球的对手时，还要补 `opponentCn` 里的中文简称 —— 查不到会直接显示三字母代号，不会出错，只是不好看

5. 等下一次定时抓取，或者去 Actions 手动触发 `update-calendar`

> ⚠️ 对手中文简称**上限 3 个汉字**。chip 宽度是按 7dp 字号下 6 个宽度单位算的，超了后端会截断并打日志（不是静默丢弃，去 workflow 日志里能看到）。

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

**一个「整体」合成一张卡，不是一天一张。** F1 西班牙站的 FP1/FP2/FP3/排位/正赛是**一张**卡（标题「F1 · 西班牙站」，第二行列各场次），而不是五张各说各话的卡 —— 一个周末本来就是一回事，拆开会把列表撑得很长、还看不出它们的关系。分组在后端做（`tools/fetch_calendar.py` 的 `group` 字段）。

**棒球的「整体」是系列赛，不是比赛。** 这是同一个原则的另一个例子：道奇一周 6 场，一场一张卡的话 7 天窗口里能占 7 张，把一整周只有一场的 F1/MotoGP 全挤下去。所以 MLB 按系列赛分组，判据是 `seriesGameNumber == 1`（不能用「对手变了就换组」—— 同一对手在主客场的两组不该并起来）。第二行因此多一个场次数：`vs 红人 · 4 连战`。注意**卡片第二行对队伍类只写一次对手**，不会像赛车项目那样把 short 串起来（四个「红人」串成一行没有信息量）。

**卡片第二行放不下时会按重要性取舍，不是简单截断。** MotoGP 一个周末有 FP1/练习/FP2/Q1/Q2/冲刺/热身/正赛八节，全列出来一行放不下。规则是：先去重（Q1、Q2 都叫「排位」），还超就保正赛和排位、让练习赛让位（正赛 3 > 排位 2 > 练习 1）。直接截断的话会把最重要的那几节切掉，而且不报错、不崩，只是看不见。

**可滚动用 ListView + RemoteViewsService 实现。** 安卓的小组件只有 `ListView`/`GridView` 这类「集合控件」能滚动，而且行内容不能直接塞进 RemoteViews，得由一个 `RemoteViewsService` 逐行提供。见 `WidgetService.kt`。顶栏（品牌条 / 七天迷你条 / 状态 / 刷新）是 ListView 外面的普通控件，这在这个模式里是合法的。

**卡片图标由用户自己上传，程序不去网上找。** 试过自动拉队标，三个问题凑在一起没法看：拉回来的图**明暗不一定配卡片底色**（F1 和 MotoGP 的标是白色/浅色的，本来就是给深色背景用的，放到浅色卡片上就看不清，皇马队徽的白底又和卡片融在一起）、各家给的尺寸留白比例都不一样排成一列参差不齐、来源本身还不稳（TLS 指纹风控、403、给的还是 Android 解不了的 SVG）。所以改成：App 里有个「卡片图标」区，每个分组传一张，存本机 `filesDir/logos/`，传了用图、没传用字母块。

上传的图会先缩到 96px 再存。**这一步不能省**：`setImageViewBitmap` 会把整张位图塞进 Binder 事务（全进程共享约 1MB），用户随手挑一张几 MB 的照片，解码后好几 MB，直接就是 `TransactionTooLargeException` —— 表现是小组件不更新，而且报错信息完全指不到这里。

**多端点兜底链 + 三级降级，是为了「源挂了」这件事。** 2026-09 那轮 Sofascore 风控印证了这个设计是对的：两个源同时挂掉，但因为是**每个源各自 try/catch**，F1 / MotoGP / LoL 一点没受影响，`sources` 里另外两条记着错误原因、App 的「数据源状态」直接显示出来。所以加新源时也要守这个约定：**一个源失败绝不能带崩整个文件**。

**每个源的重试是刻意的。** 这些站点都在 CDN 后面，实测会偶发 TLS 握手超时（同一台机器同一个 URL，上一次成功、这一次超时）。不重试的话，一次网络抖动就会让某一类赛事整天没有数据 —— 而 App 端看起来只是「今天没比赛」，根本看不出是抓取失败。

**为什么把 key 全放在 header 而不是 URL。** 有些服务商习惯用 `?api_key=xxx`，但那在本项目里是个陷阱：抓取失败时错误消息会被写进**公开的** `data/calendar.json`，URL 里的 key 就这么永久留在 git 历史里了。所以三个注册制源全走 header（`X-Auth-Token` / `Authorization`），并且在写文件前还会再扫一遍产物。

---

## 参考

各家的接口文档：

- [OpenF1](https://openf1.org/docs/) —— F1，免 key，免费档无次数限制
- [football-data.org](https://www.football-data.org/documentation/quickstart) —— 足球，注册制
- [MLB Stats API](https://github.com/toddrob99/MLB-StatsAPI/wiki) —— 棒球，MLB 官方，免 key（社区整理的端点说明）
- [balldontlie](https://docs.balldontlie.io/) —— NBA，注册制
- [PandaScore](https://developers.pandascore.co/docs/introduction) —— 电竞（CS2 走 `/csgo/` 路由），注册制
- [LoL Esports](https://lolesports.com/schedule)
- [MotoGP Pulselive](https://api.motogp.pulselive.com/motogp/v1/events?seasonYear=2026&isFinished=false) —— ⚠️ 站点前端私有 API，无公开文档，见上面的「已知风险源」

历史上用过、已经换掉的：[Jolpica](https://api.jolpi.ca/ergast/)（F1，换成了 OpenF1）、[Sofascore](https://www.sofascore.com/)（足球 + CS2，2026-09 对 CI 出口 IP 封了 403）。
