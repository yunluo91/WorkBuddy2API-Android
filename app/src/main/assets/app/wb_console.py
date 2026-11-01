#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""综合API网关 · 内置控制台（对话 + 终端）

由 wb_boot.py 常驻起在 (网关端口 + 2)，跟思考调试台（端口 + 1）并排。
所有出站请求都不发 HTTP，直接在同一个 Python 进程里调 wb_proxy 的函数，
所以不需要 API Key、也不会有面板会话盐问题：

  /v1/models  -> wb_proxy.fetch_models()        + wb_proxy.model_entry()
  对话        -> wb_proxy.build_upstream_body() + 上游 /v1/chat/completions

对话页支持：选模型 / 调思考强度 / 多会话（上下文各存一份）/ 流式输出。
终端页支持：在 App 的 uid 下执行 shell 命令，历史可翻，随时中断。
"""

import json
import os
import select
import signal
import subprocess
import sys
import threading
import time
from urllib.parse import urlparse, parse_qs

import wb_channels

try:
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
except Exception:                                              # pragma: no cover
    from http.server import BaseHTTPRequestHandler, HTTPServer as ThreadingHTTPServer

BASE = os.path.dirname(os.path.abspath(__file__))

# WB-RESTORE app67 那次替换吃掉的全局常量
USAGE_LOG = os.path.join(BASE, "usage", "usage.jsonl")

_OUTCOME_LABEL = {
    "completed": "成功",
    "client_aborted": "客户端中断",
    "upstream_aborted": "上游中断",
    "failed": "失败",
    "error": "错误",
    "empty_reasoning": "空推理",
}

_WORKDIR_CANDIDATES = (
    "/sdcard",
    "/storage/emulated/0",
    "/storage/self/primary",
    "/mnt/sdcard",
)

STATE_DIR = os.path.join(BASE, "wb_app")
CHAT_FILE = os.path.join(STATE_DIR, "chat.json")
TERM_FILE = os.path.join(STATE_DIR, "terminal.log")
CONSOLE_PORT_FILE = os.path.join(STATE_DIR, "console.port")

REGISTRY = None          # wb_boot 注入
_gateway_port = 0
_console_port = 0
_chat_lock = threading.Lock()


# ---------------------------------------------------------------- 注册表

def bind(registry):
    """wb_boot 在跑上游之前把注册表交过来（build_upstream_body 等）。"""
    global REGISTRY
    REGISTRY = registry


def _call(name, *args, **kwargs):
    reg = REGISTRY or {}
    fn = reg.get(name)
    if fn is None:
        raise RuntimeError("上游函数 %s 还没就绪" % name)
    return fn(*args, **kwargs)


def _ensure_dir():
    try:
        os.makedirs(STATE_DIR, exist_ok=True)
    except Exception:
        pass


# ---------------------------------------------------------------- 会话存储

def load_chat():
    try:
        with open(CHAT_FILE, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if isinstance(data, dict) and isinstance(data.get("sessions"), list):
            return data
    except Exception:
        pass
    return {"sessions": [], "active": ""}


def save_chat(data):
    _ensure_dir()
    tmp = CHAT_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False)
    os.replace(tmp, CHAT_FILE)


def new_session(title="新会话"):
    data = load_chat()
    sid = "s%d" % int(time.time() * 1000)
    data["sessions"].append({
        "id": sid,
        "title": title,
        "model": "",
        "effort": "",          # 空 = 跟随 App 的默认档位
        "context": 0,          # 0 = 用模型默认上下文
        "created": int(time.time()),
        "messages": [],
    })
    data["active"] = sid
    save_chat(data)
    return sid


# ---------------------------------------------------------------- 模型清单

def _levels_of(entry):
    """该模型支持的思考档位 + 默认档，取上游自己的目录（wb_proxy 的实现）。

    注意：/v1/models 的返回体里没有档位信息（capabilities.reasoning 只是个布尔），
    真正的档位在 wb_proxy.model_reasoning_meta() 里，所以走那条路。
    """
    mid = ""
    if isinstance(entry, dict):
        mid = str(entry.get("id") or "")
    if not mid:
        return [], ""
    try:
        meta = _call("model_reasoning_meta", mid) or {}
    except Exception:
        meta = {}
    if not isinstance(meta, dict):
        return [], ""
    levels = meta.get("supportedEfforts") or []
    if not isinstance(levels, list):
        levels = []
    levels = [str(x) for x in levels]
    fixed = meta.get("effort")
    if not levels and isinstance(fixed, str) and fixed.strip():
        levels = [fixed.strip()]
    default = meta.get("defaultEffort")
    return levels, (default if isinstance(default, str) else "")


def _ks():
    import wb_settings
    return wb_settings


def _num(v, default=0):
    try:
        if v is None:
            return default
        f = float(v)
        return f
    except Exception:
        return default


def _slim_row(r):
    """把一条原始记录压成前端要的形状。字段名短，传得快。"""
    outcome = r.get("outcome") or ("failed" if r.get("error") else "completed")
    ok = (outcome == "completed")
    rates = r.get("cost_rates") or {}
    msg = r.get("message")
    status = r.get("status")
    if ok:
        reason = ""
    else:
        label = _OUTCOME_LABEL.get(outcome, outcome)
        bits = []
        if status:
            bits.append("HTTP %s" % status)
        if msg:
            bits.append(str(msg))
        reason = label + (("：" + " · ".join(bits)) if bits else "")
    return {
        "at": r.get("at") or 0,
        "iso": r.get("iso") or "",
        "model": r.get("model") or "",
        "ok": ok,
        "outcome": outcome,
        "reason": reason,
        "status": status,
        "message": msg or "",
        "pt": int(_num(r.get("prompt_tokens"))),
        "ct": int(_num(r.get("completion_tokens"))),
        "rt": int(_num(r.get("reasoning_tokens"))),
        "cat": int(_num(r.get("cached_tokens"))),
        "tt": int(_num(r.get("total_tokens"))),
        "credit": _num(r.get("credit")),
        "cny": r.get("cost_cny"),
        "rates": {
            "hit": rates.get("input_cache_hit"),
            "miss": rates.get("input_cache_miss"),
            "out": rates.get("output"),
        } if rates else None,
        "unit": r.get("cost_unit"),
        "cur": r.get("cost_currency"),
        "ms": int(_num(r.get("elapsed_ms"))),
        "ttft": int(_num(r.get("ttft_ms"))),
        "tps": _num(r.get("tokens_per_sec")),
        "hit_pct": _num(r.get("cache_hit_pct")),
        "effort": r.get("reasoning_effort") or "",
        "stream": bool(r.get("stream")),
        "realm": r.get("realm") or "",
        "n_msgs": int(_num(r.get("n_msgs"))),
        "account": (r.get("account") or "")[:8],
        "key_name": r.get("key_name") or "",
    }


def _tail_rows(limit):
    """从 usage.jsonl 尾部倒着读，拿最新 limit 条。

    整个文件可能有几十万行，直接 readlines 会吃掉一堆内存；
    这里从尾部按块往回读，读够就停。
    """
    if not os.path.exists(USAGE_LOG):
        return []
    rows = []
    try:
        with open(USAGE_LOG, "rb") as fh:
            fh.seek(0, os.SEEK_END)
            pos = fh.tell()
            buf = b""
            block = 64 * 1024
            while pos > 0 and len(rows) < limit:
                step = min(block, pos)
                pos -= step
                fh.seek(pos)
                buf = fh.read(step) + buf
                lines = buf.split(b"\n")
                buf = lines.pop(0)      # 头部可能被截断，留到下一轮
                for raw in reversed(lines):
                    raw = raw.strip()
                    if not raw:
                        continue
                    try:
                        rows.append(json.loads(raw.decode("utf-8", "replace")))
                    except Exception:
                        continue
                    if len(rows) >= limit:
                        break
            if len(rows) < limit and buf.strip():
                try:
                    rows.append(json.loads(buf.decode("utf-8", "replace")))
                except Exception:
                    pass
    except Exception:
        return rows
    return rows


def recent_calls(limit=1000, only=None, realm="all"):
    """调用记录 + 用量统计。limit 上限 1000（用户要的）。

    WB-CALLS-REALM
    realm: "" / "all" → 全部账号（默认）
           "cn"       → 只看国内版
           "intl"     → 只看国际版

    过滤放在排序之后、统计之前 —— 这样统计数字和列表是同一批行，
    不会出现"列表 10 条，统计说 900 条"的错位。
    """
    try:
        limit = int(limit)
    except Exception:
        limit = 1000
    limit = max(1, min(1000, limit))
    raw = _tail_rows(limit)
    rows = [_slim_row(r) for r in raw]
    # WB-CALLS-ORDER
    # 这里原来有一行 rows.reverse()，注释写着「最新的排前面」，
    # 但实际效果正好相反 —— 因为 _tail_rows() 是从文件**尾部往回**读的，
    # 它返回的本来就是「新 → 旧」。
    # 再 reverse 一次就变成「旧 → 新」，最新的跑到列表最底下。
    #
    # 现在改成按 at 显式降序排一次：
    #   · 不依赖 _tail_rows 的实现细节（哪天它改了顺序也不受影响）
    #   · 日志里如果因为并发写入导致行序错乱，这里也能纠正
    rows.sort(key=lambda r: r.get("at") or 0, reverse=True)
    # WB-CALLS-REALM 按版本过滤（"all" / 空 = 不过滤）
    want = str(realm or "").strip().lower()
    if want in ("cn", "intl"):
        rows = [r for r in rows if str(r.get("realm") or "").lower() == want]
    if only == "ok":
        rows = [r for r in rows if r["ok"]]
    elif only == "bad":
        rows = [r for r in rows if not r["ok"]]

    # ---- 用量统计（只统计成功请求，失败的没花掉 token）----
    ok_rows = [r for r in rows if r["ok"]]
    tot_pt = sum(r["pt"] for r in ok_rows)
    tot_ct = sum(r["ct"] for r in ok_rows)
    tot_rt = sum(r["rt"] for r in ok_rows)
    tot_cat = sum(r["cat"] for r in ok_rows)
    tot_tt = sum(r["tt"] for r in ok_rows)
    tot_cny = 0.0
    tot_credit = 0.0
    priced = 0
    for r in ok_rows:
        if r["cny"] is not None:
            tot_cny += _num(r["cny"])
            priced += 1
        tot_credit += _num(r["credit"])
    by_model = {}
    for r in ok_rows:
        m = r["model"] or "(未知)"
        b = by_model.setdefault(m, {"model": m, "n": 0, "tt": 0, "cny": 0.0})
        b["n"] += 1
        b["tt"] += r["tt"]
        if r["cny"] is not None:
            b["cny"] += _num(r["cny"])
    models = sorted(by_model.values(), key=lambda x: -x["cny"])

    return {
        "rows": rows,
        "stats": {
            "kept": len(rows),
            "ok": len(ok_rows),
            "bad": len(rows) - len(ok_rows),
            "hit_rate": (len(ok_rows) / len(rows) * 100.0) if rows else 0.0,
            "prompt_tokens": tot_pt,
            "completion_tokens": tot_ct,
            "reasoning_tokens": tot_rt,
            "cached_tokens": tot_cat,
            "total_tokens": tot_tt,
            "cost_cny": round(tot_cny, 6),
            "credit": round(tot_credit, 4),
            "priced": priced,
            "unpriced": len(ok_rows) - priced,
            "cache_hit_pct": (tot_cat / tot_pt * 100.0) if tot_pt else 0.0,
            "models": models[:12],
        },
        "limit": limit,
    }


def run_command(cmd, timeout=45, cwd=None):
    """在 App 的 uid 下跑一条 sh 命令，返回 {'code','out','ms','cwd'}。

    cwd 的来龙去脉：shell 每次都是新进程，用户敲 cd 只改了那个子进程的目录，
    父进程（我们）是不知道的。所以命令前后各加一条 pwd，让 shell 自己把
    最终目录报回来 —— 这样 cd 才有意义，下一条命令才能接着站在新目录里。
    """
    start = time.time()
    workdir = cwd or default_workdir()
    if not os.path.isdir(workdir):
        workdir = BASE

    # __WB_CWD__ 包在 \x01 里，正常输出不会撞上；解析时整段剥掉
    script = (
        cmd + "\n"
        "__wb_rc=$?\n"
        "printf '\\001__WB_CWD__%s\\001' \"$(pwd -P 2>/dev/null || pwd)\"\n"
        "exit $__wb_rc"
    )

    try:
        proc = subprocess.Popen(
            ["/system/bin/sh", "-c", script],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            cwd=workdir,
            env=_shell_env(workdir),
            start_new_session=True,
        )
    except Exception as exc:
        return {"code": -1, "out": "启动失败：%s" % exc,
                "ms": int((time.time() - start) * 1000), "cwd": workdir}

    timed_out = False
    try:
        out, _ = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except Exception:
            proc.kill()
        try:
            out, _ = proc.communicate(timeout=5)
        except Exception:
            out = b""
    except Exception as exc:
        return {"code": -1, "out": "执行异常：%s" % exc,
                "ms": int((time.time() - start) * 1000), "cwd": workdir}

    text = (out or b"").decode("utf-8", "replace")

    # 把探针行剥出来，剩下的是用户真正要看的输出
    real_cwd = workdir
    marker = "\x01__WB_CWD__"
    idx = text.rfind(marker)
    if idx >= 0:
        tail = text[idx + len(marker):]
        end_idx = tail.find("\x01")
        if end_idx >= 0:
            cand = tail[:end_idx].strip()
            if cand and os.path.isdir(cand):
                real_cwd = cand
            text = text[:idx] + tail[end_idx + 1:]
    text = text.rstrip("\n")

    code = proc.returncode if not timed_out else -9
    if timed_out:
        text = text + "\n[超时 %ds，已杀掉]" % timeout

    try:
        with open(TERM_FILE, "a", encoding="utf-8") as fh:
            fh.write("$ %s\n%s\n[exit %s]\n" % (cmd, text, code))
    except Exception:
        pass
    return {"code": code, "out": text,
            "ms": int((time.time() - start) * 1000),
            "cwd": os.path.realpath(real_cwd)}


def _shell_env(workdir=None):
    env = dict(os.environ)
    env.setdefault("TERM", "dumb")
    env.setdefault("LANG", "C.UTF-8")
    # 让子进程里的 pwd / ls 也跟着变，否则脚本里用 $PWD 又是旧的
    if workdir:
        env["PWD"] = workdir
    return env


def default_workdir():
    for d in _WORKDIR_CANDIDATES:
        try:
            if os.path.isdir(d) and os.access(d, os.R_OK):
                return d
        except Exception:
            continue
    home = os.environ.get("HOME")
    if home and os.path.isdir(home):
        return home
    return BASE


def ensure_panel_password():
    """面板密码丢了就补回 WB_PANEL_PASSWORD_FIX 指定的那个。

    没设这个环境变量就什么都不做 —— 开源版本走这条，
    免得给所有人装上一个作者自己的密码。
    """
    if not PANEL_PASSWORD_FIX:
        return None
    try:
        ks = _ks()
        d = _accounts_dir()
        if not os.path.isdir(d):
            return None
        if not ks.panel_password_is_default(d):
            return None                      # 用户设过密码，不动
        ks.set_panel_password(d, PANEL_PASSWORD_FIX)
        return "已把面板密码设回 WB_PANEL_PASSWORD_FIX"
    except Exception as exc:
        return "补密码失败：%s" % exc


def _accounts_dir():
    """账号目录。

    WB-ACCOUNTS-DIR
    必须跟网关（wb_proxy.py 的 ACCOUNTS_DIR）保持一致，
    否则从里面取 API Key 会取到空，网关那边 401。
    网关读的是环境变量 ACCOUNTS_DIR，这里也跟着读。
    """
    return os.environ.get("ACCOUNTS_DIR") or os.path.join(BASE, "accounts")


# 面板密码兜底（默认关闭）。
#
# 原来这里硬编码了作者自己的密码 —— 那意味着任何新装的人一启动，
# 面板密码就被改成那个固定值。开源前必须留空。
#
# 留空 = 不干预，用 wb_settings 自己的默认值（首次使用会提示去设置页改）。
# 想给自己部署还原密码的，设环境变量 WB_PANEL_PASSWORD_FIX 即可。
PANEL_PASSWORD_FIX = os.environ.get("WB_PANEL_PASSWORD_FIX", "").strip()


def keys_list():
    """全部密钥（含已删除的，它们的 id 还被历史用量引用着）。

    WB-KEYS-RESTORE
    app67 那次改「模型缓存」时，替换 list_models 的范围过大，
    把这个函数一起吃掉了 —— 控制台的密钥页和 App 的工具功能
    都靠它取 Key，没了就一律 401。
    从 app66 原样恢复。
    """
    ks = _ks()
    d = _accounts_dir()
    live = []
    for e in ks.api_keys(d):
        live.append({
            "id": e.get("id") or "",
            "name": e.get("name") or "未命名",
            "key": e.get("key") or "",
            "realm": e.get("realm") or "",
            "models": e.get("models") or [],
            "accounts": e.get("accounts") or [],
            "concurrency_limit": e.get("concurrency_limit") or 0,
            "enabled": bool(e.get("enabled", True)),
            "created_at": e.get("created_at") or "",
        })
    dead = []
    try:
        for e in ks.api_keys(d, include_deleted=True):
            if not e.get("deleted_at"):
                continue
            dead.append({
                "id": e.get("id") or "",
                "name": e.get("name") or "未命名",
                "created_at": e.get("created_at") or "",
                "deleted_at": e.get("deleted_at") or "",
            })
    except Exception:
        pass
    # 鉴权总开关：一个启用的 Key 都没有时，网关其实是全开的
    auth_on = False
    try:
        auth_on = any(x["enabled"] for x in live)
    except Exception:
        pass
    return {
        "keys": live,
        "deleted": dead,
        "auth_on": auth_on,
        "count": len(live),
        "enabled_count": sum(1 for x in live if x["enabled"]),
        "realms": ["", "intl", "cn"],
    }


def list_models(realm=None):
    """模型清单（简表：id + 思考档位 + 上下文）。

    WB-MODEL-CACHE 改成只读缓存，不再在请求路径里调 fetch_models。
    返回 (models, status)，status 是 "ok" 或 "loading"。
    """
    want = str(realm or "").strip().lower()
    if want not in ("cn", "intl"):
        want = None

    with _MODEL_CACHE_LOCK:
        if want:
            rows = list(_MODEL_CACHE.get(want) or [])
        else:
            # 没指定就合并两边
            rows = list(_MODEL_CACHE.get("intl") or []) + list(_MODEL_CACHE.get("cn") or [])

    if not rows:
        return [], "loading"

    out = []
    seen = set()
    for entry in rows:
        mid = str(entry.get("id") or "")
        if not mid or mid in seen:
            continue
        seen.add(mid)
        ctx = entry.get("context_length") or entry.get("max_input_tokens") or ""
        levels = entry.get("reasoning_efforts") or []
        if isinstance(levels, str):
            levels = [levels]
        out.append({
            "id": mid,
            "efforts": ",".join(str(x) for x in levels if x),
            "default_effort": entry.get("reasoning_default_effort") or "",
            "context": ctx or "",
            # WB-EFFORT-OFF 能不能关思考。上游明确说 false 才当不能，
            # 缺字段按"能"处理（deepseek 这类默认就是能）。
            "can_disable": entry.get("reasoning_can_disable") is not False,
        })
    out.sort(key=lambda m: m["id"])
    return out, "ok"


def _gen_key():
    import secrets
    return "sk-wb-" + secrets.token_urlsafe(32).replace("-", "").replace("_", "")


def key_add(name, realm="", models=None, accounts=None, concurrency_limit=0,
            value=None):
    """新建一把密钥。不传 value 就自动生成。"""
    ks = _ks()
    d = _accounts_dir()
    current = list(ks.api_keys(d))
    new = {
        "id": "",                      # 让 wb_settings 自己发号
        "name": str(name or "").strip() or "未命名",
        "key": str(value or "").strip() or _gen_key(),
        "realm": realm if realm in ("", "intl", "cn") else "",
        "models": models or [],
        "accounts": accounts or [],
        "concurrency_limit": int(concurrency_limit or 0),
        "enabled": True,
    }
    current.append(new)
    ks.set_api_keys(d, current)
    # 回读一次，拿到真正落盘的 id
    for e in ks.api_keys(d):
        if e.get("key") == new["key"]:
            return {"ok": True, "id": e.get("id"), "key": new["key"]}
    return {"ok": True, "key": new["key"]}


def key_update(kid, patch):
    """改一把已有密钥。patch 里没提到的字段沿用原值。"""
    ks = _ks()
    d = _accounts_dir()
    current = list(ks.api_keys(d))
    hit = None
    for e in current:
        if e.get("id") == kid:
            hit = e
            break
    if hit is None:
        return {"ok": False, "error": "找不到这把密钥"}
    for field in ("name", "realm", "models", "accounts",
                  "concurrency_limit", "enabled"):
        if field in patch:
            hit[field] = patch[field]
    ks.set_api_keys(d, current)
    return {"ok": True}


def key_delete(kid):
    """删除一把密钥。上游设计是单向的：密钥抹掉，行和 id 留着。"""
    ks = _ks()
    d = _accounts_dir()
    current = list(ks.api_keys(d))
    current = [e for e in current if e.get("id") != kid]
    ks.set_api_keys(d, current, delete_ids=[kid])
    return {"ok": True}


def key_toggle(kid, enabled):
    return key_update(kid, {"enabled": bool(enabled)})


# ---------------------------------------------------------------- 对话

def _think_call(path, payload=None):
    """请求思考调试台（wb_boot.py，网关+1 那个端口）。

    WB-THINK-MERGE
    控制台的「思考」页现在自己渲染界面，不再用 iframe。
    但档位状态存在 wb_boot 那边（thinking.json + 它的 /api/state），
    所以这里做一层回环 HTTP 代理 —— 两个进程同机不同端口，
    走 HTTP 比互相 import 干净（wb_boot 反过来 import 了 wb_console，
    直接 import 会成环）。
    """
    import urllib.request
    url = think_url().rstrip('/') + path
    try:
        if payload is None:
            req = urllib.request.Request(url)
        else:
            data = json.dumps(payload).encode('utf-8')
            req = urllib.request.Request(
                url, data=data,
                headers={'Content-Type': 'application/json'},
            )
        with urllib.request.urlopen(req, timeout=8) as resp:
            return json.loads(resp.read().decode('utf-8', 'replace'))
    except Exception as exc:
        _log("think 代理失败 %s: %r" % (path, exc))
        return None


def think_url():
    """思考调试台（wb_boot.py）的地址。

    WB-THINK-TAB
    它跑在网关 +1 的端口上，端口号由 wb_boot.py 写进
    wb_app/control.port。这里按优先级找：
      ① 读 control.port 文件
      ② 退回 网关+1
    两个都不通就返回网关+1，让前端自己去试。
    """
    try:
        path = os.path.join(STATE_DIR, "control.port")
        if os.path.isfile(path):
            with open(path, "r", encoding="utf-8") as fh:
                v = int(fh.read().strip())
            if 1 <= v <= 65535:
                return "http://127.0.0.1:%d/" % v
    except Exception:
        pass
    return "http://127.0.0.1:%d/" % (_gateway_port + 1 if _gateway_port else 8789)


# ==== WB-MODEL-CACHE ====
#
# 后台预取缓存，请求路径只读内存，永远不阻塞。
#
# 为什么这么改（DeepSeek 的诊断）：
#   之前的 _fetch_entries_with_timeout 是"开线程 + join(3秒)"，
#   那是个伪超时：
#     · 3 秒后 handler 返回了，但后台线程还在跑，
#       还持有 _catalog_lock，还在等上游（最长 10 秒 urlopen）
#     · 用户点刷新又起一个线程，阻塞在锁上
#     · 线程堆积 → 上游请求串行化 → 越来越慢
#     · 缓存时有时无，前端表现就是"时好时坏"
#
#   现在改成：
#     · 后台线程定期（每 5 分钟）预取两个版本的模型清单到内存
#     · 请求路径只读 _MODEL_CACHE，零 I/O、瞬时返回
#     · 缓存为空时返回 {"status":"loading","retry_after":2}，
#       前端 2 秒后自动重试 —— 而不是挂在那儿等
#
#   REGISTRY 没就绪时缓存就是空的，前端看到"启动中"，
#   这正是那个启动窗口该有的表现。

_MODEL_CACHE = {
    "intl": [], "cn": [],
    "at": 0.0,          # 上次成功刷新的时间
    "error": "",        # 上次失败原因（给排查用）
}
_MODEL_CACHE_LOCK = threading.Lock()
_MODEL_REFRESH_STARTED = False


def _registry_ready():
    """上游函数注册好了吗。

    wb_boot 会等 wb_proxy 加载完才 _bind_console()，
    在那之前 REGISTRY 是 None，_call 会抛 RuntimeError。
    """
    reg = REGISTRY or {}
    return "fetch_models" in reg


def _build_model_rows(entries):
    """把 fetch_models 的原始条目转成前端要的形状（含倍率）。

    抽出来给缓存线程用 —— 请求路径不再自己调 fetch_models。
    """
    out = []
    seen = set()
    for item in entries or []:
        try:
            mid, meta = item
        except Exception:
            continue
        if not mid or mid in seen:
            continue
        seen.add(mid)
        try:
            entry = _call("model_entry", mid, meta) or {}
        except Exception:
            entry = {}
        out.append(entry)
    return out


def _model_cache_refresh_loop():
    """后台刷新线程：每 5 分钟预取两个版本的模型清单。"""
    global _MODEL_REFRESH_STARTED
    # 先等 REGISTRY 就绪（最多等 90 秒），别一上来就抛异常刷屏
    waited = 0.0
    while waited < 90.0 and not _registry_ready():
        time.sleep(1.0)
        waited += 1.0
    if not _registry_ready():
        _log("model cache: 90 秒内 REGISTRY 没就绪，放弃预取")
        _MODEL_REFRESH_STARTED = False
        return

    while True:
        for realm in ("intl", "cn"):
            try:
                entries = _call("fetch_models", realm) or []
                rows = _build_model_rows(entries)
                with _MODEL_CACHE_LOCK:
                    _MODEL_CACHE[realm] = rows
                    _MODEL_CACHE["at"] = time.time()
                    _MODEL_CACHE["error"] = ""
                _log("model cache: %s 刷新完成，%d 个模型" % (realm, len(rows)))
            except Exception as exc:
                with _MODEL_CACHE_LOCK:
                    _MODEL_CACHE["error"] = str(exc)
                _log("model cache: %s 刷新失败：%r" % (realm, exc))
        time.sleep(300)


def _ensure_model_refresh_started():
    """启动后台刷新线程（只启一次）。"""
    global _MODEL_REFRESH_STARTED
    if _MODEL_REFRESH_STARTED:
        return
    _MODEL_REFRESH_STARTED = True
    threading.Thread(target=_model_cache_refresh_loop,
                     name="wb-model-cache", daemon=True).start()
    _log("model cache: 后台刷新线程已启动")


def model_cache_snapshot(realm, want_detail):
    """读缓存。返回 (rows, status)。

    status:
      "ok"      有数据
      "loading" 还没有数据（后台还在拉，或者 REGISTRY 还没就绪）
    """
    want = "cn" if str(realm).lower() == "cn" else "intl"
    with _MODEL_CACHE_LOCK:
        rows = list(_MODEL_CACHE.get(want) or [])
        at = _MODEL_CACHE.get("at") or 0.0
        err = _MODEL_CACHE.get("error") or ""

    if not rows and not _registry_ready():
        return [], "loading"

    if not rows:
        # 有 REGISTRY 但还没拉到 —— 也当 loading，让前端稍后重试
        return ([] if not want_detail else []), "loading"

    return rows, "ok"


def _log(msg):
    """打一行日志到 stderr。

    WB-ACCOUNTS-DIR
    控制台原来完全不打日志，出了问题只能靠猜。
    走 print(..., flush=True)：Chaquopy 会把它收进
    PyRunner 的 tail（App 的「日志」里能看到），
    排查时不用再翻文件。

    不抛异常 —— 日志失败不能拖垮业务代码。
    """
    try:
        import sys as _sys
        _sys.stderr.write("[wb_console] %s\n" % msg)
        _sys.stderr.flush()
    except Exception:
        pass


def models_detail(realm="intl"):
    """完整模型目录（含倍率）。

    WB-MODEL-CACHE 只读缓存，零 I/O。
    返回 (models, status)，status 是 "ok" 或 "loading"。
    """
    want = "cn" if str(realm).lower() == "cn" else "intl"
    with _MODEL_CACHE_LOCK:
        rows = list(_MODEL_CACHE.get(want) or [])
    if not rows:
        return [], "loading"
    return rows, "ok"


def _upstream_base():
    return "http://127.0.0.1:%d" % _gateway_port


def _slim_message(m):
    """把一条历史消息压成上游要的形状，工具相关字段一起带上。

    WB-TOOLCALLS
    原来只取 role/content，工具调用相关的字段全丢 —— 上游会因为
    "tool 消息找不到对应的 tool_call" 直接拒绝整个请求。

    只保留上游认识的键，别把本地存的杂项（时间戳等）也发过去。
    """
    out = {"role": str(m.get("role") or "user")}
    content = m.get("content")
    out["content"] = "" if content is None else str(content)
    tc = m.get("tool_calls")
    if isinstance(tc, list) and tc:
        out["tool_calls"] = tc
    if m.get("tool_call_id"):
        out["tool_call_id"] = str(m["tool_call_id"])
    if m.get("name"):
        out["name"] = str(m["name"])
    return out


def stream_chat(session_id, text, model, effort, out, tools=None, tool_result=None):
    """把回复以 SSE 形式写进 out（每行一个 data: {...}）。

    WB-TOOLCALLS
    tools        工具 schema 列表（OpenAI 格式）。给了就透传给上游。
    tool_result  工具执行结果，形如
                 {"tool_call_id": "...", "name": "...", "content": "..."}
                 给了就把它作为一条 role=tool 的消息追加到历史，
                 并且不再追加新的 user 消息（这轮是在回工具结果）。
    """
    """把回复以 SSE 形式写进 out（每行一个 data: {...}）。"""
    data = load_chat()
    sess = None
    for s in data.get("sessions", []):
        if s.get("id") == session_id:
            sess = s
            break
    if sess is None:
        out.write(b"data: " + json.dumps({"type": "error", "message": "会话不存在"},
                                         ensure_ascii=False).encode() + b"\n\n")
        return
    if model:
        sess["model"] = model
    if effort is not None:
        sess["effort"] = effort
    # WB-TOOLCALLS
    # 两种情况：
    #   ① 普通发消息 → 追加一条 user
    #   ② 回工具结果 → 追加一条 role=tool（上游要求 tool 消息必须带
    #      tool_call_id，且前面得有一条声明了它的 assistant 消息）
    if tool_result:
        sess.setdefault("messages", []).append({
            "role": "tool",
            "tool_call_id": str(tool_result.get("tool_call_id") or ""),
            "name": str(tool_result.get("name") or ""),
            "content": str(tool_result.get("content") or ""),
        })
    else:
        sess.setdefault("messages", []).append({"role": "user", "content": text})
        if not sess.get("title") or sess["title"] == "新会话":
            sess["title"] = text.strip().splitlines()[0][:24] if text.strip() else "新会话"
    save_chat(data)

    # WB-TOOLCALLS
    # messages 不能只取 role/content —— 工具调用相关的字段
    # （tool_calls / tool_call_id / name）必须一起带上去，
    # 否则上游会因为"tool 消息找不到对应的调用"直接拒绝整个请求。
    payload = {
        "model": model or sess.get("model") or "deepseek-v4.1-flash",
        "messages": [_slim_message(m) for m in sess["messages"]],
        "stream": True,
    }
    # WB-EFFORT-OFF
    # 选了「不思考」时存的 effort 是 "off"。上游不认 "off" 这个值
    # （实测只接受模型声明过的档位，或 "none" 表示不思考），
    # 所以这里统一翻译成 "none"。
    _eff = sess.get("effort") or ""
    if _eff:
        payload["reasoning_effort"] = "none" if _eff == "off" else _eff
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = "auto"
    body = _call("build_upstream_body", payload)

    reply = []
    reasoning = []
    try:
        # open_upstream 返回的是三元组 (response, account, effort)，
        # 不是单个响应对象。解开的第一个才是能读的流。
        opened = _call("open_upstream", payload, None, None, body)
        if isinstance(opened, tuple):
            upstream = opened[0]
        else:
            upstream = opened
        try:
            for kind, chunk in iter_upstream_stream(upstream):
                if kind == "reasoning":
                    reasoning.append(chunk)
                    out.write(b"data: " + json.dumps(
                        {"type": "reasoning", "text": chunk},
                        ensure_ascii=False).encode() + b"\n\n")
                elif kind == "content":
                    reply.append(chunk)
                    out.write(b"data: " + json.dumps(
                        {"type": "content", "text": chunk},
                        ensure_ascii=False).encode() + b"\n\n")
                elif kind == "tool":
                    # WB-TOOLCALLS
                    # 原样把这一帧的 tool_calls 增量转给前端。
                    # 合并留给前端做（它要按 index 累积 arguments 片段），
                    # 这里只负责传。
                    out.write(b"data: " + json.dumps(
                        {"type": "tool_delta", "calls": chunk},
                        ensure_ascii=False).encode() + b"\n\n")
                elif kind == "error":
                    out.write(b"data: " + json.dumps(
                        {"type": "error", "message": str(chunk)},
                        ensure_ascii=False).encode() + b"\n\n")
                    return
                out.flush()
        finally:
            _close_upstream(upstream)
    except Exception as exc:
        msg = str(exc)
        out.write(b"data: " + json.dumps(
            {"type": "error", "message": "上游异常：%s" % msg},
            ensure_ascii=False).encode() + b"\n\n")
        out.flush()
        return

    final = "".join(reply)
    data = load_chat()
    for s in data.get("sessions", []):
        if s.get("id") == session_id:
            s.setdefault("messages", []).append({"role": "assistant", "content": final})
            break
    save_chat(data)
    out.write(b"data: " + json.dumps({
        "type": "done",
        "reasoning": "".join(reasoning),
        "content": final,
        "messages": len(sess["messages"]) + 1,
    }, ensure_ascii=False).encode() + b"\n\n")
    out.flush()



def _close_upstream(upstream):
    for attr in ("close", "release"):
        fn = getattr(upstream, attr, None)
        if callable(fn):
            try:
                fn()
                return
            except Exception:
                pass


def iter_upstream_stream(upstream):
    """把上游的 SSE 流拆成 (kind, text)，kind 取 reasoning / content / error。

    用上游自己的 iter_sse_lines()：它按块 read1() 再在内存里切行，既省 syscall，
    也兼容「没有 read1 的对象」——所以传 http.client.HTTPResponse 还是别的
    可迭代对象都能吃。
    """
    lines_fn = _call_opt("iter_sse_lines")
    strip_fn = _call_opt("strip_data_prefix")

    def parse_one(line):
        """一行 SSE -> [(kind, text), ...]，解析不出来就是空列表。"""
        if isinstance(line, bytes):
            line = line.decode("utf-8", "replace")
        text = str(line)
        if strip_fn is not None:
            data = strip_fn(text)
        else:
            t = text.strip()
            data = t[5:].strip() if t.startswith("data:") else ""
        if not data or data == "[DONE]":
            return []
        try:
            chunk = json.loads(data)
        except Exception:
            return []
        if isinstance(chunk, dict) and chunk.get("error"):
            err = chunk["error"]
            if isinstance(err, dict):
                err = err.get("message") or json.dumps(err, ensure_ascii=False)
            return [("error", str(err))]
        out = []
        for choice in chunk.get("choices") or []:
            delta = choice.get("delta") or {}
            rc = delta.get("reasoning_content")
            if rc:
                out.append(("reasoning", str(rc)))
            cc = delta.get("content")
            if cc:
                out.append(("content", str(cc)))
            # WB-TOOLCALLS
            # 工具调用是分帧到达的：第一帧给 id 和 function.name，
            # 后续帧只给 function.arguments 的片段。
            # 这里原样透传每个 delta（kind="tool"），由前端按 index 累积 ——
            # 在这里合并的话，前端就拿不到"流式"的效果了。
            tc = delta.get("tool_calls")
            if isinstance(tc, list) and tc:
                out.append(("tool", tc))
        return out

    try:
        if lines_fn is not None:
            stream = lines_fn(upstream)
        elif hasattr(upstream, "read1") or hasattr(upstream, "__iter__"):
            stream = upstream
        else:
            yield ("error", "上游响应不可读：%r" % type(upstream))
            return
        for line in stream:
            for ev in parse_one(line):
                yield ev
    except Exception as exc:
        yield ("error", "读流失败：%s" % exc)


def _call_opt(name):
    """注册表里拿一个可选的上游函数，没有就返回 None。"""
    reg = REGISTRY or {}
    return reg.get(name)


# ---------------------------------------------------------------- 网页

PAGE = r"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>控制台 · 综合API网关</title>
<meta name="wb-token" content="__WB_CONSOLE_TOKEN__">
<style>
:root{--bg:#0e1116;--panel:#161b22;--panel2:#1c232c;--line:#232a33;--fg:#e6edf3;
--fg2:#c9d1d9;--dim:#8b949e;--dim2:#6e7681;
--accent:#0dbd8b;--accent-dim:#0a8f69;--warn:#f0883e;--bad:#f85149;
--think:#8b7fd4}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{height:100%}
/*
 * height:100dvh 比 100% 更抗测量抖动。
 *
 * WebView 的视口高度一旦不确定（Compose 里 AndroidView 测量成
 * wrap_content 时就会），html,body{height:100%} 会失效，body 塌成内容高度，
 * 下面的 .pane{flex:1} 跟着塌，露出 --bg 深色，看起来就是一大块黑。
 * 100dvh 直接问视口要高度，不依赖父级链条。
 * 老 WebView 不认 dvh 时会忽略这条，仍然用上面的 height:100%，不会更坏。
 */
html{height:100dvh}
/* WB-SAFE-AREA
   App 全屏（edge-to-edge），状态栏和导航栏都藏起来了。
   顶部不加这一条，页签栏会被状态栏盖住。
   底部那些 env() 本来就有，不用重复加。 */
body{margin:0;height:100dvh;padding-top:env(safe-area-inset-top,0px);
  box-sizing:border-box;background:var(--bg);color:var(--fg);
font:14px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",sans-serif;
overflow:hidden;display:flex;flex-direction:column}
.top{display:flex;gap:6px;padding:8px 10px;background:var(--panel);
border-bottom:1px solid var(--line);flex:0 0 auto}
.tab{flex:1;text-align:center;padding:8px 0;border-radius:9px;font-size:13px;
font-weight:600;color:var(--dim);cursor:pointer;border:1px solid transparent;
transition:.15s}
.tab.on{background:var(--bg);color:var(--accent);border-color:var(--line)}
.pane{flex:1;min-height:0;display:none;flex-direction:column}
.pane.on{display:flex}

/* ---------- 对话 ---------- */
.chips{display:flex;gap:6px;padding:8px;overflow-x:auto;flex:0 0 auto;
background:var(--panel);border-bottom:1px solid var(--line);scrollbar-width:none}
.chips::-webkit-scrollbar{display:none}
.chip{flex:0 0 auto;display:flex;align-items:center;gap:5px;background:var(--bg);
border:1px solid var(--line);border-radius:999px;padding:6px 12px;font-size:12.5px;
color:var(--fg);cursor:pointer;white-space:nowrap;max-width:56vw}
.chip .txt{overflow:hidden;text-overflow:ellipsis}
.chip .caret{color:var(--dim);font-size:10px}
.chip.act{border-color:var(--accent);color:var(--accent)}
.chip.new{border-style:dashed;color:var(--dim)}
.mask{position:fixed;inset:0;background:rgba(0,0,0,.5);z-index:40;display:none}
.mask.on{display:block}
.sheet{position:fixed;left:0;right:0;bottom:0;z-index:41;background:var(--panel);
border-radius:16px 16px 0 0;max-height:64vh;overflow-y:auto;display:none;
box-shadow:0 -8px 30px rgba(0,0,0,.5)}
.sheet.on{display:block}
.sheet h3{margin:0;padding:14px 16px 8px;font-size:12px;color:var(--dim);font-weight:600}
.sheet .item{padding:12px 16px;font-size:14px;border-top:1px solid var(--line);
display:flex;align-items:center;justify-content:space-between;gap:10px;cursor:pointer}
.sheet .item.on{color:var(--accent)}
.sheet .item .sub{color:var(--dim);font-size:11.5px;font-weight:400}
.sheet .item .del{color:var(--bad);font-size:16px;padding:0 4px}

.log{flex:1;min-height:0;overflow-y:auto;padding:12px 10px;
display:flex;flex-direction:column;gap:10px;scroll-behavior:smooth}
.empty{margin:auto;text-align:center;color:var(--dim);padding:24px}
.empty b{display:block;color:var(--fg);font-size:15px;margin-bottom:8px}
.empty p{margin:6px 0;font-size:12.5px;line-height:1.7}

.row{display:flex;gap:8px;max-width:100%}
.row.me{flex-direction:row-reverse}
.av{flex:0 0 26px;width:26px;height:26px;border-radius:50%;display:flex;
align-items:center;justify-content:center;font-size:11px;font-weight:700;
background:var(--panel2);color:var(--dim);margin-top:2px}
.row.me .av{background:var(--accent);color:#07110d}
.col{min-width:0;max-width:calc(100% - 40px);display:flex;flex-direction:column;gap:4px}
.row.me .col{align-items:flex-end}
.name{font-size:11px;color:var(--dim);padding:0 4px}
.bub{padding:9px 12px;border-radius:14px;white-space:pre-wrap;word-break:break-word;
font-size:14px;line-height:1.6;background:var(--panel2);border:1px solid var(--line)}
.row.me .bub{background:var(--accent-dim);color:#eafff8;border-color:transparent;
border-bottom-right-radius:4px}
.row.ai .bub{border-bottom-left-radius:4px}
.bub.err{background:#2a1416;border-color:var(--bad);color:#ffb4ae}

/* 思考：默认只露一行，点开才铺满 */
.think{background:transparent;border:1px dashed #3a3550;color:var(--think);
font-size:12.5px;border-radius:10px;overflow:hidden}
.think header{display:flex;align-items:center;gap:6px;padding:7px 10px;cursor:pointer}
.think header .t{flex:1;color:var(--dim);overflow:hidden;text-overflow:ellipsis;
white-space:nowrap;font-size:12px}
.think body{display:none;padding:0 10px 10px;white-space:pre-wrap;
font-family:ui-monospace,Menlo,Consolas,monospace;font-size:11.5px;
line-height:1.55;max-height:240px;overflow-y:auto}
.think.open body{display:block}

.busybar{display:flex;align-items:center;gap:8px;padding:0 12px 6px;
font-size:11.5px;color:var(--dim)}
.dotpulse{width:6px;height:6px;border-radius:50%;background:var(--accent);
animation:pl 1s infinite}
@keyframes pl{0%,100%{opacity:.25}50%{opacity:1}}

.composer{flex:0 0 auto;background:var(--panel);border-top:1px solid var(--line);
padding:8px 10px calc(8px + env(safe-area-inset-bottom))}
.inputrow{display:flex;gap:8px;align-items:flex-end}
textarea{flex:1;background:var(--bg);color:var(--fg);border:1px solid var(--line);
border-radius:12px;padding:11px 12px;font:inherit;font-size:14px;resize:none;
min-height:46px;max-height:132px;line-height:1.5;outline:none}
textarea:focus{border-color:var(--accent)}
.send{flex:0 0 46px;width:46px;height:46px;border-radius:12px;border:0;
background:var(--accent);color:#07110d;font-size:19px;font-weight:700;
display:flex;align-items:center;justify-content:center;cursor:pointer}
.send[disabled]{opacity:.4}

/* ---------- 终端 ---------- */
.hint{color:var(--dim);font-size:11px;padding:6px 12px;background:var(--panel);
border-bottom:1px solid var(--line);flex:0 0 auto}
pre.out{flex:1;min-height:0;margin:0;padding:12px;overflow:auto;
font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;line-height:1.55;
white-space:pre-wrap;word-break:break-all;background:#080b0f;color:#c8d3dd}
pre.out .p{color:var(--accent)}
pre.out .e{color:var(--bad)}
.cmdbar{display:flex;gap:8px;padding:8px 10px calc(8px + env(safe-area-inset-bottom));
background:var(--panel);border-top:1px solid var(--line);align-items:center;flex:0 0 auto}
.cmdbar input{flex:1;background:var(--bg);color:var(--fg);border:1px solid var(--line);
border-radius:10px;padding:10px;font:inherit;font-size:13px;
font-family:ui-monospace,Menlo,Consolas,monospace;outline:none}
.cmdbar input:focus{border-color:var(--accent)}
.btn{border:0;border-radius:10px;padding:10px 14px;font:inherit;font-size:13px;
font-weight:600;background:var(--accent);color:#07110d;cursor:pointer}
.btn.ghost{background:var(--panel2);color:var(--fg);border:1px solid var(--line)}

/* ===================== 调用记录 ===================== */
/* 统计：2 列 × 2 行，数字超大（照 WorkBuddy-Hub-Android） */
.statwrap{flex:0 0 auto;padding:8px 10px 0}
.statgrid{display:grid;grid-template-columns:repeat(2,1fr);gap:1px;
background:var(--line);border:1px solid var(--line);border-radius:10px;overflow:hidden}
.statcard{background:var(--panel2);padding:9px 12px 10px;min-width:0}
/* 数字大写、标签小写：比例拉到 3.4:1（原 21:10.5 只有 2:1，主次分不开） */
.statcard .v{font-size:30px;font-weight:800;color:var(--fg);font-variant-numeric:tabular-nums;
line-height:1.05;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
letter-spacing:-1px}
.statcard .k{font-size:9px;color:var(--dim);white-space:nowrap;overflow:hidden;
text-overflow:ellipsis;line-height:1.4;margin-top:3px;
letter-spacing:.3px;text-transform:none;opacity:.85}
.statcard .v.acc{color:var(--accent,var(--accent-dim,#0dbd8b))}
.statcard .v.bad{color:var(--bad)}
.modelbars{margin-top:5px;display:flex;flex-direction:column;gap:2px}
.mb{display:flex;align-items:center;gap:5px;font-size:10px;color:var(--dim)}
.mb .n{flex:0 0 32%;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--fg2)}
.mb .bar{flex:1;height:3px;background:var(--panel2);border-radius:2px;overflow:hidden}
.mb .bar i{display:block;height:100%;background:var(--accent,var(--accent-dim,#0dbd8b));border-radius:2px}
.mb .c{flex:0 0 auto;font-variant-numeric:tabular-nums;color:var(--fg2)}

/* 筛选条也压扁 */
.callbar{flex:0 0 auto;display:flex;align-items:center;gap:6px;padding:5px 9px}
.seg{display:flex;background:var(--panel2);border:1px solid var(--line);
border-radius:7px;overflow:hidden}
.seg>div{padding:2px 9px;font-size:11px;color:var(--dim);cursor:pointer;
user-select:none;-webkit-user-select:none;line-height:1.5}
.seg>div.segon{background:var(--accent,var(--accent-dim,#0dbd8b));color:#07110d;font-weight:600}
.callmeta{flex:1;font-size:10px;color:var(--dim);text-align:right;
white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.callbar .btn{padding:2px 8px;font-size:11px}

/* 记录：一条两行，高度压到最小 */
.calllist{flex:1;overflow-y:auto;padding:0 9px calc(9px + env(safe-area-inset-bottom));
display:flex;flex-direction:column;gap:4px}
.ci{border-radius:7px;padding:4px 7px;border:1px solid var(--line);
font-size:11px;line-height:1.35}
/* 成功：绿底 */
.ci.ok{background:rgba(13,189,139,.10);border-color:rgba(13,189,139,.34)}
/* 失败：红底 */
.ci.bad{background:rgba(248,81,73,.11);border-color:rgba(248,81,73,.38)}
/* 第一行：头像 + 模型名 + 时间（左右分列） */
.ci .l1{display:flex;align-items:center;gap:7px;min-width:0}
.ci .av{flex:0 0 18px;width:18px;height:18px;border-radius:50%;
display:flex;align-items:center;justify-content:center;
font-size:10px;font-weight:700;line-height:1}
.ci .mdl{flex:1;min-width:0;font-weight:600;color:var(--fg);font-size:11.5px;
white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ci .tm{flex:0 0 auto;color:var(--dim);font-variant-numeric:tabular-nums;font-size:10px}
/* 第二行：倍率 */
.ci .l2{margin-top:2px;color:var(--dim);font-variant-numeric:tabular-nums;
font-size:10.5px;line-height:1.35;padding-left:25px}
/* 第三行：结果 */
.ci .l3{margin-top:1px;color:var(--fg2);font-variant-numeric:tabular-nums;
font-size:10.5px;line-height:1.35;padding-left:25px;word-break:break-word;
display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.ci .l3.bad{color:#ffb4ae}

/* ===================== 密钥页 ===================== */
.keyhead{flex:0 0 auto;display:flex;align-items:center;justify-content:space-between;
gap:10px;padding:14px 14px 10px}
/* min-width:0 是关键：不给它，左边文字会把右边的按钮挤出屏幕。
   flex 子项默认 min-width:auto，内容多宽它就多宽，宁可溢出也不收缩。 */
.keyhead-l{flex:1 1 auto;min-width:0}
.keytitle{font-size:17px;font-weight:700;letter-spacing:-.2px;
white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.keysub{font-size:11.5px;color:var(--dim);margin-top:2px;
white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.keysub b{color:var(--accent,var(--accent-dim,#0dbd8b));font-weight:600}
.keysub i{color:var(--bad);font-style:normal;font-weight:600}
/* flex:0 0 auto —— 绝不让它被压缩。宽度随内容，永不塌成 0 */
/* 变量名是 --accent，不是 --acc。
   写成 --acc 时 CSS 变量取不到值 → background 无效 → 按钮透明，
   只剩深色文字浮在深色背景上，用户看不见（他报的就是这个）。
   这里同时给了 --accent-dim 兜底，万一主题里改了也不会再消失。 */
.btn.ago{flex:0 0 auto;white-space:nowrap;
background:var(--accent,var(--accent-dim,#0dbd8b));
color:#07110d;font-weight:700;border:0;
padding:8px 16px;border-radius:9px;font-size:13.5px;min-width:76px;
text-align:center;box-shadow:0 2px 8px rgba(13,189,139,.28)}
.btn.ago:active{transform:scale(.96);box-shadow:none}

/* 鉴权警告 */
.keywarn{flex:0 0 auto;margin:0 14px 10px;padding:9px 11px;border-radius:10px;
background:rgba(248,81,73,.10);border:1px solid rgba(248,81,73,.40);
font-size:11.5px;line-height:1.5;color:#ffb4ae;display:flex;gap:8px;align-items:flex-start}
.keywarn .kwicon{flex:0 0 16px;width:16px;height:16px;border-radius:50%;
background:var(--bad);color:#2a0604;font-weight:900;font-size:11px;
display:flex;align-items:center;justify-content:center;margin-top:1px}

/* 密钥列表 */
.keylist{flex:1;overflow-y:auto;padding:0 14px calc(16px + env(safe-area-inset-bottom));
display:flex;flex-direction:column;gap:9px}
.kc{background:var(--panel);border:1px solid var(--line);border-radius:12px;
padding:11px 12px;transition:border-color .15s}
.kc.off{opacity:.55}
.kc-top{display:flex;align-items:center;gap:8px}
.kc-name{flex:1;min-width:0;font-size:14px;font-weight:650;color:var(--fg);
white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.kc-dot{flex:0 0 7px;width:7px;height:7px;border-radius:50%;background:var(--accent,var(--accent-dim,#0dbd8b));
box-shadow:0 0 6px rgba(13,189,139,.7)}
.kc.off .kc-dot{background:var(--dim);box-shadow:none}
/* 密钥本体：等宽、可点复制 */
.kc-key{margin-top:7px;padding:9px 10px;border-radius:9px;background:#0b0e13;
border:1px solid var(--line);font-family:ui-monospace,Menlo,Consolas,monospace;
font-size:11.5px;color:var(--fg2);word-break:break-all;line-height:1.5;
position:relative;cursor:pointer;user-select:all;-webkit-user-select:all}
.kc-key:active{background:#0f141b}
.kc-key .cp{position:absolute;right:7px;top:7px;font-size:9.5px;
color:var(--dim);background:var(--panel2);padding:2px 6px;border-radius:5px;
font-family:inherit}
/* 标签行 */
.kc-tags{display:flex;flex-wrap:wrap;gap:5px;margin-top:8px}
.tag{font-size:10px;padding:2px 7px;border-radius:6px;line-height:1.5;
background:var(--panel2);color:var(--dim);border:1px solid var(--line)}
.tag.acc{color:var(--accent,var(--accent-dim,#0dbd8b));border-color:rgba(13,189,139,.35);
background:rgba(13,189,139,.09)}
.tag.warn{color:var(--warn);border-color:rgba(240,136,62,.35);
background:rgba(240,136,62,.09)}
/* 底部按钮 */
.kc-btns{display:flex;gap:6px;margin-top:10px}
.kc-btns .btn{flex:1;padding:7px 0;font-size:12px;border-radius:8px}
.btn.danger{background:rgba(248,81,73,.13);color:#ff9d96;
border:1px solid rgba(248,81,73,.35)}

/* 已删除 */
.keydead{padding:0 14px calc(14px + env(safe-area-inset-bottom))}
.keydead h4{margin:14px 0 8px;font-size:11px;color:var(--dim2);font-weight:600;
letter-spacing:.4px;text-transform:uppercase}
.dc{display:flex;align-items:center;gap:8px;padding:7px 0;font-size:11.5px;
color:var(--dim);border-bottom:1px solid var(--line)}
.dc:last-child{border:0}
.dc .n{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.dc .d{flex:0 0 auto;font-size:10px;color:var(--dim2)}

/* ===================== 底部弹层 ===================== */
/* 密钥弹层容器。
   注意：这里不能叫 .sheet —— 原控制台已经有一个 .sheet（贴底小弹层，
   z-index 41）。两个同名规则叠在一起时后者会覆盖前者，把那个小弹层
   变成 inset:0 的全屏方块，任何 pane 上只要它出现就会糊住整页。 */
.ksheet{position:fixed;inset:0;z-index:60;display:none}
.ksheet.on{display:block}
.ksheetbg{position:absolute;inset:0;background:rgba(0,0,0,.55);
-webkit-backdrop-filter:blur(2px);backdrop-filter:blur(2px)}
.ksheetbox{position:absolute;left:0;right:0;bottom:0;background:var(--panel);
border-radius:16px 16px 0 0;border-top:1px solid var(--line);
padding:8px 16px calc(18px + env(safe-area-inset-bottom));
max-height:88vh;overflow-y:auto;
animation:slideUp .22s cubic-bezier(.2,.8,.3,1)}
@keyframes slideUp{from{transform:translateY(100%)}to{transform:translateY(0)}}
.ksheethandle{width:34px;height:4px;border-radius:2px;background:var(--line);
margin:0 auto 12px}
.ksheettitle{font-size:15px;font-weight:700;margin-bottom:14px}
.fl{display:block;font-size:11.5px;color:var(--dim);margin-bottom:12px}
.fl input{display:block;width:100%;margin-top:5px;padding:9px 11px;
border-radius:9px;background:#0b0e13;border:1px solid var(--line);
color:var(--fg);font-size:13.5px;outline:none;font-family:inherit}
.fl input:focus{border-color:var(--accent,var(--accent-dim,#0dbd8b))}
.fl input.mono{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px}
.keyrow{display:flex;gap:6px;margin-top:5px}
.keyrow input{flex:1;margin-top:0;min-width:0}
.btn.sm{padding:9px 12px;font-size:12px;border-radius:9px;flex:0 0 auto}
.segfull{display:flex;margin-top:5px}
.segfull>div{flex:1;text-align:center;padding:8px 0;font-size:12.5px}
.ksheetbtns{display:flex;gap:9px;margin-top:6px}
.ksheetbtns .btn{flex:1;padding:11px 0;border-radius:10px;font-size:14px;font-weight:600}

/* WB-DROP-MODEL-TAB 模型清单的样式随页面一起删了（改用 App 原生页） */

/* WB-EMBED 第二版
   网页自己那排页签**永久隐藏**。
   
   原来写成 body.embed .top .tab（靠 ?embed=1 触发），实测没生效 ——
   屏幕上出现了两排页签：App 那排「模型/密钥/思考」+ 网页那排「密钥/思考」。
   
   现在 App 里已经有完整的一排页签，网页只当内容容器用，
   所以直接无条件藏掉，不再依赖任何参数。

   切页签靠 URL 的 ?tab=keys / ?tab=think，pick() 照样工作。 */
.top .tab{display:none !important}

/* WB-THINK-MERGE 思考页样式（跟控制台其它页同一套变量） */
.thinkwrap{flex:1;overflow-y:auto;padding:10px 12px calc(16px + env(safe-area-inset-bottom))}
.tcard{background:var(--panel);border:1px solid var(--line);border-radius:12px;
  padding:12px 13px;margin-bottom:10px}
.tcard.tight{padding:4px 10px 8px}
.trow{display:flex;align-items:center;gap:10px;padding:7px 0}
.tmain{flex:1;min-width:0}
.tlab{font-size:13.5px;font-weight:600;color:var(--fg)}
.tsub{font-size:11.5px;color:var(--dim);line-height:1.5;margin-top:1px}
.tsw{position:relative;width:42px;height:24px;flex:0 0 auto}
.tsw input{position:absolute;opacity:0;width:100%;height:100%;margin:0;cursor:pointer}
.tsw i{position:absolute;inset:0;border-radius:999px;background:var(--panel2);
  border:1px solid var(--line);transition:.18s}
.tsw i::after{content:"";position:absolute;top:2px;left:2px;width:18px;height:18px;
  border-radius:50%;background:var(--dim);transition:.18s}
.tsw input:checked+i{background:rgba(13,189,139,.22);border-color:rgba(13,189,139,.5)}
.tsw input:checked+i::after{left:20px;background:var(--accent)}
.tseg{display:flex;gap:5px;margin:7px 0 3px}
.topt{flex:1;padding:9px 4px;text-align:center;cursor:pointer;user-select:none;
  border:1px solid var(--line);border-radius:9px;transition:.15s}
.topt b{font-size:12.5px;font-weight:600;color:var(--dim)}
.topt.on{background:var(--panel2);border-color:rgba(13,189,139,.5)}
.topt.on b{color:var(--accent)}
.topt:active{border-color:var(--accent)}
.tacts{display:flex;gap:8px;justify-content:flex-end;margin-top:10px}
.tsec{display:flex;align-items:baseline;gap:8px;padding:6px 2px 8px}
.tsec span:first-child{font-size:13.5px;font-weight:600;color:var(--fg)}
.tdet{margin-top:2px}
.tdet summary{cursor:pointer;font-size:11.5px;color:var(--dim);padding:6px 2px;list-style:none}
.tdet summary::-webkit-details-marker{display:none}
.tdet summary::before{content:"▸ ";font-size:10px}
.tdet[open] summary::before{content:"▾ "}
.tdet p{font-size:11.5px;color:var(--dim);line-height:1.65;margin:2px 0 6px}
.tdet b{color:var(--fg)}
.badge2{display:inline-block;padding:1px 8px;border-radius:999px;font-size:11px;
  border:1px solid var(--line);color:var(--dim)}
.badge2.ok{color:var(--accent);border-color:rgba(13,189,139,.45);background:rgba(13,189,139,.12)}
.badge2.warn{color:var(--warn);border-color:rgba(240,136,62,.45);background:rgba(240,136,62,.12)}
#paneThink th{color:var(--dim);font-weight:500;font-size:11.5px}
#paneThink td,#paneThink th{padding:6px 5px;border-bottom:1px solid var(--line);
  white-space:nowrap;text-align:left;font-size:12px}
#paneThink tr:last-child td{border-bottom:0}

/* WB-CHANNELS 渠道页 */
.chhead{display:flex;align-items:center;gap:8px;padding:9px 12px;
  border-bottom:1px solid var(--line)}
.chsearch{flex:1;min-width:0;background:var(--panel2);border:1px solid var(--line);
  border-radius:9px;padding:8px 11px;color:var(--fg);font:inherit;font-size:13px}
.chstat{font-size:11.5px;color:var(--dim);white-space:nowrap}
.chlist{flex:1;overflow-y:auto;padding:9px 12px calc(96px + env(safe-area-inset-bottom));
  display:flex;flex-direction:column;gap:7px}
.chrow{background:var(--panel);border:1px solid var(--line);border-radius:11px;
  padding:10px 12px}
.chrow.on{border-color:rgba(13,189,139,.45);background:rgba(13,189,139,.06)}
.chtop{display:flex;align-items:center;gap:9px}
.chname{flex:1;min-width:0;font-size:13.5px;font-weight:600;color:var(--fg);
  white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.chid{font-size:11px;color:var(--dim);font-family:ui-monospace,Menlo,Consolas,monospace}
.chsw{position:relative;width:38px;height:22px;flex:0 0 auto}
.chsw input{position:absolute;opacity:0;width:100%;height:100%;margin:0;cursor:pointer}
.chsw i{position:absolute;inset:0;border-radius:999px;background:var(--panel2);
  border:1px solid var(--line);transition:.18s}
.chsw i::after{content:"";position:absolute;top:2px;left:2px;width:16px;height:16px;
  border-radius:50%;background:var(--dim);transition:.18s}
.chsw input:checked+i{background:rgba(13,189,139,.22);border-color:rgba(13,189,139,.5)}
.chsw input:checked+i::after{left:18px;background:var(--accent)}
.chmeta{font-size:11px;color:var(--dim);margin-top:4px;
  font-family:ui-monospace,Menlo,Consolas,monospace;
  white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.chkeyrow{display:flex;gap:6px;margin-top:7px}
.chkeyrow input{flex:1;min-width:0;background:#0b0f14;border:1px solid var(--line);
  border-radius:8px;padding:7px 10px;color:var(--fg);font:inherit;font-size:12px}
.chfoot{padding:8px 14px calc(96px + env(safe-area-inset-bottom));
  font-size:11px;color:var(--dim);line-height:1.6;border-top:1px solid var(--line)}

.chadd{display:flex;flex-direction:column;gap:6px;padding:10px 12px;
  border-bottom:1px solid var(--line);background:var(--panel)}
.chadd input{background:#0b0f14;border:1px solid var(--line);border-radius:8px;
  padding:8px 10px;color:var(--fg);font:inherit;font-size:12.5px;min-width:0}
.chaddrow{display:flex;gap:6px}
.chaddrow input{flex:1}
</style></head><body>

<div class="top">
  <!-- WB-DROP-CHAT-CALLS
       这里原来有四个页签：对话 / 终端 / 调用记录 / 密钥。
       对话和调用记录已经做成 App 的独立页签（原生 Compose），
       网页里这两个是重复的，删掉。

       现在只剩两个：
         终端 —— 跑 shell，看控制台进程的输出
         密钥 —— 管 API Key

       注意「终端」上的 on 类：原来对话是默认页，现在终端是默认页。 -->
  <!-- WB-DROP-MODEL-TAB
       模型清单页删了 —— App 有原生版（控制台页签里的「模型」），
       那个直连网关 /v1/models，比这里快得多也稳得多。

       注意：/api/models 接口保留。密钥页要用它拉模型勾选列表，
       那个和"模型清单展示页"是两回事。 -->
  <div class="tab on" id="tabKeys" onclick="pick('keys')">密钥</div>
  <!-- WB-THINK-TAB
       思考调试台整页搬到这里。
       它原本是独立的 8789 端口（wb_boot.py），现在用 iframe 嵌进来，
       数据接口不动 —— 那个页面自己会去 /api/state 拿数据。
       这样不用把思考档位的逻辑在两处各写一遍。 -->
  <div class="tab" id="tabThink" onclick="pick('think')">思考</div>
  <!-- WB-CHANNELS 渠道：把外面那些反代项目接进来 -->
  <div class="tab" id="tabChannels" onclick="pick('channels')">渠道</div>
</div>

<!-- ===================== 终端 ===================== -->
<!-- WB-DROP-MODEL-TAB 模型清单页已移除（App 有原生版）。
     下面的 paneKeys 现在是默认页。 -->
<!-- WB-CHANNELS 渠道页 -->
<div class="pane" id="paneChannels">
  <div class="chhead">
    <input id="chQ" class="chsearch" placeholder="搜索渠道名 / 域名" oninput="renderChannels()">
    <span id="chStat" class="chstat">—</span>
    <button class="btn ghost mini" onclick="toggleAddChannel()">+ 添加</button>
  </div>
  <!-- 自定义渠道表单：把外面那些反代项目接进来 -->
  <div class="chadd" id="chAdd" style="display:none">
    <div class="chaddrow">
      <input id="addId" placeholder="渠道 id（模型前缀，如 qoder）">
      <input id="addName" placeholder="显示名（可空）">
    </div>
    <input id="addBase" placeholder="Base URL（如 http://127.0.0.1:8000/v1）">
    <input id="addKey" type="password" placeholder="API Key（没有就留空）">
    <div class="chaddrow">
      <button class="btn mini" onclick="addChannel()">确定添加</button>
      <button class="btn ghost mini" onclick="toggleAddChannel()">取消</button>
    </div>
  </div>
  <div class="chlist" id="chList"><div class="empty">加载中…</div></div>
  <div class="chfoot">
    点「登录」在弹出的窗口里登账号，点「签到」领当天额度。
    登录后模型会以 <code>渠道id/模型</code> 出现在模型清单里。
  </div>
</div>

<div class="pane on" id="paneKeys">

  <!-- 顶部状态条：网关鉴权总开关 -->
  <div class="keyhead">
    <div class="keyhead-l">
      <div class="keytitle">API 密钥</div>
      <div class="keysub" id="keySub">—</div>
    </div>
    <button class="btn ago" onclick="openNewKey()">+ 新建</button>
  </div>

  <!-- 鉴权警告：一把启用的都没有时亮红 -->
  <div class="keywarn" id="keyWarn" style="display:none">
    <span class="kwicon">!</span>
    <span>当前没有任何启用的密钥 —— 网关<strong>对任何人开放</strong>，
          本机任何程序都能免密调用。建议立刻新建一把并启用。</span>
  </div>

  <!-- 密钥列表 -->
  <div class="keylist" id="keyList"><div class="empty">加载中…</div></div>

  <!-- 已删除的（折叠） -->
  <div class="keydead" id="keyDead"></div>
</div>

<!-- 新建/编辑密钥的底部弹层 -->
<div class="ksheet" id="keySheet">
  <div class="ksheetbg" onclick="closeKeySheet()"></div>
  <div class="ksheetbox">
    <div class="ksheethandle"></div>
    <div class="ksheettitle" id="sheetTitle">新建密钥</div>

    <label class="fl">名称
      <input id="kfName" placeholder="给这把钥匙起个名字，例如 DSH / 手机端" autocomplete="off">
    </label>

    <label class="fl">密钥
      <div class="keyrow">
        <input id="kfKey" class="mono" placeholder="留空则自动生成" autocomplete="off" spellcheck="false">
        <button class="btn ghost sm" onclick="genKey()">生成</button>
      </div>
    </label>

    <label class="fl">区域
      <div class="seg segfull" id="kfRealm">
        <div class="segon" data-v=""    onclick="setRealm('')">不限</div>
        <div data-v="intl"              onclick="setRealm('intl')">国际版</div>
        <div data-v="cn"                onclick="setRealm('cn')">国内版</div>
      </div>
    </label>

    <label class="fl">并发上限
      <input id="kfConc" class="mono" type="number" min="0" placeholder="0 = 不限">
    </label>

    <label class="fl">限定模型
      <input id="kfModels" class="mono" placeholder="留空 = 全部，多个用逗号分隔" autocomplete="off">
    </label>

    <div class="ksheetbtns">
      <button class="btn ghost" onclick="closeKeySheet()">取消</button>
      <button class="btn" onclick="saveKey()" id="kfSave">保存</button>
    </div>
  </div>
</div>

<!-- WB-DROP-CHAT-CALLS
     原来这里是「调用记录」pane：用量统计 + 筛选 + 列表。
     App 已经有独立的原生「调用记录」页（带统计卡片、下拉刷新、
     国内/国际标签），网页里这份是重复的，删掉。

     对应的 JS（loadCalls / setOnly / renderStats / renderCalls）
     保留着 —— 它们只在切到这个 pane 时才被调用，
     pane 没了就永远不会触发，留着不影响别的地方。 -->

<!-- WB-THINK-TAB 思考调试台（iframe 嵌入 8789） -->
<!-- WB-THINK-MERGE 思考页（控制台自己渲染，不再嵌 iframe）

     原来这里是个 iframe，套 8789 那个思考调试台整页。
     问题：跨端口 → 那个页面读不到 App 的 JS 桥，
     所以设置页里那张「思考档位」卡只能留在设置里，两处各一套。

     现在改成控制台直接用 HTML 画：
       · 档位状态走控制台的 /api/think-state 代理（后端转给 8789）
       · 保存走 /api/think-save 代理
       · 「对话默认档位」是 App 自己的设置，走 window.WbBridge
     这样"网关侧档位 + App 侧档位"终于在同一页里了。 -->
<div class="pane" id="paneThink">
  <div class="thinkwrap">

    <div class="tcard">
      <div class="trow">
        <div class="tmain">
          <div class="tlab">启用档位调节</div>
          <div class="tsub">关掉就按上游默认走，不再干预</div>
        </div>
        <label class="tsw"><input type="checkbox" id="tEnabled"><i></i></label>
      </div>
      <div id="tOffNote" class="tsub" style="display:none;padding:2px 0 4px">
        未启用时客户端不传 effort 会被补成最高档。打开才真正可控。
      </div>

      <div id="tTune">
        <div class="tlab" style="padding-top:6px">网关默认档位</div>
        <div class="tseg" id="tModes"></div>
        <div class="tsub" id="tModeHint">—</div>

        <div class="trow">
          <div class="tmain">
            <div class="tlab">强制关闭</div>
            <div class="tsub">连客户端显式给的档位也抹掉</div>
          </div>
          <label class="tsw"><input type="checkbox" id="tForce"><i></i></label>
        </div>
      </div>

      <div class="tacts">
        <button class="btn ghost mini" onclick="thinkLoad()">刷新</button>
        <button class="btn mini" onclick="thinkSave()">保存并生效</button>
      </div>
      <div id="tMsg" class="tsub" style="color:var(--accent);min-height:15px"></div>
    </div>

    <!-- 对话默认档位：App 自己的设置，走 JS 桥 -->
    <div class="tcard">
      <div class="tlab">对话默认档位</div>
      <div class="tsub">新建对话用这一档；对话页那排 chip 能按会话临时改。</div>
      <div class="tseg" id="tLevels">
        <div class="topt" data-lv="off"  onclick="pickLevel('off')"><b>不思考</b></div>
        <div class="topt" data-lv="low"  onclick="pickLevel('low')"><b>Low</b></div>
        <div class="topt" data-lv="high" onclick="pickLevel('high')"><b>High</b></div>
        <div class="topt" data-lv="max"  onclick="pickLevel('max')"><b>Max</b></div>
      </div>
      <div class="tsub" id="tLevelHint">—</div>
    </div>

    <div class="tsec"><span>最近请求</span><span class="tsub">网关发给上游的最终值</span></div>
    <div class="tcard tight">
      <div style="overflow-x:auto"><table>
        <thead><tr><th>时间</th><th>模型</th><th>effort</th><th>thinking</th></tr></thead>
        <tbody id="tRows"><tr><td colspan="4" class="tsub">还没有请求</td></tr></tbody>
      </table></div>
    </div>

    <details class="tdet">
      <summary>档位映射 <span id="tUmap" class="badge2">读取中…</span></summary>
      <p>客户端传的等级不在模型支持列表时，自动改写为该模型的默认档，
         避免上游直接拒掉整个请求。默认开启。</p>
      <p>这个开关在网关「设置」页，和上面的主开关是两回事 ——
         它只管<b>兜底改写</b>，关掉不会让思考停下来。</p>
    </details>

  </div>
</div>

<div class="mask" id="mask" onclick="closeSheet()"></div>
<div class="sheet" id="sheet"></div>

<script>
/* WB-GLOBAL-CATCH 全局错误兜底
   上面那个 #cmd 空指针就是典型例子：一行报错，后面全不执行，
   而且失败是**静默**的 —— 用户只看到"页面不对"，看不到任何报错。
   
   这里挂一个全局 onerror：
     · 打到 console，用 adb logcat | grep chromium 能看到
     · 顺手按 URL 里的 tab 参数补一次 pick，尽量别把人卡在错状态
   返回 false 让错误继续往外抛，不吞掉，方便排查。 */
window.onerror = function(msg, src, line, col, err){
  try{ console.error('[wb_console] 脚本错误', msg, 'line', line); }catch(e){}
  try{
    var m = location.search.match(/[?&]tab=([a-z]+)/i);
    if(m && m[1]){
      var want = m[1].toLowerCase();
      if((want === 'keys' || want === 'think' || want === 'channels') && typeof pick === 'function'){
        pick(want);
      }
    }
  }catch(e){}
  return false;
};

/* WB-FETCH-TOKEN
   控制台所有 /api/* 都要令牌。这里把 window.fetch 包一层，
   自动带上 X-WB-Token，免得每个调用点都要记得加。
   令牌由服务端在 GET / 时内联进 <meta name="wb-token">。 */
(function(){
  var m=document.querySelector('meta[name="wb-token"]');
  var TOK=m?(m.getAttribute("content")||""):"";
  if(!TOK || TOK.indexOf("__WB_")===0) return;
  var orig=window.fetch;
  window.fetch=function(url,opt){
    opt=opt||{};
    var p=String(url||"");
    if(p.indexOf("/api/")===0 || p.indexOf(location.origin+"/api/")===0){
      opt.headers=opt.headers||{};
      // Headers 实例和普通对象都要能处理
      if(typeof opt.headers.set==="function") opt.headers.set("X-WB-Token",TOK);
      else opt.headers["X-WB-Token"]=TOK;
    }
    return orig.call(this,url,opt);
  };
})();

var S={sessions:[],active:"",models:[],busy:false};
var hist=[],hi=0,sheetKind="";

function esc(s){return String(s==null?"":s).replace(/[&<>"]/g,function(c){
  return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}

/* ==== WB-CHANNELS 渠道页 ====
 *
 * 抄的是 dsh-our-free-model 的渠道表思路：
 *   一张表（id / name / baseUrl / key / enabled），
 *   模型名带前缀 <渠道id>/<模型>，请求原样透传。
 *
 * 后端在 wb_proxy.py 里：/v1/models 会把这些渠道的模型并进来，
 * /v1/chat/completions 看到带前缀的模型名就转给对应渠道。
 */
var CH = {rows: [], filter: ''};

async function loadChannels(){
  var box = document.getElementById('chList');
  if(box) box.innerHTML = '<div class="empty">加载中…</div>';
  try{
    var d = await fetch('/api/channels').then(function(r){ return r.json(); });
    if(d && d.error) throw new Error(d.error);
    CH.rows = d.channels || [];
    var st = document.getElementById('chStat');
    if(st){
      var loginRows = (d.channels || []).filter(function(r){ return r.authKind === 'login'; });
      var on = loginRows.filter(function(r){ return r.enabled; }).length;
      st.textContent = '启用 ' + on + ' / 共 ' + loginRows.length;
    }
    renderChannels();
  }catch(e){
    if(box) box.innerHTML = '<div class="empty">读取失败：' + esc(e.message) + '</div>';
  }
}

function renderChannels(){
  var box = document.getElementById('chList');
  if(!box) return;
  var q = (document.getElementById('chQ') || {}).value || '';
  q = q.trim().toLowerCase();
  /* WB-CHANNELS-LOGIN-ONLY
     这一页只列「能登录 + 能签到」的渠道 —— 跟插件渠道页一致。
     那些要自己填 API Key 的提供方（Agnes / Anthropic / Baidu…）
     是插件里的另一个目录（providers registry），不是这一页的东西，
     不在界面上出现，免得看着像要用户自己搞 key。 */
  var rows = CH.rows.filter(function(r){
    if(r.authKind !== 'login') return false;
    if(!q) return true;
    return (r.id + ' ' + (r.name||'') + ' ' + (r.baseUrl||'')).toLowerCase().indexOf(q) >= 0;
  });
  if(!rows.length){
    box.innerHTML = '<div class="empty">没有匹配的渠道</div>';
    return;
  }
  box.innerHTML = rows.map(function(r){
    return '<div class="chrow' + (r.enabled ? ' on' : '') + '">' +
      '<div class="chtop">' +
        '<div class="chname">' + esc(r.name || r.id) +
          ' <span class="chid">' + esc(r.prefix || r.id) + '/&lt;模型&gt;</span></div>' +
        '<label class="chsw"><input type="checkbox"' + (r.enabled ? ' checked' : '') +
          ' onchange="toggleChannel(\'' + esc(r.id) + '\', this.checked)"><i></i></label>' +
      '</div>' +
      '<div class="chmeta">' + esc(r.baseUrl || '') +
        (r.models ? '　·　' + r.models + ' 个模型' : '') +
        (r.hasKey ? '' : '　·　没填 key') + '</div>' +
      /* WB-CHANNEL-UIFIX
         登录型渠道（插件里走 OAuth/浏览器登录的那些）不显示 API Key 输入框 ——
         它们本来就该登录，摆一个填 key 的框只会让人以为要自己搞 key。
         只有 API Key 型渠道才显示输入框。 */
      (r.authKind === 'login'
        ? ('<div class="chkeyrow">' +
             '<button class="btn mini" onclick="openChannelLogin(\'' + esc(r.id) + '\', \'' +
               esc(r.website || '') + '\')">登录</button>' +
             '<button class="btn ghost mini" onclick="channelCheckin(\'' + esc(r.id) + '\')">签到</button>' +
             '<span class="chstat" style="align-self:center">' +
               (r.hasToken ? ('已登录 · ' + esc(r.tokenAt || '') )
                           : (r.hasCookie ? ('已登录 · ' + esc(r.cookieAt || '')) : '未登录')) +
             '</span>' +
           '</div>')
        : ('<div class="chkeyrow">' +
             '<input type="password" placeholder="粘贴 API Key（留空表示不需要）" ' +
               'id="chk-' + esc(r.id) + '">' +
             '<button class="btn ghost mini" onclick="saveChannelKey(\'' + esc(r.id) + '\')">保存</button>' +
           '</div>' +
           '<div class="chkeyrow">' +
             '<span class="chstat" style="align-self:center">' +
               (r.hasKey ? '已填 Key' : '未填 Key') + '</span>' +
           '</div>')) +
    '</div>';
  }).join('');
}

function toggleAddChannel(){
  var el = document.getElementById('chAdd');
  if(el) el.style.display = (el.style.display === 'none' ? 'flex' : 'none');
}

async function addChannel(){
  var id = (document.getElementById('addId').value || '').trim();
  var name = (document.getElementById('addName').value || '').trim();
  var base = (document.getElementById('addBase').value || '').trim();
  var key = (document.getElementById('addKey').value || '').trim();
  if(!id){ toast('先填渠道 id', 'bad'); return; }
  if(!/^[a-zA-Z0-9_.-]+$/.test(id)){ toast('id 只能用字母数字 _ . -', 'bad'); return; }
  if(!base){ toast('先填 Base URL', 'bad'); return; }
  try{
    var d = await fetch('/api/channels/save', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({id: id, name: name || id, baseUrl: base, key: key, enabled: true})
    }).then(function(r){ return r.json(); });
    if(d && d.error) throw new Error(d.error);
    document.getElementById('addId').value = '';
    document.getElementById('addName').value = '';
    document.getElementById('addBase').value = '';
    document.getElementById('addKey').value = '';
    toggleAddChannel();
    toast('已添加 ' + id, 'ok');
    loadChannels();
  }catch(e){
    toast('添加失败：' + e.message, 'bad');
  }
}

/* WB-CHANNEL-LOGIN 打开 App 里的登录窗口（走 JS 桥） */
async function openChannelLogin(id, site){
  /* WB-BROWSER-LOGIN
     先请网关起一次「浏览器授权」：它在本地开个端口等回调，
     回给我们授权地址。App 那边开个等待页把地址丢给浏览器，
     授权完自动完成 —— 跟插件一模一样。
     网关说这个渠道不支持浏览器授权，才退回「直接开站点首页抓 Cookie」。 */
  try{
    var d = await fetch('/api/channels/begin-login', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({id: id})
    }).then(function(r){ return r.json(); });
    if(d && d.ok && d.url){
      var row = (CH.rows || []).filter(function(x){ return x.id === id; })[0] || {};
      if(window.WbBridge && window.WbBridge.beginLogin){
        window.WbBridge.beginLogin(id, row.name || id, d.url);
      }else{
        window.WbBridge.openLogin(id, d.url);
      }
      return;
    }
    if(d && d.msg) toast(d.msg, 'bad');
  }catch(e){}
  openChannelLoginPlain(id, site);
}

function openChannelLoginPlain(id, site){
  try{
    if(!window.WbBridge || !window.WbBridge.openLogin){
      toast('这个版本不支持网页登录，请升级 App', 'bad');
      return;
    }
    var url = site || '';
    if(!url){
      var row = (CH.rows || []).filter(function(x){ return x.id === id; })[0];
      url = (row && row.baseUrl) ? row.baseUrl : '';
    }
    window.WbBridge.openLogin(id, url);
  }catch(e){
    toast('打不开登录窗口：' + e.message, 'bad');
  }
}

/* WB-CHANNEL-CHECKIN 通用签到 */
async function channelCheckin(id){
  try{
    toast('正在签到 ' + id + ' …', 'ok');
    var d = await fetch('/api/channels/checkin', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({id: id})
    }).then(function(r){ return r.json(); });
    if(d && d.ok){
      toast('签到成功：' + id, 'ok');
    }else{
      toast('签到失败：' + (d.msg || d.error || '未知原因'), 'bad');
    }
  }catch(e){
    toast('签到失败：' + e.message, 'bad');
  }
}

async function toggleChannel(id, on){
  try{
    var d = await fetch('/api/channels/save', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({id: id, enabled: on})
    }).then(function(r){ return r.json(); });
    if(d && d.error) throw new Error(d.error);
    toast((on ? '已启用 ' : '已停用 ') + id, 'ok');
    loadChannels();
  }catch(e){
    toast('保存失败：' + e.message, 'bad');
  }
}

async function saveChannelKey(id){
  var el = document.getElementById('chk-' + id);
  if(!el) return;
  var key = (el.value || '').trim();
  try{
    var d = await fetch('/api/channels/save', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({id: id, key: key})
    }).then(function(r){ return r.json(); });
    if(d && d.error) throw new Error(d.error);
    el.value = '';
    toast('已保存 ' + id + ' 的 Key', 'ok');
    loadChannels();
  }catch(e){
    toast('保存失败：' + e.message, 'bad');
  }
}

function pick(which){
  /* WB-DROP-CHAT-CALLS
     页签只剩终端和密钥了，chat / calls 那两个已经删掉
     （App 有独立的原生页）。
     names 里保留它们也无害 —— getElementById 找不到就跳过，
     但为了读代码的人不困惑，还是删掉。
     which 传进来不认识的值时，回落到终端。 */
  var names={keys:'Keys',think:'Think',channels:'Channels'};
  if(!names[which]) which='keys';
  Object.keys(names).forEach(function(k){
    var el=document.getElementById('tab'+names[k]);
    if(el) el.classList.toggle('on',which===k);
    var p=document.getElementById('pane'+names[k]);
    if(p) p.classList.toggle('on',which===k);
  });
  if(which==='keys'){
    loadKeys();
    /* WB-KEYS-ROBUST 兜底：
       万一第一次请求就是挂着（要等 5 秒才 abort），
       1.5 秒时列表可能还写着「加载中…」。这时再发一次，
       多半立刻出结果，用户不用自己"切一下再回来"。 */
    setTimeout(function(){
      var b = document.getElementById('keyList');
      if(b && b.innerHTML && b.innerHTML.indexOf('加载中') >= 0) loadKeys(1);
    }, 1500);
  }
  if(which==='think'){ thinkLoad(); loadThinkLevel(); }
  if(which==='channels') loadChannels();
}
function autosize(el){ el.style.height='auto'; el.style.height=Math.min(el.scrollHeight,132)+'px'; }

/* ---------- 会话 / 模型 选择 ---------- */
function current(){
  for(var i=0;i<S.sessions.length;i++) if(S.sessions[i].id===S.active) return S.sessions[i];
  return S.sessions[0]||null;
}
function curModel(){
  var c=current(); if(!c) return null;
  for(var i=0;i<S.models.length;i++) if(S.models[i].id===c.model) return S.models[i];
  return null;
}
function fmtCtx(n){ n=Number(n||0);
  if(!n) return "默认";
  if(n>=1000000) return (n/1000000)+"M";
  if(n>=1000) return Math.round(n/1000)+"K";
  return String(n); }

function renderChips(){
  var c=current(), box=document.getElementById('chips');
  if(!box) return;   // 对话 pane 已删
  var m=curModel();
  var parts=[];
  parts.push('<div class="chip act" onclick="openSessions()"><span class="txt">'+
    esc((c&&c.title)||'新会话')+'</span><span class="caret">▾</span></div>');
  parts.push('<div class="chip" onclick="openModels()"><span class="txt">'+
    esc((m&&m.id)||'选择模型')+'</span><span class="caret">▾</span></div>');
  if(m && m.efforts){
    var eff=(c&&c.effort)||'';
    parts.push('<div class="chip" onclick="openEffort()"><span class="txt">思考 '+
      esc(eff||'跟随默认')+'</span><span class="caret">▾</span></div>');
  }
  if(m && m.context){
    parts.push('<div class="chip" onclick="openCtx()"><span class="txt">上下文 '+
      fmtCtx(m.context)+'</span><span class="caret">▾</span></div>');
  }
  box.innerHTML=parts.join('');
}

/* ---------- 底部抽屉 ---------- */
function openSheet(kind,title,items){
  sheetKind=kind;
  var sh=document.getElementById('sheet');
  var html='<h3>'+esc(title)+'</h3>';
  html+=items.map(function(it){
    return '<div class="item'+(it.on?' on':'')+'" onclick="'+it.act+'">'+
      '<span>'+esc(it.label)+(it.sub?'<span class="sub"> '+esc(it.sub)+'</span>':'')+'</span>'+
      (it.del?'<span class="del" onclick="event.stopPropagation();'+it.del+'">×</span>':'')+
      '</div>';
  }).join('');
  sh.innerHTML=html;
  sh.classList.add('on');
  document.getElementById('mask').classList.add('on');
}
function closeSheet(){
  document.getElementById('sheet').classList.remove('on');
  document.getElementById('mask').classList.remove('on');
}

function openSessions(){
  var items=[{label:'＋ 新会话',act:'newChat()'}];
  S.sessions.forEach(function(s){
    items.push({label:s.title||'新会话', on:s.id===S.active,
      sub:(s.messages||[]).length+' 条',
      act:"openSess('"+s.id+"')",
      del:"delSess('"+s.id+"')"});
  });
  openSheet('sess','会话',items);
}
function openModels(){
  var c=current();
  openSheet('model','选择模型',S.models.map(function(m){
    return {label:m.id, sub:m.context?fmtCtx(m.context):'', on:c&&c.model===m.id,
      act:"pickModel('"+m.id+"')"};
  }));
}
function openEffort(){
  var c=current(), m=curModel();
  var levels=(m&&m.efforts?m.efforts.split(','):[]).filter(Boolean);
  var labels={none:'关闭思考',minimal:'最小',low:'低',medium:'中',high:'高',
              xhigh:'极高',max:'最大'};
  var items=[{label:'跟随默认', sub:(m&&m.default_effort)||'', on:!(c&&c.effort),
    act:"pickEffort('')"}];
  items.push({label:labels.none, on:!!(c&&c.effort==='none'), act:"pickEffort('none')"});
  levels.forEach(function(v){
    items.push({label:labels[v]||v, sub:v, on:c&&c.effort===v, act:"pickEffort('"+v+"')"});
  });
  openSheet('effort','思考强度',items);
}
function openCtx(){
  var m=curModel();
  var opts=(m&&m.context)?[m.context,Math.floor(m.context/2),300000,128000]:
    [1000000,300000,128000];
  var seen={},items=[];
  opts.forEach(function(v){ if(v&&!seen[v]){seen[v]=1;
    items.push({label:fmtCtx(v), sub:String(v), on:m&&m.context===v,
      act:"pickCtx("+v+")"});}});
  openSheet('ctx','上下文长度',items);
}

/* ---------- 动作 ---------- */
function loadModels(){
  /* WB-MODEL-CACHE 加超时。
     它请求的也是 /api/models，跟模型页抢同一个连接池。
     WebView 对同一主机的并发连接有限（HTTP/1.1 通常 6 个），
     这个挂住会影响别的请求，所以给它也加超时。 */
  var ctl = new AbortController();
  var timer = setTimeout(function(){ try{ ctl.abort(); }catch(e){} }, 8000);
  return fetch('/api/models', {signal: ctl.signal})
    .then(function(r){ return r.ok ? r.json() : {models: []}; })
    .then(function(d){
      S.models = d.models || [];
      renderChips();
    })
    .catch(function(){})
    .finally(function(){ clearTimeout(timer); });
}
function loadChat(){
  return fetch('/api/chat').then(function(r){return r.json();}).then(function(d){
    S.sessions=d.sessions||[]; S.active=d.active||"";
    if(!S.sessions.length) return newChat();
    renderChips(); renderLog();
  }).catch(function(){});
}
function openSess(id){ closeSheet(); S.active=id;
  fetch('/api/chat/active',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({id:id})}).then(loadChat); }
function newChat(){ closeSheet();
  return fetch('/api/chat/new',{method:'POST'}).then(loadChat); }
function delSess(id){
  fetch('/api/chat/delete',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({id:id})}).then(function(){closeSheet();loadChat();});
}
function pickModel(v){ closeSheet();
  var c=current(); if(!c) return newChat().then(function(){pickModel(v);});
  fetch('/api/chat/meta',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({id:c.id,model:v})}).then(loadChat); }
function pickEffort(v){ closeSheet();
  var c=current(); if(!c) return;
  fetch('/api/chat/meta',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({id:c.id,effort:v})}).then(loadChat); }
function pickCtx(v){ closeSheet();
  var c=current(); if(!c) return;
  fetch('/api/chat/meta',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({id:c.id,context:v})}).then(loadChat); }

/* ---------- 渲染对话 ---------- */
function renderLog(){
  /* WB-DROP-CHAT-CALLS 对话 pane 已删，这些元素不存在了。
     加 null 保护：即使某条路径调到也不会崩。 */
  var c=current(), box=document.getElementById('log');
  if(!box) return;
  if(!c || !(c.messages||[]).length){
    box.innerHTML='<div class="empty"><b>内置 Agent</b>'+
      '<p>可以直接对话，也能在下面的「终端」页执行命令。</p>'+
      '<p>点上面的胶囊选会话、模型和思考强度。</p></div>';
    return;
  }
  box.innerHTML=c.messages.map(function(m){
    var me=m.role==='user';
    return '<div class="row '+(me?'me':'ai')+'">'+
      '<div class="av">'+(me?'你':'AI')+'</div>'+
      '<div class="col"><div class="name">'+(me?'你':'Agent')+'</div>'+
      '<div class="bub">'+esc(m.content)+'</div></div></div>';
  }).join('');
  box.scrollTop=box.scrollHeight;
}

/* ---------- 发送 ---------- */
function send(){
  if(S.busy) return;
  var c=current(); if(!c) return;
  var ta=document.getElementById('text');
  if(!ta) return;                       // 对话 pane 已删
  var text=ta.value.trim(); if(!text) return;
  ta.value=''; ta.style.height='auto';
  var box=document.getElementById('log');
  if(!box) return;                      // 对话 pane 已删
  box.innerHTML+='<div class="row me"><div class="av">你</div><div class="col">'+
    '<div class="name">你</div><div class="bub">'+esc(text)+'</div></div></div>';

  var think=document.createElement('div');
  think.className='row ai'; think.style.display='none';
  think.innerHTML='<div class="av">AI</div><div class="col">'+
    '<div class="think" id="thinkBox"><header onclick="this.parentNode.parentNode'+
    '.querySelector(\'.think\').classList.toggle(\'open\')">'+
    '<span class="t" id="thinkFirst">思考中…</span><span class="caret">▾</span></header>'+
    '<body id="thinkBody"></body></div></div>';
  box.appendChild(think);

  var ai=document.createElement('div');
  ai.className='row ai';
  ai.innerHTML='<div class="av">AI</div><div class="col"><div class="name">Agent</div>'+
    '<div class="bub" id="aiBub"></div></div>';
  box.appendChild(ai);
  box.scrollTop=box.scrollHeight;

  S.busy=true;
  var sb=document.getElementById('sendBtn'); if(sb) sb.disabled=true;
  var bb=document.getElementById('busybar'); if(bb) bb.style.display='flex';

  var body={id:c.id,text:text,model:(curModel()||{}).id||'',
            effort:(c.effort||'')};
  fetch('/api/chat/send',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify(body)}).then(function(res){
    if(!res.ok||!res.body){ throw new Error('HTTP '+res.status); }
    var rd=res.body.getReader(), dec=new TextDecoder(), buf='';
    var bub=document.getElementById('aiBub');
    var tb=document.getElementById('thinkBody');
    var tf=document.getElementById('thinkFirst');
    function pump(){
      rd.read().then(function(r){
        if(r.done){ finish(); return; }
        buf+=dec.decode(r.value,{stream:true});
        var parts=buf.split('\n\n'); buf=parts.pop();
        parts.forEach(function(p){
          var m=p.match(/^data:\s*(.*)$/m); if(!m) return;
          var ev; try{ev=JSON.parse(m[1]);}catch(e){return;}
          if(ev.type==='reasoning'){
            think.style.display='flex';
            tb.textContent+=ev.text;
            var first=(tb.textContent.split('\n')[0]||'').slice(0,60);
            tf.textContent=first||'思考中…';
          }else if(ev.type==='content'){
            bub.textContent+=ev.text;
          }else if(ev.type==='error'){
            var d=document.createElement('div');
            d.className='bub err'; d.textContent='出错了：'+ev.message;
            ai.querySelector('.col').appendChild(d);
          }
          box.scrollTop=box.scrollHeight;
        });
        pump();
      }).catch(function(e){ fail(e); });
    }
    pump();
  }).catch(function(e){ fail(e); });

  function fail(e){
    var d=document.createElement('div');
    d.className='row ai';
    d.innerHTML='<div class="av">!</div><div class="col"><div class="bub err">'+
      '请求失败：'+esc(e.message)+'</div></div>';
    box.appendChild(d); box.scrollTop=box.scrollHeight;
    finish();
  }
  function finish(){
    S.busy=false; var _sb=document.getElementById('sendBtn'); if(_sb) _sb.disabled=false;
    document.getElementById('busybar').style.display='none';
    setTimeout(loadChat,260);
  }
}

/* ---------- 终端 ---------- */
/* 当前目录存在 JS 里：shell 每次都是新进程，不把 cwd 带上去，cd 就白敲了 */
var CWD='';
function shorten(p){
  if(!p) return '';
  if(p.indexOf('/sdcard/')===0) return '~'+p.slice(7);
  if(p==='/sdcard') return '~';
  return p;
}
function paintCwd(){
  var el=document.getElementById('cwdTag');
  if(el) el.textContent=(CWD?shorten(CWD):'…');
}
function exec(){
  var inp=document.getElementById('cmd');
  var cmd=inp.value.trim(); if(!cmd) return;
  hist.push(cmd); hi=hist.length; inp.value='';
  var out=document.getElementById('out');
  out.innerHTML+='<span class="p">'+esc(shorten(CWD)||'$')+' $ '+esc(cmd)+'</span>\n';
  fetch('/api/term',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({cmd:cmd,cwd:CWD||null})}).then(function(r){return r.json();}).then(function(d){
    out.innerHTML+=esc(d.out)+'\n<span class="p">[exit '+d.code+' · '+d.ms+'ms]</span>\n\n';
    if(d.cwd){ CWD=d.cwd; paintCwd(); }
    out.scrollTop=out.scrollHeight;
  }).catch(function(e){
    out.innerHTML+='<span class="e">执行失败：'+esc(e.message)+'</span>\n';
  });
}
/* ---------- 密钥 ---------- */
var KSTATE={keys:[],deleted:[],auth_on:false};
var KEDIT=null;   // null=新建，字符串=编辑中的 id

function maskKey(k){
  if(!k) return '';
  if(k.length<=16) return k;
  return k.slice(0,11)+'····'+k.slice(-4);
}
function copyText(t,el){
  var done=function(){
    if(el){var o=el.textContent;el.textContent='已复制';
      setTimeout(function(){el.textContent=o;},900);}
  };
  if(navigator.clipboard && navigator.clipboard.writeText){
    navigator.clipboard.writeText(t).then(done).catch(function(){fallback();});
  } else fallback();
  function fallback(){
    var ta=document.createElement('textarea');
    ta.value=t; ta.style.position='fixed'; ta.style.opacity='0';
    document.body.appendChild(ta); ta.select();
    try{document.execCommand('copy'); done();}catch(e){}
    document.body.removeChild(ta);
  }
}

function renderKeys(d){
  KSTATE=d;
  var ks=d.keys||[], dead=d.deleted||[];
  document.getElementById('keySub').innerHTML =
    '共 <b>'+ks.length+'</b> 把 · 启用 <b>'+(d.enabled_count||0)+'</b> 把';

  var warn=document.getElementById('keyWarn');
  warn.style.display = (d.enabled_count>0) ? 'none' : 'flex';

  var box=document.getElementById('keyList');
  if(!ks.length){
    box.innerHTML='<div class="empty"><b>还没有密钥</b>'+
      '<p>新建一把，客户端才能带 Key 调用。<br>'+
      '不建的话网关对任何人开放。</p>'+
      '<button class="btn ago" style="margin-top:14px;display:inline-block" '+
      'onclick="openNewKey()">+ 新建第一把密钥</button></div>';
  } else {
    box.innerHTML=ks.map(function(k){
      var tags=[];
      if(k.realm==='intl') tags.push('<span class="tag acc">国际版</span>');
      else if(k.realm==='cn') tags.push('<span class="tag acc">国内版</span>');
      else tags.push('<span class="tag">不限区域</span>');
      if(k.concurrency_limit>0)
        tags.push('<span class="tag warn">并发 '+k.concurrency_limit+'</span>');
      if(k.models && k.models.length)
        tags.push('<span class="tag">限 '+k.models.length+' 个模型</span>');
      if(k.accounts && k.accounts.length)
        tags.push('<span class="tag">限 '+k.accounts.length+' 个账号</span>');
      if(k.created_at) tags.push('<span class="tag">'+esc(k.created_at)+'</span>');

      return '<div class="kc'+(k.enabled?'':' off')+'">'+
        '<div class="kc-top">'+
          '<span class="kc-dot"></span>'+
          '<span class="kc-name">'+esc(k.name)+'</span>'+
        '</div>'+
        '<div class="kc-key" onclick="copyText(\''+esc(k.key).replace(/'/g,"")+'\',this)">'+
          esc(maskKey(k.key))+'<span class="cp">点击复制</span></div>'+
        '<div class="kc-tags">'+tags.join('')+'</div>'+
        '<div class="kc-btns">'+
          '<button class="btn ghost" onclick="editKey(\''+k.id+'\')">编辑</button>'+
          '<button class="btn ghost" onclick="toggleKey(\''+k.id+'\','+(k.enabled?'false':'true')+')">'+
            (k.enabled?'停用':'启用')+'</button>'+
          '<button class="btn danger" onclick="delKey(\''+k.id+'\',\''+esc(k.name).replace(/'/g,"")+'\')">删除</button>'+
        '</div>'+
      '</div>';
    }).join('');
  }

  var db=document.getElementById('keyDead');
  if(dead.length){
    db.innerHTML='<h4>已删除 · 历史用量仍按 id 归属</h4>'+dead.map(function(k){
      return '<div class="dc"><span class="n">'+esc(k.name)+'</span>'+
        '<span class="d">'+esc(k.deleted_at)+'</span></div>';
    }).join('');
  } else db.innerHTML='';
}

/* WB-KEYS-ROBUST
   现象：刚进控制台点「密钥」，列表一直停在 HTML 初始的「加载中…」，
        切到别的页签再回来就好了。

   原因：这个 fetch 没有超时也没有重试。只要那一次请求没返回
        （WebView 刚建好、连接还没热、被后续导航打断…），
        promise 就永远 pending，界面永远「加载中」。
        再回来时重新 fetch 一次多半就成 —— 所以看起来像"要切一下"。

   现在：
     · AbortController 5 秒超时，挂住也算失败，不会无限等
     · 失败自动重试一次（600ms 后）
     · 两次都失败才显示「读取失败」+ 重试按钮
   第二个参数 retry 是内部用的。 */
function loadKeys(retry){
  var box = document.getElementById('keyList');
  var ctl = null, timer = null;
  try{
    if(typeof AbortController === 'function'){
      ctl = new AbortController();
      timer = setTimeout(function(){ try{ ctl.abort(); }catch(e){} }, 5000);
    }
  }catch(e){}

  var opt = {method:'POST',headers:{'Content-Type':'application/json'},body:'{}'};
  if(ctl) opt.signal = ctl.signal;

  fetch('/api/keys', opt)
    .then(function(r){ return r.json(); })
    .then(function(d){
      if(timer) clearTimeout(timer);
      if(d && d.error) throw new Error(d.error);
      renderKeys(d);
    })
    .catch(function(e){
      if(timer) clearTimeout(timer);
      if(!retry){
        setTimeout(function(){ loadKeys(1); }, 600);
        return;
      }
      if(box) box.innerHTML =
        '<div class="empty"><b>读取失败</b><p>' +
        esc(e && e.message ? e.message : '网络无响应') +
        '</p><p style="margin-top:8px">' +
        '<button class="btn ghost mini" onclick="loadKeys()">重试</button></p></div>';
    });
}

function setRealm(v){
  var seg=document.getElementById('kfRealm');
  for(var i=0;i<seg.children.length;i++)
    seg.children[i].classList.toggle('segon',seg.children[i].getAttribute('data-v')===v);
}
function getRealm(){
  var seg=document.getElementById('kfRealm');
  for(var i=0;i<seg.children.length;i++)
    if(seg.children[i].classList.contains('segon'))
      return seg.children[i].getAttribute('data-v');
  return '';
}
function genKey(){
  /* WB-GENKEY-SAFE
     原来直接用 crypto.getRandomValues。
     在 WebView 里如果页面不被判定为安全上下文（http:// 且 WebView 版本
     较老时会发生），window.crypto 或 crypto.getRandomValues 可能是
     undefined —— 那一行会抛 TypeError，整个页面脚本挂掉，表现就是闪退。

     现在分三层：
       ① crypto.getRandomValues  最好
       ② msCrypto.getRandomValues  老 IE/WebView 前缀
       ③ Math.random 兜底（不够强随机，但生成一把本地 API Key 够用，
          而且用户可以自己改）
     任何一层都不再抛异常。 */
  var hex = '';
  try{
    var a = new Uint8Array(24);
    var c = window.crypto || window.msCrypto;
    if(c && typeof c.getRandomValues === 'function'){
      c.getRandomValues(a);
      for(var i=0;i<a.length;i++) hex += a[i].toString(16).padStart(2,'0');
    }
  }catch(e){
    hex = '';
  }
  if(hex.length !== 48){
    // 兜底：48 个十六进制字符
    hex = '';
    var chars = '0123456789abcdef';
    for(var j=0;j<48;j++) hex += chars.charAt(Math.floor(Math.random()*16));
  }
  var el = document.getElementById('kfKey');
  if(el) el.value = 'sk-wb-' + hex;
}
function openNewKey(){
  KEDIT=null;
  document.getElementById('sheetTitle').textContent='新建密钥';
  document.getElementById('kfName').value='';
  document.getElementById('kfKey').value='';
  document.getElementById('kfConc').value='';
  document.getElementById('kfModels').value='';
  setRealm('');
  document.getElementById('keySheet').classList.add('on');
}
function editKey(id){
  var k=null;
  for(var i=0;i<KSTATE.keys.length;i++) if(KSTATE.keys[i].id===id) k=KSTATE.keys[i];
  if(!k) return;
  KEDIT=id;
  document.getElementById('sheetTitle').textContent='编辑密钥';
  document.getElementById('kfName').value=k.name||'';
  document.getElementById('kfKey').value=k.key||'';
  document.getElementById('kfConc').value=k.concurrency_limit||'';
  document.getElementById('kfModels').value=(k.models||[]).join(', ');
  setRealm(k.realm||'');
  document.getElementById('keySheet').classList.add('on');
}
function closeKeySheet(){
  document.getElementById('keySheet').classList.remove('on');
}
function parseModels(v){
  return String(v||'').split(',').map(function(x){return x.trim();})
    .filter(function(x){return x.length>0;});
}
function saveKey(){
  var name=document.getElementById('kfName').value.trim();
  var key=document.getElementById('kfKey').value.trim();
  var realm=getRealm();
  var conc=parseInt(document.getElementById('kfConc').value||'0',10)||0;
  var models=parseModels(document.getElementById('kfModels').value);
  var btn=document.getElementById('kfSave');

  btn.disabled=true; btn.textContent='保存中…';
  var done=function(){btn.disabled=false;btn.textContent='保存';};

  if(KEDIT){
    fetch('/api/keys/update',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({id:KEDIT,patch:{name:name,realm:realm,
        concurrency_limit:conc,models:models}})})
      .then(function(r){return r.json();})
      .then(function(d){ done(); if(d.ok){closeKeySheet();loadKeys();} else alert(d.error||'保存失败'); })
      .catch(function(e){ done(); alert(e.message); });
  } else {
    fetch('/api/keys/add',{method:'POST',headers:{'Content-Type':'application/json'},
      body:JSON.stringify({name:name||'未命名',realm:realm,
        concurrency_limit:conc,models:models,value:key||null})})
      .then(function(r){return r.json();})
      .then(function(d){
        done();
        if(d.ok){ closeKeySheet(); loadKeys();
          if(d.key) setTimeout(function(){ alert('新密钥已生成：\n\n'+d.key+'\n\n请复制保存好。'); },300);
        } else alert(d.error||'创建失败');
      })
      .catch(function(e){ done(); alert(e.message); });
  }
}
function toggleKey(id,en){
  fetch('/api/keys/toggle',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({id:id,enabled:en})})
    .then(function(r){return r.json();}).then(function(){loadKeys();});
}
function delKey(id,name){
  if(!confirm('删除密钥「'+name+'」？\n\n密钥会被抹掉，不能恢复。\n历史用量仍按 id 保留。')) return;
  fetch('/api/keys/delete',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({id:id})})
    .then(function(r){return r.json();}).then(function(){loadKeys();});
}

/* ---------- 调用记录 ---------- */
var CALL_ONLY='';
function setOnly(v){
  CALL_ONLY=v;
  var seg=document.getElementById('callSeg');
  for(var i=0;i<seg.children.length;i++){
    seg.children[i].classList.toggle('segon', seg.children[i].getAttribute('data-only')===v);
  }
  loadCalls();
}
function fmtNum(n){
  n=Number(n||0);
  if(n>=1e9) return (n/1e9).toFixed(2)+'B';
  if(n>=1e6) return (n/1e6).toFixed(2)+'M';
  if(n>=1e4) return (n/1e3).toFixed(1)+'K';
  return n.toLocaleString('zh-CN');
}
function fmtMoney(v){
  if(v===null||v===undefined) return '—';
  v=Number(v);
  if(!isFinite(v)) return '—';
  if(v===0) return '0';
  if(v<0.000001) return '<0.000001';
  if(v<0.01) return v.toFixed(6);
  return v.toFixed(4);
}
function fmtRate(r){
  /* 倍率：按每百万 token 的单价折算成「倍率」显示。
     上游 cost_rates 给的是 unit 内的单价（默认每 1,000,000 token 的 USD），
     这里原样显示成 ×N 太误导，直接显示单价 + 单位更实在。 */
  if(!r) return '';
  var u=r.unit||1000000;
  var per = u>=1000000 ? '/M' : ('/'+u);
  var out=[];
  if(r.hit!=null) out.push('缓存命中 '+r.hit+per);
  if(r.miss!=null) out.push('未命中 '+r.miss+per);
  if(r.out!=null) out.push('输出 '+r.out+per);
  return out.join(' · ');
}

function renderStats(st){
  var g=document.getElementById('statGrid');
  /* 照 WorkBuddy-Hub-Android 的布局：2 列 × 2 行，数字超大，标签小字 */
  /* 标签越短越好：数字 30px 时标签只有 9px，字多了挤成一条看不清 */
  var cards=[
    [fmtNum(st.ok),             '成功',   'acc'],
    [fmtNum(st.bad),            '失败',   st.bad>0?'bad':''],
    [fmtNum(st.total_tokens),   '总 Tokens', ''],
    [fmtNum(st.prompt_tokens),  '输入',   '']
  ];
  g.innerHTML=cards.map(function(c){
    return '<div class="statcard"><div class="k">'+c[0]+'</div>'+
      '<div class="v '+c[2]+'">'+c[1]+'</div></div>';
  }).join('');

  /* 按模型汇总：只有一个模型时不占地方，多于一个才画条 */
  var bars=document.getElementById('modelBars');
  var ms=st.models||[];
  if(ms.length<2){ bars.innerHTML=''; return; }
  var max=0;
  ms.forEach(function(m){ if(m.cny>max) max=m.cny; });
  bars.innerHTML=ms.map(function(m){
    var pct = max>0 ? (m.cny/max*100) : 0;
    return '<div class="mb"><span class="n" title="'+esc(m.model)+'">'+esc(m.model)+
      '</span><span class="bar"><i style="width:'+pct.toFixed(1)+'%"></i></span>'+
      '<span class="c">'+m.n+'次 ¥'+fmtMoney(m.cny)+'</span></div>';
  }).join('');
}

function renderCalls(d){
  renderStats(d.stats||{});
  var rows=d.rows||[];
  document.getElementById('callMeta').textContent =
    '共 '+rows.length+' 条' + (CALL_ONLY?('（'+(CALL_ONLY==='ok'?'仅成功':'仅失败')+'）'):'') +
    ' · 上限 1000';
  var box=document.getElementById('callList');
  if(!rows.length){
    box.innerHTML='<div class="empty"><b>暂无记录</b><p>发一次请求就有了。</p></div>';
    return;
  }
  box.innerHTML=rows.map(function(r){
    var cls = r.ok ? 'ok' : 'bad';
    /* 字母头像：模型名的首字母（大写）。deepseek → D，kimi → K
       颜色按模型名哈希取，同一个模型每次都同一个色。 */
    var initial=(r.model||'?').charAt(0).toUpperCase();
    var hue=0;
    for(var i=0;i<(r.model||'').length;i++) hue=(hue*31+r.model.charCodeAt(i))%360;

    /* 第一行：头像 + 模型名（左）  时间（右） */
    var l1='<div class="l1">'+
      '<span class="av" style="background:hsl('+hue+',42%,26%);color:hsl('+hue+',70%,72%)">'+
        esc(initial)+'</span>'+
      '<span class="mdl" title="'+esc(r.model)+'">'+esc(r.model||'(未知)')+'</span>'+
      '<span class="tm">'+esc((r.iso||'').slice(5,19).replace('T',' '))+'</span>'+
      '</div>';

    /* 第二行：倍率（直接显示你的 cost_rates 原始单价） */
    var rateLine='';
    if(r.rates){
      var per=(r.unit&&r.unit>=1000000)?'':(' /'+fmtNum(r.unit||1));
      var bits=[];
      if(r.rates.miss!=null) bits.push(r.rates.miss);
      if(r.rates.out!=null)  bits.push(r.rates.out);
      if(bits.length) rateLine='<div class="l2">倍率 '+bits.join(' / ')+per+'</div>';
    }

    /* 第三行：结果。成功=入/出+消耗，失败=原因 */
    var l3='';
    if(r.ok){
      var parts=[fmtNum(r.pt)+' 入 / '+fmtNum(r.ct)+' 出'];
      if(r.credit>0) parts.push('消耗 '+fmtNum(r.credit));
      else if(r.cny!=null) parts.push('消耗 ¥'+fmtMoney(r.cny));
      l3='<div class="l3">'+parts.join(' · ')+'</div>';
    }else{
      l3='<div class="l3 bad">'+esc(r.reason || ('失败 · '+(r.outcome||'')))+'</div>';
    }

    return '<div class="ci '+cls+'">'+l1+rateLine+l3+'</div>';
  }).join('');
}

function loadCalls(){
  var box=document.getElementById('callList');
  if(box && !box.children.length) box.innerHTML='<div class="empty">加载中…</div>';
  fetch('/api/calls',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({limit:1000,only:CALL_ONLY||null})})
    .then(function(r){return r.json();})
    .then(renderCalls)
    .catch(function(e){
      document.getElementById('callList').innerHTML=
        '<div class="empty"><b>读取失败</b><p>'+esc(e.message)+'</p></div>';
    });
}

/* 打开控制台时问一下默认目录，别让提示符一直空着 */
fetch('/api/term/cwd').then(function(r){return r.json();}).then(function(d){
  if(d&&d.cwd){ CWD=d.cwd; paintCwd(); }
}).catch(function(){});
/* WB-CMD-NULL-FIX
   致命 bug 修复。
   
   终端页（paneTerm）在前面几版已经被删了，配套的 <input id="cmd"> 也一起没了，
   但这段历史命令按键处理忘了删。它跑在脚本**顶层**：
   
       document.getElementById('cmd')  → null
       null.addEventListener(...)      → TypeError
   
   JS 是单线程同步执行 —— 这一行抛错，后面所有代码全部不执行。
   而它正好排在 WB-TAB-PARAM（读取 ?tab=think）**前面**，于是：
     · 页面不读 URL 参数 → 不调 pick('think')
     · 最底部的 loadModels() 也不跑
     · 结果就是「导航高亮在思考，内容却停在默认的密钥页，
        密钥列表还卡在初始的加载中…」
   
   这就是那个"偶发切错页"的真正原因 —— 其实不是偶发，
   冷启动带参数加载必定翻车；看着正常的那几次，是因为
   手动点了页签（走 onclick 里的 pick），或者 WebView 从缓存恢复。 */
var cmdEl = document.getElementById('cmd');
if (cmdEl) {
  cmdEl.addEventListener('keydown',function(e){
    if(e.key==='ArrowUp'){ if(hi>0){hi--;this.value=hist[hi]||'';} e.preventDefault(); }
    else if(e.key==='ArrowDown'){ if(hi<hist.length-1){hi++;this.value=hist[hi]||'';}
      else {hi=hist.length;this.value='';} e.preventDefault(); }
  });
}

/* WB-DROP-CHAT-CALLS
   原来这里是 loadModels().then(loadChat) —— 打开页面先拉模型清单和对话。
   对话那个 pane 已经删了（App 有独立的原生对话页），不用再拉。
   模型清单还有别处用（密钥页的模型勾选），保留。 */
/* WB-THINK-TAB
   思考调试台在 8789（wb_boot.py），这里用 iframe 嵌进控制台。
   懒加载：第一次点开这个页签时才给 src 赋值，不点就不请求。

   端口从 /api/think-url 拿（控制台自己知道 8789 在哪），
   拿不到就退回 网关+1。 */
var THINK_LOADED = false;
function loadThinkFrame(){
  if(THINK_LOADED) return;
  var f = document.getElementById('thinkFrame');
  if(!f) return;
  THINK_LOADED = true;
  fetch('/api/think-url')
    .then(function(r){ return r.json(); })
    .then(function(d){
      f.src = (d && d.url) ? d.url : 'http://127.0.0.1:8789/';
    })
    .catch(function(){ f.src = 'http://127.0.0.1:8789/'; });
}

/* ==== WB-DROP-MODEL-TAB ====
 *
 * 这里原来是一整套模型清单的前端代码：
 *   fetchJsonWithTimeout / loadModelCatalog / switchModelRealm /
 *   modelAvatar / parseCredits / formatCredits / showModelDetail /
 *   copyModelId，加上 MODEL_REALM / MODEL_LOAD_TOKEN /
 *   MODEL_RETRY_TIMER / MODEL_CACHE 四个状态。
 *
 * 全删了 —— 模型清单改成 App 的原生页（控制台页签里的「模型」），
 * 直连网关 /v1/models，不再经过控制台这层 Python。
 *
 * 保留的部分：
 *   · /api/models 接口和 list_models()  —— 密钥页要拉模型勾选列表
 *   · loadModels()（上面那个）           —— 同上
 *   · fmtCtx()                          —— 对话页在用它格式化 token 数
 */

/* WB-EMBED 被 App 嵌进来时藏掉网页自己的页签栏 */
(function(){
  try{
    if(/[?&]embed=1/.test(location.search)) document.body.classList.add('embed');
  }catch(e){}
})();


/* ==== WB-THINK-MERGE 思考页 ====
 *
 * 两块东西合在一页：
 *   ① 网关侧档位（wb_boot.py 的 thinking.json）
 *        读 /api/think-state → 控制台代理 → 8789/api/state
 *        写 /api/think-save  → 控制台代理 → 8789/api/thinking
 *   ② App 侧对话默认档位（SharedPreferences 的 think_level）
 *        走 window.WbBridge（控制台页面是同源加载的，桥可用）
 */
var THINK = {enabled:true, mode:'catalog', force_off:false, upstream_map:null,
             env:'', requests:[]};
var THINK_MODES = ['off','catalog','high'];
var THINK_DESC = {
  off: '客户端不传 effort 就不思考（和官方端点一致）',
  catalog: '只用模型目录里给的默认档位',
  high: '上游老行为，一律强制最高档'
};
var THINK_NAME = {off:'不思考', catalog:'按模型默认', high:'最高档'};

var LEVEL_DESC = {
  off:  '发 reasoning_effort="none"，上游认这个值',
  low:  '浅一点，省额度',
  high: '多数情况的默认',
  max:  '最深，思考 token 最多'
};
var THINK_LEVEL = 'high';

function bridgeCall(fn, arg){
  try{
    if(!window.WbBridge) return null;
    if(arg === undefined) return window.WbBridge[fn]();
    return window.WbBridge[fn](arg);
  }catch(e){ return null; }
}

function loadThinkLevel(){
  var v = bridgeCall('getThinkLevel');
  if(v) THINK_LEVEL = v;
  renderThinkLevel();
}

function pickLevel(lv){
  THINK_LEVEL = lv;
  bridgeCall('setThinkLevel', lv);
  renderThinkLevel();
  toast('对话默认档位已设为 ' + (lv === 'off' ? '不思考' : lv), 'ok');
}

function renderThinkLevel(){
  var box = document.getElementById('tLevels');
  if(box){
    for(var i=0;i<box.children.length;i++){
      var el = box.children[i];
      el.classList.toggle('on', el.getAttribute('data-lv') === THINK_LEVEL);
    }
  }
  var h = document.getElementById('tLevelHint');
  if(h) h.textContent = LEVEL_DESC[THINK_LEVEL] || '';
}

async function thinkLoad(){
  try{
    var d = await fetch('/api/think-state').then(function(r){ return r.json(); });
    if(d && d.error){
      var m = document.getElementById('tMsg');
      if(m) m.textContent = d.error;
      return;
    }
    THINK.enabled = !!d.enabled;
    THINK.mode = d.mode || 'catalog';
    THINK.force_off = !!d.force_off;
    THINK.upstream_map = d.upstream_map;
    THINK.env = d.env || '';
    THINK.requests = d.requests || [];
    renderThink();
  }catch(e){
    var m2 = document.getElementById('tMsg');
    if(m2) m2.textContent = '读取失败：' + e.message;
  }
}

function renderThink(){
  var en = document.getElementById('tEnabled');
  if(en) en.checked = THINK.enabled;
  var note = document.getElementById('tOffNote');
  if(note) note.style.display = THINK.enabled ? 'none' : 'block';
  var pad = document.getElementById('tTune');
  if(pad){ pad.style.opacity = THINK.enabled ? '1' : '.42';
           pad.style.pointerEvents = THINK.enabled ? 'auto' : 'none'; }

  var seg = document.getElementById('tModes');
  if(seg){
    seg.innerHTML = THINK_MODES.map(function(m){
      return '<div class="topt' + (THINK.mode===m?' on':'') + '" data-m="' + m +
        '" onclick="pickMode(\'' + m + '\')" title="' +
        esc(m + ' — ' + (THINK_DESC[m]||'')) + '"><b>' +
        esc(THINK_NAME[m]||m) + '</b></div>';
    }).join('');
  }
  var hint = document.getElementById('tModeHint');
  if(hint) hint.textContent = THINK_DESC[THINK.mode] || '';

  var f = document.getElementById('tForce');
  if(f) f.checked = THINK.force_off;

  var um = document.getElementById('tUmap');
  if(um){
    var known = THINK.upstream_map !== null && THINK.upstream_map !== undefined;
    um.textContent = known ? (THINK.upstream_map ? '已开启' : '已关闭') : '未知';
    um.className = 'badge2 ' + (THINK.upstream_map ? 'ok' : 'warn');
  }

  var tb = document.getElementById('tRows');
  if(tb){
    if(!THINK.requests.length){
      tb.innerHTML = '<tr><td colspan="4" class="tsub">还没有请求</td></tr>';
    }else{
      tb.innerHTML = THINK.requests.map(function(r){
        return '<tr><td>' + esc(r.t) + '</td><td>' + esc(r.model||'') +
          '</td><td>' + esc(r.effort||'—') + '</td><td>' +
          esc(r.thinking||'—') + '</td></tr>';
      }).join('');
    }
  }
}

function pickMode(m){
  THINK.mode = m;
  renderThink();
}

async function thinkSave(){
  var btnMsg = document.getElementById('tMsg');
  try{
    var d = await fetch('/api/think-save', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({
        enabled: THINK.enabled,
        mode: THINK.mode,
        force_off: THINK.force_off,
      })
    }).then(function(r){ return r.json(); });
    if(d && d.error){
      if(btnMsg) btnMsg.textContent = '保存失败：' + d.error;
      return;
    }
    THINK.enabled = !!d.enabled;
    THINK.mode = d.mode || THINK.mode;
    THINK.force_off = !!d.force_off;
    renderThink();
    if(btnMsg) btnMsg.textContent = '已保存并生效';
    setTimeout(function(){ if(btnMsg) btnMsg.textContent = ''; }, 2500);
  }catch(e){
    if(btnMsg) btnMsg.textContent = '保存失败：' + e.message;
  }
}

/* 两个开关 + 首次进入时加载 */
(function(){
  var en = document.getElementById('tEnabled');
  if(en) en.addEventListener('change', function(e){
    THINK.enabled = e.target.checked; renderThink();
  });
  var f = document.getElementById('tForce');
  if(f) f.addEventListener('change', function(e){
    THINK.force_off = e.target.checked;
  });
})();

/* WB-TAB-PARAM
   支持用 ?tab=keys / ?tab=term / ?tab=think 直接打开某个页签。
   App 的「设置 → 客户端接入 → API Key」就是靠这个跳过来的。

   不认识的参数就留在默认页（终端），不报错。 */
(function(){
  try{
    var m = location.search.match(/[?&]tab=([a-z]+)/i);
    if(m && m[1]){
      var want = m[1].toLowerCase();
      if(want === 'keys' || want === 'think' || want === 'channels'){
        pick(want);
      }
    }
  }catch(e){}
})();

loadModels();
</script></body></html>"""


def _json_send(handler, code, obj):
    raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(raw)))
    handler.send_header("Cache-Control", "no-store")
    handler.end_headers()
    try:
        handler.wfile.write(raw)
    except Exception:
        pass


# ---------------------------------------------------------------- 控制台鉴权
#
# WB-CONSOLE-AUTH
#
# 之前整个控制台的 /api/* 完全不设防：任何能连上 127.0.0.1:8790 的进程都能
# 造密钥、删密钥、执行任意 shell（/api/term）、用账号池发请求。
# 现在改成：
#   · 页面本身（/ 和 /index.html）照旧放行 —— 它不含任何机密
#   · 其余 /api/* 必须带令牌
#   · 令牌存在 wb_app/console.token，App 的 WebView 打开页面时由页面自己取
#
# 令牌怎么给到前端：页面是同一个 server 发的，所以首次 GET / 时把令牌
# 内联进 HTML（<meta name="wb-token">），前端所有 fetch 自动带上。
# 局域网里的其它机器拿不到这份 HTML 的机密部分以外的东西……但为了让
# 端口扫描拿不到令牌，GET / 也要看来源：只允许回环地址。

_CONSOLE_TOKEN = None
_CONSOLE_TOKEN_FILE = os.path.join(STATE_DIR, "console.token")


def console_token():
    """控制台令牌。首次调用时生成并落盘。"""
    global _CONSOLE_TOKEN
    if _CONSOLE_TOKEN:
        return _CONSOLE_TOKEN
    try:
        with open(_CONSOLE_TOKEN_FILE, "r", encoding="utf-8") as fh:
            tok = fh.read().strip()
        if len(tok) >= 24:
            _CONSOLE_TOKEN = tok
            return tok
    except Exception:
        pass
    import secrets as _sec
    tok = _sec.token_urlsafe(32)
    try:
        _ensure_dir()
        with open(_CONSOLE_TOKEN_FILE, "w", encoding="utf-8") as fh:
            fh.write(tok)
        try:
            os.chmod(_CONSOLE_TOKEN_FILE, 0o600)
        except Exception:
            pass
    except Exception:
        pass
    _CONSOLE_TOKEN = tok
    return tok


def _peer_is_loopback(handler):
    try:
        addr = handler.client_address[0]
    except Exception:
        return False
    return addr in ("127.0.0.1", "::1", "localhost")


class Handler(BaseHTTPRequestHandler):
    server_version = "wb-console/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _token_ok(self):
        """请求有没有带对令牌。三种带法都认，前端用 header。"""
        want = console_token()
        got = (self.headers.get("X-WB-Token") or "").strip()
        if not got:
            # 兼容 ?token= 和 Authorization: Bearer
            try:
                parsed = urlparse(self.path)
                qs = parse_qs(parsed.query or "")
                got = (qs.get("token") or [""])[0].strip()
            except Exception:
                got = ""
        if not got:
            auth = (self.headers.get("Authorization") or "").strip()
            if auth.lower().startswith("bearer "):
                got = auth[7:].strip()
        if not got:
            return False
        # 定长比较，别让计时差泄出前缀
        if len(got) != len(want):
            return False
        diff = 0
        for a, b in zip(got, want):
            diff |= ord(a) ^ ord(b)
        return diff == 0

    def _deny(self):
        _json_send(self, 401, {"error": "控制台令牌无效或缺失"})

    def _body(self):
        try:
            n = int(self.headers.get("Content-Length") or 0)
            data = json.loads(self.rfile.read(n) or b"{}")
            return data if isinstance(data, dict) else {}
        except Exception:
            return {}

    def do_GET(self):
        # WB-MODELS-TAB
        # do_GET 原来只 split("?")[0] 拿路径，没解析 query。
        # 我新加的 /api/models-detail?realm=xxx 里用了 query 变量，
        # 那是没有的 —— 会抛 NameError，接口 500，前端拿不到数据，
        # 表现就是"刷新和切换都没反应"。
        # 这里补上解析。
        # 文件顶部是 from urllib.parse import urlparse, parse_qs，
        # 直接用这两个函数名，不要写 urllib.parse.xxx
        _parsed = urlparse(self.path)
        path = _parsed.path
        query = parse_qs(_parsed.query)
        if path in ("/", "/index.html"):
            # 页面要带令牌，所以只发给本机。局域网里别人拿到这个 HTML
            # 就等于拿到令牌，那鉴权白做了。
            if not _peer_is_loopback(self):
                _json_send(self, 403, {"error": "control console is local-only"})
                return
            html = PAGE.replace("__WB_CONSOLE_TOKEN__", console_token())
            raw = html.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                self.wfile.write(raw)
            except Exception:
                pass
            return
        if path == "/api/models":
            # WB-MODEL-CACHE 只读内存缓存，不碰 fetch_models，永不阻塞
            if not self._token_ok():
                self._deny()
                return
            _ensure_model_refresh_started()
            _want = (query.get("realm") or [None])[0]
            try:
                _list, _status = list_models(_want)
                _resp = {"models": _list, "realm": str(_want or "auto")}
                if _status == "loading":
                    # 还没有数据：告诉前端稍后重试，而不是让它干等
                    _resp["status"] = "loading"
                    _resp["retry_after"] = 2
                    if not _registry_ready():
                        _resp["error"] = "上游函数还没就绪（网关启动中）"
                _json_send(self, 200, _resp)
            except Exception as exc:
                _log("/api/models 失败：%r" % exc)
                _json_send(self, 200, {
                    "models": [], "realm": str(_want or "auto"),
                    "error": str(exc),
                })
            return
        if path == "/api/models-detail":
            # WB-MODEL-CACHE 完整模型目录（含 credits 倍率），只读缓存
            if not self._token_ok():
                self._deny()
                return
            _ensure_model_refresh_started()
            _realm = (query.get("realm") or ["intl"])[0] if query else "intl"
            _models, _status = models_detail(_realm)
            _resp2 = {"models": _models, "realm": _realm}
            if _status == "loading":
                _resp2["status"] = "loading"
                _resp2["retry_after"] = 2
            _json_send(self, 200, _resp2)
            return
        if path == "/api/channels":
            if not self._token_ok():
                self._deny()
                return
            try:
                rows = wb_channels.load(_accounts_dir())
                _json_send(self, 200, {
                    "channels": wb_channels.describe(rows),
                    "enabled": len(wb_channels.enabled(rows)),
                    "total": len(rows),
                    # WB-CHANNELS 冷却表：429/5xx 后这家会暂时跳过
                    "cooldowns": wb_channels.cooldowns(),
                })
            except Exception as exc:
                _json_send(self, 200, {"error": str(exc)})
            return
        if path == "/api/think-state":
            if not self._token_ok():
                self._deny()
                return
            data = _think_call("/api/state")
            if data is None:
                _json_send(self, 200, {"error": "思考调试台没响应"})
            else:
                _json_send(self, 200, data)
            return
        if path == "/api/think-url":
            # WB-THINK-TAB 告诉前端思考调试台在哪
            _json_send(self, 200, {"url": think_url()})
            return
        if path == "/api/chat":
            if not self._token_ok():
                self._deny()
                return
            _json_send(self, 200, load_chat())
            return
        _json_send(self, 404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        body = self._body()

        # 所有 POST 都在这里过闸：没有令牌一律 401。
        # 放在最前面是刻意的 —— 以后加新接口不用记得单独加鉴权，
        # 忘了反而才是安全的（默认关着）。
        if path.startswith("/api/") and not self._token_ok():
            self._deny()
            return

        if path == "/api/chat/new":
            sid = new_session()
            _json_send(self, 200, {"id": sid})
            return
        if path == "/api/chat/delete":
            data = load_chat()
            sid = str(body.get("id") or "")
            data["sessions"] = [s for s in data.get("sessions", [])
                                if s.get("id") != sid]
            if data.get("active") == sid:
                data["active"] = (data["sessions"][-1]["id"]
                                  if data["sessions"] else "")
            save_chat(data)
            _json_send(self, 200, {"ok": True})
            return
        if path == "/api/chat/active":
            data = load_chat()
            data["active"] = str(body.get("id") or data.get("active") or "")
            save_chat(data)
            _json_send(self, 200, {"ok": True})
            return
        if path == "/api/chat/meta":
            data = load_chat()
            for s in data.get("sessions", []):
                if s.get("id") == body.get("id"):
                    if "model" in body:
                        s["model"] = str(body.get("model") or "")
                    if "effort" in body:
                        s["effort"] = str(body.get("effort") or "")
                    if "context" in body:
                        try:
                            s["context"] = int(body.get("context") or 0)
                        except Exception:
                            s["context"] = 0
            save_chat(data)
            _json_send(self, 200, {"ok": True})
            return
        if path == "/api/channels/begin-login":
            # WB-BROWSER-LOGIN 起一次浏览器授权：本地监听 + 返回授权地址
            if not self._token_ok():
                self._deny()
                return
            try:
                body2 = body if isinstance(body, dict) else {}
                _json_send(self, 200, wb_channels.begin_login(
                    _accounts_dir(), str(body2.get("id") or "").strip()))
            except Exception as exc:
                _json_send(self, 200, {"ok": False, "msg": str(exc)})
            return
        if path == "/api/channels/login-state":
            if not self._token_ok():
                self._deny()
                return
            try:
                body2 = body if isinstance(body, dict) else {}
                _json_send(self, 200, wb_channels.login_state(
                    _accounts_dir(), str(body2.get("id") or "").strip()))
            except Exception as exc:
                _json_send(self, 200, {"ok": False, "pending": False, "msg": str(exc)})
            return
        if path == "/api/channels/login-url":
            if not self._token_ok():
                self._deny()
                return
            try:
                body2 = body if isinstance(body, dict) else {}
                _json_send(self, 200, wb_channels.login_url(
                    _accounts_dir(), str(body2.get("id") or "").strip()))
            except Exception as exc:
                _json_send(self, 200, {"ok": False, "msg": str(exc)})
            return
        if path == "/api/channels/login-callback":
            if not self._token_ok():
                self._deny()
                return
            try:
                body2 = body if isinstance(body, dict) else {}
                _json_send(self, 200, wb_channels.login_callback(
                    _accounts_dir(),
                    str(body2.get("id") or "").strip(),
                    str(body2.get("code") or "").strip(),
                    str(body2.get("state") or "").strip()))
            except Exception as exc:
                _json_send(self, 200, {"ok": False, "msg": str(exc)})
            return
        if path == "/api/channels/cookie":
            if not self._token_ok():
                self._deny()
                return
            try:
                body2 = body if isinstance(body, dict) else {}
                cid = str(body2.get("id") or "").strip()
                ck = str(body2.get("cookie") or "").strip()
                if not cid or not ck:
                    _json_send(self, 200, {"error": "缺少 id 或 cookie"})
                    return
                r = wb_channels.set_cookie(_accounts_dir(), cid, ck)
                _json_send(self, 200, {"ok": True, "id": cid,
                                       "cookieAt": r.get("cookieAt"),
                                       "len": len(ck)})
            except Exception as exc:
                _json_send(self, 200, {"error": str(exc)})
            return
        if path == "/api/channels/checkin":
            if not self._token_ok():
                self._deny()
                return
            try:
                body2 = body if isinstance(body, dict) else {}
                cid = str(body2.get("id") or "").strip()
                _json_send(self, 200, wb_channels.checkin(_accounts_dir(), cid))
            except Exception as exc:
                _json_send(self, 200, {"ok": False, "error": str(exc)})
            return
        if path == "/api/channels/remove":
            if not self._token_ok():
                self._deny()
                return
            try:
                body2 = body if isinstance(body, dict) else {}
                cid = str(body2.get("id") or "").strip()
                wb_channels.remove(_accounts_dir(), cid)
                _json_send(self, 200, {"ok": True})
            except Exception as exc:
                _json_send(self, 200, {"error": str(exc)})
            return
        if path == "/api/channels/save":
            if not self._token_ok():
                self._deny()
                return
            try:
                body2 = body if isinstance(body, dict) else {}
                cid = str(body2.get("id") or "").strip()
                if not cid:
                    _json_send(self, 200, {"error": "缺少 id"})
                    return
                # WB-CHANNELS 存在就更新、不存在就新建 —— 自定义渠道靠这条路加
                hit = wb_channels.upsert(_accounts_dir(), {
                    "id": cid,
                    "name": str(body2.get("name") or "").strip() or None,
                    "baseUrl": str(body2.get("baseUrl") or "").strip() or None,
                    "key": body2.get("key") if "key" in body2 else None,
                    "enabled": body2.get("enabled") if "enabled" in body2 else None,
                })
                _json_send(self, 200, {"ok": True, "id": cid,
                                       "enabled": bool(hit.get("enabled"))})
            except Exception as exc:
                _json_send(self, 200, {"error": str(exc)})
            return
        if path == "/api/think-save":
            # WB-THINK-MERGE 转发到思考调试台的保存接口
            body2 = body if isinstance(body, dict) else {}
            data = _think_call("/api/thinking", body2)
            if data is None:
                _json_send(self, 200, {"error": "思考调试台没响应"})
            else:
                _json_send(self, 200, data)
            return
        if path == "/api/term":
            cmd = str(body.get("cmd") or "")
            # 前端把当前目录带上来，这样 cd 才有意义：shell 每次都是新进程，
            # 不把 cwd 存回来，用户 cd 完下一条命令又回原处了。
            cwd = body.get("cwd")
            cwd = cwd.strip() if isinstance(cwd, str) and cwd.strip() else None
            if cwd and not os.path.isdir(cwd):
                cwd = None
            result = run_command(cmd, timeout=int(body.get("timeout") or 45), cwd=cwd)
            _json_send(self, 200, result)
            return
        if path == "/api/term/cwd":
            # 只报一个默认目录，前端第一次进来用它初始化
            _json_send(self, 200, {"cwd": default_workdir(), "home": default_workdir()})
            return
        if path == "/api/keys":
            _json_send(self, 200, keys_list())
            return
        if path == "/api/keys/add":
            _json_send(self, 200, key_add(
                body.get("name"),
                realm=body.get("realm") or "",
                models=body.get("models"),
                accounts=body.get("accounts"),
                concurrency_limit=body.get("concurrency_limit") or 0,
                value=body.get("value"),
            ))
            return
        if path == "/api/keys/update":
            _json_send(self, 200, key_update(str(body.get("id") or ""),
                                             body.get("patch") or {}))
            return
        if path == "/api/keys/delete":
            _json_send(self, 200, key_delete(str(body.get("id") or "")))
            return
        if path == "/api/keys/toggle":
            _json_send(self, 200, key_toggle(str(body.get("id") or ""),
                                             body.get("enabled")))
            return
        if path == "/api/calls":
            try:
                limit = int(body.get("limit") or 1000)
            except Exception:
                limit = 1000
            only = body.get("only")
            only = only if only in ("ok", "bad") else None
            realm = str(body.get("realm") or "all")
            _json_send(self, 200, recent_calls(limit, only, realm))
            return
        if path == "/api/calls/get":
            try:
                limit = int(body.get("limit") or 1000)
            except Exception:
                limit = 1000
            only = body.get("only")
            only = only if only in ("ok", "bad") else None
            realm = str(body.get("realm") or "all")
            _json_send(self, 200, recent_calls(limit, only, realm))
            return
        if path == "/api/chat/send":
            sid = str(body.get("id") or "")
            text = str(body.get("text") or "")
            model = str(body.get("model") or "")
            effort = body.get("effort")
            effort = "" if effort is None else str(effort)
            # WB-TOOLCALLS
            # tools: 工具 schema，前端决定要不要给
            # tool_result: 回工具执行结果时用；这时 text 可以是空的
            tools = body.get("tools")
            if not isinstance(tools, list):
                tools = None
            tool_result = body.get("tool_result")
            if not isinstance(tool_result, dict):
                tool_result = None
            # 有 tool_result 时可以不带 text；两者都没有才算坏请求
            if not sid or (not text and not tool_result):
                _json_send(self, 400, {"error": "bad request"})
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            try:
                stream_chat(sid, text, model, effort, self.wfile,
                            tools=tools, tool_result=tool_result)
            except Exception as exc:
                try:
                    self.wfile.write(b"data: " + json.dumps(
                        {"type": "error", "message": str(exc)},
                        ensure_ascii=False).encode() + b"\n\n")
                except Exception:
                    pass
            return
        _json_send(self, 404, {"error": "not found"})


def serve(host, port):
    global _console_port
    httpd = None
    for offset in range(0, 8):
        try:
            httpd = ThreadingHTTPServer((host, port + offset), Handler)
            _console_port = port + offset
            break
        except Exception:
            continue
    if httpd is None:
        return 0
    httpd.daemon_threads = True
    _ensure_dir()
    # WB-MODEL-CACHE 服务一起来就启动后台模型清单刷新线程。
    # 不等第一次请求才启 —— 那样用户第一次打开模型页必然看到"加载中"。
    try:
        _ensure_model_refresh_started()
    except Exception as exc:
        _log("启动模型缓存线程失败：%r" % exc)
    # 启动时补一次面板密码（只在密码是默认值时动手，见 ensure_panel_password）
    try:
        msg = ensure_panel_password()
        if msg:
            print("wb_console:", msg, flush=True)
    except Exception:
        pass
    try:
        with open(CONSOLE_PORT_FILE, "w", encoding="utf-8") as fh:
            fh.write(str(_console_port))
    except Exception:
        pass
    try:
        httpd.serve_forever(poll_interval=0.5)
    except Exception:
        pass
    return _console_port


def set_gateway_port(port):
    global _gateway_port
    _gateway_port = int(port)
