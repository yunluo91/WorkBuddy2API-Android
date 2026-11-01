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

    # 老文件补新预置：渠道表是「配置」，不是「快照」，
    # 版本更新加了新渠道时，用户不该被要求删掉 channels.json 才能看到。
    # 只补缺的 id，已有的（哪怕用户改过）一律不动。
    try:
        have = {r.get("id") for r in rows if isinstance(r, dict)}
        added = 0
        for p in _load_presets():
            if p.get("id") and p["id"] not in have:
                item = dict(p)
                item.setdefault("enabled", False)
                item.setdefault("key", "")
                item.setdefault("prefix", item.get("id"))
                # 已经登录/填过 key 的字段保持空
                item["cookie"] = ""
                rows.append(item)
                added += 1
        if added:
            try:
                save(accounts_dir, rows)
            except Exception:
                pass
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
            "cookie": str(r.get("cookie") or ""),
            "cookieAt": str(r.get("cookieAt") or ""),
            "website": str(r.get("website") or ""),
            "checkinKind": str(r.get("checkinKind") or ""),
            "authKind": str(r.get("authKind") or "apikey"),
            "token": str(r.get("token") or ""),
            "refreshToken": str(r.get("refreshToken") or ""),
            "tokenAt": str(r.get("tokenAt") or ""),
            "oauthSession": r.get("oauthSession") if isinstance(r.get("oauthSession"), dict) else {},
            "checkin": r.get("checkin") if isinstance(r.get("checkin"), dict) else {},
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
    if chan.get("token"):
        req.add_header("Authorization", "Bearer " + chan["token"])
    elif chan.get("key"):
        req.add_header("Authorization", "Bearer " + chan["key"])
    # 账号型渠道：凭据是从 App 内网页登录抓下来的整条 Cookie
    if chan.get("cookie"):
        req.add_header("Cookie", chan["cookie"])
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


def set_cookie(accounts_dir, cid, cookie):
    """把网页登录抓到的 Cookie 存到渠道上。"""
    rows = load(accounts_dir)
    for r in rows:
        if r.get("id") == cid:
            r["cookie"] = str(cookie or "").strip()
            r["cookieAt"] = time.strftime("%Y-%m-%d %H:%M:%S")
            save(accounts_dir, rows)
            return r
    raise KeyError("没有这个渠道: %s" % cid)


def request(chan, path, method="GET", body=None, timeout=30, extra_headers=None):
    """用渠道凭据打它的任意接口（签到、查积分、拉模型都走这里）。

    path 以 / 开头，拼在 baseUrl 之后；body 是 dict 就按 JSON 发。
    """
    url = chan["baseUrl"].rstrip("/") + "/" + path.lstrip("/")
    data = None
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if chan.get("token"):
        req.add_header("Authorization", "Bearer " + chan["token"])
    elif chan.get("key"):
        req.add_header("Authorization", "Bearer " + chan["key"])
    if chan.get("cookie"):
        req.add_header("Cookie", chan["cookie"])
    for k, v in (chan.get("headers") or {}).items():
        try:
            req.add_header(k, v)
        except Exception:
            pass
    for k, v in (extra_headers or {}).items():
        try:
            req.add_header(k, v)
        except Exception:
            pass
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8", "replace")
    try:
        return json.loads(raw)
    except Exception:
        return {"raw": raw[:2000]}


# ---------------------------------------------------------------- 账号登录（OAuth）

# 回调地址的固定端口：WebView 里由 App 拦截，不需要真的有人监听，
# 但 redirect_uri 必须和授权时提交的完全一致，所以写死。
OAUTH_CALLBACK_PORT = 18988
OAUTH_CALLBACK_PATH = "/auth/callback"


def _now_ms():
    return int(time.time() * 1000)


def _uuid4():
    import uuid
    return str(uuid.uuid4())


def _lobsterai_version():
    """取客户端版本号（日期式，如 2026.10.24）。

    exchange 的 body 里必填，写错会被拒。先试官方更新接口，
    拿不到就用一个近似的日期串兜底 —— 兜底值可能过期，
    所以调用方出错时要把这条一起报出来方便定位。
    """
    try:
        req = urllib.request.Request(
            "https://api-overmind.youdao.com/openapi/get/luna/hardware/lobsterai/prod/update",
            headers={"User-Agent": "lobsterai"})
        with urllib.request.urlopen(req, timeout=12) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
        def pick(o):
            if isinstance(o, dict):
                for k in ("version", "latestVersion", "appVersion"):
                    v = o.get(k)
                    if isinstance(v, str) and v:
                        return v
                for v in o.values():
                    got = pick(v)
                    if got:
                        return got
            elif isinstance(o, list):
                for v in o:
                    got = pick(v)
                    if got:
                        return got
            return ""
        v = pick(data)
        if v:
            return v
    except Exception:
        pass
    return time.strftime("%Y.%m.%d")


def login_url(accounts_dir, cid):
    """给 OAuth 型渠道生成登录地址，同时把会话参数存回渠道行。

    state / uuid / firstKeyfrom 必须在回调时原样带回，所以先落盘。
    """
    rows = load(accounts_dir)
    hit = _chan_by_id(rows, cid)
    if hit is None:
        return {"ok": False, "msg": "没有这个渠道: %s" % cid}
    kind = str(hit.get("checkinKind") or hit.get("authKind") or "")
    if kind != "lobsterai":
        return {"ok": False, "msg": "这个渠道还不支持 App 内登录，可以直接填 Key"}

    state = _uuid4()
    session = {
        "state": state,
        "uuid": _uuid4(),
        "firstKeyfrom": str(_now_ms()),
        "at": _now_ms(),
    }
    hit["oauthSession"] = session
    save(accounts_dir, rows)

    from urllib.parse import urlencode
    portal = str(hit.get("portalBase") or "https://lobsterai.youdao.com")
    q = urlencode({
        "source": "electron",
        "redirect_uri": "http://127.0.0.1:%d%s" % (OAUTH_CALLBACK_PORT, OAUTH_CALLBACK_PATH),
        "state": state,
    })
    return {"ok": True, "id": cid, "url": "%s/portal#/login?%s" % (portal, q),
            "callbackPort": OAUTH_CALLBACK_PORT, "state": state}


def login_callback(accounts_dir, cid, code, state=""):
    """回调拿到 code → 换 token → 存进渠道。

    换到的 accessToken 之后所有请求都用 Authorization: Bearer，
    refreshToken 留着续期（续期逻辑下一轮再做）。
    """
    rows = load(accounts_dir)
    hit = _chan_by_id(rows, cid)
    if hit is None:
        return {"ok": False, "msg": "没有这个渠道: %s" % cid}
    if not code:
        return {"ok": False, "msg": "回调里没有 code"}

    kind = str(hit.get("checkinKind") or hit.get("authKind") or "")
    if kind != "lobsterai":
        return {"ok": False, "msg": "这个渠道还不支持换取凭据"}

    session = hit.get("oauthSession") or {}
    if state and session.get("state") and state != session.get("state"):
        return {"ok": False, "msg": "state 对不上（可能不是这次登录的回调）"}

    version = _lobsterai_version()
    body = {
        "authCode": code,
        "firstKeyfrom": str(session.get("firstKeyfrom") or _now_ms()),
        "latestKeyfrom": str(_now_ms()),
        "uuid": str(session.get("uuid") or _uuid4()),
        "version": version,
    }
    url = hit["baseUrl"].rstrip("/") + "/api/auth/exchange"
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    req.add_header("User-Agent", "lobsterai")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return {"ok": False, "msg": "exchange HTTP %s: %s"
                % (exc.code, exc.read()[:200].decode("utf-8", "replace"))}
    except Exception as exc:
        return {"ok": False, "msg": "exchange 失败：%r" % (exc,)}

    try:
        doc = json.loads(raw)
    except Exception:
        return {"ok": False, "msg": "exchange 返回不是 JSON：%s" % raw[:200]}

    payload = doc.get("data") if isinstance(doc, dict) else None
    if not isinstance(payload, dict):
        payload = doc if isinstance(doc, dict) else {}
    token = (payload.get("accessToken") or payload.get("access_token")
             or (doc.get("data") or {}).get("accessToken") or "")
    if not token:
        return {"ok": False, "msg": "没拿到 accessToken（version=%s）：%s" % (version, raw[:200])}

    hit["token"] = str(token)
    hit["refreshToken"] = str(payload.get("refreshToken") or payload.get("refresh_token") or "")
    hit["tokenAt"] = time.strftime("%Y-%m-%d %H:%M:%S")
    hit["oauthSession"] = {}
    save(accounts_dir, rows)
    return {"ok": True, "id": cid, "tokenLen": len(token), "version": version}


# ---------------------------------------------------------------- 签到（按类型）

def _chan_by_id(rows, cid):
    for r in rows:
        if r.get("id") == cid:
            return r
    return None


def _checkin_loomy(chan):
    """讯飞 Loomy：一次 POST /points/first-login（幂等）。"""
    data = request(chan, "/points/first-login", method="POST", body={}, timeout=30)
    ok = not (isinstance(data, dict) and data.get("error"))
    return {"ok": ok, "id": chan["id"], "result": data,
            "msg": "" if ok else "接口返回错误"}


def _checkin_lobsterai(chan):
    """有道 LobsterAI：查槽位 → 查上下文 → 领取，三步。

    照 vendor/channel-pack/src/lobsterai-credits.ts 的顺序：
      ① GET  /api/client-activities/slot?placement=…&clientVersion=…
      ② GET  /api/client-activities/{code}/context?configRevision=…
      ③ POST /api/client-activities/{code}/actions/check_in
    已经领过就不能再领（claimedToday），这时直接回报 already。
    """
    slot = request(chan, "/api/client-activities/slot?placement=app&clientVersion=1.0.0",
                   method="GET", timeout=25)
    env = slot.get("data") if isinstance(slot, dict) else None
    code = ""
    if isinstance(env, dict):
        code = str(env.get("activityCode") or "")
    if not code:
        return {"ok": False, "id": chan["id"], "msg": "拿不到活动 id（可能没登录或活动已结束）",
                "result": slot}

    ctx = request(chan, "/api/client-activities/%s/context?configRevision=1" % code,
                  method="GET", timeout=25)
    body = ctx.get("data") if isinstance(ctx, dict) else {}
    if isinstance(body, dict) and body.get("claimedToday"):
        return {"ok": True, "id": chan["id"], "already": True,
                "msg": "今天已经签到过了", "result": ctx}

    got = request(chan, "/api/client-activities/%s/actions/check_in" % code,
                  method="POST", body={}, timeout=25)
    ok = not (isinstance(got, dict) and got.get("error"))
    return {"ok": ok, "id": chan["id"], "result": got,
            "msg": "" if ok else "领取接口返回错误"}


CHECKIN_KINDS = {
    "loomy": _checkin_loomy,
    "lobsterai": _checkin_lobsterai,
}


def checkin(accounts_dir, cid):
    """通用签到：按渠道行里的 checkin 配置打一次。

    配置长这样（存在 channels.json 的渠道行里）：
        "checkin": {"path": "/user/sign", "method": "POST", "body": {}}

    每家平台的签到接口不一样，但形式都是「带凭据打一个地址」，
    所以把差别做成配置，代码只写一遍。
    """
    rows = load(accounts_dir)
    hit = None
    for r in rows:
        if r.get("id") == cid:
            hit = r
            break
    if hit is None:
        return {"ok": False, "msg": "没有这个渠道: %s" % cid}
    # 有专门实现的先走专门实现
    kind = str(hit.get("checkinKind") or "").strip()
    if kind in CHECKIN_KINDS:
        # OAuth 型渠道登录后拿到的是 token，Cookie 型拿到的是 cookie，
        # 两者有一个就算登录过了。
        if not (hit.get("cookie") or hit.get("token")):
            return {"ok": False, "msg": "还没登录 —— 先点「登录」把账号登进去"}
        try:
            return CHECKIN_KINDS[kind](hit)
        except Exception as exc:
            return {"ok": False, "id": cid, "msg": str(exc)}

    cfg = hit.get("checkin") or {}
    path = str(cfg.get("path") or "").strip()
    if not path:
        return {"ok": False, "msg": "这个渠道还没配签到接口"}
    try:
        data = request(hit, path,
                       method=str(cfg.get("method") or "POST").upper(),
                       body=cfg.get("body") if isinstance(cfg.get("body"), dict) else {},
                       timeout=30,
                       extra_headers=cfg.get("headers") if isinstance(cfg.get("headers"), dict) else None)
        return {"ok": True, "id": cid, "result": data}
    except Exception as exc:
        return {"ok": False, "id": cid, "msg": str(exc)}


def describe(rows):
    """给控制台看的摘要。"""
    return [{
        "id": c.get("id"),
        "name": c.get("name"),
        "prefix": c.get("prefix") or c.get("id"),
        "baseUrl": c.get("baseUrl"),
        "enabled": bool(c.get("enabled")),
        "hasKey": bool(c.get("key")),
        "hasCookie": bool(c.get("cookie")),
        "hasToken": bool(c.get("token")),
        "tokenAt": c.get("tokenAt") or "",
        "cookieAt": c.get("cookieAt") or "",
        "hasCheckin": bool((c.get("checkin") or {}).get("path")),
        "authKind": c.get("authKind") or "apikey",
        "website": str(c.get("website") or ""),
        "models": len(c.get("models") or []),
    } for c in rows]


# ---------------------------------------------------------------- 转发

# ---------------------------------------------------------------- 降级与冷却

# {渠道 id: 冷却到什么时候（epoch 秒）}
_COOLDOWN = {}

# 429 / 5xx 属于「这家现在不行，换一家试试」，其余的错误不降级
_COOLDOWN_SECONDS = 120


def _classify_error(exc):
    """把异常分成 rate / server / other。只有前两种触发降级。"""
    code = getattr(exc, "code", None)
    if code == 429:
        return "rate"
    if isinstance(code, int) and 500 <= code < 600:
        return "server"
    return "other"


def _mark_cooldown(cid, seconds=_COOLDOWN_SECONDS):
    _COOLDOWN[cid] = time.time() + seconds


def _in_cooldown(cid):
    until = _COOLDOWN.get(cid) or 0
    if until <= time.time():
        _COOLDOWN.pop(cid, None)
        return False
    return True


def cooldowns():
    """给控制台看：现在哪些渠道在冷却。"""
    now = time.time()
    return [{"id": k, "left": int(v - now)} for k, v in _COOLDOWN.items() if v > now]


def _candidates(rows, chan, model):
    """同一个裸模型名，还能走哪些渠道（当前渠道排第一）。

    只认静态清单里声明过这个模型的渠道 —— 没声明的不能瞎猜，
    否则会把请求发到根本听不懂这个名字的上游。
    """
    out = [chan]
    for r in rows:
        if r.get("id") == chan.get("id") or not r.get("enabled"):
            continue
        if any((m or {}).get("id") == model for m in (r.get("models") or [])):
            out.append(r)
    return out


def forward_with_fallback(rows, chan, model, payload, timeout=600):
    """按候选链逐个尝试。返回 (响应对象, 实际用的渠道)。

    策略抄 freellmapi：429 / 5xx → 那家进冷却 → 换下一家重试。
    只在这一步失败时降级：一旦响应头到手（开始流式），出错就不再重试，
    因为客户端已经收到部分内容了，重发会造成重复。
    """
    last = None
    tried = []
    for cand in _candidates(rows, chan, model):
        cid = cand.get("id")
        if _in_cooldown(cid):
            tried.append("%s(冷却中)" % cid)
            continue
        try:
            resp = forward(cand, model, payload, timeout=timeout)
            return resp, cand
        except urllib.error.HTTPError as exc:
            kind = _classify_error(exc)
            tried.append("%s(%s)" % (cid, exc.code))
            if kind in ("rate", "server"):
                _mark_cooldown(cid)
            last = exc
            continue
        except Exception as exc:
            tried.append("%s(%r)" % (cid, exc))
            last = exc
            continue
    if last is not None:
        try:
            setattr(last, "tried", tried)
        except Exception:
            pass
        raise last
    raise RuntimeError("所有候选渠道都在冷却：%s" % ", ".join(tried))


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
    if chan.get("token"):
        req.add_header("Authorization", "Bearer " + chan["token"])
    elif chan.get("key"):
        req.add_header("Authorization", "Bearer " + chan["key"])
    # 账号型渠道：凭据是从 App 内网页登录抓下来的整条 Cookie
    if chan.get("cookie"):
        req.add_header("Cookie", chan["cookie"])
    for k, v in (chan.get("headers") or {}).items():
        try:
            req.add_header(k, v)
        except Exception:
            pass
    return urllib.request.urlopen(req, timeout=timeout)
