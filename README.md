<p align="center">
  <img src="https://avatars.githubusercontent.com/u/226801564?v=4" width="108" height="108" alt="avatar">
</p>

<h1 align="center">WorkBuddyAPI · 手机版</h1>

<p align="center">
  <sub>陨落[洛杉矶高级二改王] · <a href="https://github.com/yunluo91">@yunluo91</a></sub>
</p>

<p align="center">
  <a href="https://github.com/yunluo91/WorkBuddy2API-Android/releases/latest"><img src="https://img.shields.io/badge/Release-2.0.1--app191?style=flat-square" alt="Release"></a>
  <img src="https://img.shields.io/badge/Android-8.0+-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android">
  <img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Python-3.11-3776AB?style=flat-square&logo=python&logoColor=white" alt="Python">
  <img src="https://img.shields.io/badge/API-OpenAI_Compatible-412991?style=flat-square" alt="OpenAI API">
  <img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" alt="MIT">
</p>

<p align="center">
  <a href="https://github.com/yunluo91/WorkBuddy2API-Android/releases/latest"><b>⬇ 下载 APK</b></a>
  　·　
  <a href="使用说明.md">使用说明</a>
  　·　
  <a href="PITFALLS.md">踩坑记录</a>
  　·　
  <a href="https://github.com/yunluo91/WorkBuddy2API-Android/releases">所有版本</a>
</p>

<p align="center">
  <sub>基于 <a href="https://github.com/ardeyouxipianyi/workbuddy2api-hub">ardeyouxipianyi/workbuddy2api-hub</a>（MIT）</sub>
</p>

> [!NOTE]
> **这是什么** —— 把上游那个 Python 网关塞进一个 Android APK 里跑，不用电脑、不用 Docker、不用 Termux。
> 装完打开就是一个本地 OpenAI 兼容服务：`http://127.0.0.1:8888/v1`。
>
> 本项目只做「手机外壳 + 原生界面 + 修补」，网关逻辑全部来自上游。手机端之外的玩法（Docker 部署、账号自动化、看板全部功能）请看上游仓库。

> [!IMPORTANT]
> 仅用于协议学习与个人自用。请优先使用官方客户端，不要用主账号，不要共享账号，也不要商业转售。

---

## 目录

- [一、它做什么](#一它做什么)
- [二、快速开始](#二快速开始)
- [三、界面一览](#三界面一览)
- [四、控制台三页](#四控制台三页)
- [五、设置](#五设置)
- [六、面板密码](#六面板密码)
- [七、思考档位](#七思考档位)
- [八、端口与服务](#八端口与服务)
- [九、API Key 与客户端接入](#九api-key-与客户端接入)
- [十、从源码构建](#十从源码构建)
- [十一、目录结构](#十一目录结构)
- [十二、已知限制](#十二已知限制)
- [十三、更新记录](#十三更新记录)
- [十四、致谢与来源](#十四致谢与来源)

---

## 一、它做什么

```
com.yunluo.wb2api
├── 前台服务              锁屏/切后台时保住进程（specialUse + 部分唤醒锁）
├── Chaquopy 内嵌 CPython 3.11
│   ├── wb_proxy.py       上游网关：OpenAI 兼容 API、账号池、重试、用量记账
│   ├── wb_boot.py        启动器：思考档位 watcher + 调试台
│   └── wb_console.py     控制台：模型目录、密钥、思考档位
├── WebView               看板 dashboard.html（网关自带的管理面板）
└── 原生 Compose 界面
    ├── 对话              支持工具调用（读写文件、跑 shell）
    ├── 调用记录          逐条请求的模型 / 档位 / 耗时 / 积分
    ├── 看板              网关面板
    ├── 控制台            模型（原生） / 密钥 / 思考
    └── 设置              后台保活、数据管理、网关参数、客户端接入
```

**相对上游多了什么**

| | 上游（电脑跑） | 本项目（手机跑） |
|---|---|---|
| 运行环境 | Python 3.9+ / Docker / 绿色包 | 一个 APK，无需 root |
| 对话界面 | 网页 | 原生 Compose，支持工具调用 |
| 调用记录 | 网页表格 | 原生列表，逐条看档位与积分 |
| 模型清单 | 网页 | 原生页面，12 个品牌真 logo、PRO 徽章 |
| 思考档位 | 网页设置 | 原生 + 网页混合，两层合并一页 |
| 后台存活 | 系统服务 | 前台服务 `specialUse` + 唤醒锁 + 电池豁免 |
| 备份迁移 | 手动拷目录 | 设置 → 数据管理，整包 zip 一键备份/恢复 |

**包名**

| 项 | 值 |
|---|---|
| `applicationId` | `com.yunluo.wb2api` |
| `namespace` / 源码包名 / R / BuildConfig | `com.yunluo.wb2api` |
| 桌面显示名 | `WorkBuddyAPI` |

四项统一，源码目录是 `java/com/yunluo/wb2api/`。

**版本号约定**：`versionName` 形如 `1.10.11-app134`（跟内嵌网关版本走，后面是 App 构建号），`versionCode` **固定 100**。
这样任意历史版本之间都能互相覆盖安装，不会出现 `INSTALL_FAILED_VERSION_DOWNGRADE`。

---

## 二、快速开始

### 1. 装 APK

从 [Releases](https://github.com/yunluo91/WorkBuddy2API-Android/releases/latest) 下 `WorkBuddyAPI-1.10.11-app134.apk`。
提示「未知来源」时允许即可。桌面图标叫 **WorkBuddy2API 2**。

### 2. 打开等网关起来

首次启动要解压 Python 环境，约 10–30 秒，屏幕上有进度。
底部「控制台」能点开、看板能出页面，就算起来了。

### 3. 后台保活（重要，别跳过）

**这一步不做，网关会在你切出去几分钟后掉线。**

App 里点「后台保活」，按提示授权两项：

```
① 电池优化豁免     → 系统设置里允许 App 后台运行
② 通知权限         → 前台服务要有一条常驻通知，没有通知系统会杀掉服务
```

**国产 ROM 还要另外放行三处**（各家叫法不同）：

```
· 自启动 / 后台自启
· 省电策略改成「无限制 / 不优化」
· 任务列表里把这个 App 下拉锁定（别让「一键清理」把它清掉）
```

**这些都是系统管的，App 只能提示，改不了。**

> 怎么判断有没有设置成功：锁屏放 10 分钟，解锁后看通知栏那条还在不在、
> 状态是不是「运行中」。掉线的话通知会变成「未启动」或显示自救次数。

## 手机上怎么保证不掉线

系统随时可能在内存紧张时杀后台，所以这里叠了几层，任何一层生效都能把网关拉回来：

| 层 | 做法 | 说明 |
|---|---|---|
| 前台服务 | `foregroundServiceType=specialUse` | 带常驻通知，系统不会随便回收；不用 `dataSync` 是因为它有 6h/24h 上限 |
| 划掉任务不停 | `stopWithTask="false"` | 从任务列表划掉 App，服务继续跑；只有通知栏「停止」才真停 |
| 进程重启 | `START_STICKY` | 被系统回收后自动重建 |
| 唤醒锁 | `PARTIAL_WAKE_LOCK` | 屏幕关了 CPU 也醒着，socket 不会睡死；服务销毁时一定释放 |
| 健康探测 | 每 3 秒探一次 `/health` | 连续 15 次（约 45 秒）探不通 = 进程活着但不干活，自动重启 Python |
| 闹钟兜底 | `AlarmManager` | 兜底的重启入口 |

**通知栏会显示状态**，出问题时能看出是哪一层起作用了：

```
WorkBuddyAPI · 运行中                      正常
WorkBuddyAPI · 运行中 · 自救 2 次           健康探测发现僵死，自动重启过 2 次
WorkBuddyAPI · 未启动 · 上次被系统强杀（137） 被系统杀了，看上一节怎么放行
```

## 三、界面一览

底部五个页签（浮在内容上方的玻璃胶囊，选中项有绿色药丸）：

### 对话

原生聊天页，支持工具调用。

- 模型可以读写 `/sdcard` 下的文件、跑 shell 命令
- 工具审批三档：全部放行 / 自动判断 / 每次询问
- 输入框上方那排 chip 是**按会话**临时改思考档位
- 会话保存在本地，可新建 / 切换 / 删除

### 调用记录

逐条请求的模型、档位、耗时、积分、token。
顶部可按国内版 / 国际版筛选。

### 看板

`dashboard.html`，上游自带的管理面板，功能跟电脑上一样。

### 控制台

见 [第四节](#四控制台三页)。

### 设置

见 [第五节](#五设置)。

---

## 四、控制台三页

顶部一排滑动药丸：**模型 / 密钥 / 思考**。

> 前两个页签在网页里，第三个是原生页。切换用 Compose 控制，
> 网页自己的页签栏已被 CSS 隐藏，屏幕上只会看到一排。

### 模型

原生 Compose 页面，**直连网关 `/v1/models`**，不走网页，最快也最稳。

- 12 个品牌的真 logo：DeepSeek / 智谱 / Kimi / MiniMax / 混元 / OpenAI /
  Gemini / Grok / Claude / 通义 / 阶跃 / Space Bunny
- 显示倍率、上下文长度、能力标签（视觉 / 工具 / 推理）
- 橙色 **PRO** 徽章 = 需要 Pro 账号
- 按倍率从低到高排序，没倍率的排最后
- 点一行看详情，确定 = 复制模型 id
- 顶部可切国际版 / 国内版

### 密钥

- 建 / 改 / 停用 / 删除 API Key
- 可限定 realm（不限区域 / 国内 / 国际）
- 列表左边圆点是启停状态
- 每把 Key 的用量按 Key 归属统计

### 思考

两层档位都在这一页，见 [第七节](#七思考档位)。

---

## 五、设置

```
后台保活        电池优化豁免 / 通知权限 / 服务状态
数据管理        → 独立二级页
网关设置        端口、局域网、开机自启、面板密码
调用记录筛选    默认显示哪个 realm
客户端接入      地址与 Key 的现成片段，点一下复制
当前状态        端口、进程、账号数
关于
```

### 数据管理（二级页）

从设置顶部那个入口点进去，左上角可返回。

**账号导入导出**

- 导出全部：国内 + 国际一次导出成 json（含 accessToken / refreshToken）
- 从文件导入：`uid` 相同**覆盖**、`uid` 不同**添加**、文档内重复**跳过**、解析失败**计入 invalid**

**数据迁移**

- 备份到文件：整包 zip（账号池、用量、设置、面板密码全在里面）
- 从文件恢复：恢复完 App 自己重启
- 恢复时会**强制把端口写成当前包的默认值** —— 备份里带的是旧端口，
  原样还原可能和别的实例抢同一个监听端口

> 为什么这两块收进二级页：都是一次性操作（换机、换包名才用一次），
> 摊在设置首页只会把真正要改的选项挤下去。

---

## 六、面板密码

面板密码保护的是**网关的数据接口**，不是 App 本身。

| 项 | 说明 |
|---|---|
| 保护谁 | `/accounts/*`、`/tasks*`、`/scheduler*`、`/logs*` 以及面板登录 |
| 默认值 | `admin` |
| 存哪 | 账号目录下的 `settings.json`（存哈希，不存明文） |
| 在 App 里改 | 设置 → 网关设置 → 面板密码 → 保存（**改完要重启网关**） |
| 谁在用它 | 控制台调面板接口、账号导入导出、网页看板登录 |

**认证方式**

```http
POST /panel/login
Content-Type: application/json

{"password":"你的密码"}
```

返回 `{"ok":true,"token":"…"}`，之后所有面板接口带请求头 `X-Panel-Token: <token>`。

注意：**Bearer 不行**。`X-Panel-Token` 是唯一认的头，
用 `Authorization: Bearer` 会得到 `{"error":{"message":"panel password required"}}`。

**App 侧要不要每次输**

不用。设置里填过一次，App 会记住并随网关启动传进去
（内部是 `--panel-password <密码>`），导出 / 导入直接用记住的那份。

**忘了怎么办**

没有重置 UI。密码哈希在账号目录的 `settings.json` 里，
所以**提前用「数据管理 → 备份到文件」留一份**是最省事的办法。
真忘了且没备份，只能清掉 App 数据重来（账号会一起没）。

> 顺带一提：这条密码只管数据接口，不影响 `/v1/*` 的推理调用。
> 推理走的是 API Key（`Authorization: Bearer sk-wb-…`），两套东西。

---

## 七、思考档位

容易混，分清楚两层 —— 都在 **控制台 → 思考** 一页里，设置页不管这个。

| 层 | 作用 | 存放位置 |
|---|---|---|
| 网关默认档位 | 客户端**没传** `reasoning_effort` 时，网关补哪一档 | `thinking.json` |
| 对话默认档位 | App 自己的对话页发请求时带哪一档 | SharedPreferences |

**网关默认档位**三选一：

| 档 | 含义 |
|---|---|
| 不思考 | 客户端不传 effort 就不思考 |
| 按模型默认 | 用模型目录里该模型支持的默认档 |
| 最高档 | 上游老行为，一律强制最高档 |

另有 **强制关闭** 开关：打开后连客户端显式给的档位也抹掉，一律不思考。
此时「默认档位」选什么都不生效。

**对话默认档位**四选一：不思考 / Low / High / Max。
对话页那排 chip 能按会话临时改，不动全局。

**几个实测过的事实**

- `reasoning_effort: "off"` 上游返回 **HTTP 400**，所以「不思考」发的是 `"none"`
- 不支持关思考的模型（例如 `hy3`），「不思考」会被网关退回该模型默认档
- 档位越高思考 token 越多，额度消耗越快
- 额度是「账号 × 模型」24 小时窗口，用尽返回 429，code 6004
- 思考页底部的「最近请求」显示的是网关**最终发出去**的 `effort` 和 `thinking`

---

## 八、端口与服务

| 端口 | 服务 | 用途 |
|---|---|---|
| 8888 | 网关 | OpenAI 兼容 API（`/v1/chat/completions`、`/v1/models`） |
| 8889 | 调试台 | 思考档位状态、最近请求的最终取值 |
| 8890 | 控制台 | 模型目录、密钥、思考档位 |

都只监听 `127.0.0.1`。

**改端口**：设置 → 网关设置 → 端口。改主端口后，调试台自动 `+1`、控制台 `+2`。
**改完要重启网关才生效。**

**局域网访问**：设置 → 网关设置 → 打开「局域网」，
然后把 `127.0.0.1` 换成设置里显示的内网 IP。开启后服务监听 `0.0.0.0`。

---

## 九、API Key 与客户端接入

**建 Key**：控制台 → 密钥 → 新建。

| 客户端 | Base URL | 备注 |
|---|---|---|
| DSH | `http://127.0.0.1:8888/v1` | 思考档位要在 DSH 自己的 provider 配置里声明 |
| ChatBox / NextChat | 同上 | 标准 OpenAI 兼容 |
| OpenAI SDK | 同上 | `base_url="http://127.0.0.1:8888/v1"` |

设置 → 客户端接入 里有现成的地址和 Key 片段，点一下直接复制。

**现成片段**（把 `sk-wb-...` 换成控制台里那把）：

```bash
# curl
curl http://127.0.0.1:8888/v1/chat/completions \
  -H "Authorization: Bearer sk-wb-..." \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4.1-flash","messages":[{"role":"user","content":"hi"}]}'

# Python SDK
from openai import OpenAI
client = OpenAI(base_url="http://127.0.0.1:8888/v1", api_key="sk-wb-...")

# 环境变量（多数客户端认这个）
OPENAI_BASE_URL=http://127.0.0.1:8888/v1
OPENAI_API_KEY=sk-wb-...
```

**局域网给别人用**：设置 → 网关设置 → 打开局域网，然后把 `127.0.0.1` 换成手机 IP。
**必须同时设非空的面板密码和 API Key**，否则等于把账号池公开在局域网里。

**指定区域**：请求里带 `?realm=intl` 或 `X-Realm: intl` 头；
如果这把 Key 在控制台里绑了区域，那个绑定优先，请求头会被忽略。

> DSH 这类客户端**不读**网关宣告的能力，需要你在它自己的 provider 配置里
> 声明 `reasoningEfforts` 才会显示档位菜单 —— 跟网关无关。

---

## 十、从源码构建

需要 JDK 17、Android SDK 36、Python 3.11（Chaquopy 用）。

```bash
# 1. 准备签名（本仓库不提供）
keytool -genkeypair -v -keystore keystore/wb2api.jks \
        -alias wb2api -keyalg RSA -keysize 2048 -validity 10000

# 2. 写 keystore.properties
cat > keystore.properties <<PROP
storeFile=keystore/wb2api.jks
storePassword=你的密码
keyAlias=wb2api
keyPassword=你的密码
PROP

# 3. 构建
./gradlew :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/`。

> 改了 `assets/` 下任何文件时，先 `:app:clean` 再 `:app:assembleRelease`，
> 否则 Gradle 会认为资源没变而跳过打包。

---

## 十一、目录结构

```
app/src/main/
├── assets/app/            Python 网关与页面
│   ├── wb_proxy.py        网关主体
│   ├── wb_accounts.py     账号池
│   ├── wb_settings.py     面板密码、设置
│   ├── wb_console.py      控制台 + 内嵌网页
│   ├── wb_boot.py         启动器 + 思考页
│   └── dashboard.html     看板
├── java/com/yunluo/wb2api/
│   ├── MainActivity.kt    界面主体
│   ├── GatewayService.java 前台服务
│   ├── PyRunner.java      Python 生命周期
│   ├── Prefs.java         设置存取
│   ├── agent/             工具调用、整包备份恢复
│   ├── data/              网关客户端与数据模型
│   └── ui/                Compose 界面
└── res/                   图标、品牌 logo
```

---

## 十二、已知限制

- **不读上游的 `/v1/models` 能力宣告**：DSH 一类客户端需在自身 provider 配置里
  声明 `reasoningEfforts` 才会显示档位菜单，跟网关无关
- 账号池数据在 App 私有目录，卸载即丢 —— 用「数据管理 → 备份到文件」留一份
- 网页版控制台的页签栏被 CSS 永久隐藏，浏览器直接打开 `:8890` 只能用
  `?tab=keys` / `?tab=think` 指定页
- **面板密码没有重置入口**，忘了只能清数据（见第六节）
- 手机做网关的吞吐远低于电脑，长时间高并发建议还是用电脑 / 服务器

> 早期版本前台服务用的是 `dataSync`，Android 14+ 有「每 24 小时累计 6 小时」
> 的硬上限，到点系统强制停掉 —— 这就是「跑着跑着掉后台」的原因。
> 现已改为 `specialUse`，没有累计时长限制。

---

## 十三、更新记录

### 1.10.11-app134

**看板整份换成 1.10.11 版，再把自研的六个块贴回去。** 这次两边的东西全都要。

从上游新界面拿到的：

- **账号分组**：分组筛选下拉、点账号名旁的芯片改分组、「+ 分组」、按组批量启停/
  刷新凭证/测试/补积分、导入时指定分组、API Key 按组选入移除
- **账号列表分页**：第 X 页 / 共 Y 页 + 上一页下一页
- **风控连坐暂停面板**：剩余秒数提示 + 立即解除 + 设置里改暂停时长
- **API Key 配置示例**：Base URL 与 cURL 片段，一键复制
- **代理槽下拉改进**：多出「自动（随机挑选未使用的代理槽）」，登录时也能选出口
- **新设置项**：单请求体上限、出口级瞬时失败退避、用量聚合保留窗口、账号级并发上限
- **导航布局切换**：新导航布局 / 返回传统布局

保留的自研界面（逐个贴回，全部核对存在且只定义一次）：

```
标题署名（宽屏内联 + 窄屏单独一行）
账号导出（弹窗，一行一个可复制）
单账号导出（弹窗 + 软换行）
粘贴导入（wbacc1: 解析 → dryRun 预览 → 确认导入）
全局提示词「导入」按钮
后台定时任务（签到/旅行/保活/猫猫 + 抖动）
```

```
验证
· dashboard JS node --check 通过（521,960 字节的脚本块）
· 十个自研函数各定义 1 次，无重复
· 沙箱用真网关渲染：页面 1,249,965 字节，
  标题「综合API网关」、署名、定时任务、粘贴导入、分组、连坐暂停、API 示例 全部在
· current_version = 1.10.11，启动日志零异常
· 39 个 .py 语法全过
```

### 1.10.11-app133

**网关升到 1.10.11。** 这次上游只改了 8 个文件，合并量很小，三方合并零冲突。

拿到的修复：

- **小账号池长会话不再每轮换号** —— 页大小覆盖整个 realm 时固定同一账号，
  避免前缀缓存丢失、Token 消耗翻倍（对 4~6 个号的小池子最实在）
- **恢复提示词改写** —— 两条脱敏规则退化成空操作的问题修好
- **live 免费倍率不覆盖付费价** —— 远端目录返回 `x0.00` 时保留内置已知付费倍率
- **413 不再被连接重置吞掉** —— 先回 413 再按 Content-Length 排空上传
- **风控连坐暂停可配** —— 暂停时长进 settings，可看倒计时并提前解除
  （以前只有环境变量 `WB_RESTRICTION_HOLD_SECONDS`）
- CodeArts 参数错误不再冷却整个账号池、模型 ID 精确匹配

保留的自研改动（全部核对过）：

```
realm 标记 / 失败原因字段 / 启动重算区域 / 关思考两条路径判定
后台排程抖动 / 后台打乱账号顺序 / 定时任务界面 / 抖动设置项
```

```
验证
· 三方合并：wb_proxy / wb_accounts / wb_scheduler / wb_settings 全部零冲突
· repair_realm 仍归属 class AccountPool（上次合并踩过的坑，这次专门核了）
· 沙箱真跑：/health ok=true、/v1/models 200（19 个模型）、
  current_version = 1.10.11、抖动设置 20 → 42 写入成功、
  restriction_hold_seconds 已在 settings 里、启动日志零异常
· 39 个 .py 语法全过
```

### 1.10.9-app132

**清理：注释里不留第三方项目的名字。**

- 三处注释里提到的外部项目名改写成中性描述（技术含义不变）
- 无功能改动，行为与 app131 完全一致

### 1.10.9-app131

**后台定时任务加「防机械节拍」抖动，面板上补上定时任务设置界面。**

上游对「同一出口 IP 上多个账号以完全一致的机械节拍活动」判得很重（有实战记录：
6 个号 3 分钟内全被 11140 限制）。以前的排程是准点 `HH:00:00` 触发、账号顺序固定、
间隔 2~5 秒 —— 就是最典型的机械节拍。

- **后台排程抖动**：不再卡整点第 0 分钟，而是在该小时内随机挑一分钟触发
  （范围 0–N 分钟，N 可配，默认 20，设 0 = 关闭）
- **后台打乱账号顺序**：池子顺序天天一样本身也是可识别的节拍
- **后台账号间隔拉到 20~120 秒随机**（原来是 2~5 秒，几乎同时）
- **只影响后台定时**：面板上手动点「签到」保持原来的快速执行（间隔 2~5 秒），
  点了立刻看到结果 —— 人点的动作本身不机械
- **看板 → 设置 → 后台定时任务**：签到 / 旅行 / 保活 / 猫猫 四个小时表 + 抖动，
  可视化编辑。后端一直支持读写 `settings.schedule`，以前只能手改 JSON

```
验证
· 设置读写：设 35 → 35；越界 999 → 夹到 55；非法 abc → 保持原值；
  非法小时 25 → HTTP 400
· 抖动逻辑：span=0 恒为 0（旧行为）；span=20/55 同一小时内稳定、跨小时随机
· 39 个 .py 语法全过，dashboard JS node --check 通过
· ：app:assembleRelease BUILD SUCCESSFUL，versionCode 100
```

### 1.10.9-app130

**思考处理补齐、后台自愈、定时任务可视化、文档补全。**

思考（`/v1` 请求）
- 「关思考」现在两条路径都认：`thinking.type=disabled` 和
  `reasoning_effort=off/none/disabled`。判定成「关」之后会先清掉档位字段，
  再把 `thinking` 明确置 disabled —— 否则 `off` 传到上游会被就近抬成最弱正档，
  表现就是「说要关，实际开着最弱一档」

后台自愈
- **健康探测**：每 3 秒探一次 `/health`，连续 15 次（约 45 秒）不通 = 进程活着但
  不干活，自动重启 Python
- **被杀诊断**：通知栏显示自救次数和上次死因（`rc=137` = 被系统强杀 / `134` = Python 异常 等）

看板
- **设置 → 后台定时任务**：签到 / 旅行 / 保活 / 猫猫 四个小时表可视化编辑。
  后端一直支持读写 `settings.schedule`，以前只能手改 JSON

文档
- 新增 [PITFALLS.md](PITFALLS.md)：记本机特有的坑（构建、合并、WebView、下载截断等 18 条）
- README 新增「手机上怎么保证不掉线」：六层保活机制 + 通知栏状态怎么读
- README「后台保活」一节重写：写清授权两项 + 国产 ROM 三处 + 怎么验证
- README 客户端接入补 curl / Python SDK / 环境变量现成片段
- README 新增「使用声明与免责」
- 仓库加了 12 个 topics 标签

```
验证
· 39 个 .py 语法全过
· 沙箱启动 /health ok=true、控制台/调试台跨模块符号全在
· dashboard JS node --check 通过
· ：app:assembleRelease BUILD SUCCESSFUL，versionCode 100
```

### 1.10.9-app129

**网关上游升到 1.10.9，自研改动一处没丢。**

三方对比合并（base = 上游 v1.8.0，theirs = 上游 v1.10.9，ours = 本仓库）：

- 我们没动过的 27 个 `.py` 直接换新
- 带进上游新增 6 个模块：`wb_codearts` / `wb_codearts_crypto` /
  `wb_media` / `wb_media_client` / `wb_media_tasks` / `wb_usage_io`
- `pricing.json` 换上游新价目
- `wb_proxy.py` 5 处冲突全解（上游给 RateLimited 加了 `reset_at=` 参数）
- `wb_accounts.py` 合并把 `repair_realm` 放进了 `class Account`，
  手工移回 `class AccountPool`（沙箱跑出来的真 bug）
- `dashboard.html` / `wb_console.py` / `wb_boot.py` **保我们的**
  （我们看板用到的 48 个后端路径在 1.10.9 里一个不缺，旧看板配新后端安全）

拿到的东西：

- CodeArts 原生第三出口（PKCE + DPoP + 续期轮转 + 签到）
- 生图 / 生视频（`/v1/images/*`、`/v1/videos`，默认关闭）
  - `kling-video-v3` 720P/1080P/4K，3–15 秒，约 26 积分/秒
  - `minimax-video-h3` 768P/2K，4–15 秒，约 14.29 积分/秒
  - `hunyuan-image-alpha` / `-edit` 约 6 积分/张
  - 新增 `GET /v1/media/models`：开关关着也能查清单和实测单价
- 账号健康：401 真失效判定、11140 风控避让、配置并发锁、UID 注入防护
- 日志轮转日限校验、`usage.jsonl` 原子追加
- 国际版 `deepseek-v4.1-flash` 原生免费通道（0 积分）

```
验证
· 39 个 .py 语法全过
· 沙箱真跑 /health ok=true（带 codearts 段）、/v1/models 200（19 个模型）、
  媒体路由默认 404（符合预期）、启动日志零异常
· 控制台与调试台引用的 8 个跨模块符号在 1.10.9 里全都在
· 「关于」里的上游版本改为 v1.10.9（原来写死 v1.8.0）
```

合并细节见 [MERGE-NOTES.md](MERGE-NOTES.md)。

### 1.8.0-app126

**账号导出/导入改成手机上能用的形态，补上署名。**

- **导出不再落文件**：点「导出」直接弹窗，把每个账号压成一行 `wbacc1:<base64url>`，下面「复制全部」一键拷走。手机上再也不用找「下载到哪了」
- **单账号导出也是弹窗**：每个账号卡片上的「导出」只弹这一个账号的那一行，带「复制」「全选」，长串自动折行显示（3630 字符一行，不折行得横向拖）
- **新增「粘贴导入」**：一个输入框，贴 `wbacc1:…` 进去（一行一个）→ 先 dryRun 预览「新增几 / 覆盖几 / 无效几」→ 确认导入。uid 相同覆盖、不同新增，脏行自动跳过，也兼容整份 JSON 粘贴
- 原来「选文件导入」那套**原样保留**，两个入口并存
- **设置 → 关于**：加作者署名，并修掉正文颜色偏暗看不清的问题（显式 `WbMuted` / `WbText`）
- **看板标题署名**：窄屏原来被 `display:none` 藏掉了，现在单独占一行显示

```
验证
· 真实导出数据往返：6 条 → 编码 → 解码，uid/accessToken/refreshToken 完全一致
· 单账号导出接口带 uid 只回 1 条，且带 refreshToken
· 混入脏行：成功 2、跳过 1，不整体失败
· :app:assembleRelease BUILD SUCCESSFUL，签名 OK，versionCode 100
```

### 1.8.0-app122

**回到干净的 WorkBuddy 网关，补三个小功能。**

- 撤掉多渠道 / 账号型渠道那一整套实验（浏览器登录、Cookie 抓取、OAuth 换 token）。那部分留在分支 `channels-experiment` 和标签 `archive-channels`，主线不再包含
- 网关显示名统一成「综合API网关」（控制台 / 思考调试台 / 用量看板）
- 看板 → 设置 → 全局提示词：新增「导入」按钮，选 `.txt` / `.md` / `.json` 读入文本框（上限 64 KiB，超了截断）。只填不保存，改完再点「保存」
- 看板 → 账号：**导出现在直接摊在屏幕上**，不再落文件（手机上找不到路径）。一行一个 `wbacc1:<base64url>`，下面「复制全部」一键拷走
- 看板 → 账号：**新增「粘贴导入」**，一个输入框贴进去 → 先 dryRun 预览「新增几 / 覆盖几 / 无效几」→ 再确认导入。uid 相同覆盖、不同新增，脏行自动跳过
- 单行约 3.6K 字符（只保留 uid / accessToken / refreshToken / realm 等必要字段），也兼容整份 JSON 粘贴

```
验证
· 真实导出数据往返：6 条 → 编码 → 解码，uid/accessToken/refreshToken 完全一致
· 混入脏行：成功 2、跳过 1，不整体失败
· 网关 /accounts/import dryRun：updated 6、invalid 0
· :app:assembleRelease BUILD SUCCESSFUL，签名 OK，versionCode 100
```

### 1.8.0-app101

- **软件显示名改为 `WorkBuddyAPI`**：桌面图标、启动页标题、常驻通知、关于页统一
- 去掉旧的「 2」后缀（当初为和旧包共存才加的，包名统一后不需要了）

### 1.8.0-app100

- 包名彻底统一 `com.yunluo.wb2api`：`applicationId` / `namespace` / 源码目录 / R / BuildConfig 全部一致
- 后台保活：前台服务 `dataSync` → `specialUse`，加 `stopWithTask=false`、`PARTIAL_WAKE_LOCK`
- 设置页新增「后台保活」卡：电池优化豁免一键申请、通知权限、服务状态

### 1.8.0-app99

- 定位并修复 `dataSync` 的 6 小时上限（掉后台的真因）

### 1.8.0-app98

- 密钥页 `/api/keys` 加 5 秒超时与自动重试，修「一直加载中，要切一下才好」
- 切页签改回「只加载一次 + 注入」，不再每次整页重载

### 1.8.0-app97

- **修掉致命前端 bug**：控制台里 `document.getElementById('cmd')` 是空指针
  （终端页早删了，这段代码忘了删），它在脚本顶层抛 `TypeError`，
  后面所有代码不执行 —— 表现是「点思考却进密钥页、密钥列表卡在加载中」
- 加全局 `window.onerror` 兜底；返回键不再在历史参数间来回跳

### 1.8.0-app94

- 思考档位从设置页删除，整个并进「控制台 → 思考」一页
- 控制台加 `/api/think-state`、`/api/think-save` 两个代理接口

### 1.8.0-app93

- 「思考强度」并入「思考档位」
- 账号导入导出 + 数据迁移收进「数据管理」二级页

### 1.8.0-app91

- 控制台顶部改成滑动药丸式分段控件

### 1.8.0-app87

- 模型 / 密钥 / 思考压成同一页的三个页签

### 1.8.0-app84

- 换包名 `com.yunluo.wb2api`、端口 8788→8888，可与旧版共存

### 1.8.0-app82

- 账号导入导出
- 开源：GPL-3.0 → 最终改为 MIT

---

## 十四、使用声明与免责

### 用途

本项目是**个人自用工具**：管理你自己的账号、给自己本地工具提供一个 OpenAI 兼容入口。

**不做、也不授权**：批量注册小号；对外售卖 API 额度、共享账号池、代充；
二次加壳或捆绑卡密收费分发；以「公益服」「低价中转」等名义变相收费。

### 风险

- 上游对非官方客户端的识别与拦截在持续收紧，**这条通道随时可能失效**
- 因使用非官方客户端导致的账号风控、限流、封禁，**由使用者自行承担**
- 请优先使用官方客户端；**不要用主账号**，不要共享账号

### 关于本仓库

- 手机外壳、原生界面与各类修补为本项目新增，MIT 协议
- 网关逻辑来自上游 [ardeyouxipianyi/workbuddy2api-hub](https://github.com/ardeyouxipianyi/workbuddy2api-hub)（MIT），
  其版权声明保留在 `NOTICE`
- 仓库**不含**任何账号凭据、签名密钥与本机运行数据

### 凭证安全

账号凭据在本机以 **AES-256-GCM 加密**存储（纯 Python 实现，不引入第三方依赖），
但「导出账号」功能的输出是**明文**（含 accessToken / refreshToken），
发给别人等于把账号交出去。

---

## 十五、致谢与来源

- **[ardeyouxipianyi/workbuddy2api-hub](https://github.com/ardeyouxipianyi/workbuddy2api-hub)** —— 整个网关本体
  （`wb_proxy.py`、`wb_accounts.py`、`wb_settings.py`、`dashboard.html` 等），MIT。
  这个手机版只是它的外壳，没有它就没有这个项目。
- **[Chaquopy](https://chaquo.com/chaquopy/)** —— 让 CPython 跑进 Android 进程，MIT
- **[Jetpack Compose](https://developer.android.com/jetpack/compose)** —— 界面，Apache-2.0
- **[kyant0/backdrop](https://github.com/Kyant0/Backdrop)** —— 玻璃模糊与透镜效果，Apache-2.0
- **[lobehub/icons](https://github.com/lobehub/lobe-icons)** —— 各模型品牌图标，MIT

完整第三方清单见 [NOTICE](NOTICE)。

---

## 许可

**MIT**，见 [LICENSE](LICENSE)。

Copyright (c) 2026 陨落(落山鸡高级二改王)

二次修改、再分发、商用都可以，保留版权与许可声明即可。
