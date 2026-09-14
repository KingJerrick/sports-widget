#!/usr/bin/env python3
"""
把七个赛事源抓成一个 calendar.json。

── 为什么抓取放在云端而不是手机里 ────────────────────────────────────────
手机只发一个 HTTP GET 拿这一个文件，好处有三条：
  1. 解析逻辑在这里，改一次重跑 workflow 就行，**不用重新打包发 APK**
  2. 抓取只发生在 GitHub 的服务器上，你的手机 IP 不会因为高频轮询被赛事站点风控
  3. 数据有 git 历史，出问题能回溯

第 1 条最要紧：源里有几个是**网页私有接口**，无文档无承诺，结构随时可能变。
放在这里只需要改一个函数。

── 数据源的来龙去脉 ────────────────────────────────────────────────────
2026-09 之前，足球（皇马）和 CS2（猎鹰）走的是 Sofascore 的网页私有接口。
它对本机住宅 IP 正常，但对 GitHub Actions 的出口 IP **返回 403** ——
同一份代码、同一个 UA，9 月 11 日还能跑，9 月 12 日就全红了。
这是按 TLS 指纹 / 出口 IP 做的风控，也正是「用私有接口换免注册」要付的代价。

于是能换注册制的全换了，换来的是**有文档、有承诺、key 走 header** 的接口：

    f1        OpenF1              api.openf1.org           免 key
    motogp    Pulselive           api.motogp.pulselive.com ⚠️ 仍是私有接口
    football  football-data.org   api.football-data.org    注册制 X-Auth-Token
    mlb       MLB Stats API       statsapi.mlb.com         免 key（官方）
    nba       balldontlie         api.balldontlie.io       注册制 Authorization
    cs2       PandaScore          api.pandascore.co        注册制 Bearer
    lol       lolesports          esports-api.lolesports.com  Riot 官方数据

**MotoGP 没有能长期用的注册制接口**：Sportradar 有 MotoGP v2 但是 30 天试用、
到期断供，正式接入要走企业销售合同；ESPN 不覆盖 MotoGP；TheSportsDB 免费档
几乎是空的。所以它继续留在 Pulselive —— 它恰恰是这轮风控里活下来的那个，
但这不代表它可靠，换源时优先换它。

── 密钥怎么进这个脚本 ──────────────────────────────────────────────────
key **只以环境变量形式存在**，绝不写进这个文件、也绝不进仓库：

  · CI：GitHub Actions Secret → workflow 的 env: → os.environ
  · 本地：tools/.env（已在 .gitignore 里），跑之前自己填

三条硬规矩，改代码时别破坏：

  1. **key 一律走请求 header，绝不拼进 URL。** 一旦进 URL，key 就会顺着
     RuntimeError 的消息被写进 data/calendar.json 的 sources.<cat>.error ——
     而那个文件是要提交到公开仓库的，等于永久留在 git 历史里。
     公开仓库的 Actions 日志也是任何人都能看的，所以异常消息里也不能有 key。
  2. **落盘前用 assert_no_secrets 扫一遍产物**（见 main），命中就直接中止。
  3. 打印「配没配」可以，打印值不行。

── 只用标准库 ──────────────────────────────────────────────────────────
urllib / json / datetime / argparse，不装 requests —— workflow 里省掉 pip install
一步。tools/.env 也是手写的十几行解析，不为它引入 python-dotenv。
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG_PATH = ROOT / "data" / "config.json"
OUT_PATH = ROOT / "data" / "calendar.json"
# 同时也往 APK 里塞一份快照，作为「没网 + 没缓存」时的最后兜底
ASSET_PATH = ROOT / "app" / "src" / "main" / "assets" / "calendar.json"
ENV_PATH = ROOT / "tools" / ".env"

# 一次发布这么多天的赛程，App 自己从中截未来 7 天。
# 放这么宽是为了容错：即使 Actions 定时挂了一周，小组件也不会突然空掉 ——
# 赛程本来就是提前几周就定下来的。
WINDOW_DAYS = 60

# 往回发布几天。要能盖住 App 那边 5 天的回溯窗口 + 最多 6 小时的抓取间隔 + 余量，
# 理由见 main() 里用它的地方。
BACK_DAYS = 7

# 部分 CDN / 站点对没有 UA 的请求直接 403
UA = "sports-widget/1.0 (+https://github.com/KingJerrick/sports-widget)"

# lolesports 网页自己用的公开 key。
#
# ⚠️ 这**不是**本项目自己的凭证：任何打开 lolesports.com 开发者工具的人都能看到它，
# 它只是 Riot 网页客户端的标识。所以没把它收进 Secrets —— 收进去只是把
# 「轮换时改代码提交」换成「轮换时改 GitHub 设置」，安全性并没有变化，
# 却会让 fork 了仓库的人跑不起来。
#
# 真正的风险是 Riot 轮换它：轮换了改这一行即可，不用动 App。
LOL_API_KEY = "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z"

# ── 关于卡片图标 ──────────────────────────────────────────────────────────
# **后端不管图标**，只下发每个类别的字母块标记（mark 字段，如 F1 / GP / 皇马）。
#
# 曾经试过在这里下发图标地址、由 App 去原站拉，放弃了：
#   · 拉回来的图明暗不一定配 App 的卡片底色 —— F1 和 MotoGP 的标是白色/浅色的，
#     本来就是给深色背景用的，放到浅色卡片上看不清
#   · 各家给的尺寸、留白、比例都不一样，排成一列参差不齐
#   · 来源本身不稳（TLS 指纹风控、403、给的还是 Android 解不了的 SVG）
#
# 现在是**用户在 App 里自己传**，每个分组一张，存本机。传了用图，没传用 mark。

# ── chip 文字的长度上限 ────────────────────────────────────────────────────
# 小组件每列可用宽约 28.7dp，7dp 字号下能放 3 个汉字或 6 个拉丁字符。
# 这里按「汉字算 2 个宽度单位、拉丁算 1 个」来量，上限 6 个单位。
# 超了就在后端截断 —— 不要指望小组件的 ellipsize 兜底，它只能掩盖问题。
SHORT_WIDTH_LIMIT = 6


# ══ 密钥 ══════════════════════════════════════════════════════════════════

# 三个注册制源的 key。改这里要同步改 .github/workflows/update-calendar.yml 的 env 段。
SECRET_KEYS = ("FOOTBALL_DATA_TOKEN", "PANDASCORE_TOKEN", "BALLDONTLIE_KEY")


def load_secrets() -> dict:
    """
    读密钥。**环境变量优先**，缺失时退回 tools/.env。

    本地跑的时候把 key 写进 tools/.env（已 gitignore），CI 里由 workflow 的
    env: 从 GitHub Secrets 注入。两条路都走同一个字典，下面的代码不用区分。

    环境变量优先是为了 CI：万一有人在 runner 上误放了一个 .env，
    也不该盖过 Secrets。
    """
    out = {k: (os.environ.get(k) or "").strip() for k in SECRET_KEYS}

    if ENV_PATH.exists():
        for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            # 跳过空行和 # 注释；没有 = 的行也不认
            if not line or line.startswith("#") or "=" not in line:
                continue
            name, _, value = line.partition("=")
            name = name.strip()
            # 值两边的引号是给人看的，解析时去掉
            value = value.strip().strip('"').strip("'")
            if name in out and not out[name]:
                out[name] = value

    return out


SECRETS = load_secrets()


def assert_no_secrets(text: str, where: str) -> None:
    """
    落盘前的最后一道闸：产物里出现任何密钥明文就中止。

    为什么需要这个 —— 上面那三条规矩靠人守，这一条靠机器守。
    产物是要 commit 到**公开仓库**的，一次疏忽就是永久泄漏（git 历史删不掉）。
    宁可整个 workflow 失败，也不能把 key 推出去。
    """
    for name, value in SECRETS.items():
        if value and value in text:
            raise SystemExit(
                f"\n⛔ 已中止：{name} 的明文出现在 {where} 里。\n"
                f"   这个文件会被提交到公开仓库，继续写下去等于把密钥发出去。\n"
                f"   检查一下是不是把 key 拼进了 URL 或日志。\n"
            )


def report_secrets() -> None:
    """
    只报「配没配」，**绝不回显值**。

    少了 key 的源会各自失败，但失败信息藏在 sources.<cat>.error 里、
    要打开 App 才看得到。这里先打一行，日志里一眼就能看出是「没配」还是「接口挂了」。
    """
    for name in SECRET_KEYS:
        print(f"  {name:<20} {'已配置' if SECRETS[name] else '未配置'}")


# ══ 调试开关 ══════════════════════════════════════════════════════════════
#
# 三个注册制源的响应形状是照官方文档写的，第一次拿到 key 时必须核对一遍
# 字段名有没有变。--raw 把上游原始响应打出来就是干这个的。
#
# 用全局变量而不是往每个 fetch_xxx 加参数：SOURCES 注册表要求它们的签名统一，
# 加个 raw 参数会让七个函数全都得跟着改，而它只是个调试开关。

RAW = False
RAW_LIMIT = 6000


def dump_raw(obj, tag: str) -> None:
    """--raw 时把上游响应打出来。截断是故意的 —— 整份响应几万字，刷屏了看不见重点。"""
    if not RAW:
        return
    text = json.dumps(obj, ensure_ascii=False)
    print(f"\n──── 原始响应 [{tag}] 共 {len(text)} 字符 ────")
    print(text[:RAW_LIMIT] + ("…（已截断）" if len(text) > RAW_LIMIT else ""))
    print("──── 结束 ────\n")


# ══ 工具 ══════════════════════════════════════════════════════════════════

def http_get(url: str, headers: dict | None = None, attempts: int = 3) -> str:
    """
    所有源都走这个。带重试是因为这些站点实测会偶发 TLS 握手超时
    （同一台机器同一个 URL，上一次成功、这一次超时）。重试两次基本就稳了 ——
    不然一次网络抖动就会让某一类赛事整天没有数据，而 App 端看起来只是「今天没比赛」。

    ⚠️ headers 里会带密钥。异常消息只带 URL（密钥从不进 URL，见文件头），
    所以 last 里的异常不会泄漏 key —— 改这个函数时别把 headers 也塞进消息里。
    """
    last: Exception | None = None
    for i in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
            with urllib.request.urlopen(req, timeout=20) as resp:
                return resp.read().decode("utf-8", errors="replace")
        except Exception as exc:  # noqa: BLE001 —— 网络层的任何异常都值得重试
            last = exc
            if i < attempts - 1:
                print(f"    · 第 {i + 1} 次失败（{type(exc).__name__}），重试…")
                time.sleep(3 * (i + 1))
    raise last  # type: ignore[misc]


def to_utc(value: str) -> datetime:
    """
    把各源五花八门的时间串统一成带时区的 datetime。

    实际会碰到的形状：
      "2026-09-13T13:00:00Z"        MLB Stats API、football-data.org
      "2026-03-08T04:00:00+00:00"   OpenF1
      "2026-09-13T14:00:00+0200"    MotoGP Pulselive，偏移量不带冒号
      "2026-09-12T09:00:00Z"        lolesports
    """
    s = value.strip().replace("Z", "+00:00")
    # Python 3.11 之前不认 "+0200" 这种不带冒号的偏移，补一个冒号
    if len(s) >= 5 and s[-5] in "+-" and s[-4:].isdigit():
        s = s[:-2] + ":" + s[-2:]
    dt = datetime.fromisoformat(s)
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def iso_z(dt: datetime) -> str:
    """统一输出成 UTC 的 ISO8601，秒级精度。App 端按设备时区再换算。"""
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def char_width(s: str) -> int:
    """汉字算 2、其余算 1。"""
    return sum(2 if ord(c) > 0x2E7F else 1 for c in s)


def clip_short(s: str) -> str:
    """
    把 chip 文字裁到宽度上限内。超了就截断并打一条日志 ——
    日志是为了让「小组件里显示不全」这类问题在 workflow 输出里就能看见，
    而不是等到盯着桌面才发现。
    """
    s = (s or "").strip()
    if char_width(s) <= SHORT_WIDTH_LIMIT:
        return s

    out, width = "", 0
    for c in s:
        w = 2 if ord(c) > 0x2E7F else 1
        if width + w > SHORT_WIDTH_LIMIT:
            break
        out += c
        width += w
    print(f"    · 截断 short: {s!r} -> {out!r}")
    return out


# 一场赛事有多值得占小组件那一格。3 = 最重要。
#
# 为什么需要这个字段：小组件每列只放得下 3 条，而实测一个周六能有 7 场
# （F1 两节 + MotoGP 四节 + 一场 LoL）。**按时间顺序截断是错的** ——
# 周日那天会显示成「皇马的球、MotoGP 热身、MotoGP 正赛」，把 F1 正赛挤掉，
# 正好丢掉整个周末最该看到的那一场。
# 所以排序按重要性来，App 端用这个字段决定谁留下。
RANK_RACE = 3   # 正赛、冲刺赛，以及追的队的比赛（本来就少，每场都重要）
RANK_QUALI = 2  # 排位、冲刺排位
RANK_PRACTICE = 1


def duration_for(cfg: dict, code: str | None = None, default: int = 120) -> int:
    """
    这类比赛该算多久（分钟）。App 靠它算「进行中」。

    赛车项目按**场次代号**查 durations 表 —— 正赛和练习赛差着一倍，
    一个值盖不住；队伍类用 durationMinutes 单个值就够。
    两张表都从 config 来，所以**改时长不用重装 App**（和改队伍一个路子）。

    这只是个近似：开赛 + 这个时长之前都算在打，不看实际什么时候跑完。
    App 那边不留缓冲，超了就直接翻成「已结束」。
    """
    table = cfg.get("durations")
    if table and code:
        return int(table.get(code, default))
    return int(cfg.get("durationMinutes", default))


def make_event(cat, eid, title, short, start, end, venue, source, rank,
               group, group_name, url=None, dur=120, result=None, win=None):
    """
    group / group_name：小组件是按**卡片**显示的，一张卡对应一组赛事。

      赛车项目一个比赛周末是一个整体：F1 西班牙站的 FP1/FP2/排位/正赛
      应该是**一张**卡（标题「F1 · 西班牙站」，下面列出各场次），
      而不是五张各说各话的卡。所以同一个周末的场次共用 group。

      队伍类一般没有这种层级，一场比赛就是一张卡，group 用比赛自己的 id。
      **例外是棒球**：一个系列赛（3~4 连战）是一个整体，理由和赛车项目一样 ——
      见 fetch_mlb 里那段说明。

    ── dur / result / win 这三个字段是给「三种状态」用的 ──────────────────
    **状态不在后端算。** 抓取每 6 小时才跑一次，这份 JSON 最多可能已经过期
    6 小时 —— 按抓取时刻算出来的「进行中」写进文件，等手机读到的时候早就打完了。
    所以后端只下发**判据**，由 App 拿设备当前时刻现算：

      dur     典型时长（分钟），开赛 + dur 之前都算在打
      result  赛果显示串。队伍类统一是「**我们追的队**得分-对手得分」——
              标题里的主客写法（@ 红人 / vs 巨人）会变，比分跟着变就读不明白了
      win     这场我们赢了没。App 靠它数系列赛的「N 胜 M 负」；
              赛车项目没有胜负概念，给 None

    已经结束但超出 24 小时的事件照样下发（窗口本来就覆盖不到那么远），
    「只显示 24 小时内的」这条规矩在 App 端。
    """
    return {
        "id": eid,
        "cat": cat,
        "title": title,
        "short": clip_short(short),
        "start": iso_z(start),
        "end": iso_z(end) if end else None,
        "venue": venue or None,
        "source": source,
        "rank": rank,
        "group": group,
        "groupName": group_name,
        "url": url,
        "dur": dur,
        "result": result,
        "win": win,
    }


def pick_short(code: str | None, fallback: str | None, cn_map: dict) -> str:
    """
    对手短名，三字母代号优先。

    足球（football-data.org 的 tla）、棒球（MLB 的 abbreviation）、
    篮球（balldontlie 的 abbreviation）三家都给三字母代号，所以三张中文覆盖表
    用的是同一套键。查不到就用代号本身（3 个拉丁字符，正好放得下），
    再不行才退回队名 —— 队名多半会被 clip_short 截断，是最后的选择。
    """
    code = (code or "").strip().upper()
    if code and code in cn_map:
        return cn_map[code]
    if code:
        return code
    return (fallback or "?").strip()


def team_side(home: dict, away: dict, team_id, cat_side=("主", "客")):
    """
    判断我们追的队这场是主是客，返回 (对手, 主客标记)。

    **用 id 比用队名可靠** —— 各家的展示名会随语言、随赛季变，id 不变。
    两边都没匹配上时给「?」而不是猜：显示错了比显示「?」更糟。
    """
    if home.get("id") == team_id:
        return away, cat_side[0]
    if away.get("id") == team_id:
        return home, cat_side[1]
    return away, "?"


# ══ F1 · OpenF1 ══════════════════════════════════════════════════════════
#
# api.openf1.org，免注册、有文档。一次请求拿到整年全部场次，
# 而且**每节练习赛都在**（这是它比 api-sports.io 的 F1 接口强的地方 ——
# 那家只给正赛，一个周末最重要的 FP/排位信息全没有）。
#
# session_name 的取值实测有：
#     Practice 1/2/3、Qualifying、Sprint Qualifying、Sprint、Race
#     Day 1/2/3   ← 这是冬测，不是比赛周末，要挡掉
#
# 所以下面这张表**同时是白名单**：不在表里的一律跳过，
# 以后 OpenF1 加了新场次类型也不会莫名其妙混进日历。

OPENF1_SESSION_LABELS = {
    "Practice 1":        ("第一次练习", "FP1", RANK_PRACTICE),
    "Practice 2":        ("第二次练习", "FP2", RANK_PRACTICE),
    "Practice 3":        ("第三次练习", "FP3", RANK_PRACTICE),
    "Qualifying":        ("排位赛", "排位", RANK_QUALI),
    "Sprint Qualifying": ("冲刺排位", "冲刺", RANK_QUALI),
    "Sprint":            ("冲刺赛", "冲刺赛", RANK_RACE),
    "Race":              ("正赛", "正赛", RANK_RACE),
}

F1_COUNTRY_CN = {
    "Spain": "西班牙", "Azerbaijan": "阿塞拜疆", "Bahrain": "巴林", "Saudi Arabia": "沙特",
    "Australia": "澳大利亚", "Japan": "日本", "China": "中国", "United States": "美国",
    "USA": "美国", "Italy": "意大利", "Monaco": "摩纳哥", "Canada": "加拿大",
    "Austria": "奥地利", "Great Britain": "英国", "United Kingdom": "英国",
    "Belgium": "比利时", "Hungary": "匈牙利", "Netherlands": "荷兰", "Singapore": "新加坡",
    "Mexico": "墨西哥", "Brazil": "巴西", "Qatar": "卡塔尔",
    "United Arab Emirates": "阿布扎比", "UAE": "阿布扎比",
    "Korea": "韩国", "South Korea": "韩国", "France": "法国", "Germany": "德国",
    "Portugal": "葡萄牙", "Turkey": "土耳其", "Russia": "俄罗斯", "Vietnam": "越南",
    "Sweden": "瑞典", "Switzerland": "瑞士", "South Africa": "南非", "Argentina": "阿根廷",
    "India": "印度", "Malaysia": "马来西亚", "Finland": "芬兰",
}


def f1_winner(session_key) -> str | None:
    """
    已结束正赛的分站冠军，返回车手代号（ANT / VER 这种）。

    要两跳：session_result 只给 driver_number，得再查 drivers 换名字。
    实测 2026-09-13 西班牙站 → position 1 / driver_number 12 → Kimi ANTONELLI。

    ⚠️ 失败**不能**让整个 F1 源挂掉 —— 冠军是锦上添花，赛程本身已经拿到了。
    所以这里自己 try/except，拿不到就返回 None（卡片退化成只有「已结束」）。
    """
    try:
        rows = json.loads(http_get(
            f"https://api.openf1.org/v1/session_result"
            f"?session_key={session_key}&position=1"))
        if not rows or rows[0].get("driver_number") is None:
            return None
        drv = json.loads(http_get(
            f"https://api.openf1.org/v1/drivers"
            f"?session_key={session_key}&driver_number={rows[0]['driver_number']}"))
        if not drv:
            return None
        # 缩写最适合塞进卡片右侧那个时间位（原本放「09-14 06:40」的地方）；
        # 没有缩写的旧数据就退回姓氏
        return drv[0].get("name_acronym") or drv[0].get("last_name")
    except Exception as exc:  # noqa: BLE001 —— 见 docstring，不能带崩 F1 源
        print(f"    · 取冠军失败（{type(exc).__name__}），这场不显示冠军")
        return None


def fetch_f1(cfg: dict, lo: datetime, hi: datetime) -> list:
    season = lo.year
    url = f"https://api.openf1.org/v1/sessions?year={season}"
    data = json.loads(http_get(url))
    dump_raw(data, "f1/openf1")

    # 只有「已经跑完的正赛」才值得去查冠军，所以需要一个当前时刻。
    # 这里的几十秒误差无所谓 —— 它只决定要不要多打两个请求，
    # 真正的三态判定在 App 端按设备时刻算（见 make_event 的说明）。
    now = datetime.now(timezone.utc)

    events = []
    for s in data:
        label, short, rank = OPENF1_SESSION_LABELS.get(
            s.get("session_name"), (None, None, None)
        )
        # 白名单外的（冬测 Day 1/2/3、以后新增的场次类型）直接跳过
        if not label:
            continue
        # 取消的场次不该占一格 —— 实测有这个字段，别等真出现了才发现
        if s.get("is_cancelled"):
            continue
        raw_start = s.get("date_start")
        if not raw_start:
            continue

        start = to_utc(raw_start)
        if not (lo <= start <= hi):
            continue

        # 用 country_name 而不是 location：location 是「Sakhir」「Suzuka」这种地名，
        # 中文表是按国家建的；country_name 的值实测全都能命中 F1_COUNTRY_CN。
        country = s.get("country_name") or ""
        place = F1_COUNTRY_CN.get(country) or s.get("location") or "?"
        end = to_utc(s["date_end"]) if s.get("date_end") else start + timedelta(hours=1)

        # 只对**已经跑完的正赛**去查冠军。练习赛/排位不查（没意义），
        # 没跑完的也不查（还没冠军）。所以一周最多两三场比赛会多打两跳。
        result = None
        if s.get("session_name") == "Race" and end < now:
            result = f1_winner(s.get("session_key"))

        events.append(make_event(
            cat="f1",
            # session_key 是 OpenF1 自己的稳定主键
            eid=f"f1-{s.get('session_key')}",
            title=f"F1 {place}站 · {label}",
            short=short,
            start=start,
            end=end,
            venue=s.get("circuit_short_name"),
            source="openf1",
            rank=rank,
            # 同一个比赛周末（meeting_key）的 FP1/排位/正赛共用一张卡
            group=f"f1-{s.get('meeting_key')}",
            group_name=f"{place}站",
            url=f"https://www.formula1.com/en/racing/{season}",
            # 场次代号就是 config 里 durations 表的键
            dur=duration_for(cfg, s.get("session_name")),
            result=result,
            # 赛车没有胜负概念
            win=None,
        ))
    return events


# ══ MotoGP · Pulselive ═══════════════════════════════════════════════════
#
# ⚠️ **已知风险源：这是全套里唯一还剩下的网页私有接口。**
#
# motogp.com 自己的前端在调的接口 —— 无公开文档、无承诺。换源的时候优先换它。
# 之所以没换成注册制：市面上**没有能长期用的 MotoGP 接口** ——
# Sportradar 有 MotoGP v2 但只有 30 天试用、到期断供，正式接入要走企业销售合同；
# ESPN 不覆盖 MotoGP；TheSportsDB 免费档搜不到 MotoGP。
#
# 它没在 2026-09 那轮风控里被封，但这只是运气，不代表它稳。
# 真挂了的表现是 sources.motogp.ok=false，处理办法见 README 的「源挂了怎么办」。
#
# 实测已经踩到两个坑（换源或它改版时对照）：
#   · seasons 在 /motogp/v1/**results**/seasons，不是 /seasons（那个返回 400）
#   · /events 只认 seasonYear，不认 seasonUuid
#
# 好消息是一个请求就能拿到整站三天的全部场次：事件对象的 broadcasts[] 里
# type == "SESSION" 的条目就是日程，不用再调第二个接口。

MOTOGP_SESSION_LABELS = {
    "FP1": ("第一次练习", "FP1", RANK_PRACTICE),
    "FP2": ("第二次练习", "FP2", RANK_PRACTICE),
    "PR":  ("练习赛", "练习", RANK_PRACTICE),
    "Q1":  ("排位赛第一节", "排位", RANK_QUALI),
    "Q2":  ("排位赛第二节", "排位", RANK_QUALI),
    "SPR": ("冲刺赛", "冲刺", RANK_RACE),
    "WUP": ("热身赛", "热身", RANK_PRACTICE),
    "RAC": ("正赛", "正赛", RANK_RACE),
}


def fetch_motogp(cfg: dict, lo: datetime, hi: datetime) -> list:
    season = lo.year
    url = (f"https://api.motogp.pulselive.com/motogp/v1/events"
           f"?seasonYear={season}&isFinished=false")
    data = json.loads(http_get(url))
    dump_raw(data, "motogp/pulselive")

    want_class = cfg.get("class", "MotoGP")
    events = []
    for ev in data:
        # 不过滤的话，发布会、车队见面会、冬测会全混进日历 ——
        # 2026 赛季 46 条事件里大部分都不是比赛
        if ev.get("kind") != "GP":
            continue

        place = ev.get("shortname") or ev.get("name") or "?"
        venue = (ev.get("circuit") or {}).get("name")

        for b in ev.get("broadcasts") or []:
            if b.get("type") != "SESSION":
                continue
            # 一个周末同时跑 MotoGP / Moto2 / Moto3 三个组，只要配置里那个组
            if (b.get("category") or {}).get("name") != want_class:
                continue
            raw = b.get("date_start")
            if not raw:
                continue
            start = to_utc(raw)
            if not (lo <= start <= hi):
                continue

            code = (b.get("shortname") or "").upper()
            label, short, rank = MOTOGP_SESSION_LABELS.get(
                code, (b.get("name") or code, code, RANK_PRACTICE)
            )
            end = to_utc(b["date_end"]) if b.get("date_end") else start + timedelta(hours=1)

            events.append(make_event(
                cat="motogp",
                eid=f"mgp-{ev.get('id')}-{b.get('id')}",
                title=f"MotoGP {place}站 · {label}",
                short=short,
                start=start,
                end=end,
                venue=venue,
                source="pulselive",
                rank=rank,
                group=f"mgp-{ev.get('id')}",
                group_name=f"{place}站",
                dur=duration_for(cfg, code),
                # ⚠️ 这里**故意不给 result**：MotoGP 的成绩接口是私有接口的
                # 非公开路径，试了 9 条全 400/404（详见 config.json 里的 _no_winner）。
                # 所以 MotoGP 的完赛卡片只标「已结束」，不像 F1 那样显示冠军 ——
                # 两个赛车类别的完赛卡片长得不一样，这是数据源的限制，不是 bug。
                result=None,
                win=None,
            ))
    return events


# ══ 足球 · football-data.org ═════════════════════════════════════════════
#
# 注册制，免费档 10 次/分钟（本脚本一次运行只发 1~2 次请求，余量极大）。
# 免费档明确包含西甲(PD)和欧冠(CL)，当年赛季数据完整。
#
# ⚠️ 对手短名用的是它的 **tla** 字段（BAR / ATM / SEV），
# 与 config.json 里 opponentCn 的键对得上 —— 那张表是当年配 Sofascore 的
# nameCode 时建的，两家的代号几乎一致，换源时只补了几个。

FOOTBALL_API_BASE = "https://api.football-data.org/v4"


def fetch_football(cfg: dict, lo: datetime, hi: datetime) -> list:
    token = SECRETS.get("FOOTBALL_DATA_TOKEN")
    if not token:
        raise RuntimeError(
            "未配置 FOOTBALL_DATA_TOKEN。"
            "仓库 Settings → Secrets and variables → Actions 加一个，"
            "本地跑就写进 tools/.env"
        )

    team_id = cfg.get("footballDataTeamId")
    team_label = cfg.get("teamLabel") or cfg.get("team") or "足球"
    cn_map = cfg.get("opponentCn") or {}
    comp_labels = cfg.get("competitionLabels") or {}
    headers = {"X-Auth-Token": token}

    lo_d, hi_d = lo.strftime("%Y-%m-%d"), hi.strftime("%Y-%m-%d")
    events = []
    for code in cfg.get("competitions") or ["PD"]:
        url = (f"{FOOTBALL_API_BASE}/competitions/{code}/matches"
               f"?dateFrom={lo_d}&dateTo={hi_d}")
        data = json.loads(http_get(url, headers=headers))
        dump_raw(data, f"football/{code}")

        for m in data.get("matches") or []:
            home = m.get("homeTeam") or {}
            away = m.get("awayTeam") or {}

            # 服务端就算不认 dateFrom/dateTo、把整季都返回回来，这里也照样对
            if team_id not in (home.get("id"), away.get("id")):
                continue

            raw_start = m.get("utcDate")
            if not raw_start:
                continue
            start = to_utc(raw_start)
            if not (lo <= start <= hi):
                continue

            opp, side = team_side(home, away, team_id)
            opp_name = opp.get("name") or "?"
            # 优先用配置里的中文赛事名（PD → 西甲），
            # 它给的是「Primera Division」这种官方全称，显示在卡片上不亲切
            tour = comp_labels.get(code) or (m.get("competition") or {}).get("name") or code

            # 已经打完的把比分带上（不再像以前那样直接跳过）。窗口从「昨天」开始
            # （见 main），所以这里拿到的多半就是昨晚刚结束的那场。
            #
            # 比分一律写成「我们-对手」，不跟着主客变：卡片标题的写法会变
            # （皇马(主) / 皇马(客)），比分再跟着变就分不清哪个数是我们的了。
            result, win = None, None
            if (m.get("status") or "").upper() == "FINISHED":
                ft = ((m.get("score") or {}).get("fullTime") or {})
                hs, aws = ft.get("home"), ft.get("away")
                if hs is not None and aws is not None:
                    ours, theirs = (hs, aws) if side == "主" else (aws, hs)
                    result = f"{ours}-{theirs}"
                    win = ours > theirs

            events.append(make_event(
                cat="football",
                eid=f"fd-{m.get('id')}",
                title=f"{team_label}({side}) vs {opp_name} · {tour}",
                short=pick_short(opp.get("tla"), opp.get("shortName") or opp_name, cn_map),
                start=start,
                # 它不给结束时间，也不给场地 —— App 端本来就不解析这两个字段
                end=None,
                venue=None,
                source="football-data",
                # 追的队的比赛本来就少，每一场都值得占一格
                rank=RANK_RACE,
                group=f"fd-{m.get('id')}",
                group_name=tour,
                dur=duration_for(cfg),
                result=result,
                win=win,
            ))
    return events


# ══ 棒球 · MLB Stats API ═════════════════════════════════════════════════
#
# **MLB 官方的接口**，免注册、免 key —— 这已经是最可靠的一档了，
# 不可能再有比它更权威的源（mlb.com 自己就在用它）。
#
# hydrate=team 是为了拿 team.abbreviation（LAD / SF / SD），对手中文表按它建。
# 不加 hydrate 的话 team 对象里只有 id 和 name，name 是「Los Angeles Dodgers」
# 这种全称，直接塞进 chip 会被 clip_short 截成「Los A」。


def fetch_mlb(cfg: dict, lo: datetime, hi: datetime) -> list:
    team_id = cfg.get("mlbTeamId")
    team_label = cfg.get("teamLabel") or cfg.get("team") or "棒球"
    cn_map = cfg.get("opponentCn") or {}

    url = ("https://statsapi.mlb.com/api/v1/schedule"
           f"?sportId=1&teamId={team_id}"
           f"&startDate={lo.strftime('%Y-%m-%d')}&endDate={hi.strftime('%Y-%m-%d')}"
           f"&hydrate=team")
    data = json.loads(http_get(url))
    dump_raw(data, "mlb/statsapi")

    events = []
    # ── 系列赛归并 ────────────────────────────────────────────────────────
    # 棒球一周 6 场，如果一场一张卡，7 天窗口里道奇一个人就占 7 张，
    # 把 F1、MotoGP 这些一周只有一场的全挤到列表底下（实测过）。
    # 所以一个系列赛（同一对手、同一主客场、连着打 3~4 天）并成一张卡 ——
    # 和「F1 一个比赛周末并成一张卡」是同一个道理，只是这里的「一个整体」
    # 是系列赛而不是比赛周末。
    #
    # 判据用 seriesGameNumber：它在每个系列赛的第一场等于 1。
    # 不用「对手变了就换一组」—— 同一对手可能先在客场打一组、隔几周再在主场打一组，
    # 那两组不该并在一起。分组还顺带把主客场分开了。
    #
    # 窗口是从中间切进来的（lo = 昨天），首场很可能不在窗口里，
    # 所以 series_key 为空时也要起一组，不能干等下一个 1。
    series_key = None

    for day in data.get("dates") or []:
        for g in day.get("games") or []:
            # ⚠️ 系列赛边界必须在下面那些 continue **之前**判。
            # 反过来的话，被跳过的「系列赛首场」（比如刚打完、状态已经是 Final 的那场）
            # 就不会触发换组，后面几场会错接到上一组去。
            if series_key is None or g.get("seriesGameNumber") == 1:
                series_key = f"mlb-{g.get('gamePk')}"

            state = ((g.get("status") or {}).get("detailedState") or "")
            # 推迟的不进日历 —— 它没有确定时间，挂在那里只会占位
            if "postponed" in state.lower():
                continue
            # 打完的**保留**（以前是跳过的），把比分带上。
            # 用 startswith 是因为实测有「Final」「Final: Tied」「Game Over」几种写法
            finished = state.lower().startswith("final")

            raw_start = g.get("gameDate")
            if not raw_start:
                continue
            start = to_utc(raw_start)
            if not (lo <= start <= hi):
                continue

            sides = g.get("teams") or {}
            home = (sides.get("home") or {}).get("team") or {}
            away = (sides.get("away") or {}).get("team") or {}
            opp, side = team_side(home, away, team_id)
            opp_name = opp.get("name") or "?"
            opp_short = pick_short(opp.get("abbreviation"), opp.get("name"), cn_map)

            # 比分一律「我们-对手」，不跟着主客变 —— 理由同 fetch_football
            result, win = None, None
            if finished and side in ("主", "客"):
                hs = (sides.get("home") or {}).get("score")
                aws = (sides.get("away") or {}).get("score")
                if hs is not None and aws is not None:
                    ours, theirs = (hs, aws) if side == "主" else (aws, hs)
                    result = f"{ours}-{theirs}"
                    win = ours > theirs

            events.append(make_event(
                cat="mlb",
                eid=f"mlb-{g.get('gamePk')}",
                title=f"{team_label}({side}) vs {opp_name}",
                short=opp_short,
                start=start,
                end=None,
                venue=(g.get("venue") or {}).get("name"),
                source="statsapi",
                rank=RANK_RACE,
                # 整个系列赛共用一张卡
                group=series_key,
                # 卡片标题读作「道奇 · @ 红人」（客场）/「道奇 · vs 巨人」（主场），
                # 沿用北美体育的 @ / vs 记法 —— 一眼能看出这组是在谁家打的。
                # 对手已经写在这里了，所以第二行的「vs 红人」不再重复对手之外的东西，
                # 多出来的是场次数（「vs 红人 · 4 连战」）。
                group_name=f"{'@' if side == '客' else 'vs'} {opp_short}",
                dur=duration_for(cfg),
                result=result,
                win=win,
            ))
    return events


# ══ 篮球 · balldontlie ═══════════════════════════════════════════════════
#
# 注册制，免费档 5 次/分钟（本脚本一次运行只发 1 次请求）。
# 免费档包含 Games 端点，能按 team_ids / start_date / end_date 过滤。
#
# 为什么不用官方端点（都实测过）：
#   · stats.nba.com 带全套请求头返回 200，但响应体是 NBA.com 的首页 HTML ——
#     反爬墙，不是数据，看状态码会以为成功了
#   · cdn.nba.com 403（住宅 IP 也 403）
#   · data.nba.net 证书错误，已经废弃
#
# ⚠️ 字段名注意：是 **visitor_team** 不是 away_team，是 **home_team**。
# 而且 Authorization 头**不带 Bearer 前缀** —— 这点和 PandaScore 不一样，
# 两者写反了都会 401，但报错信息看不出区别。


def fetch_nba(cfg: dict, lo: datetime, hi: datetime) -> list:
    key = SECRETS.get("BALLDONTLIE_KEY")
    if not key:
        raise RuntimeError(
            "未配置 BALLDONTLIE_KEY。"
            "仓库 Settings → Secrets and variables → Actions 加一个，"
            "本地跑就写进 tools/.env"
        )

    team_id = cfg.get("balldontlieTeamId")
    team_name = cfg.get("team") or ""
    team_label = cfg.get("teamLabel") or team_name or "篮球"
    cn_map = cfg.get("opponentCn") or {}

    url = (f"https://api.balldontlie.io/v1/games?team_ids[]={team_id}"
           f"&start_date={lo.strftime('%Y-%m-%d')}&end_date={hi.strftime('%Y-%m-%d')}"
           f"&per_page=100")
    # 不带 Bearer 前缀，见上面那段注释
    data = json.loads(http_get(url, headers={"Authorization": key}))
    dump_raw(data, "nba/balldontlie")

    events = []
    for g in data.get("data") or []:
        home = g.get("home_team") or {}
        away = g.get("visitor_team") or {}

        # 先用 id 匹配；id 对不上就退回按队名匹配。
        # 双保险是因为它的 id 体系我们没实测过，而队名（full_name）是自解释的。
        if team_id not in (home.get("id"), away.get("id")) and \
           team_name not in (home.get("full_name"), away.get("full_name")):
            continue

        # 打完的**保留**（以前是跳过的），把比分带上
        finished = (g.get("status") or "").lower().startswith("final")

        raw_start = g.get("date")
        if not raw_start:
            continue
        start = to_utc(raw_start)
        if not (lo <= start <= hi):
            continue

        # 上面可能走的是队名匹配，这里统一按 id 判主客；判不出来再退回队名
        if team_id in (home.get("id"), away.get("id")):
            ours_home = home.get("id") == team_id
        else:
            ours_home = home.get("full_name") == team_name
        opp = away if ours_home else home
        side = "主" if ours_home else "客"

        opp_name = opp.get("full_name") or opp.get("name") or "?"

        # 比分一律「我们-对手」，不跟着主客变 —— 理由同 fetch_football
        result, win = None, None
        if finished:
            hs, aws = g.get("home_team_score"), g.get("visitor_team_score")
            if hs is not None and aws is not None:
                ours, theirs = (hs, aws) if ours_home else (aws, hs)
                result = f"{ours}-{theirs}"
                win = ours > theirs

        events.append(make_event(
            cat="nba",
            eid=f"nba-{g.get('id')}",
            title=f"{team_label}({side}) vs {opp_name}",
            short=pick_short(opp.get("abbreviation"), opp.get("name"), cn_map),
            start=start,
            end=None,
            venue=None,
            source="balldontlie",
            rank=RANK_RACE,
            group=f"nba-{g.get('id')}",
            # 同 mlb：标题读作「勇士 · NBA」，对齐「皇马 · 西甲」的写法
            group_name="NBA",
            dur=duration_for(cfg),
            result=result,
            win=win,
        ))
    return events


# ══ CS2 · PandaScore ═════════════════════════════════════════════════════
#
# 注册制，免费档 1000 次/小时（本脚本一次运行只发 1 次请求）。
# Bearer token，注意和 balldontlie 不一样，那个不带前缀。
#
# 为什么不用官方的 HLTV 之类：没有官方接口，HLTV 明确禁止抓取。
# PandaScore 是电竞数据里唯一有正经文档和免费档的。
#
# ⚠️ 这里**没有用 filter[opponent_id]** 做服务端过滤，而是拉一页 upcoming
# 再本地按对手 id 过滤。服务端过滤语法没实测过，本地过滤是不依赖文档的 ——
# 代价是一次拉 100 条，命中率取决于猎鹰未来 60 天有没有排上比赛。
# 真出现「赛程里有但这里没抓到」，再换成 filter[opponent_id]。


def fetch_cs2(cfg: dict, lo: datetime, hi: datetime) -> list:
    token = SECRETS.get("PANDASCORE_TOKEN")
    if not token:
        raise RuntimeError(
            "未配置 PANDASCORE_TOKEN。"
            "仓库 Settings → Secrets and variables → Actions 加一个，"
            "本地跑就写进 tools/.env"
        )

    team_id = cfg.get("pandascoreTeamId")
    team_name = cfg.get("team") or ""
    team_label = cfg.get("teamLabel") or team_name or "CS2"

    headers = {"Authorization": f"Bearer {token}"}

    # 两次请求：未来的和过去的。过去那份专门用来出赛果 ——
    # results 字段只有 /matches/past 里有，/matches/upcoming 里没有。
    # past 那份如果失败不该把 upcoming 一起带崩，所以单独 try。
    events = []
    for endpoint, per_page in (("upcoming", 100), ("past", 50)):
        url = f"https://api.pandascore.co/csgo/matches/{endpoint}?per_page={per_page}"
        try:
            data = json.loads(http_get(url, headers=headers))
        except Exception as exc:  # noqa: BLE001
            if endpoint == "upcoming":
                raise
            # 拿不到过去的比赛只影响赛果，未来的赛程已经拿到了，不值得整源失败
            print(f"    · 取往期赛果失败（{type(exc).__name__}），这次没有 CS2 比分")
            continue
        dump_raw(data, f"cs2/pandascore/{endpoint}")

        # 返回的是一个**数组**，不是对象
        for m in data or []:
            opponents = [o.get("opponent") or {} for o in (m.get("opponents") or [])]
            if len(opponents) < 2:
                continue

            # 同样双保险：id 优先，队名兜底
            ours = next((o for o in opponents if o.get("id") == team_id), None)
            if not ours and team_name:
                ours = next((o for o in opponents if o.get("name") == team_name), None)
            if not ours:
                continue

            raw_start = m.get("begin_at")
            if not raw_start:
                continue
            start = to_utc(raw_start)
            if not (lo <= start <= hi):
                continue

            opp = next((o for o in opponents if o is not ours), {})

            # 比分一律「我们-对手」，不跟着主客变 —— 理由同 fetch_football。
            # past 列表里每场都有一个 results 数组，形如
            # [{"team_id": 1, "score": 2}, {"team_id": 2, "score": 0}]
            result, win = None, None
            if endpoint == "past":
                scores = {r.get("team_id"): r.get("score") for r in (m.get("results") or [])}
                ow, tw = scores.get(ours.get("id")), scores.get(opp.get("id"))
                if ow is not None and tw is not None:
                    result = f"{ow}-{tw}"
                    win = ow > tw

            # 赛事名尽量拼全：联赛 + 锦标赛，如「BLAST Premier · Fall Final」
            serie = (m.get("serie") or {}).get("full_name") or ""
            tour = (m.get("tournament") or {}).get("name") or ""
            suffix = " · ".join(x for x in (serie, tour) if x) or "CS2"

            events.append(make_event(
                cat="cs2",
                eid=f"ps-{m.get('id')}",
                title=f"{team_label} vs {opp.get('name') or '?'} · {suffix}",
                # 对手的 code 是 2-3 个拉丁字符（如 NAVI / FNC），天然适配小组件，
                # 所以这里不查中文表 —— 查了反而要多维护一张 60 条的表
                short=opp.get("acronym") or opp.get("name") or "?",
                start=start,
                end=to_utc(m["end_at"]) if m.get("end_at") else None,
                venue=None,
                source="pandascore",
                rank=RANK_RACE,
                group=f"ps-{m.get('id')}",
                group_name=suffix,
                dur=duration_for(cfg),
                result=result,
                win=win,
            ))
    return events


# ══ 英雄联盟 · LoL Esports ═══════════════════════════════════════════════
#
# Riot 的网页接口，带一个网页自己用的公开 key（多年没换过，但随时可能轮换；
# 真轮换了改上面 LOL_API_KEY 一行就行，不用动 App）。
# hl=zh-CN 时 blockName 直接是中文，省一层翻译。

def fetch_lol(cfg: dict, lo: datetime, hi: datetime) -> list:
    league_id = cfg["leagueId"]
    team_name = cfg["team"]
    team_label = cfg.get("teamLabel") or team_name
    url = ("https://esports-api.lolesports.com/persisted/gw/getSchedule"
           f"?hl=zh-CN&leagueId={league_id}")
    data = json.loads(http_get(url, headers={"x-api-key": LOL_API_KEY}))
    dump_raw(data, "lol/lolesports")

    events = []
    for ev in data.get("data", {}).get("schedule", {}).get("events", []):
        if ev.get("type") != "match":
            continue
        match = ev.get("match") or {}
        teams = match.get("teams") or []
        # 只留我们追的队那几场
        ours = next((t for t in teams if t.get("name") == team_name), None)
        if not ours:
            continue

        start = to_utc(ev["startTime"])
        if not (lo <= start <= hi):
            continue

        opp = next((t for t in teams if t is not ours), {})
        block = ev.get("blockName") or ""
        league = (ev.get("league") or {}).get("name") or ""
        best_of = (match.get("strategy") or {}).get("count")

        # 打完的**保留**，把比分带上。这个接口本来就把整段赛程都返回回来
        # （含几个月前打完的），靠上面那个窗口过滤把旧的挡在外面。
        #
        # 比分一律「我们-对手」，不跟着主客变 —— 理由同 fetch_football。
        result, win = None, None
        if ev.get("state") == "completed":
            ow = (ours.get("result") or {}).get("gameWins")
            tw = (opp.get("result") or {}).get("gameWins")
            if ow is not None and tw is not None:
                result = f"{ow}-{tw}"
                win = ow > tw

        suffix = " ".join(x for x in (league, block) if x)
        events.append(make_event(
            cat="lol",
            eid=f"lol-{match.get('id')}",
            title=f"{team_label} vs {opp.get('name', '?')}"
                  + (f" · {suffix}" if suffix else "")
                  + (f" BO{best_of}" if best_of else ""),
            # 对手的 code 是 IG / AL / BLG 这种 2-3 字符，天然适配小组件
            short=opp.get("code") or opp.get("name") or "?",
            start=start,
            end=start + timedelta(hours=3),
            venue=None,
            source="lolesports",
            rank=RANK_RACE,
            group=f"lol-{match.get('id')}",
            group_name=suffix or "比赛",
            url="https://lolesports.com/schedule",
            dur=duration_for(cfg),
            result=result,
            win=win,
        ))
    return events


# ══ 主流程 ════════════════════════════════════════════════════════════════

# 顺序就是日志和 sources 里的顺序：赛车项目在前（它们一个周末撑起一张卡），
# 队伍类在后。
SOURCES = [
    ("f1", fetch_f1),
    ("motogp", fetch_motogp),
    ("football", fetch_football),
    ("mlb", fetch_mlb),
    ("nba", fetch_nba),
    ("cs2", fetch_cs2),
    ("lol", fetch_lol),
]


def parse_args() -> argparse.Namespace:
    ap = argparse.ArgumentParser(
        description="把七个赛事源抓成一个 calendar.json",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "调试用法：\n"
            "  python tools/fetch_calendar.py --only football --raw\n"
            "     只跑足球这一个源，并把上游原始响应打出来。\n"
            "     --only 模式**不写任何文件** —— 免得用一份残缺的日历\n"
            "     把 data/calendar.json 覆盖掉。\n"
        ),
    )
    ap.add_argument("--only", metavar="CAT",
                    help=f"只跑一个类别，可选：{', '.join(k for k, _ in SOURCES)}")
    ap.add_argument("--raw", action="store_true",
                    help="把上游原始响应打到 stdout（核对字段名用，会截断）")
    return ap.parse_args()


def main() -> int:
    global RAW
    args = parse_args()
    RAW = args.raw

    only = (args.only or "").strip().lower()
    if only and only not in dict(SOURCES):
        print(f"未知类别 {only!r}，可选：{', '.join(k for k, _ in SOURCES)}", file=sys.stderr)
        return 2

    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))

    print("密钥：")
    report_secrets()

    now = datetime.now(timezone.utc)
    # 往回收 7 天，不是 1 天。
    #
    # 一开始只要 1 天（「今天已经打完的场次也要进来」），但加了赛果之后不够用了：
    # 棒球一个系列赛横跨 4 天，等它打完的时候，前面几场早就掉出 1 天的窗口 ——
    # 系列赛战绩会从「3 胜 1 负」退化成「1 胜 0 负」，看着像只打了一场。
    #
    # 7 天 = App 那边 5 天的回溯窗口 + 最多 6 小时的抓取间隔 + 一点余量，
    # 保证 App 想看的区间在发布出来的数据里一定是完整的。
    lo = now - timedelta(days=BACK_DAYS)
    hi = now + timedelta(days=WINDOW_DAYS)

    all_events: list = []
    health: dict = {}
    # 类别的显示名和图标地址，给 App 的卡片标题 + 左侧图标用。
    # 都从 config 里推导，所以改 config 换了队伍，这两张表会自动跟着变。
    labels: dict = {}
    # 字母块标记：用户还没上传图标时，卡片左侧显示这个
    marks: dict = {}

    for cat, fn in SOURCES:
        if only and cat != only:
            continue
        cfg = config.get(cat) or {}

        labels[cat] = cfg.get("teamLabel") or cfg.get("label") or cat
        marks[cat] = cfg.get("mark") or labels[cat][:2]

        if only:
            # --only 是调试：即便 config 里关着也照跑，不然没法验证新写的源
            pass
        elif not cfg.get("enabled"):
            health[cat] = {"ok": True, "count": 0, "error": None, "enabled": False}
            print(f"[{cat}] 已禁用，跳过")
            continue

        print(f"[{cat}] 抓取中…")
        try:
            got = fn(cfg, lo, hi)
            all_events.extend(got)
            health[cat] = {"ok": True, "count": len(got), "error": None, "enabled": True}
            print(f"[{cat}] {len(got)} 场")
        except Exception as exc:  # noqa: BLE001 —— 单个源失败绝不能带崩整个文件
            msg = f"{type(exc).__name__}: {exc}"
            health[cat] = {"ok": False, "count": 0, "error": msg, "enabled": True}
            print(f"[{cat}] 失败：{msg}", file=sys.stderr)

    all_events.sort(key=lambda e: (e["start"], e["cat"]))

    if only:
        # 调试模式到此为止，不写文件（见 parse_args 的说明）
        print(f"\n--only {only}：{len(all_events)} 场")
        for e in all_events[:15]:
            print(f"  {e['start']}  {e['cat']:<8} {e['short']:<8} {e['title']}")
        if len(all_events) > 15:
            print(f"  …还有 {len(all_events) - 15} 场")
        return 0

    out = {
        "generated_at": iso_z(now),
        "window_days": WINDOW_DAYS,
        "labels": labels,
        "marks": marks,
        "sources": health,
        "events": all_events,
    }
    text = json.dumps(out, ensure_ascii=False, indent=2)

    # 落盘前的最后一道闸，见 assert_no_secrets
    assert_no_secrets(text, "data/calendar.json")

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(text, encoding="utf-8")

    # 往 APK 里也塞一份，作为「没网 + 没缓存」时的最后兜底。
    # 目录可能还不存在（第一次跑），建一下。
    ASSET_PATH.parent.mkdir(parents=True, exist_ok=True)
    ASSET_PATH.write_text(text, encoding="utf-8")

    ok = sum(1 for h in health.values() if h["ok"])
    print(f"\n共 {len(all_events)} 场，{ok}/{len(health)} 个源正常")
    print(f"已写入 {OUT_PATH.relative_to(ROOT)} 与 {ASSET_PATH.relative_to(ROOT)}")

    # 全部源都挂掉时返回非 0，让 workflow 变红 —— 至少能收到提醒。
    # 只有部分源挂掉不算失败：这是常态，App 端会按类别降级。
    return 0 if ok > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
