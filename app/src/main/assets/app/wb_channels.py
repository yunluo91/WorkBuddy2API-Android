"""多渠道路由（WB-CHANNELS）

把「别的反代项目」当成上游接进来跑。

思路抄自 dsh-our-free-model 的 src/providers：
  · 一张渠道表：每家一个 {id, name, baseUrl, auth, models}
  · 模型名带渠道前缀：<渠道>/<模型>，例如 qoder/gpt-5.2、loomy/spark-4
  · 请求原样透传：只把 model 换成真实模型名、按渠道挂上鉴权头，
    其余 body 一个字段都不动（所以思考档位、工具调用、流式都能过）

为什么这样做：外面那些 qoder2api / lobsterai2api / loomy2api / zcode-api
本来就是 OpenAI 兼容的本地端点，没必要在网关里重写它们的登录流程——
把它们当渠道接进来，wd 就变成了一个聚合器。
"""

import json
import os
import threading
import time
import urllib.error
import urllib.request

CHANNELS_FILE = "channels.json"
PRESET_FILE = "channels_preset.json"

_lock = threading.Lock()
_cache = {"path": None, "mtime": 0, "channels": []}

# 模型清单缓存：{渠道 id: (取到的时刻, [模型])}
_model_cache = {}
MODEL_CACHE_TTL = 600          # 10 分钟


# ---------------------------------------------------------------- 读写

def channels_path(accounts_dir):
    return os.path.join(accounts_dir, CHANNELS_FILE)


def _preset_path():
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), PRESET_FILE)


def _load_presets():
    try:
        with open(_preset_path(), encoding="utf-8") as f:
            rows = json.load(f)
        return rows if isinstance(rows, list) else []
    except Exception:
        return []


def load(accounts_dir):
    """读渠道表。文件不存在时按预置表生成一份（全部禁用）。"""
    path = channels_path(accounts_dir)
    try:
        mtime = os.path.getmtime(path)
    except OSError:
        mtime = 0

    with _lock:
        if _cache["path"] == path and _cache["mtime"] == mtime and mtime:
            return _cache["channels"]

    rows = None
    if mtime:
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            rows = data.get("channels") if isinstance(data, dict) else data
        except Exception:
            rows = None

    if rows is None:
        rows = []
        for p in _load_presets():
            item = dict(p)
            item.setdefault("enabled", False)
            item.setdefault("key", "")
            item.setdefault("prefix", item.get("id"))
            rows.append(item)
        try:
            save(accounts_dir, rows)
        except Exception:
            pass

    rows = [r for r in rows if isinstance(r, dict) and r.get("id") and r.get("baseUrl")]
    with _lock:
        _cache["path"] = path
        _cache["mtime"] = mtime
        _cache["channels"] = rows
    return rows


def save(accounts_dir, rows):
    path = channels_path(accounts_dir)
    os.makedirs(accounts_dir, exist_ok=True)
    clean = []
    for r in rows:
        if not isinstance(r, dict):
            continue
        cid = str(r.get("id") or "").strip()
        base = str(r.get("baseUrl") or "").strip()
        if not cid or not base:
            continue
        clean.append({
            "id": cid,
            "name": str(r.get("name") or cid),
            "baseUrl": base,
            "prefix": str(r.get("prefix") or cid),
            "key": str(r.get("key") or ""),
            "enabled": bool(r.get("enabled")),
            "format": str(r.get("format") or "openai-chat"),
            "headers": r.get("headers") if isinstance(r.get("headers"), dict) else {},
            "models": [m for m in (r.get("models") or []) if isinstance(m, dict) and m.get("id")],
            "website": str(r.get("website") or ""),
        })
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"channels": clean}, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)
    with _lock:
        _cache["mtime"] = 0
    return clean


# ---------------------------------------------------------------- 解析模型名

def enabled(rows):
    return [r for r in rows if r.get("enabled")]


def resolve(rows, model_id):
    """把 <渠道>/<模型> 拆开。渠道没配或没启用就返回 None（交回原逻辑）。"""
    if not isinstance(model_id, str) or "/" not in model_id:
        return None
    head, rest = model_id.split("/", 1)
    for r in rows:
        if not r.get("enabled"):
            continue
        if r.get("prefix") == head or r.get("id") == head:
            if not rest:
                return None
            return r, rest
    return None


# ---------------------------------------------------------------- 模型清单

def _fetch_models(chan, timeout=15):
    """从渠道自己的 /models 拉清单；失败返回空。"""
    url = chan["baseUrl"].rstrip("/") + "/models"
    req = urllib.request.Request(url, method="GET")
    req.add_header("Accept", "application/json")
    if chan.get("key"):
        req.add_header("Authorization", "Bearer " + chan["key"])
    for k, v in (chan.get("headers") or {}).items():
        try:
            req.add_header(k, v)
        except Exception:
            pass
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8", "replace"))
    rows = data.get("data") if isinstance(data, dict) else data
    out = []
    for m in rows or []:
        if isinstance(m, dict) and m.get("id"):
            out.append({"id": str(m["id"])})
        elif isinstance(m, str):
            out.append({"id": m})
    return out


def _channel_models(chan):
    """静态清单优先；没有就现场拉一次，缓存 10 分钟。"""
    if chan.get("models"):
        return chan["models"]
    cid = chan.get("id")
    hit = _model_cache.get(cid)
    now = time.time()
    if hit and now - hit[0] < MODEL_CACHE_TTL:
        return hit[1]
    try:
        got = _fetch_models(chan)
        _model_cache[cid] = (now, got)
        return got
    except Exception:
        if hit:
            return hit[1]
        return []


def as_models(rows):
    """合并成 /v1/models 的条目，模型名带渠道前缀。"""
    out = []
    for chan in enabled(rows):
        prefix = chan.get("prefix") or chan.get("id")
        for m in _channel_models(chan):
            mid = m.get("id")
            if not mid:
                continue
            entry = {
                "id": "%s/%s" % (prefix, mid),
                "object": "model",
                "owned_by": chan.get("id"),
                "vendor": str(chan.get("id") or "")[:1],
                "channel": chan.get("id"),
                "channel_name": chan.get("name"),
            }
            for k in ("name", "contextWindow", "vision", "reasoning"):
                if m.get(k) is not None:
                    entry[{"contextWindow": "context_window"}.get(k, k)] = m[k]
            out.append(entry)
    return out


def upsert(accounts_dir, row):
    """新增或更新一个渠道（自定义渠道靠这个加进来）。"""
    rows = load(accounts_dir)
    cid = str(row.get("id") or "").strip()
    if not cid:
        raise ValueError("缺少 id")
    hit = None
    for r in rows:
        if r.get("id") == cid:
            hit = r
            break
    if hit is None:
        hit = {"id": cid, "prefix": cid, "models": [], "headers": {},
               "enabled": False, "key": "", "name": cid}
        rows.append(hit)
    for k in ("name", "baseUrl", "prefix", "format", "website"):
        if row.get(k):
            hit[k] = str(row[k]).strip()
    if "key" in row:
        hit["key"] = str(row.get("key") or "").strip()
    if "enabled" in row:
        hit["enabled"] = bool(row.get("enabled"))
    if not hit.get("baseUrl"):
        raise ValueError("缺少 baseUrl")
    save(accounts_dir, rows)
    return hit


def remove(accounts_dir, cid):
    rows = [r for r in load(accounts_dir) if r.get("id") != cid]
    return save(accounts_dir, rows)


def describe(rows):
    """给控制台看的摘要。"""
    return [{
        "id": c.get("id"),
        "name": c.get("name"),
        "prefix": c.get("prefix") or c.get("id"),
        "baseUrl": c.get("baseUrl"),
        "enabled": bool(c.get("enabled")),
        "hasKey": bool(c.get("key")),
        "models": len(c.get("models") or []),
    } for c in rows]


# ---------------------------------------------------------------- 转发

def forward(chan, model, payload, timeout=600):
    """把请求转发给渠道，返回未读取的 HTTP 响应对象（调用方自己流式读）。

    body 原样透传，只改 model；鉴权按渠道配置挂。
    这样思考档位、reasoning_effort、tools、stream 这些字段都不用管，
    客户端给什么就传什么。
    """
    url = chan["baseUrl"].rstrip("/") + "/chat/completions"
    body = dict(payload)
    body["model"] = model
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")

    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "text/event-stream" if body.get("stream") else "application/json")
    if chan.get("key"):
        req.add_header("Authorization", "Bearer " + chan["key"])
    for k, v in (chan.get("headers") or {}).items():
        try:
            req.add_header(k, v)
        except Exception:
            pass
    return urllib.request.urlopen(req, timeout=timeout)
