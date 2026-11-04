# 踩坑记录

给接手这个仓库的人（或 AI）看。**这里只记「本机特有的坑」和「改错了会出事的地方」**，
通用工程约定不写在这儿。按遇到的时间倒序排。

环境：Android + Chaquopy 内嵌 CPython 3.11，构建在 Linux 容器里，
产物是 arm64-v8a 单 ABI 的 APK。

---

## 构建

### 1. 打 APK 必须整份同步 `app/src`，不能只拷改过的文件

```bash
cd /root/wb2api-build
rm -rf app/src app/build
cp -a /sdcard/workbuddy2api-android/app/src app/src
cp /sdcard/workbuddy2api-android/app/build.gradle app/build.gradle
```

**别偷懒只拷一个文件**：`assets/` 里换过东西之后 Gradle 的增量缓存会算错，
表现是打完包装上去还是旧页面。改过 `assets/` 就一定要 `:app:clean`。

### 2. 两个 Gradle 任务不能同时跑

同一个构建目录被两个 `gradle` 进程同时用，会得到：

```
e: Daemon compilation failed
Task :app:mergeReleaseJavaResource FAILED
Task :app:mergeDexRelease FAILED
```

**看到这个先确认没有别的构建在跑**，不要急着改代码。

### 3. 构建时间

`:app:clean` 后 `:app:assembleRelease` 大约 2.5–3 分钟。
超过 5 分钟没动静，八成是 Chaquopy 在重新下载 Python 轮子（网络问题）。

### 4. 非 ASCII 文件名会让 apksigner 挂

打完包先复制成 ASCII 名字再签名：

```bash
cp app/build/outputs/apk/release/app-release.apk /sdcard/WorkBuddyAPI-<版本>.apk
```

---

## 网关（Python 侧）

### 5. `webServer.register()` 收的是对象，不是函数

```python
# 对
ctx.webServer.register({"kind": "exact", "path": "/x", "handler": fn})
# 错 —— 不报错，但建出一条 path=undefined 的路由，永远匹配不上
ctx.webServer.register(fn)
```

### 6. 合并上游之后，语法通过不代表能跑

`git merge-file` 报「0 冲突」也可能把方法塞进错的类里。
**必须真起一次网关**：

```bash
python3 wb_proxy.py --port 18888 --accounts /tmp/test-accounts
curl -s localhost:18888/health
```

判断标准：`/health` 返回 `ok: true`，启动日志里没有 `Traceback` / `failed`。

### 7. 端口参数与环境变量

```
--port       命令行参数
PORT         环境变量兜底
ACCOUNTS_DIR 数据目录（不设就用脚本旁边的 accounts/）
```

沙箱测试时两者都要给，否则会写到真实数据目录里去。

### 8. `/v1/models` 不含媒体模型

`kling-video-v3`、`hy-image-3.5` 这类用 `NON_CHAT_IDS` 明确排除了。
要查媒体模型用 `GET /v1/media/models`（开关关着也能查）。

### 9. 请求指定区域有优先级

```
?realm= 参数  >  API Key 绑定的区域  >  X-Realm 头  >  全局设置
```

**Key 绑了区域的话，`X-Realm` 头会被无视。** 排查「为什么我的 intl 请求被记成 cn」时先看这个。

### 10. 跨出口兜底默认关，而且只允许 intl → cn

```python
realm_fallback_enabled  默认 False
方向：国际版限流 → 退回国内版    （反向永远不做：
      国内是便宜/免费那一侧，静默挪到国际出口会花掉没打算花的钱）
```

---

## 界面（WebView / Compose）

### 11. WebView 里 `<input type=file>` 要 App 侧实现

不实现 `onShowFileChooser` 的话，点了没反应，也不报错。
看板和控制台两个 WebView 都已经实现了。

### 12. 看板响应头是 `no-cache` + ETag

改完 `dashboard.html` 装上 APK 就生效，不用清缓存。
**但如果 APK 没换，改源码是看不到的** —— 浏览器不会去拉服务器上没有的文件。

### 13. 窄屏下 `.wb-signature` 会被隐藏

```css
@media (max-width:640px){ .wb-signature{display:none} }
```

手机上看不到署名就是这个原因。要显示得另起一行（`.wb-signature-line`）。

---

## 环境

### 14. 抓 GitHub 上的大文件会断

`raw.githubusercontent.com` 拉 300KB 以上的 PNG 经常 `IncompleteRead`。
用 GitHub API 的 base64 接口更稳：

```bash
curl -s -H "Authorization: Bearer $GH_TOKEN" \
  https://api.github.com/repos/<owner>/<repo>/contents/<path>
# 取 content 字段做 base64 解码
```

**下载完一定要校验**：PNG 就用 zlib 解一遍 IDAT，解不开就是截断的。
坏图的表现是「界面上什么都没有」，不会报错。

### 15. 设备策略限制

```
pm install          被策略拦截，只能由用户手动装
/app/open file://   被拒，传不了本地文件
读屏                 部分场景被拒
```

改完 APK 只能让用户自己点安装。

---

## 改代码时容易踩的

### 16. Python 三元表达式不能带 `return`

```python
return a if c else b      # 对
a if c else return b      # SyntaxError
```

### 17. 给 dict 补字段时先判类型

```python
rc = item.get("reasoning_content")
if not isinstance(rc, str):      # None / 数字 都算缺失
    rc = ""
```

上游对 `reasoning_content` 只判 `len > 0` 不 trim，所以空白串能过、
空串不能过 —— 要占位就填一个空格 `" "`。

### 18. 替换大段代码用行号时，从后往前删

先删前面的，后面的行号全变了。

---

## 版本号约定

```
versionName = <内嵌网关版本>-app<构建号>
例如  1.10.9-app129

versionCode 固定 100（用户可以覆盖安装，不用卸载）
```

改上游就动前半段，改 App 就加后半段。
