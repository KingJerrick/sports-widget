#!/usr/bin/env python3
"""
把五个赛事源抓成一个 calendar.json。

── 为什么抓取放在云端而不是手机里 ────────────────────────────────────────
手机只发一个 HTTP GET 拿这一个文件，好处有三条：
  1. 解析逻辑在这里，改一次重跑 workflow 就行，**不用重新打包发 APK**
  2. 抓取只发生在 GitHub 的服务器上，你的手机 IP 不会因为高频轮询被赛事站点风控
  3. 数据有 git 历史，出问题能回溯

第 1 条最要紧：五个源里有三个是**网页私有接口**（MotoGP、Sofascore、lolesports），
无文档无承诺，响应结构随时可能变。放在这里只需要改一个函数。

── 只用标准库 ──────────────────────────────────────────────────────────
urllib / json / datetime，不装 requests —— workflow 里省掉 pip install 一步。
唯一的例外是 Sofascore：它对客户端 TLS 指纹做风控，Python 的 ssl 栈会吃 403，
只能让那一个源走 curl 子进程（curl 在 ubuntu-latest 和 Windows 上都自带）。
详见 http_get_curl 上面的实测记录。

── 每个源独立失败 ──────────────────────────────────────────────────────
fetch_xxx() 各自 try/catch，一个源挂掉只影响它自己那一类，其余照常出数据，
失败信息写进 sources.<cat>.error，App 的「数据源状态」直接显示。
"""

import json
import shutil
import subprocess
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

# 一次发布这么多天的赛程，App 自己从中截未来 7 天。
# 放这么宽是为了容错：即使 Actions 定时挂了一周，小组件也不会突然空掉 ——
# 赛程本来就是提前几周就定下来的。
WINDOW_DAYS = 60

# 部分 CDN / 站点对没有 UA 的请求直接 403
UA = "sports-widget/1.0 (+https://github.com/KingJerrick/sports-widget)"

# lolesports 网页自己用的公开 key。Riot 随时可能轮换，轮换了改这一行即可。
LOL_API_KEY = "0TvQnueqKa5mxJntVWt0w4LpLfEkrV1Ta8rQBb9Z"

# ── 图标地址 ──────────────────────────────────────────────────────────────
# 每个类别一个图标，App 拉下来缓存到本地，显示在卡片左侧。
#
# 为什么是「App 运行时去原站拉」而不是打包进 APK 或提交进仓库：
#   · 队标是别人的商标，扔进公开仓库算是再分发
#   · 打进 APK 意味着构建时要联网下载，本机 Android Studio 构建会缺资源
#   · 运行时拉一次就缓存，之后离线也能显示；拉不到就退回字母块，不会开天窗
#
# 全部实测过（见每条的注释），格式都是 Android 的 BitmapFactory 能解的。
# 想换图标直接在这里改，或者在某类的 config 里加 "logo": "..." 覆盖。
STATIC_LOGOS = {
    # F1 官网自己的品牌标。原地址是 SVG（Android 解不了），
    # 好在 media.formula1.com 是 Cloudinary，加个 f_png 参数就转成 PNG 了。
    "f1": "https://media.formula1.com/image/upload/c_lfill,w_128,h_128,f_png/q_auto"
          "/v1740000001/fom-website/2026/F1%20App%20Store%20Logo/f1-app-logo.svg",
    # MotoGP 母公司 Dorna 的静态资源站，180×180 的 PNG
    "motogp": "https://static.dorna.com/assets/logos/mgp/brand/mgp-favicon-180x180.png",
}


def sofascore_logo(team_id: int) -> str:
    """Sofascore 的队徽接口。皇马和猎鹰都从这里取，跟随 config 里的 sofascoreId 走。"""
    return f"https://api.sofascore.com/api/v1/team/{team_id}/image"


# 有些源把队标随数据一起给（比如 lolesports），抓的时候顺手记在这里，
# main() 最后合并进 logos。
_discovered_logos: dict = {}

# ── chip 文字的长度上限 ────────────────────────────────────────────────────
# 小组件每列可用宽约 28.7dp，7dp 字号下能放 3 个汉字或 6 个拉丁字符。
# 这里按「汉字算 2 个宽度单位、拉丁算 1 个」来量，上限 6 个单位。
# 超了就在后端截断 —— 不要指望小组件的 ellipsize 兜底，它只能掩盖问题。
SHORT_WIDTH_LIMIT = 6


# ══ 工具 ══════════════════════════════════════════════════════════════════

def http_get(url: str, headers: dict | None = None, attempts: int = 3) -> str:
    """
    大部分源用这个。少数对 TLS 指纹敏感的源走 [http_get_curl]。

    带重试是因为这几个源都在 Cloudflare 后面，实测会偶发 TLS 握手超时
    （同一台机器同一个 URL，上一次成功、这一次超时）。重试两次基本就稳了 ——
    不然一次网络抖动就会让某一类赛事整天没有数据，而 App 端看起来只是「今天没比赛」。
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


# Sofascore 对**客户端 TLS 指纹**做风控，而不是看 UA 或 IP。
# 实测（同一台机器、同一时刻、同一个 URL）：
#     curl                                 -> 200
#     curl --http1.1                       -> 200
#     curl -A "Python-urllib/3.12"         -> 200   ← 说明不是看 UA
#     Python urllib（换浏览器 UA、补齐全部请求头）-> 403 Varnish
# 所以只能让这一个源走 curl 子进程。curl 在 ubuntu-latest 和 Windows 上都自带。
BROWSER_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")


def curl_request(url: str, headers: dict | None = None) -> tuple[int, str]:
    """
    返回 (HTTP 状态码, 响应体)。

    刻意**不加 --fail**：状态码要自己判断。因为对 Sofascore 来说 404 是正常业务状态
    （这个队未来没有比赛），不是错误 —— 混在一起就分不出「没比赛」和「抓取失败」了。
    """
    exe = shutil.which("curl")
    if not exe:
        raise RuntimeError("系统里找不到 curl（这个源需要它绕开 TLS 指纹风控）")

    # -w 把状态码追加到响应体后面，用换行分隔，回来再切开
    # --retry 交给 curl 自己重试网络层错误（连接超时、TLS 握手失败等），
    # 不重试 HTTP 状态码 —— 那些是业务状态，见下面 404 的处理
    cmd = [exe, "-sS", "--max-time", "25", "--retry", "2", "--retry-delay", "2",
           "-w", "\n%{http_code}", "-A", BROWSER_UA]
    for k, v in (headers or {}).items():
        cmd += ["-H", f"{k}: {v}"]
    cmd.append(url)

    proc = subprocess.run(cmd, capture_output=True, timeout=60)
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", errors="replace").strip()[:200]
        raise RuntimeError(f"curl 退出码 {proc.returncode}：{err}")

    out = proc.stdout.decode("utf-8", errors="replace")
    body, _, status = out.rpartition("\n")
    return int(status or 0), body


def to_utc(value: str) -> datetime:
    """
    把各源五花八门的时间串统一成带时区的 datetime。

    实际会碰到的形状：
      "2026-09-13T13:00:00Z"        Jolpica（date + time 拼起来）
      "2026-09-13T14:00:00+0200"    MotoGP Pulselive，偏移量不带冒号
      "2026-09-12T09:00:00Z"        lolesports
      Unix 秒                        Sofascore，另外处理
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


def make_event(cat, eid, title, short, start, end, venue, source, rank,
               group, group_name, url=None):
    """
    group / group_name：小组件是按**卡片**显示的，一张卡对应一组赛事。

      赛车项目一个比赛周末是一个整体：F1 西班牙站的 FP1/FP2/排位/正赛
      应该是**一张**卡（标题「F1 · 西班牙站」，下面列出各场次），
      而不是五张各说各话的卡。所以同一个周末的场次共用 group。

      队伍类没有这种层级，一场比赛就是一张卡，group 用比赛自己的 id。
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
    }


# ══ F1 · Jolpica ═════════════════════════════════════════════════════════
#
# Ergast 的社区继任者，Cloudflare 托管。传统 Ergast 只有正赛日期，Jolpica 补上了
# 练习赛/排位的时间字段，所以一个源就够了，不用再找第二个。
#
# 两种周末的字段不一样，都要认：
#   常规周末：FirstPractice / SecondPractice / ThirdPractice / Qualifying
#   冲刺周末：FirstPractice / Qualifying / SprintQualifying / Sprint（没有二三练）
# 所以下面按「出现哪个字段就取哪个」来处理，不写死顺序。

# ⚠️ 正赛不在这个表里，它是特例 —— 见 fetch_f1 里的说明。
# 冲刺排位和冲刺赛都简称「冲刺」会分不清，所以分开：冲刺 / 冲刺赛（都是 2~3 个汉字，放得下）。
F1_SESSION_LABELS = {
    "FirstPractice":    ("第一次练习", "FP1", RANK_PRACTICE),
    "SecondPractice":   ("第二次练习", "FP2", RANK_PRACTICE),
    "ThirdPractice":    ("第三次练习", "FP3", RANK_PRACTICE),
    "Qualifying":       ("排位赛", "排位", RANK_QUALI),
    "SprintQualifying": ("冲刺排位", "冲刺", RANK_QUALI),
    "Sprint":           ("冲刺赛", "冲刺赛", RANK_RACE),
    "Race":             ("正赛", "正赛", RANK_RACE),
}

F1_COUNTRY_CN = {
    "Spain": "西班牙", "Azerbaijan": "阿塞拜疆", "Bahrain": "巴林", "Saudi Arabia": "沙特",
    "Australia": "澳大利亚", "Japan": "日本", "China": "中国", "United States": "美国",
    "USA": "美国", "Italy": "意大利", "Monaco": "摩纳哥", "Canada": "加拿大",
    "Austria": "奥地利", "Great Britain": "英国", "UK": "英国", "Belgium": "比利时",
    "Hungary": "匈牙利", "Netherlands": "荷兰", "Singapore": "新加坡", "Mexico": "墨西哥",
    "Brazil": "巴西", "Qatar": "卡塔尔", "United Arab Emirates": "阿布扎比", "UAE": "阿布扎比",
    "Korea": "韩国", "South Korea": "韩国", "France": "法国", "Germany": "德国",
    "Portugal": "葡萄牙", "Turkey": "土耳其", "Russia": "俄罗斯", "Vietnam": "越南",
    "Sweden": "瑞典", "Switzerland": "瑞士", "South Africa": "南非", "Argentina": "阿根廷",
    "India": "印度", "Malaysia": "马来西亚", "Finland": "芬兰",
}


def fetch_f1(cfg: dict, lo: datetime, hi: datetime) -> list:
    season = lo.year
    url = f"https://api.jolpi.ca/ergast/f1/{season}/races.json?limit=100"
    data = json.loads(http_get(url))
    races = data["MRData"]["RaceTable"]["Races"]

    events = []
    for race in races:
        circuit = race.get("Circuit", {})
        country = circuit.get("Location", {}).get("country", "")
        place = F1_COUNTRY_CN.get(country) or race.get("raceName", "?")
        venue = circuit.get("circuitName")
        round_no = race.get("round", "?")
        # 第 14 站 → 链接到该站的正赛页面
        race_url = f"https://www.formula1.com/en/racing/{season}"

        for field, (label, short, rank) in F1_SESSION_LABELS.items():
            if field not in cfg.get("sessions", list(F1_SESSION_LABELS)):
                continue

            if field == "Race":
                # ⚠️ 正赛是个特例：Jolpica/Ergast 把它的时间放在 race 对象的**顶层**
                # date/time 上，而不是像练习赛那样有个 "Race" 子对象。
                # 2026 赛季 23 站全都如此 —— 照着字段名遍历会正好漏掉整个周末最重要的那场，
                # 而且不报错、不崩，只是那一格永远空着（这个坑是靠对着真实输出核对才发现的）。
                node = {"date": race.get("date"), "time": race.get("time")}
            else:
                node = race.get(field)

            if not node or not node.get("date"):
                continue
            t = node.get("time") or "00:00:00Z"
            start = to_utc(f"{node['date']}T{t}")
            if not (lo <= start <= hi):
                continue
            events.append(make_event(
                cat="f1",
                eid=f"f1-{season}-r{round_no}-{field.lower()}",
                title=f"F1 {place}站 · {label}",
                short=short,
                start=start,
                end=start + timedelta(hours=2),
                venue=venue,
                source="jolpica",
                rank=rank,
                # 同一站的 FP1/FP2/排位/正赛共用一张卡
                group=f"f1-{season}-r{round_no}",
                group_name=f"{place}站",
                url=race_url,
            ))
    return events


# ══ MotoGP · Pulselive ═══════════════════════════════════════════════════
#
# motogp.com 自己的前端在调的接口 —— 无公开文档、无承诺，属于易变的那一类。
# 实测已经踩到两个坑：
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
            ))
    return events


# ══ CS2 / 足球 · Sofascore ═══════════════════════════════════════════════
#
# 一个源覆盖两类。网页私有接口，但实测免注册、免 header、大陆直连 0.4s，
# 而且覆盖比 football-data.org 免费版还全（连国王杯都有）。
#
# events/next/0 一次返回 30 场未来比赛，足够盖住 7 天窗口。

def _team_short(team: dict, cn_map: dict) -> str:
    """对手短名：中文覆盖表 -> nameCode/acronym -> 球队名截断。"""
    for key in ("nameCode", "shortName", "acronym"):
        code = team.get(key)
        if code and code in cn_map:
            return cn_map[code]
    for key in ("nameCode", "acronym"):
        code = team.get(key)
        if code:
            return code
    return (team.get("shortName") or team.get("name") or "?").strip()


def fetch_sofascore(cfg: dict, cat: str, lo: datetime, hi: datetime) -> list:
    team_id = cfg["sofascoreId"]
    team_name = cfg["team"]
    team_label = cfg.get("teamLabel") or team_name
    cn_map = cfg.get("opponentCn") or {}
    url = f"https://api.sofascore.com/api/v1/team/{team_id}/events/next/0"
    # 注意走 curl_request 而不是 http_get —— 原因见上面 BROWSER_UA 那段注释
    status, body = curl_request(url)

    # ⚠️ Sofascore 对「未来没有比赛」的队返回的是 **404，不是空数组**。
    # 猎鹰现在就是这个状态（BLAST 打完、下一站还没排上），而它是常态不是故障 ——
    # 记成失败的话，App 的「数据源状态」会一直挂红，反而把真故障淹了。
    if status == 404:
        print("    （404 —— 该队未来暂无赛事，属正常状态）")
        return []
    if status != 200:
        raise RuntimeError(f"HTTP {status}：{body[:200]}")

    data = json.loads(body)

    events = []
    for ev in data.get("events", []):
        ts = ev.get("startTimestamp")
        if not ts:
            continue
        start = datetime.fromtimestamp(int(ts), tz=timezone.utc)
        if not (lo <= start <= hi):
            continue

        home = ev.get("homeTeam") or {}
        away = ev.get("awayTeam") or {}
        # 判断我们追的队这场是主是客，对手取另一边。
        # 用 id 比用队名可靠 —— Sofascore 的展示名会随语言变。
        if home.get("id") == team_id:
            opp, side = away, "主"
        elif away.get("id") == team_id:
            opp, side = home, "客"
        else:
            opp, side = away, "?"

        opp_name = opp.get("name") or "?"
        tour = (ev.get("tournament") or {}).get("name") or ""

        events.append(make_event(
            cat=cat,
            eid=f"sofa-{ev.get('id')}",
            title=f"{team_label}({side}) vs {opp_name}" + (f" · {tour}" if tour else ""),
            short=_team_short(opp, cn_map),
            start=start,
            end=None,
            venue=None,
            source="sofascore",
            # 追的队的比赛本来就少，每一场都值得占一格
            rank=RANK_RACE,
            # 队伍类没有「一个周末」这种层级，一场比赛就是一张卡
            group=f"sofa-{ev.get('id')}",
            group_name=tour or "比赛",
            url=f"https://www.sofascore.com/event/{ev.get('id')}",
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

        # 队标是随赛程一起给的，顺手记下来给小组件用。
        # ⚠️ 接口给的是 http:// 地址，Android 9 以后默认禁止明文流量，必须升成 https。
        img = (ours.get("image") or "").replace("http://", "https://")
        if img:
            _discovered_logos["lol"] = img

        start = to_utc(ev["startTime"])
        if not (lo <= start <= hi):
            continue

        opp = next((t for t in teams if t is not ours), {})
        block = ev.get("blockName") or ""
        league = (ev.get("league") or {}).get("name") or ""
        best_of = (match.get("strategy") or {}).get("count")

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
        ))
    return events


# ══ 主流程 ════════════════════════════════════════════════════════════════

SOURCES = [
    ("f1", fetch_f1),
    ("motogp", fetch_motogp),
    ("cs2", lambda cfg, lo, hi: fetch_sofascore(cfg, "cs2", lo, hi)),
    ("football", lambda cfg, lo, hi: fetch_sofascore(cfg, "football", lo, hi)),
    ("lol", fetch_lol),
]


def main() -> int:
    config = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))

    now = datetime.now(timezone.utc)
    # 从一天前开始收，这样今天已经打完的场次也能进来 ——
    # 日历本来就该显示「今天有什么」，而不是「今天还剩什么」。
    lo = now - timedelta(days=1)
    hi = now + timedelta(days=WINDOW_DAYS)

    all_events: list = []
    health: dict = {}
    # 类别的显示名和图标地址，给 App 的卡片标题 + 左侧图标用。
    # 都从 config 里推导，所以改 config 换了队伍，这两张表会自动跟着变。
    labels: dict = {}
    logos: dict = {}
    # 字母块标记：图标还没拉下来（首次安装 / 离线 / 被拦）时，
    # 卡片左侧显示这个，不至于开天窗
    marks: dict = {}

    for cat, fn in SOURCES:
        cfg = config.get(cat) or {}

        labels[cat] = cfg.get("teamLabel") or cfg.get("label") or cat
        marks[cat] = cfg.get("mark") or labels[cat][:2]
        if cfg.get("logo"):
            logos[cat] = cfg["logo"]                      # config 里写死了就用它
        elif cat in STATIC_LOGOS:
            logos[cat] = STATIC_LOGOS[cat]
        elif cfg.get("sofascoreId"):
            # 换了队伍，队徽地址跟着 sofascoreId 自动变，不用手工同步
            logos[cat] = sofascore_logo(int(cfg["sofascoreId"]))

        if not cfg.get("enabled"):
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

    # lolesports 那边队标是随赛程一起给的，抓取时顺手记下来（见 fetch_lol）
    logos.update(_discovered_logos)

    out = {
        "generated_at": iso_z(now),
        "window_days": WINDOW_DAYS,
        "labels": labels,
        "logos": logos,
        "marks": marks,
        "sources": health,
        "events": all_events,
    }
    text = json.dumps(out, ensure_ascii=False, indent=2)

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
