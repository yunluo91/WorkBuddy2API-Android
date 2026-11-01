# WorkBuddy2API · 手机版

把 Python 写的 OpenAI 兼容网关塞进一个 Android APK 里跑。

用 [Chaquopy](https://chaquo.com/chaquopy/) 内嵌 CPython，网关原样在进程里运行；
外面套一层 Jetpack Compose 的原生界面，管账号、看调用记录、调思考档位、
还能让模型读写手机上的文件。

**当前版本：`1.8.0-app100` / `versionCode 100` / `compileSdk 36` / `minSdk 24`**

> `versionCode` 固定 100，不随版本递增。这样任意历史版本之间都能互相覆盖安装，
> 不会出现「装了新版就装不回旧版」（`INSTALL_FAILED_VERSION_DOWNGRADE`）。

---

## 包名

| 项 | 值 |
|---|---|
| `applicationId`（装到设备上的包名） | `com.yunluo.wb2api` |
| `namespace` / 源码包名 / R / BuildConfig | `com.yunluo.wb2api` |
| 桌面显示名 | `WorkBuddy2API 2` |

四项统一，源码目录是 `java/com/yunluo/wb2api/`。

---

## 它做什么

```
com.yunluo.wb2api
├── 前台服务              锁屏/切后台时保住进程（specialUse + 部分唤醒锁）
├── Chaquopy 内嵌 CPython 3.11
│   ├── wb_proxy.py       上游网关：OpenAI 兼容 API，账号池、重试、用量记账
│   ├── wb_boot.py        启动器：思考档位 watcher + 调试台
│   └── wb_console.py     控制台：密钥、模型目录、思考档位
├── WebView               看板 dashboard.html（网关自带的管理面板）
└── 原生 Compose 界面
    ├── 对话              支持工具调用（读写文件、跑 shell）
    ├── 调用记录          逐条请求的模型/档位/耗时/积分
    ├── 看板              网关面板
    ├── 控制台            模型（原生） / 密钥 / 思考
    └── 设置              后台保活、数据管理、网关参数、客户端接入
```

三个本地服务（都只监听 `127.0.0.1`）：

| 端口 | 服务 | 用途 |
|---|---|---|
| 8888 | 网关 | OpenAI 兼容 API（`/v1/chat/completions`、`/v1/models`） |
| 8889 | 调试台 | 思考档位状态、最近请求的最终取值 |
| 8890 | 控制台 | 密钥、模型目录、思考档位 |

端口在设置里可改。改主端口时，调试台自动用 `+1`、控制台用 `+2`。
改完要重启网关才生效。

---

## 主要功能

**网关**
- OpenAI 兼容：`/v1/chat/completions`、`/v1/models`，可直接接各类客户端
- 账号池：多账号轮换、失效重试、额度窗口追踪
- 用量记账：每条请求一行 JSONL，含模型、档位、token、积分、耗时
- 国内版 / 国际版双 realm

**模型清单**
- 原生 Compose 页面，直连网关 `/v1/models`，不经过网页
- 12 个品牌的真 logo（DeepSeek / 智谱 / Kimi / MiniMax / 混元 / OpenAI /
  Gemini / Grok / Claude / 通义 / 阶跃 / Space Bunny）
- 按倍率排序，标出 PRO 模型、上下文长度、能力（视觉 / 工具 / 推理）

**思考控制**（都在「控制台 → 思考」一页里，设置页不再管这个）

分两层，同一页展示：

| 层 | 作用 | 存放位置 |
|---|---|---|
| 网关默认档位 | 网关怎么给上游补 effort | `thinking.json` |
| 对话默认档位 | App 自己发请求时带哪一档 | SharedPreferences |

- 网关侧：启用开关 / 默认档位（不思考 · 按模型默认 · 最高档）/ 强制关闭
- App 侧：不思考 · Low · High · Max；对话页那排 chip 能按会话临时改
- 「不思考」发 `reasoning_effort: "none"`（不是 `"off"`，上游不认那个值）
- 非法档位由网关改写成该模型默认档，不会让上游拒掉整个请求
- 最近请求会显示网关**最终发出去**的 `effort` 和 `thinking`

**后台保活**

- 前台服务类型 `specialUse`（不是 `dataSync`，见下面的已知限制）
- `stopWithTask="false"`：从最近任务划掉也不停
- `START_STICKY`：被系统杀掉后自动重建
- `PARTIAL_WAKE_LOCK`：屏幕关了 CPU 仍醒着，socket 继续服务
- 设置页里能一键申请电池优化豁免，并显示实时状态

**对话 + 工具**
- 模型可以读写 `/sdcard` 下的文件、跑 shell 命令
- 三档审批：全部放行 / 自动判断 / 每次询问

**数据管理**（设置 → 数据管理，独立二级页）

- 账号导入导出：一次导出国内 + 国际全部账号；导入时 uid 相同覆盖、uid 不同添加
- 数据迁移：整包 zip 备份 / 恢复（换包名、换机用）
  恢复时会强制把端口写成当前包的默认值，避免和共存的老包抢同一个端口

---

## 构建

需要 JDK 17、Android SDK 36、Python 3.11（Chaquopy 用）。

```bash
# 1. 准备签名（不提供，自己生成）
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

改了 `assets/` 下任何文件时，先 `:app:clean` 再 `:app:assembleRelease`，
否则 Gradle 会认为资源没变而跳过打包。

---

## 目录结构

```
app/src/main/
├── assets/app/            Python 网关与页面
│   ├── wb_proxy.py        网关主体
│   ├── wb_accounts.py     账号池
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

## 已知限制

- **不读上游的 `/v1/models` 能力宣告**：DSH 一类客户端需要在它自己的 provider 配置里
  声明 `reasoningEfforts` 才会显示思考档位菜单，跟网关无关
- 账号池数据在 App 私有目录，卸载即丢（要用「数据管理 → 备份到文件」留一份）
- 网页版控制台的页签栏被 CSS 永久隐藏，直接用浏览器打开 `:8890` 只能用
  `?tab=keys` / `?tab=think` 指定页

> 早期版本前台服务用的是 `dataSync`，Android 14+ 有「每 24 小时累计 6 小时」
> 的硬上限，到点系统强制停掉 —— 这就是「跑着跑着掉后台」的原因。
> 现已改为 `specialUse`，没有累计时长限制。

---

## 第三方

| 组件 | 许可 |
|---|---|
| [Chaquopy](https://chaquo.com/chaquopy/) | MIT |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | Apache-2.0 |
| [kyant0/backdrop](https://github.com/Kyant0/Backdrop) | Apache-2.0 |
| 品牌图标 | [lobehub/icons](https://github.com/lobehub/lobe-icons) · MIT |

---

## 许可

**MIT**，见 [LICENSE](LICENSE)。

Copyright (c) 2026 陨落(落山鸡高级二改王)

二次修改、再分发、商用都可以，保留版权与许可声明即可。

### 来源

- Python 网关部分（`assets/app/`）来自 WorkBuddy2API 原项目，原项目是 MIT
- 品牌图标来自 [lobehub/icons](https://github.com/lobehub/lobe-icons)（MIT）

完整的第三方清单见 [NOTICE](NOTICE)。
