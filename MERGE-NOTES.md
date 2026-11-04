# 上游合并记录

## 2026-11-04：网关 1.8.0 → 1.10.9

### 做法

拿三个源做三方对比，**只把我们没动过的文件整体换新**，我们改过的走合并：

```
base   = 上游 release v1.8.0 的 wb-proxy/
theirs = 上游 release v1.10.9 的 wb-proxy/
ours   = 本仓库 app/src/main/assets/app/
```

### 结果

```
① 我们没动过的 27 个 .py        → 直接换成 1.10.9 版
② 上游新增 6 个模块            → 带进来
     wb_codearts.py / wb_codearts_crypto.py
     wb_media.py / wb_media_client.py / wb_media_tasks.py
     wb_usage_io.py
③ pricing/pricing.json          → 换上游新价目
④ 我们改过的 4 个 → 三方合并
     wb_proxy.py      5 处冲突，全解（上游加了 reset_at= 参数；
                      另保住 WB-REALM-FIX-LOG 那段）
     wb_accounts.py   0 冲突，但合并把 repair_realm 放进了
                      class Account —— 手工移回 class AccountPool
     wb_settings.py   0 冲突
     dashboard.html   **没合并，保我们的**
```

### 故意没合并的

```
dashboard.html   上游 1.10.9 重写了面板布局（默认控制台布局 + 新导航）。
                 我们的导出弹窗 / 粘贴导入 / 窄屏署名都在这份上，
                 硬合会打成四不像。已确认：我们看板用到的 48 个后端路径
                 在 1.10.9 里一个不缺，所以保旧看板配新后端是安全的。
wb_console.py    自研控制台（8890），上游没有这个文件
wb_boot.py       自研思考调试台（8889），上游没有这个文件
```

### 保留了我们的 4 处补丁

```
wb_proxy.py:1581    WB-REALM-FIX-LOG   区域是猜出来的就标 realm_guessed
wb_proxy.py:16k     WB-ERR-REASON      失败行带 status / message
wb_proxy.py:25k     WB-REALM-REPAIR    启动时按 token 证据重算 realm
wb_accounts.py      repair_realm()      （合并后被误放进 Account，已移回 AccountPool）
```

### 验证

```
· 39 个 .py 语法全过
· 沙箱真跑（--port 18888/18890，独立账号目录）：
    /health        ok=true，带新的 codearts 段
    /v1/models     200，19 个模型
    /v1/images/*   /v1/videos  404（媒体默认关闭，符合预期）
    /updates/check current_version = 1.10.9
    启动日志零 error / traceback
· 控制台与调试台引用的 8 个跨模块符号在 1.10.9 里全都在
```

### 拿到什么

```
· CodeArts 原生第三出口（PKCE + DPoP + 续期 + 签到）
· 生图 / 生视频（/v1/images/*、/v1/videos，默认关闭）
· 账号健康：401 真失效判定、11140 风控避让、配置并发锁、UID 注入防护
· 日志轮转日限校验、usage.jsonl 原子追加
· 国际版 deepseek-v4.1-flash 原生免费通道（0 积分）
· 国内版 flash 真实 0.11x 计费（不再假免费）
```

### 回滚

```
app127（1.8.0 + 全部自研改动）在 /sdcard/wb2api-backup/
git: 上一提交即 1.8.0 状态
```
