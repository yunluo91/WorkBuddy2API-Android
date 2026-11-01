#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""WorkBuddy2API 手机版 · 启动器 + 思考调试台

这个文件不是上游 workbuddy2api-hub 的东西，是手机壳（com.yunluo.wb2api）自己加的，
放在 assets/app/ 里，每次启动由 PyRunner 用 runpy 跑起来，再由它去跑上游的 wb_proxy.py。

它解决一个具体问题：上游的 default_reasoning_effort_mode() 默认是 high，
客户端「不传 reasoning_effort」会被补成最高档，于是客户端勾「关闭思考」也没用。
那个档位只认环境变量 WB_DEFAULT_REASONING_EFFORT，没有 UI、没有配置文件，
手机壳里没地方设。这里补上，并且做成能实时调的：

  1. 跑上游之前先把 WB_DEFAULT_REASONING_EFFORT 设好（读 wb_app/thinking.json）；
  2. 起一个 1 秒轮询的 watcher：配置文件一改立刻生效。上游是每个请求现读
     os.environ 的，所以改档位不用重启进程；
  3. 在 <网关端口+1> 起一个调试台网页，可以切档、强制关闭、看每个请求
     真实用了什么 effort；
  4. 「强制关闭」时把客户端显式给的 reasoning_effort 也抹掉 —— 那是比默认档
     更狠的主开关，客户端要什么都不给。

不修改上游任何一个文件，升级时直接覆盖 wb_*.py 也不影响本文件。
"""

import json
import os
import sys
import threading
import time
import traceback
import urllib.parse
import runpy

try:
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
except Exception:                                          # pragma: no cover
    from http.server import BaseHTTPRequestHandler, HTTPServer as ThreadingHTTPServer

BASE = os.path.dirname(os.path.abspath(__file__))
STATE_DIR = os.path.join(BASE, "wb_app")
STATE_FILE = os.path.join(STATE_DIR, "thinking.json")
PORT_FILE = os.path.join(STATE_DIR, "control.port")
LOG_FILE = os.path.join(STATE_DIR, "debug.log")
REQ_FILE = os.path.join(STATE_DIR, "requests.jsonl")
ACCOUNTS_DIR = os.environ.get("ACCOUNTS_DIR") or os.path.join(BASE, "accounts")

MODES = ("off", "catalog", "high")
MODE_DESC = {
    "off": "客户端不传 effort 就不思考（和官方端点一致）",
    "catalog": "只用模型目录里给的默认档位",
    "high": "上游老行为，一律强制最高档",
}

_lock = threading.Lock()
_recent = []          # 最近若干次请求的最终取值
_recent_max = 40
_control_port = 0


# ---------------------------------------------------------------- 小工具

def _ensure_dir():
    try:
        os.makedirs(STATE_DIR, exist_ok=True)
    except Exception:
        pass


def log(msg):
    try:
        _ensure_dir()
        line = "[%s] %s\n" % (time.strftime("%H:%M:%S"), msg)
        with open(LOG_FILE, "a", encoding="utf-8") as fh:
            fh.write(line)
        try:
            sys.stdout.write("[wb-boot] " + msg + "\n")
        except Exception:
            pass
    except Exception:
        pass


_ctl_port = 0

def read_state():
    """{'enabled': bool, 'mode': off|catalog|high, 'force_off': bool}"""
    data = {}
    try:
        with open(STATE_FILE, "r", encoding="utf-8") as fh:
            data = json.load(fh) or {}
    except Exception:
        data = {}
    mode = str(data.get("mode") or "").strip().lower()
    if mode not in MODES:
        mode = os.environ.get("WB_DEFAULT_REASONING_EFFORT", "").strip().lower()
        if mode not in MODES:
            mode = "off"
    # 主开关：关掉就完全不干预，回到上游原始行为（默认 high）
    enabled = data.get("enabled")
    if enabled is None:
        enabled = True
    return {"enabled": bool(enabled), "mode": mode,
            "force_off": bool(data.get("force_off"))}


def write_state(mode, force_off, enabled=True):
    mode = str(mode or "").strip().lower()
    if mode not in MODES:
        mode = "off"
    payload = {"enabled": bool(enabled), "mode": mode,
               "force_off": bool(force_off),
               "updated_at": int(time.time())}
    _ensure_dir()
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=2)
    os.replace(tmp, STATE_FILE)
    apply_env(mode, payload["enabled"])
    log("配置写入 enabled=%s mode=%s force_off=%s"
        % (payload["enabled"], mode, payload["force_off"]))
    return payload


def apply_env(mode=None, enabled=None):
    """主开关关掉 => 直接摘掉这个环境变量，上游回到它自己的默认（high）。"""
    if mode is None or enabled is None:
        st = read_state()
        if mode is None:
            mode = st["mode"]
        if enabled is None:
            enabled = st["enabled"]
    if enabled:
        os.environ["WB_DEFAULT_REASONING_EFFORT"] = mode
        return mode
    os.environ.pop("WB_DEFAULT_REASONING_EFFORT", None)
    return ""


def upstream_effort_map():
    """上游自带那个「思考等级挡位映射」开关的当前值（跟本页主开关是两回事）。"""
    try:
        sys.path.insert(0, BASE)
        import wb_settings
        return bool(wb_settings.reasoning_effort_map(ACCOUNTS_DIR))
    except Exception:
        return None


def state_for_client():
    st = read_state()
    return {
        "enabled": st["enabled"],
        "mode": st["mode"],
        "force_off": st["force_off"],
        "env": os.environ.get("WB_DEFAULT_REASONING_EFFORT", ""),
        "upstream_map": upstream_effort_map(),
        "modes": list(MODES),
        "desc": MODE_DESC,
        "port": _control_port,
        "requests": list(_recent),
    }


# ---------------------------------------------------------------- 请求侧钩子

def _record(entry):
    with _lock:
        _recent.insert(0, entry)
        del _recent[_recent_max:]
    try:
        _ensure_dir()
        with open(REQ_FILE, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(entry, ensure_ascii=False) + "\n")
    except Exception:
        pass


def _thinking_type(body):
    t = body.get("thinking") if isinstance(body, dict) else None
    if isinstance(t, dict):
        return str(t.get("type") or "")
    return ""


def _patch_upstream():
    """等 wb_proxy 作为 __main__ 加载完，把 build_upstream_body 套一层。

    上游所有发往上游模型的请求都要过这个函数（/v1/chat/completions 与
    /v1/responses 都走它），所以在这里既能记录真实取值，也能兜底强关。
    """
    waited = 0.0
    while waited < 120.0:
        main = sys.modules.get("__main__")
        fn = getattr(main, "build_upstream_body", None) if main else None
        if fn is not None and not getattr(fn, "_wb_boot_wrapped", False):
            def wrapper(payload, _origin=fn):
                body = _origin(payload)
                st = read_state()
                if st.get("enabled") and st.get("force_off") and isinstance(body, dict):
                    removed = body.pop("reasoning_effort", None)
                    body.pop("reasoningEffort", None)
                    body["thinking"] = {"type": "disabled"}
                    if removed is not None:
                        log("强制关闭：抹掉客户端给的 effort=%s" % removed)
                try:
                    _record({
                        "t": time.strftime("%H:%M:%S"),
                        "model": (body or {}).get("model", ""),
                        "effort": (body or {}).get("reasoning_effort")
                                  or (body or {}).get("reasoningEffort") or "",
                        "thinking": _thinking_type(body),
                        "force_off": bool(st.get("force_off")),
                    })
                except Exception:
                    pass
                return body

            wrapper._wb_boot_wrapped = True
            try:
                main.build_upstream_body = wrapper
            except Exception:
                setattr(main, "build_upstream_body", wrapper)
            log("已挂上 build_upstream_body（当前档位 %s，强制关闭 %s）"
                % (read_state()["mode"], read_state()["force_off"]))
            _bind_console(main)
            _patch_dashboard(main)
            return True
        time.sleep(0.2)
        waited += 0.2
    log("警告：120 秒内没等到 build_upstream_body，调试台只能改档位，无法记录请求")
    return False


def _patch_dashboard(main):
    """把「思考档位」嵌进上游看板的「智能体配置」页。

    上游 _dashboard() 是直接写 socket 的（send_response + wfile.write），不返回
    响应对象；它读文件用的是模块内的__builtins__.open，包不到函数。
    所以改法是最土但最稳的一种：把注入后的 HTML 另存一份，再把
    wb_proxy.DASHBOARD_HTML 指过去。上游每次 open() 读到的就是注入版。
    """
    import wb_proxy

    src = wb_proxy.DASHBOARD_HTML
    dst = os.path.join(os.path.dirname(src), "dashboard.wbembed.html")
    try:
        with open(src, "rb") as fh:
            raw = fh.read()
    except Exception:
        log("警告：读不到 dashboard.html，思考档位嵌不进智能体配置页")
        return

    text = raw.decode("utf-8", "replace")
    if "wbThinkSection" not in text:
        text = patch_dashboard(text)
        try:
            with open(dst, "wb") as fh:
                fh.write(text.encode("utf-8"))
        except Exception:
            log("警告：写不出注入版看板\n" + traceback.format_exc())
            return
    else:
        dst = src

    wb_proxy.DASHBOARD_HTML = dst
    # _dashboard() 里的 open(DASHBOARD_HTML) 解析的是模块全局，改 wb_proxy 那份就够。
    # 但 Handler 是在别的模块里 import 进来的，为保险起见两个命名空间都改一遍。
    try:
        import __main__ as _m
        if hasattr(_m, "DASHBOARD_HTML"):
            _m.DASHBOARD_HTML = dst
    except Exception:
        pass
    log("dashboard 已注入思考档位：%d -> %d 字节（%s）"
        % (len(raw), len(text.encode("utf-8")), os.path.basename(dst)))


def _bind_console(main):
    """把上游的几个函数交给内置控制台（对话 + 终端）。

    控制台不走 HTTP 调自己，而是在同一个进程里直接调这些函数：
    这样不需要 API Key，也不受面板会话限制，还能享受上游的账号池/重试逻辑。
    """
    try:
        import wb_console
    except Exception:
        log("警告：wb_console 没加载起来，内置控制台不可用")
        return
    names = ("build_upstream_body", "open_upstream", "fetch_models", "model_entry",
             "model_reasoning_meta", "iter_sse_lines", "strip_data_prefix")
    reg = {}
    for name in names:
        fn = getattr(main, name, None)
        if fn is None:
            try:
                import wb_proxy
                fn = getattr(wb_proxy, name, None)
            except Exception:
                fn = None
        if fn is not None:
            reg[name] = fn
    wb_console.bind(reg)
    log("内置控制台已接管上游函数：%s（缺 %s）"
        % (",".join(sorted(reg)) or "无",
           ",".join(n for n in names if n not in reg) or "无"))


def _watch():
    last = None
    while True:
        try:
            stamp = os.path.getmtime(STATE_FILE)
        except OSError:
            stamp = 0.0
        if stamp != last:
            last = stamp
            st = read_state()
            applied = apply_env(st["mode"], st["enabled"])
            log("watcher：生效 enabled=%s mode=%s 环境变量=%s"
                % (st["enabled"], st["mode"], applied or "(未设置)"))
        time.sleep(1.0)


# ---------------------------------------------------------------- 调试台网页

def _page():
    page = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>思考调试台 · WorkBuddy2API</title>
<style>
/* WB-THINK-UI
   原来这页是四张圆角大卡片 + 竖排单选卡片 + 大段说明文字，
   塞在 App 里显得很"生成感"。现在收紧成：
     · 顶部一行标题 + 当前档位
     · 一张设置卡：开关行 / 三格档位胶囊 / 强制关闭行 / 两个按钮
     · 一个列表区：最近请求
     · 说明文字挪进 <details>，要看才点开
   配色沿用控制台那套变量，两页看起来是一个应用。 */
:root{--bg:#0e1116;--card:#161b22;--card2:#1c232c;--line:#232a33;
--fg:#e6edf3;--dim:#8b949e;--accent:#0dbd8b;--warn:#f0883e}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
font:14px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",sans-serif;
-webkit-font-smoothing:antialiased}
.wrap{padding:10px 12px calc(16px + env(safe-area-inset-bottom))}

/* 顶部一行 */
.head{display:flex;align-items:center;gap:8px;padding:4px 2px 10px}
.head .h{font-size:15px;font-weight:600}
.head .sp{flex:1}
.badge{display:inline-block;padding:2px 9px;border-radius:999px;font-size:11.5px;
border:1px solid var(--line);color:var(--dim);white-space:nowrap}
.badge.ok{color:var(--accent);border-color:rgba(13,189,139,.45);
background:rgba(13,189,139,.12)}
.badge.warn{color:var(--warn);border-color:rgba(240,136,62,.45);
background:rgba(240,136,62,.12)}

/* 卡片 */
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;
padding:12px 13px;margin-bottom:10px}

/* 设置行：左边标题，右边开关 */
.line{display:flex;align-items:center;gap:10px;padding:7px 0}
.line .lab{flex:1;font-size:13.5px}
.line .sub{font-size:11.5px;color:var(--dim);margin-top:1px}

/* 开关（纯 CSS，不用原生 checkbox 那副老样子） */
.sw{position:relative;width:42px;height:24px;flex:0 0 auto}
.sw input{position:absolute;opacity:0;width:100%;height:100%;margin:0;cursor:pointer}
.sw i{position:absolute;inset:0;border-radius:999px;background:var(--card2);
border:1px solid var(--line);transition:.18s}
.sw i::after{content:"";position:absolute;top:2px;left:2px;width:18px;height:18px;
border-radius:50%;background:var(--dim);transition:.18s}
.sw input:checked+i{background:rgba(13,189,139,.22);border-color:rgba(13,189,139,.5)}
.sw input:checked+i::after{left:20px;background:var(--accent)}

/* 档位胶囊：三格等分，选中的亮一档 */
.seg{display:flex;gap:5px;margin:7px 0 3px}
.opt{flex:1;margin:0;padding:9px 4px;text-align:center;cursor:pointer;
border:1px solid var(--line);border-radius:9px;background:transparent;
transition:.15s;user-select:none}
.opt input{display:none}
.opt span b{font-size:13px;font-weight:600;color:var(--dim)}
.opt small{display:none}          /* 说明挪到下面一行，胶囊里只留名字 */
.opt.on{background:var(--card2);border-color:rgba(13,189,139,.5)}
.opt.on span b{color:var(--accent)}
.opt:active{border-color:var(--accent)}

.hint{font-size:11.5px;color:var(--dim);line-height:1.5;margin:2px 0 6px}
.msg{font-size:11.5px;color:var(--accent);min-height:16px;margin-top:2px}

.acts{display:flex;gap:8px;justify-content:flex-end;margin-top:10px}
button{background:var(--accent);color:#07110d;border:0;border-radius:9px;
padding:9px 15px;font:inherit;font-size:13px;font-weight:600;cursor:pointer}
button.ghost{background:transparent;color:var(--fg);border:1px solid var(--line)}

/* 小标题 */
.sec{display:flex;align-items:baseline;gap:8px;padding:6px 2px 8px}
.sec span:first-child{font-size:13.5px;font-weight:600}
.sec .dim{font-size:11.5px;color:var(--dim)}

/* 表格 */
table{width:100%;border-collapse:collapse;font-size:12px}
th,td{text-align:left;padding:6px 5px;border-bottom:1px solid var(--line);
white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
th{color:var(--dim);font-weight:500;font-size:11.5px}
tr:last-child td{border-bottom:0}
td.mono,th.mono,.mono{font-family:ui-monospace,Menlo,Consolas,monospace}
.tblwrap{overflow-x:auto;margin:0 -2px}

/* 说明折叠 */
details{margin-top:2px}
summary{cursor:pointer;font-size:11.5px;color:var(--dim);padding:5px 2px;list-style:none}
summary::-webkit-details-marker{display:none}
summary::before{content:"▸ ";font-size:10px}
details[open] summary::before{content:"▾ "}
details p{font-size:11.5px;color:var(--dim);line-height:1.65;margin:2px 0 6px}
details b{color:var(--fg);font-weight:600}

input[type=password]{width:100%;padding:9px 11px;border-radius:9px;
border:1px solid var(--line);background:#0b0f14;color:var(--fg);font:inherit;
margin-top:6px}
code{background:#0b0f14;padding:1px 6px;border-radius:5px;font-size:11.5px}
</style></head><body><div class="wrap">

<div class="head">
  <span class="h">思考</span>
  <span class="sp"></span>
  <span id="cur" class="badge">读取中…</span>
</div>

<div class="card">
  <div class="line">
    <div class="lab">启用档位调节
      <div class="sub">关掉就按上游默认走，不再干预</div>
    </div>
    <label class="sw"><input type="checkbox" id="enabled"><i></i></label>
  </div>

  <div id="offNote" class="hint" style="display:none">
    未启用时客户端不传 effort 会被补成最高档。主开关打开才真正可控。
  </div>

  <div id="tunePad">
    <div class="lab" style="padding-top:6px">默认档位</div>
    <div class="seg" id="opts"></div>
    <div class="hint" id="modeHint">—</div>

    <div class="line">
      <div class="lab">强制关闭
        <div class="sub">连客户端显式给的档位也抹掉</div>
      </div>
      <label class="sw"><input type="checkbox" id="force"><i></i></label>
    </div>
  </div>

  <div class="acts">
    <button class="ghost" onclick="load()">刷新</button>
    <button onclick="save()">保存并生效</button>
  </div>
  <div id="msg" class="msg"></div>
</div>

<div class="sec">
  <span>最近请求</span>
  <span class="dim">网关发给上游的最终值</span>
</div>
<div class="card" style="padding:4px 10px 8px">
  <div class="tblwrap"><table>
    <thead><tr><th>时间</th><th>模型</th><th>effort</th><th>thinking</th></tr></thead>
    <tbody id="rows"><tr><td colspan="4" style="color:var(--dim)">还没有请求</td></tr></tbody>
  </table></div>
</div>

<details>
  <summary>档位映射 <span id="umap" class="badge">读取中…</span></summary>
  <p>客户端传的等级不在模型支持列表时，自动改写为该模型的默认档，
     避免上游直接拒掉整个请求。默认开启。</p>
  <p>这个开关在网关「设置」页，和上面的主开关是两回事 ——
     它只管<b>兜底改写</b>，关掉它并不会让思考停下来。</p>
  <p style="opacity:.8">环境变量 <code id="env" class="mono">-</code></p>
</details>

<div class="card" id="pwcard" style="display:none">
  <div class="sub" style="font-size:12px">这个页面是从别的设备打开的，先填面板密码</div>
  <input type="password" id="pwd" placeholder="面板密码">
</div>

</div>
<script>
var DESC=__DESC__, MODES=__MODES__, STATE={mode:"off",force_off:false};
/* WB-THINK-UI 档位中文名。英文原名还留在 title 里备查。 */
var NAME={off:"不思考",catalog:"按模型默认",high:"最高档"};
function pwd(){ return localStorage.getItem("wbdbg_pwd")||""; }
function q(url){
  var p=pwd();
  if(!p) return url;
  return url+(url.indexOf("?")<0?"?":"&")+"pwd="+encodeURIComponent(p);
}
function render(){
  var on=STATE.enabled!==false;
  document.getElementById("enabled").checked=on;
  document.getElementById("cur").textContent=(on?(NAME[STATE.mode]||STATE.mode):"未启用");
  document.getElementById("cur").className="badge "+(on&&STATE.mode==="off"?"ok":"warn");
  document.getElementById("env").textContent=STATE.env||"(未设置，走上游默认)";
  var um=document.getElementById("umap");
  if(um){
    um.textContent=(STATE.upstream_map===null||STATE.upstream_map===undefined)
      ? "未知" : (STATE.upstream_map?"已开启":"已关闭");
    um.className="badge "+(STATE.upstream_map?"ok":"warn");
  }
  var note=document.getElementById("offNote");
  if(note) note.style.display=on?"none":"block";
  var pad=document.getElementById("tunePad");
  if(pad){ pad.style.opacity=on?"1":".38"; pad.style.pointerEvents=on?"auto":"none"; }
  var box=document.getElementById("opts");
  box.innerHTML="";
  /* WB-THINK-UI 说明不进胶囊（挤），统一显示在下面那行 modeHint。 */
  MODES.forEach(function(m){
    var d=document.createElement("label");
    d.className="opt"+(STATE.mode===m?" on":"");
    d.title=(m+(DESC[m]?(" — "+DESC[m]):""));
    d.innerHTML='<input type="radio" name="mode" value="'+m+'"'+(STATE.mode===m?" checked":"")+
      (on?"":" disabled")+
      '><span><b>'+(NAME[m]||m)+'</b></span>';
    d.onclick=function(){ STATE.mode=m; render(); };
    box.appendChild(d);
  });
  var mh=document.getElementById("modeHint");
  if(mh) mh.textContent=DESC[STATE.mode]||"";
  var en=document.getElementById("enabled");
  if(en && !en._bound){
    en._bound=1;
    en.addEventListener("change",function(e){ STATE.enabled=e.target.checked; render(); });
  }
  document.getElementById("force").checked=!!STATE.force_off;
  var rows=STATE.requests||[], tb=document.getElementById("rows");
  if(!rows.length){ tb.innerHTML='<tr><td colspan="4" style="color:var(--dim)">还没有请求</td></tr>'; }
  else{
    tb.innerHTML=rows.map(function(r){
      return "<tr><td class=mono>"+r.t+"</td><td class=mono>"+(r.model||"")+
        "</td><td class=mono>"+(r.effort||"—")+"</td><td class=mono>"+
        (r.thinking||"—")+"</td></tr>";
    }).join("");
  }
}
function load(){
  fetch(q("/api/state"),{cache:"no-store"}).then(function(r){
    if(r.status===401){ document.getElementById("pwcard").style.display="block"; return null; }
    return r.json();
  }).then(function(d){ if(d){ STATE=d; render(); } }).catch(function(){});
}
function save(){
  var body={enabled:document.getElementById("enabled").checked,
            mode:STATE.mode,force_off:document.getElementById("force").checked};
  var p=pwd(); if(p) body.pwd=p;
  fetch("/api/thinking",{method:"POST",headers:{"Content-Type":"application/json"},
    body:JSON.stringify(body)}).then(function(r){
    if(r.status===401){ document.getElementById("pwcard").style.display="block";
      document.getElementById("msg").textContent="需要面板密码"; return null; }
    return r.json();
  }).then(function(d){
    if(d){ STATE.enabled=d.enabled; STATE.mode=d.mode; STATE.force_off=d.force_off;
      STATE.env=d.env; STATE.upstream_map=d.upstream_map; render();
      document.getElementById("msg").textContent="已生效 ✓"; setTimeout(load,1200); }
  }).catch(function(){ document.getElementById("msg").textContent="保存失败"; });
}
document.getElementById("pwd").addEventListener("change",function(e){
  localStorage.setItem("wbdbg_pwd",e.target.value); load();
});
load(); setInterval(load,3000);
</script>
<script>
// 嵌进「智能体配置」页时（?embed=1）把整页收成一个普通区块：
// 藏掉大标题、去掉最大宽度限制和外边距，让卡片直接落在上游的 section 里。
(function () {
  if (!/[?&]embed=1/.test(location.search)) return;
  var t = document.getElementById('ttl');
  var s = document.getElementById('sub');
  if (t) t.style.display = 'none';
  if (s) s.style.display = 'none';
  var w = document.getElementById('wrap');
  if (w) { w.style.maxWidth = 'none'; w.style.padding = '0'; }
  document.body.style.background = 'transparent';
})();
</script>
</body></html>"""
    # 页面里 CSS 的百分号太多，不能用 % 格式化，改用令牌替换
    return (page.replace("__DESC__", json.dumps(MODE_DESC, ensure_ascii=False))
                .replace("__MODES__", json.dumps(list(MODES), ensure_ascii=False)))



# ---------------------------------------------------------------- 注入上游看板
# 把思考调试台嵌进上游 dashboard 的「智能体配置」页（#pageAgents）。
# 上游那个页面的左侧导航是 initPageNav() 扫 <section> 自动生成的，
# 所以只要往里塞一个 section，导航项就自己出来了，不用改导航。
EMBED_JS = r"""
(function () {
  var TRIES = 80, n = 0;
  function inject() {
    n++;
    var page = document.getElementById('pageAgents');
    // 页面还没渲染出来就再等一帧
    if (!page) { if (n < TRIES) return setTimeout(inject, 250); return; }
    if (document.getElementById('wbThinkSection')) return;

    var sec = document.createElement('section');
    sec.id = 'wbThinkSection';
    sec.innerHTML =
      '<div style="display:flex;align-items:center;justify-content:space-between;' +
      'margin-bottom:14px;flex-wrap:wrap;gap:10px">' +
        '<div>' +
          '<h2 style="margin:0">思考档位</h2>' +
          '<div style="font-size:12px;color:var(--dim);margin-top:4px">' +
            '客户端没写 reasoning_effort 时网关补哪一档。改完立即生效，不用重启。' +
            '强制关闭会把客户端显式给的档位也抹掉。' +
          '</div>' +
        '</div>' +
        '<button class="sec mini" id="wbThinkReload">刷新</button>' +
      '</div>' +
      '<iframe id="wbThinkFrame" src="http://127.0.0.1:__CTLPORT__/?embed=1" ' +
        'allow="clipboard-write" ' +
        'style="width:100%;height:520px;border:0;border-radius:12px;' +
        'background:var(--panel);display:block"></iframe>' +
      '<div id="wbThinkFallback" style="display:none;font-size:12px;color:var(--dim);' +
        'margin-top:8px">iframe 被拦的话，直接开 ' +
        '<a href="http://127.0.0.1:__CTLPORT__/?embed=1" target="_blank" ' +
        'style="color:var(--accent)">调试台独立页面</a></div>';

    page.appendChild(sec);

    var btn = document.getElementById('wbThinkReload');
    if (btn) btn.addEventListener('click', function () {
      var f = document.getElementById('wbThinkFrame');
      if (f) f.src = f.src;
    });

    // 上游的 initPageNav() 已经跑过了，这里手动补一次导航项
    try {
      if (typeof initPageNav === 'function') initPageNav();
    } catch (e) {}

    setTimeout(function () {
      var f = document.getElementById('wbThinkFrame');
      var hint = document.getElementById('wbThinkFallback');
      try {
        if (f && hint && !f.contentDocument && !f.contentWindow.length) {
          hint.style.display = 'block';
        }
      } catch (e) {
        // 跨文档访问被拒是正常的（本来就读不到），说明 iframe 在跑，不用提示
      }
    }, 3000);
  }
  inject();
})();
"""


def patch_dashboard(html_text):
    """往 dashboard.html 里插一行 <script>，让「智能体配置」页多出「思考档位」。"""
    if "wbThinkSection" in html_text:
        return html_text
    script = "<script>%s</script>" % EMBED_JS.replace(
        "__CTLPORT__", str(_ctl_port or 0))
    idx = html_text.rfind("</body>")
    if idx < 0:
        return html_text + script
    return html_text[:idx] + script + html_text[idx:]


def _panel_password_ok(pwd):
    try:
        sys.path.insert(0, BASE)
        import wb_settings
        return bool(wb_settings.verify_panel_password(ACCOUNTS_DIR, pwd or ""))
    except Exception:
        log("警告：校验面板密码失败 %s" % traceback.format_exc().strip().splitlines()[-1])
        return False


class Handler(BaseHTTPRequestHandler):
    server_version = "wb-debug/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _is_local(self):
        try:
            return self.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")
        except Exception:
            return False

    def _allowed(self, query):
        if self._is_local():
            return True
        pwd = (query.get("pwd") or [""])[0]
        return _panel_password_ok(pwd)

    def _send(self, code, body, ctype="application/json; charset=utf-8"):
        raw = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(raw)
        except Exception:
            pass

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        if parsed.path in ("/", "/index.html"):
            self._send(200, _page(), "text/html; charset=utf-8")
            return
        if parsed.path == "/api/state":
            if not self._allowed(query):
                self._send(401, json.dumps({"error": "need panel password"}))
                return
            self._send(200, json.dumps(state_for_client(), ensure_ascii=False))
            return
        self._send(404, json.dumps({"error": "not found"}))

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        if parsed.path != "/api/thinking":
            self._send(404, json.dumps({"error": "not found"}))
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            body = {}
        if not isinstance(body, dict):
            body = {}
        if body.get("pwd") and not query.get("pwd"):
            query = dict(query)
            query["pwd"] = [str(body.get("pwd"))]
        if not self._allowed(query):
            self._send(401, json.dumps({"error": "need panel password"}))
            return
        cur = read_state()
        enabled = body.get("enabled", cur["enabled"])
        mode = body.get("mode", cur["mode"])
        force = body.get("force_off", cur["force_off"])
        try:
            write_state(mode, force, enabled)
        except Exception:
            self._send(500, json.dumps({"error": "write failed"}))
            return
        self._send(200, json.dumps(state_for_client(), ensure_ascii=False))


def _serve(host, port):
    global _control_port
    httpd = None
    last_err = None
    for offset in range(0, 6):
        candidate = port + offset
        try:
            httpd = ThreadingHTTPServer((host, candidate), Handler)
            _control_port = candidate
            break
        except Exception as exc:
            last_err = exc
            continue
    if httpd is None:
        log("调试台起不来（%s:%d 附近端口都占用）：%s" % (host, port, last_err))
        return
    httpd.daemon_threads = True
    _ensure_dir()
    try:
        with open(PORT_FILE, "w", encoding="utf-8") as fh:
            fh.write(str(_control_port))
    except Exception:
        pass
    log("调试台：http://%s:%d/  （监听 %s）" % (host, _control_port, host))
    try:
        httpd.serve_forever(poll_interval=0.5)
    except Exception:
        pass


# ---------------------------------------------------------------- 入口

def main():
    _ensure_dir()
    argv = list(sys.argv)
    port = 8788
    host = "127.0.0.1"
    for i, item in enumerate(argv):
        if item == "--port" and i + 1 < len(argv):
            try:
                port = int(argv[i + 1])
            except Exception:
                pass
        if item == "--host" and i + 1 < len(argv):
            host = str(argv[i + 1])

    st = read_state()
    if not os.path.exists(STATE_FILE):
        try:
            write_state(st["mode"], st["force_off"], st["enabled"])
        except Exception:
            pass
    applied = apply_env(st["mode"], st["enabled"])
    log("启动：网关 %s:%d，档位调节 %s，档位 %s，强制关闭 %s，环境变量=%s"
        % (host, port, "开" if st["enabled"] else "关", st["mode"],
           st["force_off"], applied or "(未设置)"))

    threading.Thread(target=_watch, name="wb-boot-watch", daemon=True).start()
    threading.Thread(target=_patch_upstream, name="wb-boot-patch", daemon=True).start()
    bind = "0.0.0.0" if host in ("0.0.0.0", "::", "") else "127.0.0.1"
    global _ctl_port
    _ctl_port = port + 1
    threading.Thread(target=_serve, args=(bind, port + 1),
                     name="wb-boot-ctl", daemon=True).start()

    # 内置控制台：对话 + 终端，常驻 (端口 + 2)
    def _console_thread():
        try:
            import wb_console
            wb_console.set_gateway_port(port)
            wb_console.serve(bind, port + 2)
        except Exception:
            log("内置控制台起不来：\n" + traceback.format_exc())
    threading.Thread(target=_console_thread, name="wb-console", daemon=True).start()

    # 交给上游：注意 sys.modules['__main__'] 会变成执行 wb_proxy.py 的那个模块，
    # 上面 _patch_upstream 就是等它加载完再把钩子挂上去。
    sys.argv = ["wb_proxy.py"] + argv[1:]
    runpy.run_path(os.path.join(BASE, "wb_proxy.py"), run_name="__main__")


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception:
        log("wb_boot 崩了：\n" + traceback.format_exc())
        raise
