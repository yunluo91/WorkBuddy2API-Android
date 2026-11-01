# WorkBuddy2API · 手机版

把 Python 写的 OpenAI 兼容网关塞进一个 Android APK 里跑。

用 [Chaquopy](https://chaquo.com/chaquopy/) 内嵌 CPython，网关原样在进程里运行；
外面套一层 Jetpack Compose 的原生界面，管账号、看调用记录、调思考档位、
还能让模型读写手机上的文件。

**当前版本：`1.8.0-app82` / `versionCode 100` / `compileSdk 36` / `minSdk 24`**

> `versionCode` 固定 100，不随版本递增。这样任意历史版本之间都能互相覆盖安装，
> 不会出现「装了新版就装不回旧版」（`INSTALL_FAILED_VERSION_DOWNGRADE`）。

---

## 它做什么

```
com.fujiang.wb2api
├── 前台服务              锁屏/切后台时保住进程
├── Chaquopy 内嵌 CPython 3.11
│   ├── wb_proxy.py       上游网关：OpenAI 兼容 API，账号池、重试、用量记账
│   ├── wb_boot.py        启动器：思考档位 watcher + 调试台（:8789）
│   └── wb_console.py     控制台（:8790）：密钥管理、模型目录、设置
├── WebView               看板 dashboard.html（网关自带的管理面板）
└── 原生 Compose 界面
    ├── 对话              支持工具调用（读写文件、跑 shell）
    ├── 调用记录          逐条请求的模型/档位/耗时/积分
    ├── 看板              网关面板
    ├── 控制台            模型清单 / 密钥 / 思考
    └── 设置              网关参数、思考强度、账号导入导出、客户端接入
```

三个本地服务：

| 端口 | 服务 | 用途 |
|---|---|---|
| 8788 | 网关 | OpenAI 兼容 API（`/v1/chat/completions`、`/v1/models`） |
| 8789 | 调试台 | 思考档位、最近请求的最终取值 |
| 8790 | 控制台 | 密钥、模型目录、终端、设置 |

---

## 主要功能

**网关**
- OpenAI 兼容：`/v1/chat/completions`、`/v1/models`，可直接接各类客户端
- 账号池：多账号轮换、失效重试、额度窗口追踪
- 用量记账：每条请求一行 JSONL，含模型、档位、token、积分、耗时
- 国内版 / 国际版双 realm

**思考控制**
- 全局默认档（设置页）+ 按会话临时档（对话页 chip）
- 支持 Off / Low / Medium / High / XHigh / Max，按模型目录实际能力展示
- 「不思考」发 `reasoning_effort: "none"`
- 非法档位由网关改写成该模型默认档，不会让上游拒掉整个请求

**对话 + 工具**
- 模型可以读写 `/sdcard` 下的文件、跑 shell 命令
- 三档审批：全部放行 / 自动判断 / 每次询问

**账号导入导出**
- 一次导出国内 + 国际全部账号
- 导入时 uid 相同覆盖、uid 不同添加

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

---

## 目录结构

```
app/src/main/
├── assets/app/            Python 网关与页面
│   ├── wb_proxy.py        网关主体
│   ├── wb_accounts.py     账号池
│   ├── wb_console.py      控制台 + 内嵌网页
│   ├── wb_boot.py         启动器
│   └── dashboard.html     看板
├── java/com/fujiang/wb2api/
│   ├── MainActivity.kt    界面主体
│   ├── GatewayService.java 前台服务
│   ├── PyRunner.java      Python 生命周期
│   ├── agent/             工具调用（读/写/改/跑命令）
│   ├── data/              网关客户端与数据模型
│   └── ui/                Compose 界面
└── res/                   图标、品牌 logo
```

---

## 已知限制

- **不读上游的 `/v1/models` 能力宣告**：DSH 一类客户端需要在它自己的 provider 配置里
  声明 `reasoningEfforts` 才会显示思考档位菜单，跟网关无关
- **Android 14+ 的 `dataSync` 前台服务有每日累计时长上限**，长时间连续跑可能被系统停掉
- 账号池数据在 App 私有目录，卸载即丢

---

## 第三方

| 组件 | 许可 |
|---|---|
| [Chaquopy](https://chaquo.com/chaquopy/) | MIT |
| [Jetpack Compose](https://developer.android.com/jetpack/compose) | Apache-2.0 |
| [kyant0/backdrop](https://github.com/Kyant0/Backdrop) | Apache-2.0 |
| 玻璃底栏交互（`FloatingGlassBottomBar` 等） | 改编自 NPatch-miuix |
| 品牌图标 | [lobehub/icons](https://github.com/lobehub/lobe-icons) · MIT |

---

## 许可

**MIT**，见 [LICENSE](LICENSE)。

Copyright (c) 2026 陨落(落山鸡高级二改王)

二次修改、再分发、商用都可以，保留版权与许可声明即可。

### 来源与例外

- Python 网关部分（`assets/app/`）来自 WorkBuddy2API 原项目，原项目是 MIT
- `ui/glass/` 下四个文件改编自 NPatch-miuix，那份是 **GPL-3.0**，
  这四个文件及其衍生仍是 GPL-3.0
- 品牌图标来自 [lobehub/icons](https://github.com/lobehub/lobe-icons)（MIT）

完整的第三方清单与例外说明见 [NOTICE](NOTICE)。
