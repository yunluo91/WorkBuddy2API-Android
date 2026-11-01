package com.yunluo.wb2api

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.yunluo.wb2api.ui.glass.FloatingGlassBottomBarLabel
import com.yunluo.wb2api.ui.glass.FloatingGlassBottomBarIcon
import com.yunluo.wb2api.ui.glass.FloatingGlassBottomBarItem
import com.yunluo.wb2api.ui.glass.FloatingGlassBottomBar
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.yunluo.wb2api.data.AgentEntry
import com.yunluo.wb2api.data.CallsPage
import com.yunluo.wb2api.data.ChatEvent
import com.yunluo.wb2api.data.ChatState
import com.yunluo.wb2api.data.HubModel
import com.yunluo.wb2api.data.WbApi
import com.yunluo.wb2api.data.WbClient
import com.yunluo.wb2api.ui.CallsScreen
import com.yunluo.wb2api.ui.ChatScreen
import com.yunluo.wb2api.ui.ToolApprovalDialog
import com.yunluo.wb2api.ui.EFFORT_OFF
import com.yunluo.wb2api.ui.NEW_SESSION

private const val LOGIN_HOOK_JS: String = """(function(){try{if(window.__wbPwdHook)return;window.__wbPwdHook=1;var send=function(v){try{if(!v)return;WbBridge.savePanelPassword(String(v));}catch(e){}};var grab=function(){var i=document.getElementById('panelPwdInput');if(!i||!i.value)return;var g=document.getElementById('panelGate');if(g&&g.style.display==='none')return;send(i.value);};document.addEventListener('click',grab,true);document.addEventListener('keydown',function(e){if(e.key==='Enter'||e.keyCode===13)grab();},true);document.addEventListener('submit',function(ev){try{var f=ev.target;if(!f)return;var ps=f.querySelectorAll('input[type=password]');if(ps.length===1&&ps[0].value)send(ps[0].value);}catch(e){}},true);}catch(e){}})();"""

private const val HEADER_HOOK_JS: String = """(function(){try{if(window.__wbHdrHook)return;window.__wbHdrHook=1;var REFRESH='<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/></svg>';function swap(){var b=document.getElementById('btnConsoleToggle');if(!b||b.dataset.wbRef==='1')return false;b.dataset.wbRef='1';b.innerHTML=REFRESH;b.setAttribute('title','刷新页面');b.setAttribute('aria-label','刷新页面');b.removeAttribute('onclick');b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();location.reload();},true);return true;}if(!swap()){var n=0;var t=setInterval(function(){if(swap()||++n>40)clearInterval(t);},500);}}catch(e){}})();"""

/**
 * 修页面内部的"滑过头"。
 *
 * 现象：看板 → 智能体设置 → 连接信息，那一页往下滑能一直滑到全空。
 *
 * 原因不是 WebView 的 overScroll（那个我已经用 OVER_SCROLL_NEVER 关了），
 * 而是页面自己的滚动容器：里面某个 div 有 overflow:auto/scroll，
 * iOS/Android 的 WebView 对这种容器默认允许"橡皮筋"越界，
 * 滚到底继续拖，内容会被推出视口，露出下面的空白。
 *
 * 这个是上游页面（第三方），不能改它的 CSS，所以从 WebView 注入一段 JS：
 *
 *   1. 给 html/body 加 overscroll-behavior: none
 *      —— 禁掉整页的橡皮筋
 *   2. 遍历所有元素，凡是计算样式里 overflow-y 是 auto/scroll 的，
 *      也加上 overscroll-behavior: contain
 *      —— 禁掉容器内部的越界
 *   3. 用 MutationObserver 盯着 DOM 变化，新出现的滚动容器也补上
 *      —— 那个页面是前端渲染的，元素是后加的
 *
 * overscroll-behavior 在 Android WebView（Chromium 63+）上是支持的。
 */
/**
 * 把控制台的令牌交给原生层。
 *
 * 页面在 <head> 里注入了一个 <meta name="wb-token" content="...">，
 * 这里读出来通过 JS 桥送回去。没有就隔一会儿再试（页面可能还没解析完）。
 */
private const val TOKEN_BRIDGE_JS: String = """(function(){
  try{
    function push(){
      try{
        var m = document.querySelector('meta[name=wb-token]');
        var t = m ? (m.getAttribute('content')||'') : '';
        if(!t) return false;
        if(window.__wbTokenSent === t) return true;
        window.__wbTokenSent = t;
        if(window.WbBridge && WbBridge.saveToken) WbBridge.saveToken(t);
        return true;
      }catch(e){ return false; }
    }
    if(push()) return;
    var n = 0, timer = setInterval(function(){
      if(push() || ++n > 40) clearInterval(timer);
    }, 300);
  }catch(e){}
})();"""

private const val OVERSCROLL_FIX_JS: String = """(function(){
  try{
    if(window.__wbOSFix) return; window.__wbOSFix = 1;
    var STYLE_ID = 'wb-overscroll-fix';
    function ensureStyle(){
      if(document.getElementById(STYLE_ID)) return;
      var st = document.createElement('style');
      st.id = STYLE_ID;
      // 只作用在 html/body。
      // 之前这里是 '*{overscroll-behavior:contain;}'，给所有元素都加 ——
      // 那会把整页的滚动链一圈圈截断，最后表现成"内容划不动"。
      // 真正需要 contain 的滚动容器由下面的 tagScrollers() 逐个标。
      st.textContent = 'html,body{overscroll-behavior-y:none!important;}';
      (document.head || document.documentElement).appendChild(st);
    }
    function tagScrollers(){
      try{
        var all = document.querySelectorAll('div,section,main,article,ul,ol,table,tbody');
        for(var i=0;i<all.length;i++){
          var el = all[i];
          if(el.__wbOS) continue;
          var cs = getComputedStyle(el);
          var oy = cs.overflowY;
          if(oy !== 'auto' && oy !== 'scroll') continue;

          /* WB-SCROLL-FIX3 【关键】
             只有「真的有纵向可滚内容」的容器才加 contain。

             为什么：
             CSS 规范里，overflow-x:auto 且 overflow-y 没写时，
             overflow-y 会被**计算成 auto**。
             于是页面里那些只是"横向能滚的表格"（.table-wrap、
             内联 overflow-x:auto 的 div）也会被这里当成纵向滚动容器。

             而 contain 的语义是「滚到边界就停，不传给父级」。
             这种容器纵向没有可滚内容，手指一滑就被它接住，
             又因为 contain 不往上传 —— window 收不到滚动事件，
             整页纹丝不动。右侧滚动条能动，是因为那是浏览器自己
             画的，不走 DOM 命中测试。

             判断方法：scrollHeight <= clientHeight 说明纵向没有
             溢出内容，它根本不需要滚动，也就不该加 contain。 */
          if(el.scrollHeight <= el.clientHeight + 1) continue;

          el.__wbOS = 1;
          el.style.overscrollBehavior = 'contain';
          el.style.webkitOverflowScrolling = 'touch';
        }
      }catch(e){}
    }
    function boot(){ ensureStyle(); tagScrollers(); }
    boot();
    if(document.readyState !== 'complete'){
      document.addEventListener('DOMContentLoaded', boot, {once:true});
      window.addEventListener('load', boot, {once:true});
    }
    try{
      var mo = new MutationObserver(function(){ tagScrollers(); });
      mo.observe(document.documentElement, {childList:true, subtree:true});
    }catch(e){}
    var n = 0, t = setInterval(function(){ boot(); if(++n > 30) clearInterval(t); }, 400);
  }catch(e){}
})();"""

/**
 * 主界面。
 *
 * 这一版把原来的 activity_main.xml + Java 里的 findViewById 全换成了 Compose，
 * 但业务逻辑一行没删：网关启动、健康检查、页签切换、菜单、对话框
 * 全部照原样搬过来。
 *
 * 为什么不是直接在 Java 里塞一个 ComposeView：
 * app15/app16 就是这么干的，结果白屏，异常被 try/catch 吞掉，
 * logcat 里什么都没有。这次是反过来：Activity 只当宿主，
 * 界面整个交给 Compose，出现任何异常都会直接崩到 logcat，藏不住。
 */
class MainActivity : ComponentActivity() {

    // ---------------------------------------------------------------- 状态
    //
    // Compose 的界面状态。业务逻辑改这些变量，界面自动重画。
    // 用 mutableStateOf 而不是普通字段：普通字段改了 Compose 看不到。

    private var bootStatus by mutableStateOf("正在准备运行时…")
    private var bootLog by mutableStateOf("")
    private var booting by mutableStateOf(true)
    private var bootFailed by mutableStateOf(false)
    private var currentTab by mutableStateOf(WbTab.Chat)
    private var toolProgress by mutableStateOf(0)
    private var toolProgressVisible by mutableStateOf(false)
    private var toolError by mutableStateOf("")

    // ---- 原生页面的状态（对话 / 调用记录）

    private var callsPage by mutableStateOf(CallsPage())
    private var chatState by mutableStateOf(ChatState())
    private var hubModels by mutableStateOf<List<HubModel>>(emptyList())

    /** HTTP 客户端，网关就绪后才建。 */
    @Volatile private var client: WbClient? = null

    /** 控制台/调试台那个 WebHolder 的引用。
     *  调用记录页拿不到令牌时，用它把控制台页面加载一次换令牌。 */
    @Volatile private var toolHolderRef: WebHolder? = null

    /**
     * 当前挂着的 JS 对话框（alert / confirm / prompt）。
     *
     * 【为什么不用 android.app.Dialog】
     *
     * 原来 WebDialogs 在 WebChromeClient 的回调里同步 new 一个
     * android.app.Dialog，往里面塞 ComposeView.setContent { }，
     * 然后 show()。这条路在 Dialog 的场景下不稳：
     *
     *   Dialog 的 decorView 上没有 ViewTreeLifecycleOwner，
     *   Compose 会沿着 context 链往上找。正常能兜到 Activity，
     *   但 AndroidComposeView.onAttachedToWindow 与
     *   ViewTreeLifecycleOwner.set() 的先后顺序取决于 Dialog.show()
     *   内部添加 decorView 的时机 —— 某些机型上会先 attach，
     *   于是抛：
     *
     *     IllegalStateException:
     *     ViewTreeLifecycleOwner not found from AndroidComposeView
     *
     *   这个异常从 AndroidComposeView 抛出、冒泡到 ViewRootImpl，
     *   整个进程崩。而它发生在 show() 返回之后的异步 attach 阶段，
     *   WebChromeClient 回调里那层 try/catch 根本兜不住 ——
     *   这就是"点删除/新建就闪退"的真正原因。
     *
     * 现在改成状态驱动：把对话框状态挂在 Activity 的 Compose 组合里，
     * 用 androidx.compose.ui.window.Dialog 渲染。它跟 Activity 在
     * 同一棵组合树上，天然能拿到 ViewTreeLifecycleOwner，不会再抛。
     */
    private var jsDialog by mutableStateOf<JsDialogState?>(null)

    /** 工具执行器。网关就绪后建一次，之后复用。 */
    @Volatile private var agentTools: com.yunluo.wb2api.agent.AgentTools? = null

    /** 网关 API Key 缓存。开工具前取一次，之后复用。 */
    @Volatile private var agentApiKey: String = ""

    /** 有 root 吗。只在第一次建工具时探一次，结果缓存。 */
    @Volatile private var rootAvailable = false
    @Volatile private var rootProbed = false

    /** 工具调用审批模式。默认自动判断：读放行，写和命令要问。 */
    private var approvalMode by mutableStateOf(com.yunluo.wb2api.agent.ApprovalMode.AUTO)

    /** 是否给对话开工具。关掉就是普通聊天。 */
    private var toolsEnabled by mutableStateOf(false)

    /** 待回答的审批请求。非空时界面上弹确认框。 */
    private var pendingApproval by mutableStateOf<ApprovalRequest?>(null)

    /** WB-MODEL-NATIVE 原生模型页的状态。 */
    /**
     * WB-MODEL-NATIVE 控制台页签里显示什么。
     *   "web"    → 原来的网页（密钥 / 思考）
     *   "models" → 原生模型清单
     */
    private var consoleSubView by mutableStateOf("models")

    /** WB-ACCOUNT-IO 导入导出的回执。 */
    private var accountIoMsg by mutableStateOf("")

    /* WB-CHANNEL-LOGIN 渠道登录窗口的状态。
       channelLoginId 非空 = 登录窗口开着；channelLoginUrl 是它当前地址。 */
    private var channelLoginId by mutableStateOf("")
    private var channelLoginUrl by mutableStateOf("")
    private var channelLoginMsg by mutableStateOf("")

    /** 看板的两个子页："panel" = 网关面板，"channels" = 渠道账号（登录/签到）。 */
    private var boardSub by mutableStateOf("panel")

    /** WB-KEEPALIVE 设置页显示用的实时状态。 */
    private var batteryExemptState by mutableStateOf(false)
    private var notifAllowedState by mutableStateOf(true)

    /** 设置页的二级视图："" = 列表，"data" = 数据管理。 */
    private var settingsSub by mutableStateOf("")

    /** WB-DATA-MIGRATE 整包备份/恢复的回执。 */
    private var migrateMsg by mutableStateOf("")

    /** WB-THINK-LEVEL 全局默认思考强度：off / low / high / max */
    private var thinkLevel by mutableStateOf("high")

    private var modelsLoading by mutableStateOf(false)

    /**
     * 模型页专用的清单。
     *
     * 跟 hubModels 分开：那个是聊天页的模型选择器用的，
     * 数据来自控制台的简表（只有 id，没倍率没名字）。
     * 两边共用一个变量会互相覆盖 —— 聊天页一刷新，
     * 模型页的数据就被换成简表了。
     */
    private var allModels by mutableStateOf<List<HubModel>>(emptyList())
    private var modelsError by mutableStateOf("")
    private var modelsRealm by mutableStateOf("intl")

    /** 调用记录页顶部那行诊断：base / 令牌长度 / 耗时 / 行数。
     *  排查"记录为空"时先看它，不用猜。 */
    private var callsDebug by mutableStateOf("")

    /** WB-TAB-PARAM
     *  从设置页跳控制台某个页签时先记下来，等 WebView 就绪再带上去。
     *  用掉就清空 —— 否则下次进控制台还会被带到那个页签。 */
    @Volatile private var pendingConsoleTab: String = ""

    /** 流式对话的取消标志。发新消息前先把它翻掉，旧的那条会自己退出。 */
    private var chatCancel = AtomicBoolean(false)

    /** 后台刷新用的单线程，别让轮询把 IO 线程占满。 */
    private val io = Executors.newFixedThreadPool(3)

    // ---------------------------------------------------------------- 原有字段

    private lateinit var prefs: Prefs
    private var web: WebView? = null
    private var webTool: WebView? = null

    /** 上次给 webTool 加载的地址。
     *  不能用 webTool.getUrl() —— loadUrl 是异步的，getUrl() 会滞后，
     *  来回点两个页签时就会短路成白屏。 */
    private var toolLoaded = ""

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    @Volatile private var polling = false
    /** 主文档 404 的重试计数，加载成功后清零。 */
    private var retryCount = 0
    /** 控制台/调试台的重试计数。 */
    private var toolRetry = 0
    /** 看板主文档是否加载失败过（含 ERR_CONNECTION_REFUSED）。 */
    private var boardLoadFailed = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /* WB-ACCOUNT-IO 导出：让用户选保存位置（SAF），不跟存储权限较劲。
       写成 val 字段初始化 —— registerForActivityResult 只要在 STARTED 之前
       注册就行，构造时注册是合法的（跟上面 notifPermission 一样的写法）。 */
    private val exportAccountsLauncher =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> uri?.let { doExportAccounts(it) } }

    /* WB-ACCOUNT-IO 导入：让用户选文件 */
    private val importAccountsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { doImportAccounts(it) }
        }

    /* WB-DATA-MIGRATE 整包备份 / 恢复 */
    private val backupDataLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let { doBackupData(it) }
        }

    private val restoreDataLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { doRestoreData(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // WB-FULLSCREEN
        // 边到边 + 隐藏状态栏/导航栏。
        //
        // 为什么用 enableEdgeToEdge 而不是 SYSTEM_UI_FLAG_FULLSCREEN：
        //   后者在 Android 11 起被标为 deprecated，Android 15 更是
        //   强制边到边，老 flag 会被忽略。enableEdgeToEdge 是官方给
        //   的替代方案，铺满屏幕之后再用 WindowInsetsController
        //   把系统栏藏起来。
        //
        // 注意：隐藏不等于禁用。用户从边缘上滑仍能呼出系统栏，
        // 这是 Android 的规定（防止应用把用户困住），
        // 呼出后几秒无操作会自动再隐藏。
        enableEdgeToEdge()
        hideSystemBars()

        setContent {
            WbTheme {
                WbRoot()
            }
        }

        askNotificationPermission()
        startServiceCompat()
        beginBoot()
    }

    /**
     * 把状态栏和导航栏藏起来，进入真正的全屏。
     *
     * 用 WindowInsetsControllerCompat 的 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE：
     * 用户从屏幕边缘上滑能把系统栏临时叫出来，几秒后自动消失。
     * 这是 Android 唯一允许的做法 —— 没有"永久隐藏且不可呼出"这个选项。
     *
     * 每次回到前台都要重新调一次：用户手动呼出系统栏、
     * 或者切到别的应用再回来，系统会把栏恢复。
     */
    private fun hideSystemBars() {
        // WindowCompat.getInsetsController(window, view) —— 第一个参数是 Window
        val controller = androidx.core.view.WindowCompat
            .getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat
                .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(
            androidx.core.view.WindowInsetsCompat.Type.systemBars()
        )
    }

    // ---------------------------------------------------------------- 界面

    @Composable
    private fun WbRoot() {
        // 【作用域提升】Holder 必须建在 when 外面。
        //
        // remember 只在「同一个调用位置」内有效。原来 WebHolder 建在
        // BoardPane / ToolPane 内部，而 when(currentTab) 的每个分支在
        // Compose 里是独立的 slot table —— 切分支时整棵子树离开组合，
        // remember 里的 WebView 就被回收了，切回来再 new 一个。
        // 那不叫保活，是重建，而且每次重建都要重新测量，更容易触发
        // LayoutParams 那个坑。
        //
        // 提到这里之后，整个 WbRoot 生命周期内只有这两个 WebView 实例。
        val boardHolder = remember { WebHolder(this, board = true) }
        val toolHolder = remember { WebHolder(this, board = false) }
        // 存一份引用，让 CallsPane 能主动去控制台要令牌
        toolHolderRef = toolHolder

        // 布局：内容铺满全屏，底栏浮在上面（叠在内容的下边缘上）。
        //
        // 上一版内容列在 Column 里、底栏占独立一行 —— 结果有两个：
        // 内容区高度被压掉 64dp+insets；底栏下面那截系统导航栏区域
        // 露出 WbBg，看着就是一条小黑边。
        //
        // 现在内容 fillMaxSize，底栏用 Box 叠在 BottomCenter。
        // 内容各页自己用 contentPadding / bottomPadding 给底栏留位置
        // （约 96dp，底栏 64 + 底距 12 + 导航栏 ~20）。
        // WB-FULLSCREEN 安全区
        //
        // 全屏之后内容会跑到状态栏、导航栏底下。这里统一在外壳加
        // systemBars 的 padding，让界面元素避开它们。
        //
        // 但 WebView 那三页不套这个 padding —— 它们自己用
        // env(safe-area-inset-*) 处理，套两层会留白。
        // 所以 padding 只加在「启动遮罩」和「底部胶囊底栏」上，
        // 它们各自在下面的代码里处理。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WbBg)
        ) {
            // 底栏要模糊的是「这一层」的内容，所以内容先画进一个
            // layer backdrop，底栏再从这个 backdrop 里采样。
            // 这是 kyant.backdrop 的标准用法，也是原版的做法。
            val blurEnabled = true

            // 【关键】必须 remember。
            //
            // rememberLayerBackdrop 每建一个新实例就多一张离屏纹理，
            // 旧的那张不是立刻回收。不 remember 的话每次重组都会再建一个，
            // 一帧新一帧旧，看起来就是闪。
            val backdrop = rememberLayerBackdrop { drawRect(WbBg) }

            // 只有原生页（对话、调用记录）把内容画进 backdrop。
            //
            // 网页页不挂 layerBackdrop —— 这是闪的根源：
            // WebView 是 SurfaceView / 硬件层，不在 Compose 的绘制树里。
            // layerBackdrop 要把这一层重绘到离屏纹理，WebView 那块区域
            // 每帧都要重新合成，表现就是一闪一闪。
            //
            // 看板 / 控制台 / 调试台 这三个网页页因此固定用降级样式
            // （不模糊、纯色胶囊），但不再闪。
            val nativePage = currentTab == WbTab.Chat || currentTab == WbTab.Calls

            Box(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (nativePage) Modifier.layerBackdrop(backdrop) else Modifier)
                ) {
                    when (currentTab) {
                        WbTab.Chat -> ChatPane()
                        WbTab.Calls -> CallsPane()
                        WbTab.Board -> BoardPane(boardHolder)
                        /* WB-CONSOLE-SWITCH
                           切换条必须放在外面。
                           原来它在 ModelsPane 里面 —— 也就是说
                           默认显示网页时根本看不到那个按钮，
                           永远切不到原生模型页。这是个死路。
                           现在：切换条永远在顶部，下面按状态换内容。 */
                        WbTab.Console -> {
                            Column(Modifier.fillMaxSize()) {
                                ConsoleSubBar()
                                if (consoleSubView == "models") {
                                    ModelsContent()
                                } else {
                                    ToolPane(toolHolder, WbTab.Console)
                                }
                            }
                        }
                        WbTab.Settings -> SettingsPane()
                    }
                }

                if (!booting) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(
                                bottom = 12.dp + WindowInsets.navigationBars
                                    .asPaddingValues()
                                    .calculateBottomPadding(),
                            ),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        FloatingGlassBottomBar(
                            selectedIndex = { WbTab.entries.indexOf(currentTab).coerceAtLeast(0) },
                            onSelected = { idx ->
                                WbTab.entries.getOrNull(idx)?.let { showTool(it) }
                            },
                            // 原生页给真 backdrop（模糊真实内容）；
                            // 网页页给 null，底栏内部会用一层假背景代替 ——
                            // 视觉上的玻璃效果（模糊/透镜/高光/阴影）全都还在，
                            // 只是不去采样 WebView。这样后三页跟前两页一样好看。
                            backdrop = if (nativePage) backdrop else null,
                            tabsCount = WbTab.entries.size,
                            // 五个页面都开模糊。网页页模糊的是假背景，
                            // 不会触发 WebView 重采样，所以不闪。
                            isBlurEnabled = blurEnabled,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) {
                            WbTab.entries.forEachIndexed { idx, tab ->
                                val isSelected = currentTab == tab
                                FloatingGlassBottomBarItem(
                                    onClick = { showTool(tab) },
                                    selected = isSelected,
                                    label = tab.label,
                                    // WB-BAR-ALIGN 等分。不设的话页签按
                                    // 76dp 硬排，会和药丸的步进对不上。
                                    modifier = Modifier.weight(1f),
                                ) {
                                    FloatingGlassBottomBarIcon(
                                        selected = isSelected,
                                        selectedIcon = tab.icon,
                                        unselectedIcon = tab.icon,
                                    )
                                    FloatingGlassBottomBarLabel(tab.label)
                                }
                            }
                        }
                    }
                }
            }

            // WB-CHANNEL-LOGIN 渠道登录窗口（盖在最上层）
            if (channelLoginId.isNotEmpty()) {
                ChannelLoginOverlay()
            }

            // 启动遮罩盖在最上层
            if (booting) {
                WbBootScreen(
                    status = bootStatus,
                    log = bootLog,
                    showRetry = bootFailed,
                    onRetry = {
                        bootFailed = false
                        booting = true
                        startServiceCompat()
                        beginBoot()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 工具调用审批：写文件和跑命令之前问一句
            pendingApproval?.let { req ->
                ToolApprovalDialog(
                    request = req,
                    onAnswer = { allow ->
                        pendingApproval = null
                        req.answer.complete(allow)
                    }
                )
            }

            // JS 对话框：跟主界面在同一棵组合树里，不走独立 Dialog
            jsDialog?.let { dlg ->
                WbJsDialog(
                    state = dlg,
                    onCancel = {
                        when (val r = dlg.result) {
                            is JsPromptResult -> r.cancel()
                            is JsResult -> r.cancel()
                        }
                        jsDialog = null
                    },
                    onConfirm = { text ->
                        when (val r = dlg.result) {
                            is JsPromptResult -> r.confirm(text)
                            is JsResult -> r.confirm()
                        }
                        jsDialog = null
                    }
                )
            }
        }
    }

    /**
     * 看板：占满整个内容区。
     *
     * AndroidView 的 factory 只跑一次，WebView 实例在这里创建、
     * 之后一直复用。update 留空 —— 在里面 loadUrl 会导致每次重组都刷新。
     */
    @Composable
    private fun BoardPane(holder: WebHolder) {
        Column(Modifier.fillMaxSize()) {
            /* WB-BOARD-SUB 看板两页
               面板就是原来那个 dashboard.html；
               渠道是账号型渠道的登录/签到页（复用控制台网页的 paneChannels）。
               为什么挪到看板：JS 桥（WbBridge）只挂在这个 WebView 上，
               「登录」按钮要靠桥打开 App 内的登录窗口。 */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .height(38.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                    .background(WbBg)
                    .border(
                        1.dp, WbLine,
                        androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
                    ),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for ((key, label) in listOf("panel" to "面板", "channels" to "渠道")) {
                    val on = boardSub == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(3.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                            .background(if (on) WbCard2 else Color.Transparent)
                            .clickable { boardSub = key }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) WbAccent else WbMuted,
                        )
                    }
                }
            }

            AndroidView(
                factory = {
                    holder.web.also {
                        // 跟 WebHolder 构造里那道是双保险
                        it.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                update = { }
            )
        }

        // 必须等网关确认可用之后才 loadUrl。
        // 绑到 booting 上：booting 变 false 说明 healthy() 探到
        // /health 和 / 都 200，这时候端口一定在听。
        LaunchedEffect(booting, boardSub) {
            web = holder.web
            if (booting) return@LaunchedEffect
            val target = if (boardSub == "channels") {
                consoleUrl() + "?tab=channels"
            } else {
                loginUrl()
            }
            if (boardSub == "panel" && holder.web.url == null) {
                holder.web.loadUrl(target)
            } else if (boardSub == "channels") {
                holder.web.loadUrl(target)
            }
        }
    }

    /**
     * WB-CHANNEL-LOGIN 渠道登录窗口。
     *
     * 思路跟看板那套一样：在 App 里开网页，让用户自己登录，
     * 登录完把 Cookie 抓下来存到渠道上。之后签到的请求、
     * 转发到该渠道的 /v1/chat/completions 都自动带这条 Cookie。
     *
     * 为什么不用各家的 OAuth 客户端：每家一套（有的还带 WASM 校验、
     * 二维码、微信扫码），而且随时会变。抓 Cookie 是通用解法。
     */
    @Composable
    private fun ChannelLoginOverlay() {
        var web by remember { mutableStateOf<WebView?>(null) }
        var addr by remember { mutableStateOf(channelLoginUrl) }
        var loading by remember { mutableStateOf(true) }

        Column(
            Modifier
                .fillMaxSize()
                .background(WbBg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WbCard)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .clickable {
                            channelLoginId = ""
                            channelLoginUrl = ""
                            channelLoginMsg = ""
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("取消", fontSize = 13.sp, color = WbMuted)
                }
                Text(
                    text = "登录 · $channelLoginId",
                    modifier = Modifier.weight(1f),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WbText,
                )
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .background(WbAccent.copy(alpha = 0.16f))
                        .clickable {
                            val w = web ?: return@clickable
                            val cm = android.webkit.CookieManager.getInstance()
                            val here = w.url ?: channelLoginUrl
                            val cookie = cm.getCookie(here).orEmpty()
                            if (cookie.isEmpty()) {
                                channelLoginMsg = "这个页面没抓到 Cookie —— 先在上面把账号登进去"
                                return@clickable
                            }
                            channelLoginMsg = "正在保存…"
                            saveChannelCookie(channelLoginId, cookie)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("完成", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = WbAccent)
                }
            }

            // 地址栏：默认给渠道的站点，用户可以自己改
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WbCard2)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = addr,
                    onValueChange = { addr = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(color = WbText, fontSize = 12.sp),
                )
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                        .clickable {
                            val u = if (addr.startsWith("http")) addr else "https://$addr"
                            loading = true
                            web?.loadUrl(u)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("前往", fontSize = 12.sp, color = WbAccent)
                }
            }

            if (channelLoginMsg.isNotEmpty()) {
                Text(
                    text = channelLoginMsg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WbWarn.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    fontSize = 12.sp,
                    color = WbWarn,
                )
            }

            Box(Modifier.weight(1f)) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            android.webkit.CookieManager.getInstance()
                                .setAcceptCookie(true)
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(v: WebView?, url: String?) {
                                    loading = false
                                    if (url != null) addr = url
                                }

                                /* WB-OAUTH-CALLBACK 拦 OAuth 回调。
                                   LobsterAI 这套的 redirect_uri 是
                                   http://127.0.0.1:18988/auth/callback
                                   —— 故意不让它真的去请求，在这里截住 code。 */
                                override fun shouldOverrideUrlLoading(
                                    v: WebView?,
                                    req: android.webkit.WebResourceRequest?
                                ): Boolean {
                                    val u = req?.url?.toString() ?: return false
                                    return handleOAuthCallback(u)
                                }

                                @Deprecated("Deprecated in Java")
                                override fun shouldOverrideUrlLoading(v: WebView?, u: String?): Boolean {
                                    return u != null && handleOAuthCallback(u)
                                }
                            }
                            if (channelLoginUrl.isNotEmpty()) loadUrl(channelLoginUrl)
                            web = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { }
                )
                if (loading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                    )
                }
            }
        }
    }

    /**
     * WB-OAUTH-CALLBACK 从回调地址里取 code / state。
     *
     * 命中就交给网关去换 token，返回 true 表示这次导航被我们吃掉了。
     */
    private fun handleOAuthCallback(url: String): Boolean {
        if (!url.contains("/auth/callback")) return false
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val code = uri.getQueryParameter("code").orEmpty()
        val state = uri.getQueryParameter("state").orEmpty()
        if (code.isEmpty()) return false
        channelLoginMsg = "正在换取凭据…"
        saveChannelToken(channelLoginId, code, state)
        return true
    }

    /** 把 code 交给控制台换 accessToken。 */
    private fun saveChannelToken(channelId: String, code: String, state: String) {
        io.execute {
            var conn: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL(consoleUrl() + "api/channels/login-callback")
                conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 45000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-WB-Token", WbApi.token)
                }
                val body = org.json.JSONObject()
                    .put("id", channelId)
                    .put("code", code)
                    .put("state", state)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                val text = (if (conn.responseCode in 200..299) conn.inputStream
                            else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val doc = runCatching { org.json.JSONObject(text) }.getOrNull()
                val ok = doc?.optBoolean("ok", false) == true
                runOnUiThread {
                    if (ok) {
                        channelLoginMsg = "登录成功，凭据已保存"
                        channelLoginId = ""
                        channelLoginUrl = ""
                        toast("渠道 $channelId 登录成功")
                        toolWebReload()
                        try {
                            web?.evaluateJavascript("pick('channels')", null)
                        } catch (_: Throwable) {
                        }
                    } else {
                        channelLoginMsg = "换取凭据失败：" +
                                (doc?.optString("msg") ?: text.take(120))
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { channelLoginMsg = "换取凭据失败：${t.message}" }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    /** 把抓到的 Cookie 交给控制台存进渠道。 */
    private fun saveChannelCookie(channelId: String, cookie: String) {
        io.execute {
            var conn: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL(consoleUrl() + "api/channels/cookie")
                conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 12000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-WB-Token", WbApi.token)
                }
                val body = org.json.JSONObject()
                    .put("id", channelId)
                    .put("cookie", cookie)
                    .toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                runOnUiThread {
                    val ok = try {
                        org.json.JSONObject(text).optBoolean("ok", false)
                    } catch (_: Throwable) {
                        false
                    }
                    if (ok) {
                        channelLoginMsg = "已保存（${cookie.length} 字节）"
                        channelLoginId = ""
                        channelLoginUrl = ""
                        toast("渠道 $channelId 登录凭据已保存")
                        toolWebReload()
                    } else {
                        channelLoginMsg = "保存失败：HTTP $code ${text.take(120)}"
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread { channelLoginMsg = "保存失败：${t.message}" }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
    }

    /** 存完凭据让控制台网页刷新一下，渠道页的状态才更新。 */
    private fun toolWebReload() {
        try {
            toolHolderRef?.web?.evaluateJavascript("pick('channels')", null)
        } catch (_: Throwable) {
        }
    }

    /**
     * 控制台 / 调试台：共用一个 WebView，靠 toolLoaded 判断要不要重新加载。
     * 顶部那条 3dp 进度条和失败卡片也在这里。
     */
    @Composable
    private fun ToolPane(holder: WebHolder, tab: WbTab) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = {
                    holder.web.also {
                        // 跟 WebHolder 构造里那道是双保险：factory 重建时会重跑
                        it.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
                // update 留空：在里面 loadUrl 会导致每次重组都刷新
                update = { }
            )

            // 进度条贴顶
            WbToolProgress(
                progress = toolProgress,
                visible = toolProgressVisible,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // 失败卡片
            if (toolError.isNotEmpty()) {
                WbToolErrorCard(
                    message = toolError,
                    onRetry = {
                        toolError = ""
                        val url = toolUrl(tab)
                        toolLoaded = ""
                        webTool?.loadUrl(url)
                        toolLoaded = url
                    },
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        // 页签变化时按需加载。
        // 控制台和调试台现在共用同一个 toolHolder，所以靠 toolLoaded
        // 判断要不要换页 —— 这正是 Java 版 setupToolWeb 里那套逻辑。
        LaunchedEffect(tab, booting, consoleSubView) {
            webTool = holder.web
            if (booting) return@LaunchedEffect
            // 网页那两个页签（密钥/思考）靠 consoleSubView 换 URL，
            // 所以它必须在这个 key 里，否则切了页签网页不动。
            if (tab == WbTab.Console && consoleSubView == "models") {
                return@LaunchedEffect      // 模型页是原生的，不碰 WebView
            }

            if (tab == WbTab.Console) {
                /* WB-CONSOLE-TAB-LOAD 三次修法的最终版
                   
                   ① 一开始靠 URL 参数 + 「地址变了才加载」的判断 —— 偶发切错。
                   ② 改成注入 pick() —— 更不稳：页面脚本没就位时
                      evaluateJavascript 静默失败，界面停在默认页（密钥），
                      而且密钥那页的列表还停在初始的「加载中…」。
                   ③ 现在：**每次都真正导航一次**。
                   
                   带两个参数：
                     tab=keys|think   告诉页面落在哪一页
                     _=<毫秒时间戳>    让 URL 每次都不一样，
                                       WebView 一定会重新导航、重新执行脚本，
                                       不会被"同地址"优化掉
                   
                   为什么不再注入：注入依赖"页面脚本已就位"这个时序，
                   设备上实测会偶发失败，而且失败是静默的。
                   导航虽然多花两三百毫秒，但结果是确定的 ——
                   页面末尾那段 WB-TAB-PARAM 会在脚本执行完时调 pick()，
                   那条路径我用 node 模拟验证过（?tab=think →
                   tabThink 拿到 on、paneKeys 失去 on）。
                   
                   代价：每次切页签整页重载。控制台这页才 58KB，
                   而且是调试用，能接受。 */
                val base = consoleUrl()
                val pending = pendingConsoleTab
                val tabName = if (pending.isNotEmpty()) {
                    pendingConsoleTab = ""
                    pending
                } else if (consoleSubView == "think" || consoleSubView == "channels") {
                    consoleSubView
                } else {
                    "keys"
                }
                /* WB-CONSOLE-TAB-HYBRID
                   只加载一次，之后靠注入切页签。
                   
                   为什么不再每次都 loadUrl：
                     每次都整页重载 → 密钥页每次都要重新发一次 /api/keys，
                     多一次请求挂住的机会（用户看到的"点了密钥一直加载中"
                     就是这么来的）。
                   
                   为什么现在敢用注入：
                     app95 之前注入不可靠，真正的病根不是时序，是
                     wb_console.py 里 `#cmd` 那段空指针 —— 它在脚本顶层抛
                     TypeError，后面所有代码（包括读 URL 参数）全不执行。
                     现在病根修掉了，注入是可靠的。
                   
                   首次进入仍然是带参数的导航（那条路径是确定的），
                   之后切换只注入，不重载、不闪、不重发请求。 */
                withFrameNanos { }

                if (toolLoaded != base) {
                    // 第一次进控制台：带参数加载，页面末尾的 WB-TAB-PARAM 会切好
                    toolLoaded = base
                    runCatching { holder.web.clearHistory() }
                    holder.web.loadUrl(base + "?tab=" + tabName)
                } else {
                    // 已经在这个页面上了：直接注入，不动 WebView
                    holder.web.evaluateJavascript(jsPick(tabName), null)
                }
                return@LaunchedEffect
            }

            val url = toolUrl(tab)
            if (url.isNotEmpty() && url != toolLoaded) {
                toolLoaded = url
                holder.web.loadUrl(url)
            }
        }
    }

    // ---------------------------------------------------------------- 原生页面

    /** 调用记录页。数据走 /api/calls，页面自己每 2 秒轮询一次。 */
    @Composable
    private fun CallsPane() {
        LaunchedEffect(booting) {
            if (!booting) {
                refreshCalls()
                // 令牌还没到手：去后台把控制台页面加载一次，
                // 它会通过 TOKEN_BRIDGE_JS 把令牌送过来。
                // 用户不用手动去点控制台。
                if (WbApi.token.isEmpty()) {
                    val holder = toolHolderRef
                    if (holder != null && holder.web.url == null) {
                        holder.web.loadUrl(debugUrl())
                    }
                }
            }
        }
        CallsScreen(
            page = callsPage,
            onRefresh = { refreshCalls() },
            onClear = { clearCalls() },
            // 令牌没到手时给个明确提示，而不是让用户对着一张空列表
            needToken = WbApi.token.isEmpty() && callsPage.records.isEmpty(),
            debug = callsDebug,
        )
    }

    /**
     * WB-CONSOLE-NAV 滑动药丸式分段控件。
     *
     * 照 NPatch 那个「应用 / 模块」的样子做的：
     * 一个圆角容器，选中的那一格下面垫一块会滑动的药丸，
     * 换页签时药丸横移动画过去，而不是直接跳。
     *
     * NPatch 那边用的是 COUI 库的 TabRow（io.github.suqi8.coui.kmp.basic），
     * 我们工程没引那个库，所以按外观自己实现 —— 只要效果一致，
     * 不必为了一个控件多拉一个依赖。
     */
    @Composable
    private fun SlidingTabs(
        items: List<Pair<String, String>>,
        selected: String,
        onSelect: (String) -> Unit,
    ) {
        val index = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                .background(WbBg)
                .border(
                    1.dp, WbLine,
                    androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
                )
                .padding(3.dp)
        ) {
            val cell = maxWidth / items.size
            val x by animateDpAsState(
                targetValue = cell * index,
                animationSpec = tween(durationMillis = 220),
                label = "pill",
            )
            // 药丸
            Box(
                modifier = Modifier
                    .offset(x = x)
                    .width(cell)
                    .fillMaxHeight()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                    .background(WbCard2)
                    .border(
                        1.dp, WbLine,
                        androidx.compose.foundation.shape.RoundedCornerShape(999.dp)
                    )
            )
            // 文字层（盖在药丸上面）
            Row(Modifier.fillMaxSize()) {
                items.forEach { (key, label) ->
                    val on = key == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                            .clickable { if (!on) onSelect(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.5.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) WbAccent else WbMuted,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ConsoleSubBar() {
        /* WB-CUTOUT 让开摄像头挖孔：
           App 是边到边全屏，内容顶到屏幕最上面，挖孔屏会挡住。
           按设备实际挖孔高度加顶部内边距，不写死数字。 */
        val cutoutTop = WindowInsets.displayCutout
            .asPaddingValues()
            .calculateTopPadding()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = cutoutTop + 6.dp,
                    bottom = 8.dp,
                )
        ) {
            SlidingTabs(
                items = listOf(
                    "models" to "模型",
                    "keys" to "密钥",
                    "think" to "思考",
                ),
                selected = consoleSubView,
                onSelect = { key ->
                    consoleSubView = key
                    if (key == "models" && allModels.isEmpty() && !modelsLoading) {
                        loadModelsNative()
                    }
                },
            )
        }
    }

    /**
     * WB-MODEL-NATIVE 原生模型清单。
     *
     * 数据来自 App 直接请求网关 /v1/models ——
     * 跟看板页走同一条路，那条路一直是最稳的。
     */
    @Composable
    private fun ModelsContent() {
        // 进来就拉一次。不然进程恢复后 allModels 空着会一直显示加载。
        LaunchedEffect(Unit) {
            if (allModels.isEmpty() && !modelsLoading) loadModelsNative()
        }

        com.yunluo.wb2api.ui.ModelsScreen(
            models = allModels,
            loading = modelsLoading,
            error = modelsError,
            realm = modelsRealm,
            onSelectRealm = { r ->
                if (modelsRealm != r) {
                    modelsRealm = r
                    loadModelsNative()
                }
            },
            onRefresh = { loadModelsNative() },
            onCopy = { label, value ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                toast("已复制$label：$value")
            },
        )
    }

    /** 小切换按钮。 */
    @Composable
    private fun SubTab(label: String, selected: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                .background(
                    if (selected) WbAccent.copy(alpha = 0.16f)
                    else Color.Transparent
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                color = if (selected) WbAccent else WbMuted,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }

    /**
     * 设置页。
     *
     * 原来这个位置是「调试台」（8789 思考调试台）。
     * 那个页面已经搬进控制台（多一个「思考」页签），
     * 这里改成 App 自己的入口：服务地址、客户端接入、状态、调用记录筛选。
     */
    @Composable
    private fun SettingsPane() {
        if (settingsSub == "data") {
            com.yunluo.wb2api.ui.SettingsDataScreen(
                accountIoMsg = accountIoMsg,
                migrateMsg = migrateMsg,
                onExportAccounts = { startExportAccounts() },
                onImportAccounts = { startImportAccounts() },
                onBackupData = { startBackupData() },
                onRestoreData = { startRestoreData() },
                onBack = { settingsSub = "" },
            )
            return
        }

        val store = remember { com.yunluo.wb2api.agent.AgentStore(this) }
        var realmFilter by remember { mutableStateOf(store.callsRealmFilter()) }

        // WB-SETTINGS-MERGE 这些原来是设置弹窗里的状态，搬过来
        var portText by remember { mutableStateOf(prefs.port().toString()) }
        var lanOn by remember { mutableStateOf(prefs.lan()) }
        var autoStartOn by remember { mutableStateOf(prefs.autoStart()) }
        var pwdText by remember { mutableStateOf(prefs.panelPwd()) }
        var thinkOn by remember { mutableStateOf(ThinkConfig.enabled(this)) }
        var thinkMode by remember { mutableStateOf(ThinkConfig.mode(this)) }
        var thinkForceOff by remember { mutableStateOf(ThinkConfig.forceOff(this)) }

        /*
         * WB-PENDING-INIT
         * 初始值只在第一次组合时灌一次。
         *
         * 原来是直接裸写四行 pendingXxx = prefs.xxx()，那是错的 ——
         * SettingsPane 是 @Composable，每次重组都会重跑这四行。
         * 你打开局域网开关 → 界面重组 → 这四行又把 pendingLan 覆盖回
         * prefs.lan()（还是 false）→ 点保存时"没有变化"→ 开关白点。
         *
         * 用 LaunchedEffect(Unit) 包起来，它只在进入组合时跑一次。
         */
        LaunchedEffect(Unit) {
            batteryExemptState = isBatteryExempt()
            notifAllowedState = androidx.core.app.NotificationManagerCompat
                .from(this@MainActivity).areNotificationsEnabled()
            thinkLevel = prefs.thinkLevel()
            pendingPort = prefs.port()
            pendingLan = prefs.lan()
            pendingAutoStart = prefs.autoStart()
            pendingPwd = prefs.panelPwd()
        }

        // 进设置页时实测一次局域网状态 —— 那个红/绿标签要准
        LaunchedEffect(booting) {
            if (!booting) probeLanLive()
        }


        com.yunluo.wb2api.ui.SettingsScreen(
            port = prefs.port(),
            panelPassword = prefs.panelPwd(),
            hasRoot = rootAvailable,
            toolsEnabled = toolsEnabled,
            approvalLabel = approvalMode.label,
            callsRealmFilter = realmFilter,
            onSelectRealmFilter = { key ->
                realmFilter = key
                store.setCallsRealmFilter(key)
                // 立刻重拉一次，让调用记录页跟着变
                client = null
                refreshCalls()
            },
            onGoKeys = {
                // 跳到控制台，并让它落在「密钥」页签上。
                // 用 URL 参数传递 —— 控制台的 pick() 支持从 ?tab= 读初始页签。
                currentTab = WbTab.Console
                val url = consoleUrl() + "?tab=keys"
                val holder = toolHolderRef
                if (holder != null) {
                    toolLoaded = url
                    holder.web.loadUrl(url)
                } else {
                    // WebView 还没建出来（第一次进控制台）
                    pendingConsoleTab = "keys"
                }
            },
            onCopy = { label, text ->
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
                toast("已复制 $label")
            },

            // ---- WB-SETTINGS-MERGE 这些原来是设置弹窗里的内容
            prefPort = portText,
            prefLan = lanOn,
            prefAutoStart = autoStartOn,
            prefPanelPwd = pwdText,
            onPortChange = {
                portText = it
                pendingPort = it.toIntOrNull() ?: prefs.port()
            },
            onLanChange = {
                lanOn = it
                pendingLan = it
            },
            onAutoStartChange = {
                autoStartOn = it
                pendingAutoStart = it
            },
            onPanelPwdChange = {
                pwdText = it
                pendingPwd = it
            },
            onRestartGateway = { saveSettingsAndRestart() },
            onOpenData = { settingsSub = "data" },
            batteryExempt = batteryExemptState,
            serviceRunning = PyRunner.isRunning(),
            notifAllowed = notifAllowedState,
            onRequestBattery = { requestBatteryExemption() },
            onOpenNotifSettings = {
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    ).putExtra(
                        android.provider.Settings.EXTRA_APP_PACKAGE, packageName
                    ))
                } catch (_: Throwable) {
                    toast("打不开通知设置")
                }
            },
            lanIp = localIp(),
            lanLive = lanLive,
        )
    }

    /** 对话页。 */
    @Composable
    private fun ChatPane() {
        LaunchedEffect(booting) {
            if (!booting) {
                refreshChat()
                loadModels()
            }
        }
        ChatScreen(
            state = chatState,
            models = hubModels,
            onInputChange = { chatState = chatState.copy(input = it) },
            onSend = { sendChat() },
            onSelectModel = { id ->
                chatState = chatState.copy(modelId = id)
                patchChatMeta(model = id)
            },
            onSelectEffort = { effort ->
                chatState = chatState.copy(effort = effort)
                patchChatMeta(effort = effort)
            },
            onNewSession = { newChatSession() },
            onOpenSession = { id ->
                io.execute {
                    val next = client?.selectSession(id)
                    if (next != null) runOnUiThread {
                        // 保留当前输入框的内容：切会话不该把正在打的东西吃掉
                        chatState = next.copy(input = chatState.input)
                    }
                }
            },
            onDeleteSession = { id ->
                io.execute {
                    client?.deleteSession(id)
                    runOnUiThread { refreshChat() }
                }
            },
            toolsEnabled = toolsEnabled,
            onToggleTools = { on ->
                toolsEnabled = on
                if (on && !rootProbed) ensureTools()
            },
            approvalMode = approvalMode,
            onSelectApproval = { approvalMode = it },
            hasRoot = rootAvailable,
        )
    }

    /**
     * 挂上一个 JS 对话框。
     *
     * 只设置状态，不碰任何 Android 窗口 —— 真正的渲染在 WbRoot 里的
     * WbJsDialog 完成，它跟主界面同一棵组合树，不会出现
     * "ViewTreeLifecycleOwner not found" 那种崩溃。
     *
     * 注意：这里如果已经有一个对话框挂着（比如页面连着弹两个），
     * 先把旧的结掉（cancel），否则那个 JsResult 永远悬着，
     * WebView 那边会一直等，页面后面的脚本全都不执行。
     */
    private fun showJsDialog(
        kind: JsDialogKind,
        title: String,
        message: String,
        defaultValue: String,
        showCancel: Boolean,
        result: Any,
    ) {
        // 结掉上一个悬着的
        jsDialog?.let { old ->
            when (val r = old.result) {
                is JsPromptResult -> try { r.cancel() } catch (_: Throwable) {}
                is JsResult -> try { r.cancel() } catch (_: Throwable) {}
            }
        }
        jsDialog = JsDialogState(
            kind = kind,
            title = title,
            message = message,
            defaultValue = defaultValue,
            showCancel = showCancel,
            result = result,
        )
    }

    // ---------------------------------------------------------------- 原生页面 · 数据

    /**
     * 网关就绪后建客户端，顺手把令牌读出来。
     *
     * 关键：令牌是空的就不缓存。
     *
     * 控制台（8790）比网关（8788）晚启动，它启动时才把 console.token 写出来。
     * 如果第一次拉数据刚好赶在它前面，读到的是空串 —— 拿空令牌去请求，
     * 服务端一律 401，calls() 返回空，页面就是"没有调用记录"。
     * 而 client 一旦被缓存，后面每次都用那个空令牌，永远好不了。
     *
     * 所以这里只在读到令牌之后才缓存；读不到就下次再建一个。
     */
    private fun ensureClient(): WbClient? {
        // 【关键】API 在控制台端口上，不是网关端口。
        //
        // localBase() 是网关（8788）。/api/calls、/api/chat、/api/models
        // 这些路由是 wb_console.py 里的，跑在控制台端口（8790）。
        // 拿 localBase() 去请求，一律 404，页面永远是空的
        // —— 这跟有没有 API Key 一点关系都没有。
        // 【关键】/api/calls、/api/chat、/api/models、/api/keys 全部是
        // wb_console.py 的路由，跑在控制台端口（网关 + 2 = 8790）。
        //
        // 历史坑：这里的函数名曾经是反的 ——
        //   consoleUrl() 读的是 control.port（8789 = 调试台）
        //   chatUrl()    读的是 console.port（8790 = 控制台）
        // 于是拿 consoleUrl() 当 base，实际打到 8789，
        // 那个端口没有 /api/calls，一律 404，页面永远空。
        // 现在两个函数已经正名：debugUrl() = 8789，consoleUrl() = 8790。
        val base = consoleUrl()
        val token = WbApi.token.ifEmpty { readConsoleToken() }

        // 缓存必须同时满足两条：令牌非空，且 baseUrl 跟当前端口一致。
        // 只判令牌是不够的 —— 旧版本用 localBase() 建过客户端，
        // 那个实例的 baseUrl 是 8788，一旦被缓存住，后面所有请求都打错地方。
        val cached = client
        if (cached != null && token.isNotEmpty() && WbApi.baseUrl == base) return cached

        if (token.isEmpty()) {
            // 令牌还没到手（控制台没起）——不缓存，等下次重试
            client = null
            WbApi.baseUrl = base
            return WbClient(base, "")
        }
        val c = WbClient(base, token)
        client = c
        WbApi.baseUrl = base
        WbApi.token = token
        return c
    }

    /**
     * 读控制台令牌。
     *
     * 控制台启动时把它写在 files/wb_app/console.token 里，
     * 原生侧跟它同 uid，直接读文件就行，不用再登录一次。
     */
    private fun readConsoleToken(): String = try {
        val f = File(filesDir, "app/wb_app/console.token")
        if (f.exists()) f.readText().trim() else ""
    } catch (_: Throwable) {
        ""
    }

    private fun refreshCalls() {
        val c = ensureClient()
        if (c == null) {
            callsDebug = "client=null"
            return
        }
        // 用设置页里选的 realm 筛选（默认 all = 全部账号）
        val realm = runCatching { ensureAgentStore().callsRealmFilter() }.getOrDefault("all")
        io.execute {
            val t0 = System.currentTimeMillis()
            val page = c.calls(realm = realm)
            val ms = System.currentTimeMillis() - t0
            runOnUiThread {
                callsDebug = buildString {
                    append("base=").append(WbApi.baseUrl)
                    append(" · token=").append(WbApi.token.length).append("字")
                    append(" · ").append(ms).append("ms")
                    append(" · realm=").append(realm)
                    append(" · rows=").append(page.records.size)
                    append(" · ok=").append(page.stats.calls)
                }
                android.util.Log.d("WbCalls", callsDebug)
                // 只有拿到非空数据才覆盖，避免临时失败把已有记录清空
                if (page.records.isNotEmpty() || page.stats.calls > 0) {
                    callsPage = page
                }
            }
        }
    }

    private fun clearCalls() {
        val c = ensureClient() ?: return
        io.execute {
            c.clearCalls()
            val page = c.calls()
            runOnUiThread { callsPage = page }
        }
    }

    /**
     * 拉会话。
     *
     * 如果一条会话都没有（第一次打开，或者用户把会话全删了），
     * 顺手建一条 —— 否则输入框能打字、发送键却是死的，
     * 因为 sendChat 需要 sessionId 才能发。
     */
    private fun refreshChat() {
        val c = ensureClient() ?: return
        io.execute {
            var st = c.chat()
            if (st.sessionId.isEmpty()) {
                c.newSession()
                st = c.chat()
            }
            val finalState = st
            runOnUiThread {
                // 输入框和运行状态是本地状态，别被服务端覆盖
                chatState = finalState.copy(
                    input = chatState.input,
                    running = chatState.running,
                )
            }
        }
    }

    private fun patchChatMeta(model: String? = null, effort: String? = null, context: Int? = null) {
        val c = ensureClient() ?: return
        val sid = chatState.sessionId
        if (sid.isEmpty()) return
        io.execute { c.patchMeta(sid, model, effort, context) }
    }

    private fun newChatSession() {
        val c = ensureClient() ?: return
        io.execute {
            c.newSession()
            val st = c.chat()
            runOnUiThread {
                // WB-THINK-LEVEL 新会话用设置里那档，不用服务端返回的空值
                chatState = st.copy(input = chatState.input, effort = thinkLevel)
                if (st.sessionId.isNotEmpty()) {
                    val sid = st.sessionId
                    val lv = thinkLevel
                    io.execute { c.patchMeta(sid, effort = lv) }
                }
            }
        }
    }

    /**
     * 发一条消息。
     *
     * 先把用户那行和一条空白的助手行塞进列表，然后边收边往助手行上追加 ——
     * 这样用户一按发送就能看到自己的话，不用等服务端回第一个字节。
     */
    private fun sendChat() {
        val text = chatState.input.trim()
        if (text.isEmpty() || chatState.running) return

        val c = ensureClient() ?: return
        // 没有会话就现建一条。refreshChat 正常会把它建好，
        // 但用户手快的时候可能还没回来，这里兜一下。
        var sid = chatState.sessionId
        if (sid.isEmpty()) {
            sid = c.newSession() ?: ""
            if (sid.isEmpty()) {
                appendError("没有可用的会话，请稍后重试")
                return
            }
            chatState = chatState.copy(sessionId = sid)
        }
        val model = chatState.modelId
        val effort = chatState.effort
        val useTools = toolsEnabled

        // 取消上一次还没结束的流
        chatCancel.set(true)
        val flag = AtomicBoolean(false)
        chatCancel = flag

        val base = chatState.entries + AgentEntry(AgentEntry.Role.USER, text)
        chatState = chatState.copy(
            entries = base + AgentEntry(AgentEntry.Role.ASSISTANT, ""),
            input = "",
            running = true,
        )

        io.execute {
            if (useTools) runWithTools(sid, text, model, effort, flag)
            else runPlain(sid, text, model, effort, flag)
        }
    }

    // ---------------------------------------------------------------- 普通对话

    /** 不开工具时的老路径，一行没变。 */
    private fun runPlain(
        sid: String, text: String, model: String, effort: String, flag: AtomicBoolean,
    ) {
        val c = ensureClient() ?: return
        c.sendChat(sid, text, model, effort, flag) { ev ->
            runOnUiThread {
                when (ev) {
                    is ChatEvent.Content -> appendToLast(ev.text, assistant = true)
                    is ChatEvent.Reasoning -> appendToLast(ev.text, assistant = false)
                    is ChatEvent.Error -> appendError(ev.message)
                    is ChatEvent.ToolDelta -> Unit   // 没开工具，不该有
                }
            }
        }
        runOnUiThread { finishTurn() }
    }

    // ---------------------------------------------------------------- 带工具的对话

    /**
     * 工具调用循环。
     *
     * 一轮的样子：
     *   发消息（带 tools schema）
     *   → 模型可能回 tool_calls
     *   → 逐个执行（写/执行前先问用户）
     *   → 把结果作为 tool_result 再发一次
     *   → 直到模型不再要工具
     *
     * 轮数上限放在这里而不是放任它跑：模型会陷在
     * "调工具 → 不满意 → 再调" 里，没上限一次对话能把额度烧穿。
     */
    private fun runWithTools(
        sidIn: String, text: String, model: String, effort: String, flag: AtomicBoolean,
    ) {
        var sid = sidIn
        val tools = ensureTools()
        val store = ensureAgentStore()

        // 会话：用 App 自己的，不用网关那个
        var session = store.load(sid)
        if (session == null) {
            session = store.create()
            sid = session.id
            chatState = chatState.copy(sessionId = sid)
        }

        // 历史在 App 侧维护，格式就是上游要的 OpenAI 消息
        val history = session.messages.toMutableList()
        history.add(JSONObject().apply {
            put("role", "user")
            put("content", text)
        })

        val c = ensureClient() ?: return

        // 直连 /v1/chat/completions 要 API Key，控制台令牌网关不认。
        // 取不到就直接告诉用户，别让请求静默 401。
        if (agentApiKey.isEmpty()) {
            agentApiKey = c.gatewayKey()
        }
        if (agentApiKey.isEmpty()) {
            runOnUiThread {
                appendError(
                    "工具模式需要网关 API Key，但一把启用中的都没有。\n" +
                            "去「控制台 → 密钥」新建一把（或启用已有的），再试一次。"
                )
                chatState = chatState.copy(running = false)
            }
            return
        }

        val schemas = com.yunluo.wb2api.agent.AgentTools.schemas()
        val maxRounds = 12

        for (round in 1..maxRounds) {
            if (flag.get()) break

            val calls = LinkedHashMap<Int, StringBuilder>()
            val callIds = HashMap<Int, String>()
            val callNames = HashMap<Int, String>()
            val assistantText = StringBuilder()

            c.sendChat(
                sessionId = "",          // 走 App 侧历史，不需要网关会话
                text = "",
                model = model,
                effort = effort,
                cancel = flag,
                tools = schemas,
                historyOverride = history,
                apiKey = agentApiKey,
            ) { ev ->
                when (ev) {
                    is ChatEvent.Content -> {
                        assistantText.append(ev.text)
                        runOnUiThread { appendToLast(ev.text, assistant = true) }
                    }
                    is ChatEvent.Reasoning ->
                        runOnUiThread { appendToLast(ev.text, assistant = false) }
                    is ChatEvent.Error -> runOnUiThread { appendError(ev.message) }
                    is ChatEvent.ToolDelta -> {
                        val arr = ev.calls ?: return@sendChat
                        for (i in 0 until arr.length()) {
                            val row = arr.optJSONObject(i) ?: continue
                            val idx = row.optInt("index", i)
                            row.optString("id").takeIf { it.isNotEmpty() }?.let { callIds[idx] = it }
                            val fn = row.optJSONObject("function") ?: continue
                            fn.optString("name").takeIf { it.isNotEmpty() }?.let { name ->
                                callNames[idx] = name
                                runOnUiThread { appendToolStart(name) }
                            }
                            fn.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                                calls.getOrPut(idx) { StringBuilder() }.append(it)
                            }
                        }
                    }
                }
            }

            if (flag.get()) break

            // 模型给最终回答了 —— 把这一轮记进历史，收工
            if (callNames.isEmpty()) {
                if (assistantText.isNotEmpty()) {
                    history.add(JSONObject().apply {
                        put("role", "assistant")
                        put("content", assistantText.toString())
                    })
                }
                break
            }

            // 记下"模型要调工具"这一步，必须带 tool_calls 结构
            history.add(JSONObject().apply {
                put("role", "assistant")
                put("content", assistantText.toString())
                put("tool_calls", JSONArray().apply {
                    for ((idx, name) in callNames) {
                        put(JSONObject().apply {
                            put("id", callIds[idx] ?: "call_$idx")
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", name)
                                put("arguments", calls[idx]?.toString().orEmpty().ifEmpty { "{}" })
                            })
                        })
                    }
                })
            })

            // 逐个执行
            for ((idx, name) in callNames) {
                if (flag.get()) break
                val argsJson = calls[idx]?.toString().orEmpty().ifEmpty { "{}" }
                val args = parseToolArgs(argsJson)
                val id = callIds[idx] ?: "call_$idx"

                if (!askApproval(name, args)) {
                    runOnUiThread { appendToolResult(name, "用户拒绝执行", false) }
                    history.add(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", id)
                        put("content", "用户拒绝执行这个操作。")
                    })
                    continue
                }

                val outcome = tools?.run(name, args)
                val body = outcome?.text ?: "工具不可用"
                runOnUiThread { appendToolResult(name, body, outcome?.ok ?: false) }
                history.add(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", id)
                    put("content", body)
                })
            }

            if (round == maxRounds) {
                runOnUiThread {
                    appendError("工具调用已达上限（$maxRounds 轮），先停在这里。")
                }
            }
        }

        // 存盘：messages 是协议层，entries 是展示层
        runOnUiThread {
            val finalEntries = chatState.entries
                .filter { it.role != AgentEntry.Role.ASSISTANT || it.text.isNotEmpty() }
                .map {
                    com.yunluo.wb2api.agent.AgentStore.StoredEntry(
                        role = it.role.name.lowercase(),
                        text = it.text,
                    )
                }
            store.save(
                com.yunluo.wb2api.agent.AgentStore.Session(
                    id = sid,
                    title = store.deriveTitle(history),
                    updatedAt = System.currentTimeMillis(),
                    messages = history,
                    entries = finalEntries,
                )
            )
            finishTurn()
        }
    }

    /** 一轮结束后收尾：去掉末尾空白的助手行，刷新会话。 */
    private fun finishTurn() {
        val entries = chatState.entries
        val trimmed = if (entries.lastOrNull()?.let {
                it.role == AgentEntry.Role.ASSISTANT && it.text.isEmpty()
            } == true
        ) entries.dropLast(1) else entries
        chatState = chatState.copy(entries = trimmed, running = false)
        refreshChat()
    }

    /** 工具开始执行时先插一行占位，后面结果填进去。 */
    private fun appendToolStart(name: String) {
        val entries = chatState.entries.toMutableList()
        // 空白的助手行留在后面，工具行插在它前面
        val insertAt = if (entries.lastOrNull()?.role == AgentEntry.Role.ASSISTANT &&
            entries.last().text.isEmpty()
        ) entries.lastIndex else entries.size
        entries.add(insertAt, AgentEntry(AgentEntry.Role.TOOL, "$name …"))
        chatState = chatState.copy(entries = entries)
    }

    /** 工具执行完，把占位那行改掉。 */
    private fun appendToolResult(name: String, text: String, ok: Boolean) {
        val entries = chatState.entries.toMutableList()
        val marker = "$name …"
        val idx = entries.indexOfLast { it.role == AgentEntry.Role.TOOL && it.text == marker }
        val head = if (ok) "$name ✓" else "$name ✗"
        val body = if (text.length > 2000) text.take(2000) + "\n… 已截断" else text
        val line = "$head\n$body"
        if (idx >= 0) entries[idx] = AgentEntry(AgentEntry.Role.TOOL, line)
        else entries.add(AgentEntry(AgentEntry.Role.TOOL, line))
        chatState = chatState.copy(entries = entries)
    }

    /** 解析工具参数。模型偶尔给坏 JSON，别崩。 */
    private fun parseToolArgs(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val o = org.json.JSONObject(raw)
            val m = HashMap<String, Any?>()
            for (k in o.keys()) m[k] = o.opt(k)
            m
        }.getOrDefault(emptyMap())
    }

    /**
     * 问用户要不要放行这次工具调用。
     *
     * 这个函数在**后台线程**里被调用，所以它要阻塞等界面回答：
     * 用 CompletableFuture 挂起，界面点了按钮再 complete。
     */
    private fun askApproval(name: String, args: Map<String, Any?>): Boolean {
        val policy = com.yunluo.wb2api.agent.ToolPolicy
        if (!policy.needsApproval(approvalMode, name)) return true

        val summary = policy.summarize(name, args)
        val warning = policy.warningFor(name, args)
        val future = java.util.concurrent.CompletableFuture<Boolean>()

        runOnUiThread { pendingApproval = ApprovalRequest(summary, warning, future) }

        return try {
            future.get(120, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Throwable) {
            // 超时或被打断当作拒绝 —— 不能因为界面没响应就默认放行
            runOnUiThread { pendingApproval = null }
            false
        }
    }

    /** Agent 会话存储（只建一次）。 */
    @Volatile private var agentStore: com.yunluo.wb2api.agent.AgentStore? = null

    private fun ensureAgentStore(): com.yunluo.wb2api.agent.AgentStore {
        agentStore?.let { return it }
        val st = com.yunluo.wb2api.agent.AgentStore(this)
        agentStore = st
        return st
    }

    /**
     * 保存设置页的改动，必要时重启网关。
     *
     * 端口或局域网改了必须重启 —— 那是 Python 进程的绑定参数，
     * 没法热改。密码同理（面板密码在进程启动时读）。
     * 其他项（自动启动、思考档位）不用重启，它们各自即时生效。
     */
    private fun saveSettingsAndRestart() {
        // 这里的状态在 SettingsPane 里，通过下面的临时字段传出来
        // pending* 理论上有值（LaunchedEffect 进组合就灌了）。
        // 但端口那个 0 是危险默认值 —— 万一还没灌就点了保存，
        // 0 会被判成"非法端口"直接 return，用户看到的是"点了没反应"。
        // 这里兜一下：0 就当没改过。
        val newPort = if (pendingPort in 1024..65535) pendingPort else prefs.port()
        val newLan = pendingLan
        val newAuto = pendingAutoStart
        val newPwd = pendingPwd

        val portChanged = newPort != prefs.port()
        val lanChanged = newLan != prefs.lan()
        val pwdChanged = newPwd != prefs.panelPwd()

        if (newPort !in 1024..65535) {
            toast("端口要在 1024-65535 之间")
            return
        }

        // WB-PENDING-INIT 之后这里应该是可信的；
        // 但万一还是被别的路径覆盖过，至少把实情打出来，别静默失败
        android.util.Log.d("WbSettings",
            "save: port=$newPort lan=$newLan auto=$newAuto " +
                    "pwd=${if (newPwd.isEmpty()) "(空)" else "***"} " +
                    "| before: port=${prefs.port()} lan=${prefs.lan()}")

        prefs.setPort(newPort)
        prefs.setLan(newLan)
        prefs.setAutoStart(newAuto)
        if (newPwd.isNotEmpty()) prefs.setPanelPwd(newPwd)

        if (portChanged || lanChanged || pwdChanged) {
            // 客户端跟着新端口重建
            client = null
            agentApiKey = ""
            toast("已保存：端口${if (portChanged) "改了" else "没变"}、" +
                    "局域网${if (lanChanged) "改了" else "没变"}、" +
                    "密码${if (pwdChanged) "改了" else "没变"} —— 正在重启")
            // PyRunner.restart 内部会 os._exit(0) 并用闹钟把界面拉回来 ——
            // Python 和 Java 共进程，没法"只重启 Python"
            try {
                PyRunner.restart(this)
            } catch (_: Throwable) {
                toast("重启失败，请手动重新打开 App")
            }
        } else {
            // 三项都没变：只保存，不重启。
            // 明确说出来，否则用户会以为点了没反应。
            toast("已保存（端口/局域网/密码都没变，所以没重启）")
        }
    }

    /** saveSettingsAndRestart 用的临时槽位（Compose 状态不好跨函数传）。 */
    private var pendingPort: Int = 0
    private var pendingLan: Boolean = false
    private var pendingAutoStart: Boolean = false
    private var pendingPwd: String = ""

    /** 局域网是否真的在监听。true/false 是实测结果，不是开关值。 */
    private var lanLive by mutableStateOf(false)

    /**
     * 实测局域网是否在监听。
     *
     * 怎么测：请求 http://<自己的局域网IP>:<端口>/health。
     *
     * 为什么要请求局域网 IP 而不是 127.0.0.1：
     *   网关没开局域网时绑 127.0.0.1，此时请求局域网 IP 会连接被拒；
     *   开了局域网它绑 0.0.0.0，请求局域网 IP 就能通。
     *   这是开关值和实际状态之间唯一可靠的区分方式。
     *
     * 在后台线程跑，结果回主线程写 state。
     */
    private fun probeLanLive() {
        val ip = localIp()
        if (ip == "手机IP" || ip.isEmpty()) {
            lanLive = false
            return
        }
        val url = "http://$ip:${prefs.port()}/health"
        io.execute {
            var conn: java.net.HttpURLConnection? = null
            val ok = try {
                conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 1500
                    readTimeout = 1500
                    requestMethod = "GET"
                }
                conn.responseCode > 0
            } catch (_: Throwable) {
                false
            } finally {
                runCatching { conn?.disconnect() }
            }
            runOnUiThread { lanLive = ok }
        }
    }

    /**
     * WB-MODEL-NATIVE 加载模型清单。
     *
     * 直接请求**网关**（8788）的 /v1/models —— 跟看板页走同一条路，
     * 那条路一直是最稳的。
     *
     * 不再经过控制台的 /api/models：那条要过
     * 控制台Python → REGISTRY → 网关Python → 上游，
     * 中间套着异步线程，卡住就是"一直加载中"。
     */
    /**
     * WB-MODEL-NATIVE 加载模型清单。
     *
     * 用**独立线程**，不跟 io 池抢。
     *
     * 为什么：io 池只有 3 个线程，而有 13 处 io.execute 在用 ——
     * 流式对话会占一个直到回答结束（可能几分钟），
     * 调用记录页每 2 秒轮询再占一个。
     * 模型加载排不上队，modelsLoading 就永远停在 true，
     * 界面上就是"一直正在加载中"。
     *
     * 另外用 loadToken 而不是 modelsLoading 做并发判断：
     * 后者一旦因为异常没复位就永久卡死，前者每次都是新值。
     */
    private var modelLoadToken = 0

    private fun loadModelsNative() {
        val token = ++modelLoadToken
        val realm = modelsRealm
        val gateway = localBase()
        modelsLoading = true
        modelsError = ""

        Thread({
            var conn: java.net.HttpURLConnection? = null
            var rows: List<com.yunluo.wb2api.data.HubModel>? = null
            var err = ""

            try {
                // 取 API Key。控制台的 /api/keys 要控制台令牌，
                // 但这条链现在能用了（keys_list 已恢复）。
                if (agentApiKey.isEmpty()) {
                    agentApiKey = ensureClient()?.gatewayKey().orEmpty()
                }
                val key = agentApiKey
                if (key.isEmpty()) {
                    err = "没有可用的 API Key（去「控制台 → 网页 → 密钥」建一把）"
                } else {
                    val url = java.net.URL("${gateway.trimEnd('/')}/v1/models?realm=$realm")
                    conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 8000
                        readTimeout = 15000
                        setRequestProperty("Authorization", "Bearer $key")
                    }
                    val code = conn.responseCode
                    if (code != 200) {
                        err = "HTTP $code"
                    } else {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val arr = org.json.JSONObject(body).optJSONArray("data")
                        val out = ArrayList<com.yunluo.wb2api.data.HubModel>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                val id = o.optString("id")
                                if (id.isEmpty()) continue
                                val caps = o.optJSONObject("capabilities")
                                val effArr = o.optJSONArray("reasoning_efforts")
                                val efforts = ArrayList<String>()
                                if (effArr != null) {
                                    for (j in 0 until effArr.length()) {
                                        effArr.optString(j).takeIf { it.isNotEmpty() }
                                            ?.let { efforts.add(it) }
                                    }
                                }
                                val creditsRaw = o.optString("credits")
                                val credit = if (creditsRaw.isEmpty()) -1.0
                                else creditsRaw.trim().lowercase()
                                    .removePrefix("x").removeSuffix("x")
                                    .toDoubleOrNull() ?: -1.0
                                out.add(
                                    com.yunluo.wb2api.data.HubModel(
                                        id = id,
                                        name = o.optString("name").ifEmpty { id },
                                        vendor = o.optString("vendor"),
                                        multiplier = credit,
                                        efforts = efforts,
                                        contextWindow = o.optInt("context_length", 0),
                                        maxOutput = o.optInt("max_output_tokens", 0),
                                        vision = caps?.optBoolean("vision") == true,
                                        toolCalls = caps?.optBoolean("tool_calls") == true,
                                        reasoning = caps?.optBoolean("reasoning") == true,
                                        // WB-PRO-BADGE 有 entitlement 就是 Pro 限定
                                        proOnly = o.optJSONObject("entitlement") != null,
                                    )
                                )
                            }
                        }
                        if (out.isEmpty()) {
                            err = "网关返回了 0 个模型"
                        } else {
                            rows = out.sortedWith(
                                compareBy({ if (it.multiplier < 0) 999.0 else it.multiplier },
                                    { it.id })
                            )
                        }
                    }
                }
            } catch (e: Throwable) {
                err = e.message ?: "网络错误"
            } finally {
                runCatching { conn?.disconnect() }
            }

            val finalRows = rows
            val finalErr = err
            runOnUiThread {
                // 过期的结果直接丢掉（用户已经切了版本或又点了刷新）
                if (token != modelLoadToken) return@runOnUiThread
                modelsLoading = false
                if (finalRows != null) {
                    allModels = finalRows
                    modelsError = ""
                } else {
                    modelsError = finalErr.ifEmpty { "未知错误" }
                }
            }
        }, "wb-models-load").start()
    }


    // ---------------------------------------------------------------- 账号导入导出

    /** 点「导出」：弹保存框，文件名带日期。 */
    private fun startExportAccounts() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        exportAccountsLauncher.launch("workbuddy-accounts-$stamp.json")
    }

    /** 点「导入」：只收 json。 */
    private fun startImportAccounts() {
        importAccountsLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    /** 真正写文件。 */
    private fun doExportAccounts(uri: android.net.Uri) {
        val c = ensureClient() ?: return
        val gateway = localBase()
        toast("正在导出…")
        Thread({
            val json = c.exportAccounts(gateway, prefs.panelPwd())
            var msg: String
            if (json == null) {
                msg = "导出失败：拿不到数据。" +
                        "检查「网关设置 → 面板密码」对不对（/accounts/* 要面板密码，不认 API Key）"
            } else {
                msg = try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    // 数一下导出了几个账号，给个明确的回执
                    val n = runCatching {
                        org.json.JSONObject(json).optJSONArray("accounts")?.length() ?: 0
                    }.getOrDefault(0)
                    "已导出 $n 个账号（国内 + 国际），含凭证，文件请妥善保管"
                } catch (t: Throwable) {
                    "写文件失败：${t.message}"
                }
            }
            runOnUiThread {
                accountIoMsg = msg
                toast(msg)
            }
        }, "wb-acc-export").start()
    }

    /**
     * 真正读文件并导入。
     *
     * 覆盖规则交给网关：overwrite=true →
     *   uid 相同覆盖，uid 不同添加。
     */
    private fun doImportAccounts(uri: android.net.Uri) {
        val c = ensureClient() ?: return
        val gateway = localBase()
        val doc = try {
            contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            }.orEmpty()
        } catch (t: Throwable) {
            toast("读文件失败：${t.message}")
            return
        }
        if (doc.isBlank()) {
            toast("文件是空的")
            return
        }
        toast("正在导入…")
        Thread({
            val r = c.importAccounts(gateway, prefs.panelPwd(), doc, overwrite = true)
            val msg = if (r == null) {
                "导入失败：没有响应"
            } else if (r.has("error")) {
                "导入失败：" + r.optString("error").take(160)
            } else {
                // 网关把报告放在 result 里（面板那条路），也可能直接平铺
                val rep = r.optJSONObject("result") ?: r
                val added = rep.optJSONArray("added")?.length() ?: 0
                val updated = rep.optJSONArray("updated")?.length() ?: 0
                val skipped = rep.optJSONArray("skipped")?.length() ?: 0
                val invalid = rep.optJSONArray("invalid")?.length() ?: 0
                buildString {
                    append("导入完成：新增 $added，覆盖 $updated")
                    if (skipped > 0) append("，跳过 $skipped")
                    if (invalid > 0) append("，无效 $invalid")
                }
            }
            runOnUiThread {
                accountIoMsg = msg
                toast(msg)
                // 账号变了，调用记录那边的模型名/账号名要跟着刷新
                client = null
                refreshCalls()
            }
        }, "wb-acc-import").start()
    }

    // ---------------------------------------------------------------- 数据迁移

    /** 备份：文件名带包名和时间，方便新旧包区分。 */
    private fun startBackupData() {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        backupDataLauncher.launch("wb2api-data-$stamp.zip")
    }

    private fun startRestoreData() {
        restoreDataLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun doBackupData(uri: android.net.Uri) {
        migrateMsg = "正在备份…"
        Thread({
            val r = try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    com.yunluo.wb2api.agent.DataMigration.backup(this, out)
                } ?: com.yunluo.wb2api.agent.DataMigration.Result(false, "打不开目标文件")
            } catch (t: Throwable) {
                com.yunluo.wb2api.agent.DataMigration.Result(false, "写文件失败：${t.message}")
            }
            runOnUiThread {
                migrateMsg = r.message
                toast(r.message)
            }
        }, "wb-data-backup").start()
    }

    /**
     * 恢复。
     *
     * 恢复完必须重启进程 —— 账号池是常驻内存的，
     * 它下一次落盘会把刚恢复的 accounts.json 冲回旧内容。
     * 这里用 PyRunner.restart：os._exit + 闹钟把界面拉回来。
     */
    private fun doRestoreData(uri: android.net.Uri) {
        migrateMsg = "正在恢复…"
        Thread({
            val r = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    // 端口按新包的默认值写死 —— 备份里带的是旧包的端口，
                    // 原样还原会跟共存的老包抢同一个监听端口。
                    com.yunluo.wb2api.agent.DataMigration.restore(
                        this, input, forcePort = PyRunner.DEFAULT_PORT
                    )
                } ?: com.yunluo.wb2api.agent.DataMigration.Result(false, "打不开这个文件")
            } catch (t: Throwable) {
                com.yunluo.wb2api.agent.DataMigration.Result(false, "读文件失败：${t.message}")
            }
            runOnUiThread {
                migrateMsg = if (r.ok) {
                    r.message + "。App 马上重启，重启完数据就生效了。"
                } else {
                    r.message
                }
                toast(if (r.ok) "恢复完成，正在重启…" else r.message)
                if (r.ok) {
                    // 给界面一点时间把提示画出来再退出
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({
                            try {
                                com.yunluo.wb2api.PyRunner.restart(this)
                            } catch (_: Throwable) {
                                toast("请手动重新打开 App")
                            }
                        }, 1200)
                }
            }
        }, "wb-data-restore").start()
    }

    /** 建工具执行器（只建一次）。 */
    private fun ensureTools(): com.yunluo.wb2api.agent.AgentTools? {
        agentTools?.let { return it }
        if (!rootProbed) {
            rootAvailable = com.yunluo.wb2api.agent.AgentTools.detectRoot()
            rootProbed = true
            com.yunluo.wb2api.agent.AgentTools.setRootHint(rootAvailable)
        }
        val t = com.yunluo.wb2api.agent.AgentTools(
            workDir = com.yunluo.wb2api.agent.AgentTools.defaultWorkDir(),
            useRoot = rootAvailable,
        )
        agentTools = t
        return t
    }

    /**
     * 往最后一行追加文字。
     *
     * assistant=true 追加到正文行（没有就新建一行），false 追加到思考行。
     * 这样思考和正文交替出现时顺序是对的 —— 先思考后正文，而不是混在一起。
     */
    private fun appendToLast(chunk: String, assistant: Boolean) {
        if (chunk.isEmpty()) return
        val entries = chatState.entries.toMutableList()
        val want = if (assistant) AgentEntry.Role.ASSISTANT else AgentEntry.Role.REASONING
        val last = entries.lastOrNull()
        if (last != null && last.role == want) {
            entries[entries.lastIndex] = last.copy(text = last.text + chunk)
        } else {
            // 思考行插在末尾助手行之前，正文行直接追在末尾
            if (!assistant && last?.role == AgentEntry.Role.ASSISTANT && last.text.isEmpty()) {
                entries.add(entries.lastIndex, AgentEntry(want, chunk))
            } else {
                entries.add(AgentEntry(want, chunk))
            }
        }
        chatState = chatState.copy(entries = entries)
    }

    /** 出错就换掉末尾那行空白助手，改成错误行，别两个都留着。 */
    private fun appendError(message: String) {
        val entries = chatState.entries.toMutableList()
        val last = entries.lastOrNull()
        if (last?.role == AgentEntry.Role.ASSISTANT && last.text.isEmpty()) {
            entries[entries.lastIndex] = AgentEntry(AgentEntry.Role.ERROR, message)
        } else {
            entries.add(AgentEntry(AgentEntry.Role.ERROR, message))
        }
        chatState = chatState.copy(entries = entries)
    }

    /**
     * 模型目录。给对话页的模型选择器用。
     *
     * 拉完如果当前会话还没选模型，顺手选第一个 —— 否则发送键是灰的，
     * 用户会以为是坏了。第一个按倍率排（免费的排前面），
     * 拿不到倍率就按 id 排。
     */
    private fun loadModels() {
        val c = ensureClient() ?: return
        io.execute {
            val list = c.models()
            runOnUiThread {
                hubModels = list
                if (chatState.modelId.isEmpty() && list.isNotEmpty()) {
                    val pick = list.sortedBy { it.sortKey }.first().id
                    chatState = chatState.copy(modelId = pick)
                    if (chatState.sessionId.isNotEmpty()) {
                        val sid = chatState.sessionId
                        io.execute { c.patchMeta(sid, model = pick) }
                    }
                }
            }
        }
    }

    /**
     * WebView 的持有者。
     *
     * 为什么需要这个类：
     * Compose 里 AndroidView 的 factory 只在「首次组合」时调用。可是
     * when(currentTab) 的分支一变，整棵子树会从组合里摘掉再重建，
     * factory 就会再跑一次 —— 于是 new 出第二个 WebView 叠在屏幕上。
     *
     * 光把 remember 写在 Pane 里面还不够：remember 只在「同一个调用位置」
     * 内有效，when 的每个分支是独立的 slot table，切分支时 remember 里的
     * 东西照样被回收。所以 Holder 建在 WbRoot 里，作为参数往下传。
     */
    private inner class WebHolder(activity: MainActivity, board: Boolean) {
        val web: WebView = WebView(activity).also { w ->
            // 关键：显式给 MATCH_PARENT。
            //
            // WebView(activity) 创建时没有 parent，layoutParams 是 null；
            // 放进 Compose 的 AndroidViewHolder 时会被塞一个默认的
            // WRAP_CONTENT/WRAP_CONTENT。虽然 Modifier.fillMaxSize() 理论上
            // 传 EXACTLY 约束，但 WebView 的 onMeasure 在非 EXACTLY 时走
            // wrap_content 分支，首帧 / 重建 / addView 那一刻容易走偏 ——
            // 视口高度一旦不确定，网页里 html,body{height:100%} 就失效，
            // .pane{flex:1} 跟着塌，露出 #0E1116，看起来就是 3/4 屏黑块。
            //
            // Java 版没这问题，因为 XML 里写的就是 match_parent/match_parent。
            w.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            if (board) {
                web = w
                setupWeb(w)
            } else {
                webTool = w
                setupToolWeb(w)
            }
        }
    }

    // ---------------------------------------------------------------- WebView

    /**
     * 看板 WebView 的全部配置。
     * 从 Java 的 setupWeb() 逐项搬，一项没删。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
            userAgentString = userAgentString + " WB2API-App/1.0"
            setSafeBrowsingEnabled(false)
        }

        // ---- 滚动行为
        //
        // overScrollMode = NEVER 关掉"滑过头"的效果。
        //
        // WebView 默认是 ALWAYS：滑到列表尽头再拖，内容会继续往边上
        // 走一截再弹回来，那一截露出来的是 WebView 自己的背景色 ——
        // 看板那页背景是深色，所以看起来就是"滑多了、下面变空了"。
        //
        // 关掉之后到边界就停住，不会再多滑出去。
        w.overScrollMode = View.OVER_SCROLL_NEVER

        // 滚动条紧贴内容边缘、不淡出。看板是长列表，有个稳定的
        // 滚动条位置提示比一会儿消失一会儿出现好。
        w.isVerticalScrollBarEnabled = true
        w.isHorizontalScrollBarEnabled = false
        w.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        w.isScrollbarFadingEnabled = false

        // WebView 自己的背景设为页面底色。
        //
        // 万一还是有一瞬间露出底层（比如首帧、或者页面切换的间隙），
        // 露出来的是同色而不是白色 —— 视觉上就察觉不到。
        w.setBackgroundColor(0xFF0E1116.toInt())

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }

        // 页面把面板密码回传给 App，实现下次免登录
        w.addJavascriptInterface(JsBridge(), "WbBridge")

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                retryCount = 0
                boardLoadFailed = false
                // 这两个 HOOK 只是锦上添花，失败不该影响页面
                try { view?.evaluateJavascript(LOGIN_HOOK_JS, null) } catch (_: Throwable) {}
                try { view?.evaluateJavascript(HEADER_HOOK_JS, null) } catch (_: Throwable) {}
                try { view?.evaluateJavascript(OVERSCROLL_FIX_JS, null) } catch (_: Throwable) {}
                // 万一还是命中了 404 页，别让用户看见：自己退回去重新加载
                view?.let { v ->
                    v.evaluateJavascript(
                        "(function(){try{var t=(document.body?document.body.innerText:'').trim();" +
                        "if(t.indexOf('404')>=0&&t.length<400){location.reload();}}catch(e){}})();",
                        null
                    )
                }
            }

            /**
             * 主文档连不上（ERR_CONNECTION_REFUSED 等）时，不甩错误页，自己重试。
             *
             * 用户报的正是这个：
             *   net::ERR_CONNECTION_REFUSED  http://127.0.0.1:8788/?pwd=yunluo
             * 网关进程活着但端口还没在听的时候 loadUrl 就会这样。
             *
             * 处理办法：
             *   · 打上 boardLoadFailed 标记，onReady() 看到标记会强制重载
             *   · 同时自己退回去等一会儿重试，最多 12 次（6 秒）
             */
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return

                boardLoadFailed = true
                retryCount++
                if (retryCount > 12) return

                // 先把遮罩重新拉起来，别让用户盯着那个错误页
                booting = true
                bootFailed = false
                bootStatus = "网关还没起来，正在重试…"

                view?.postDelayed({ view.reload() }, 500L)
            }

            /**
             * 主文档 404 不显示错误页，安静重试。
             *
             * 网关刚启动那一小段窗口里，/ 可能还是 404。此时用户已经看到
             * 启动遮罩撤掉了，如果直接甩一张 404 出去，就是用户抱怨的那个现象。
             * 这里改成隔 500ms 重试，最多 10 次（5 秒）。
             */
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return
                val code = errorResponse?.statusCode ?: return
                if (code != 404 && code != 502 && code != 503) return
                retryCount++
                if (retryCount > 10) return
                view?.postDelayed({
                    view.reload()
                }, 500L)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = request?.url?.let { handleUrl(it) } ?: false

            @Deprecated("兼容旧版本")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                url?.let { handleUrl(Uri.parse(it)) } ?: false
        }

        w.webChromeClient = object : WebChromeClient() {
            // 接管 alert / confirm / prompt：默认实现会弹出
            // 「127.0.0.1:8788 的网页显示：」这种又丑又露地址的标题
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                if (message == null || result == null) return false
                // 弹窗本身抛异常不能让 WebView 崩 —— 兜住，
                // 并且一定要把 JsResult 结掉，否则页面脚本会一直等。
                showJsDialog(
                    JsDialogKind.ALERT, "提示", message, "", showCancel = false, result = result
                )
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                if (message == null || result == null) return false
                // 弹窗本身抛异常不能让 WebView 崩 —— 兜住，
                // 并且一定要把 JsResult 结掉，否则页面脚本会一直等。
                showJsDialog(
                    JsDialogKind.CONFIRM, "确认操作", message, "", showCancel = true, result = result
                )
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?,
                defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                if (message == null || result == null) return false
                showJsDialog(
                    JsDialogKind.PROMPT, "输入", message, defaultValue ?: "", showCancel = true, result = result
                )
                return true
            }

            override fun onJsBeforeUnload(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                if (message == null || result == null) return false
                // 弹窗本身抛异常不能让 WebView 崩 —— 兜住，
                // 并且一定要把 JsResult 结掉，否则页面脚本会一直等。
                showJsDialog(
                    JsDialogKind.CONFIRM, "确认操作", message, "", showCancel = true, result = result
                )
                return true
            }

            override fun onShowFileChooser(
                view: WebView?,
                cb: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try {
                    val i = params?.createIntent()
                    if (i == null) {
                        fileCallback = null
                        cb?.onReceiveValue(null)
                        false
                    } else {
                        startActivityForResult(i, REQ_FILE)
                        true
                    }
                } catch (t: Throwable) {
                    fileCallback = null
                    cb?.onReceiveValue(null)
                    false
                }
            }
        }
    }

    /** 控制台 / 调试台 WebView。跟看板有两处不同：不缓存、JS 桥只提供必要的几个方法。 */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupToolWeb(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // 工具页要看到最新状态，所以不缓存
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = true
            userAgentString = userAgentString + " WB2API-App/1.0"
            setSafeBrowsingEnabled(false)
        }

        /* WB-TOOL-BRIDGE 控制台网页也要桥：
           思考页读/写「对话默认档位」走的是 WbBridge.getThinkLevel /
           setThinkLevel。以前只有看板那个 WebView 注册了桥，那两个方法一直是空的。
           注意必须挂在 WebView 上，不是 WebSettings —— 挂错地方编译能过但运行会崩。 */
        w.addJavascriptInterface(JsBridge(), "WbBridge")

        // 跟看板一样：关掉滑过头。
        // 控制台的调用记录列表和控制台的终端输出都是长内容，
        // 滑到尽头再拖会露出空白，看着像"内容丢了"。
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.isVerticalScrollBarEnabled = true
        w.isHorizontalScrollBarEnabled = false
        w.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        w.isScrollbarFadingEnabled = false
        w.setBackgroundColor(0xFF0E1116.toInt())

        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                toolProgress = 0
                toolProgressVisible = true
                toolError = ""
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                toolProgressVisible = false
                toolRetry = 0
                // 把控制台令牌送回原生层，原生页面才拿得到 /api/* 的数据
                try { view?.evaluateJavascript(TOKEN_BRIDGE_JS, null) } catch (_: Throwable) {}
            }

            /**
             * 主文档 404 / 502 / 503 也重试，跟看板一样处理。
             * 控制台和调试台的端口是网关起来之后再单独起的，
             * 抢跑一步就会 404 —— 这时候静默重试，别把错误糊到用户脸上。
             */
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return
                val code = errorResponse?.statusCode ?: return
                if (code != 404 && code != 502 && code != 503) return
                toolRetry++
                if (toolRetry > 20) {
                    showToolError("HTTP $code（重试多次仍失败）")
                    return
                }
                view?.postDelayed({ view.reload() }, 600L)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // 只处理主文档失败；子资源失败不该糊一屏错误
                if (request?.isForMainFrame != true) return
                toolProgressVisible = false
                val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    error?.description?.toString() ?: "" else ""
                showToolError(desc.ifEmpty { "页面加载失败" })
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val u = request?.url ?: return false
                return !keepInside(u)
            }

            @Deprecated("兼容旧版本")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return url?.let { !keepInside(Uri.parse(it)) } ?: false
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                toolProgress = newProgress
                toolProgressVisible = newProgress < 100
            }

            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                if (message == null || result == null) return false
                // 弹窗本身抛异常不能让 WebView 崩 —— 兜住，
                // 并且一定要把 JsResult 结掉，否则页面脚本会一直等。
                showJsDialog(
                    JsDialogKind.ALERT, "提示", message, "", showCancel = false, result = result
                )
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                if (message == null || result == null) return false
                // 弹窗本身抛异常不能让 WebView 崩 —— 兜住，
                // 并且一定要把 JsResult 结掉，否则页面脚本会一直等。
                showJsDialog(
                    JsDialogKind.CONFIRM, "确认操作", message, "", showCancel = true, result = result
                )
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?,
                defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                if (message == null || result == null) return false
                showJsDialog(
                    JsDialogKind.PROMPT, "输入", message, defaultValue ?: "", showCancel = true, result = result
                )
                return true
            }

            override fun onShowFileChooser(
                view: WebView?,
                cb: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try {
                    val i = params?.createIntent()
                    if (i == null) { fileCallback = null; cb?.onReceiveValue(null); false }
                    else { startActivityForResult(i, REQ_FILE); true }
                } catch (t: Throwable) {
                    fileCallback = null; cb?.onReceiveValue(null); false
                }
            }
        }
    }

    /** 工具页的链接是否留在 App 内。 */
    private fun keepInside(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme == "http" || scheme == "https") {
            val host = uri.host ?: ""
            // 本机的三个端口留在内部；外链（比如模型文档）交给浏览器
            return host == "127.0.0.1" || host == "localhost"
        }
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun showToolError(msg: String) {
        toolError = msg
        toolProgressVisible = false
    }

    /** 看板的链接一律留在 WebView 里：OAuth 回调打回 127.0.0.1 时不能跳出 App。 */
    private fun handleUrl(uri: Uri): Boolean {
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme == "http" || scheme == "https") return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Throwable) {
            true
        }
    }

    // ---------------------------------------------------------------- 页签

    private fun showTool(tab: WbTab) {
        currentTab = tab
    }


    /**
     * 注入到控制台页面里切页签的脚本。
     *
     * WB-CONSOLE-TAB-HYBRID
     * app96 那一版为了绕开"注入不可靠"，改成每次切页签都整页重载。
     * 副作用是：密钥页每次都要重新发一次 /api/keys，多一次挂住的机
     * 会（用户看到的就是"点了密钥一直加载中"）。
     *
     * 现在真正的病根已经找到并修掉了：wb_console.py 里那段
     * document.getElementById('cmd').addEventListener(...) 是空指针，
     * 它在脚本顶层抛错，导致后面所有代码（包括读 URL 参数那段）都不执行。
     * 病因既然没了，注入就可以用了。
     *
     * 这里仍然保留重试：App 调用 evaluateJavascript 的时刻，
     * 页面脚本可能还没执行到 function pick(...) 那一步。
     * 先试一次，不行就每 80ms 再试，最多 50 次（约 4 秒）。
     */
    private fun jsPick(which: String): String {
        return "(function(){" +
                "var w='" + which + "';var n=0;" +
                "function go(){try{if(typeof pick==='function'){pick(w);return true}}" +
                "catch(e){}return false}" +
                "if(go())return;" +
                "var t=setInterval(function(){n++;if(go()||n>50){clearInterval(t)}},80)" +
                "})();"
    }

    /**
     * 哪个页签对应哪个网页。
     *
     * 对话和调用记录现在是原生页面，不走这里 —— 返回空串表示
     * 「这个页签没有网址」，调用方会据此跳过加载。
     */
    private fun toolUrl(tab: WbTab): String = when (tab) {
        WbTab.Chat -> ""
        WbTab.Calls -> ""
        WbTab.Board -> localBase()
        // WB-CONSOLE-ONE-PAGE
        // 控制台返回纯地址。页签切换不走 URL —— 见 ToolPane 里那段：
        // WebView 已经加载过时，直接 evaluateJavascript 调网页的 pick()。
        // 之前靠 URL 参数切页签，实测点了「思考」还是显示「密钥」：
        // 页面已加载的情况下 loadUrl 换 query 不一定真的重新执行页面脚本。
        WbTab.Console -> consoleUrl()
        WbTab.Settings -> ""            // 设置是原生页，没有网址
    }

    // ---------------------------------------------------------------- 启动

    private fun startServiceCompat() {
        val i = Intent(this, GatewayService::class.java)
        i.action = GatewayService.ACTION_START
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (t: Throwable) {
            toast("服务启动失败：${t.message}")
        }
    }

    /**
     * 启动轮询。
     *
     * healthy() 是同步 HTTP，最长堵 1 秒多，绝不能放主线程。
     * 之前在主线程调过，会卡首帧甚至 ANR —— 所以整个循环放后台线程，
     * 只有改 UI 状态的那几下切回主线程。
     */
    private fun beginBoot() {
        // 注意：这里不能只看 PyRunner.isRunning()。
        // 进程活着 ≠ 端口已经在听。从 Python 起来到 HTTP 端口 accept
        // 之间有几百毫秒到几秒的空窗，这会儿 loadUrl 就是
        // net::ERR_CONNECTION_REFUSED —— 用户抱怨的就是这个。
        // 必须真的探一次端口。healthy() 是同步网络调用，不能放主线程，
        // 所以整个判断都挪到下面的轮询线程里去。
        if (polling) return
        polling = true

        booting = true
        bootFailed = false
        bootStatus = "正在启动网关…"
        bootLog = ""

        val app: Context = applicationContext
        Thread({
            try {
                for (i in 0 until 300) {
                    val phase = PyRunner.phase()
                    val err = PyRunner.error()
                    val tail = PyRunner.tail(app, 60)
                    runOnUiThread {
                        bootStatus = phase
                        bootLog = tail
                    }
                    if (err.isNotEmpty()) {
                        runOnUiThread { showFailure() }
                        return@Thread
                    }
                    if (healthy(400)) {
                        runOnUiThread { onReady() }
                        return@Thread
                    }
                    if (!PyRunner.isRunning() && !PyRunner.isStarting() && i > 8) {
                        runOnUiThread { showFailure() }
                        return@Thread
                    }
                    Thread.sleep(400)
                }
                runOnUiThread { showFailure() }
            } catch (_: InterruptedException) {
                // 正常退出路径，不做处理
            } finally {
                polling = false
            }
        }, "wb2api-poll").start()
    }

    /**
     * 网关到底能不能用了。
     *
     * 这里必须要求 HTTP 200，不能像原来那样 responseCode > 0 就算过。
     * 原来的写法有个坑：网关进程刚起来、路由还没注册完时，
     * /health 会先返回 404 —— 那也是「有响应」，判成健康，
     * 于是界面立刻 loadUrl 看板，用户看到的就是一个 404 页，
     * 手动刷新一下才正常。
     *
     * 现在两道都过才算好：
     *   ① /health 必须 200
     *   ② 看板根路径 / 也必须 200（这才是用户真正要打开的那个页面）
     */
    private fun healthy(timeoutMs: Int): Boolean {
        return probe(localBase() + "health", timeoutMs) == 200 &&
                probe(localBase(), timeoutMs) == 200
    }

    /** 发一个 GET，返回状态码；任何异常都返回 -1。 */
    private fun probe(url: String, timeoutMs: Int): Int {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            c.responseCode
        } catch (_: Throwable) {
            -1
        } finally {
            try { c?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** 界面侧统一用这个基地址，永远跟 prefs.port() 一致。 */
    private fun localBase(): String = "http://127.0.0.1:${prefs.port()}/"

    /**
     * 网关确实能用了，撤掉启动遮罩。
     *
     * 这个函数只在轮询线程里探到 200 之后才被调用（通过 runOnUiThread），
     * 所以这里不再重复探测。
     *
     * 但有个细节要注意：看板 WebView 如果已经加载过、而且当前停在
     * 一个错误页（ERR_CONNECTION_REFUSED），光看 url 前缀会以为「已经加载过了」
     * 而不重载，用户就一直卡在那个错误页上。所以这里顺手判一下
     * 是不是错误页 —— 如果是，强制重载。
     */
    private fun onReady() {
        booting = false
        bootFailed = false

        val w = web
        val url = localBase()
        if (w != null) {
            val cur = w.url
            val stuck = cur == null || !cur.startsWith(url)
            // 页面没加载 / 加载失败时 url 一般还是目标地址，但页面内容是错误页。
            // 用 onPageFinished 里记录的标记判断，比猜 url 靠谱。
            if (stuck || boardLoadFailed) {
                boardLoadFailed = false
                w.loadUrl(loginUrl())
            }
        }
        // 网关这次是不是按局域网配置起来的，实测一下
        probeLanLive()
    }

    private fun showFailure() {
        booting = true
        bootFailed = true
        val err = PyRunner.error()
        bootStatus = "启动失败"
        bootLog = (if (err.isEmpty()) "" else "错误：$err\n\n") +
                PyRunner.tail(applicationContext, 80)
    }

    // ---------------------------------------------------------------- 菜单

    private fun showMenu(anchor: View) {
        val items = arrayOf("复制 Key", "设置", "日志", "电池优化", "关于", "刷新")
        android.widget.PopupMenu(this, anchor).apply {
            items.forEachIndexed { i, s -> menu.add(0, i, i, s) }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> copyKeys()
                    1 -> showSettings()
                    2 -> showLogs()
                    3 -> openBatterySettings()
                    4 -> showAbout()
                    5 -> { web?.reload(); webTool?.reload() }
                }
                true
            }
            show()
        }
    }

    // ---------------------------------------------------------------- 地址

    /**
     * 带面板密码的看板地址。
     *
     * 用户在面板里手动登录过一次，页面会通过 WbBridge 把密码回传存下来，
     * 之后每次启动直接用 ?pwd= 免登录。编码失败就退回不带密码的地址 ——
     * 顶多让用户再输一次，不能因此白屏。
     */
    private fun loginUrl(): String {
        val base = localBase()
        return try {
            base + "?pwd=" + java.net.URLEncoder.encode(prefs.panelPwd(), "UTF-8")
        } catch (_: Throwable) {
            base
        }
    }

    /** 思考调试台端口：优先读网关写下的 port 文件，读不到就按 网关+1 推。 */
    private fun debugPort(): Int {
        return try {
            val f = File(PyRunner.codeDir(this), "wb_app/control.port")
            if (f.exists()) {
                val v = readAll(f).trim().toInt()
                if (v in 1..65535) return v
            }
            prefs.port() + 1
        } catch (_: Throwable) {
            prefs.port() + 1
        }
    }

    private fun debugUrl(): String = "http://127.0.0.1:${debugPort()}/"

    /** 内置控制台（密钥 + 调用记录 + 终端）端口 = 网关 + 2 */
    private fun consolePort(): Int {
        return try {
            val f = File(PyRunner.codeDir(this), "wb_app/console.port")
            if (f.exists()) {
                val v = readAll(f).trim().toInt()
                if (v in 1..65535) return v
            }
            prefs.port() + 2
        } catch (_: Throwable) {
            prefs.port() + 2
        }
    }

    private fun consoleUrl(): String = "http://127.0.0.1:${consolePort()}/"

    /** 手机当前内网 IPv4，优先 wlan；拿不到就返回占位文案。 */
    private fun localIp(): String {
        return try {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces()
            var wifi: String? = null
            var other: String? = null
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement() ?: continue
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: ""
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val ia = addrs.nextElement() ?: continue
                    if (ia.isLoopbackAddress || ia is java.net.Inet6Address) continue
                    val ip = ia.hostAddress ?: continue
                    if (ip.isEmpty()) continue
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")) {
                        if (wifi == null) wifi = ip
                    } else if (other == null) {
                        other = ip
                    }
                }
            }
            wifi ?: other ?: "手机IP"
        } catch (_: Throwable) {
            "手机IP"
        }
    }

    private fun lanUrl(): String = "http://${localIp()}:${prefs.port()}/"

    // ---------------------------------------------------------------- 复制

    private fun copyKeys() {
        var keys = activeKeys()
        if (keys.isEmpty()) {
            keys = "（还没生成 API Key）\n现在 /v1 开放，不需要 Key；要加锁就到面板「设置」页生成一个。"
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(
            android.content.ClipData.newPlainText("WorkBuddyAPI Key", keys)
        )
        toast("已复制到剪贴板")
    }

    /**
     * 从 settings.json 里把所有启用的 Key 抠出来。
     *
     * 兼容两种写法：
     *   api_key_set + api_key     —— 单 Key 时代的老字段
     *   api_keys[]                —— 多 Key 时代的数组
     */
    private fun activeKeys(): String {
        val f = File(PyRunner.accountsDir(this), "settings.json")
        if (!f.exists()) return ""
        return try {
            val o = org.json.JSONObject(readAll(f))
            val out = mutableListOf<String>()

            if (o.optBoolean("api_key_set")) {
                val k = o.optString("api_key", "").trim()
                if (k.isNotEmpty()) out.add(k)
            }

            val arr = o.optJSONArray("api_keys")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    if (!e.optBoolean("enabled", true)) continue
                    val k = e.optString("key", e.optString("value", "")).trim()
                    val name = e.optString("name", "")
                    if (k.isNotEmpty()) out.add(if (name.isEmpty()) k else "$name = $k")
                }
            }

            out.joinToString("\n").trim()
        } catch (_: Throwable) {
            ""
        }
    }

    // ---------------------------------------------------------------- 对话框

    private fun showSettings() {
        val sheet = SettingsSheet(
            activity = this@MainActivity,
            prefs = prefs,
            localIp = localIp(),
            onSaved = { portChanged, pwdChanged ->
                when {
                    portChanged -> toast("端口/局域网已改，正在重启…")
                    pwdChanged -> toast("密码已记住")
                    else -> toast("已保存")
                }
            }
        )
        sheet.show()
    }

    private fun showLogs() {
        val text = PyRunner.tail(applicationContext, 400)
        WbInfoDialog.show(
            activity = this@MainActivity,
            title = "运行日志",
            body = text,
            copyLabel = "复制",
            onCopy = {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("log", text))
                toast("日志已复制")
            }
        )
    }

    private fun showAbout() {
        val pwdState = if (prefs.panelPwd() == Prefs.DEFAULT_PANEL_PWD) "默认 admin" else "自定义"
        val think = if (ThinkConfig.enabled(this))
            ThinkConfig.mode(this) + (if (ThinkConfig.forceOff(this)) "（强制关闭）" else "")
        else "未启用（走上游默认 high）"

        val app = this@MainActivity
        val txt = buildString {
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("内嵌 Python：Chaquopy 16.1.0 / CPython 3.11")
            appendLine("网关上游：workbuddy2api-hub v1.8.0 (MIT)")
            appendLine()
            appendLine("面板：${PyRunner.baseUrl()}")
            appendLine("API：${PyRunner.baseUrl()}v1")
            appendLine(if (prefs.lan()) "局域网：${lanUrl()}" else "局域网：未开启")
            appendLine("端口：${prefs.port()}")
            appendLine("面板密码：已记住（$pwdState）")
            appendLine("思考档位：$think")
            appendLine("思考调试台：${debugUrl()}")
            appendLine("内置控制台：${consoleUrl()}（对话 + 终端）")
            appendLine("数据目录：${PyRunner.codeDir(app).absolutePath}")
            appendLine()
            append("账号通过面板「+ 添加账号 (OAuth)」授权。")
        }

        WbInfoDialog.show(
            activity = this@MainActivity,
            title = "关于",
            body = txt
        )
    }

    private fun copyLanUrl() {
        val url = lanUrl()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("WorkBuddyAPI", url))
        toast(if (prefs.lan()) "已复制： $url" else "已复制（局域网还没开，先在设置里打开）")
    }

    private fun readAll(f: File): String {
        val sb = StringBuilder()
        f.bufferedReader().use { r ->
            val buf = CharArray(8192)
            while (true) {
                val n = r.read(buf)
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 杂项

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 这个包是否已经在电池优化白名单里。 */
    private fun isBatteryExempt(): Boolean = try {
        val pm = getSystemService(android.os.PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(packageName) == true
    } catch (_: Throwable) {
        false
    }

    /**
     * WB-BATTERY 申请电池优化豁免。
     *
     * 优先弹系统那个「是否允许后台…」的确认框（ACTION_REQUEST_IGNORE_
     * BATTERY_OPTIMIZATIONS，需要清单里声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * 权限，我们已经有）。
     *
     * 失败就退到两个更弱的入口：
     *   ① 本应用的详情页（用户在「电池」那一栏自己点）
     *   ② 电池优化列表页
     * 之所以要退化：国产 ROM 经常把这个 action 拦掉。
     * 做法参考 WorkBuddy-Hub 的 requestBatteryExemption()。
     */
    private fun requestBatteryExemption() {
        val pkgUri = android.net.Uri.parse("package:$packageName")
        val tries = listOf(
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                pkgUri
            ),
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                pkgUri
            ),
            Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        for (it in tries) {
            try {
                startActivity(it)
                return
            } catch (_: Throwable) {
                // 下一个
            }
        }
        toast("打不开电池优化设置，请到系统设置里手动允许后台运行")
    }

    /** 兼容旧调用（菜单里那个「电池优化」）。 */
    private fun openBatterySettings() = requestBatteryExemption()

    override fun onResume() {
        super.onResume()
        // 切回来时系统会把系统栏恢复，这里再藏一次
        hideSystemBars()
        // 回到前台时如果还在启动，重新探一次。
        // 这里同样不能只看 PyRunner.isRunning() —— 见 beginBoot 的注释。
        // beginBoot 内部会先探端口再决定要不要撤遮罩，
        // 而且它自己是后台线程跑的，不会卡住 onResume。
        if (booting) beginBoot()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        /* 优先让 WebView 后退（页面内部有层级），退不动再交给系统。
           
           WB-BACK-CONSOLE
           控制台例外：每次切「密钥 / 思考」都会 loadUrl 一个新地址
           （带 ?tab= 和时间戳），所以它的历史栈里全是这种"同一个页面的
           不同参数"。按返回键如果先 goBack，就会退到上一个参数版本 ——
           看起来像"页面自己跳回去了"，很怪。
           这里直接跳过控制台的后退，让它走下面的"回看板"逻辑。 */
        val w = if (currentTab == WbTab.Board) web else webTool
        if (currentTab != WbTab.Console && w != null && w.canGoBack()) {
            w.goBack()
            return
        }
        if (currentTab != WbTab.Board) {
            showTool(WbTab.Board)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val cb = fileCallback ?: return
        fileCallback = null
        cb.onReceiveValue(
            if (resultCode == Activity.RESULT_OK && data != null) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else null
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 通知栏点进来时不需要额外动作，前台服务已经把网关拉起来了
    }

    /** 面板密码回传通道。 */
    private inner class JsBridge {
        /**
         * 控制台页面把自己的令牌回传给原生层。
         *
         * 为什么需要这条：原生侧读 files/app/wb_app/console.token 这条路
         * 在某些设备上读不到（应用私有目录 + 时序问题），
         * 而页面自己一定拿得到 —— 它就写在 <meta name="wb-token"> 里。
         * 让页面送过来，比原生去猜文件什么时候写好稳得多。
         */
        @android.webkit.JavascriptInterface
        fun saveToken(tk: String?) {
            val v = tk?.trim() ?: return
            if (v.isEmpty() || v.length > 200) return
            if (v == WbApi.token) return
            WbApi.token = v
            // 令牌变了就丢掉旧客户端，下次请求用新的重建
            client = null
            io.execute {
                val c = ensureClient() ?: return@execute
                val page = c.calls()
                runOnUiThread {
                    if (page.records.isNotEmpty() || callsPage.records.isEmpty()) {
                        callsPage = page
                    }
                }
            }
        }

        /**
         * WB-THINK-MERGE 控制台「思考」页读 App 侧的对话默认档位。
         *
         * 那一页现在是控制台自己渲染的 HTML（同源加载），
         * 所以能直接用这个桥 —— 之前是 iframe 跨端口，读不到。
         */
        @android.webkit.JavascriptInterface
        fun getThinkLevel(): String = prefs.thinkLevel()

        /** 控制台「思考」页改对话默认档位。 */
        @android.webkit.JavascriptInterface
        fun setThinkLevel(lv: String?) {
            val v = (lv ?: "").trim().lowercase()
            if (v != "off" && v != "low" && v != "high" && v != "max") return
            runOnUiThread {
                prefs.setThinkLevel(v)
                thinkLevel = v
                chatState = chatState.copy(effort = v)
            }
        }

        /**
         * WB-CHANNEL-LOGIN 渠道页点「登录」→ 打开登录窗口。
         *
         * 渠道页是网页，开不了 App 里的 WebView，所以走桥。
         * url 是起始地址（渠道的站点首页），用户在窗口里自己登。
         */
        @android.webkit.JavascriptInterface
        fun openLogin(id: String?, url: String?) {
            val cid = (id ?: "").trim()
            if (cid.isEmpty()) return
            runOnUiThread {
                channelLoginId = cid
                channelLoginUrl = (url ?: "").trim().ifEmpty { "https://www.baidu.com" }
                channelLoginMsg = ""
            }
        }

        @android.webkit.JavascriptInterface
        fun savePanelPassword(pw: String?) {
            val v = pw?.trim() ?: return
            if (v.isEmpty() || v.length > 200) return
            runOnUiThread {
                val cur = web?.url
                if (cur == null || !cur.startsWith(localBase())) return@runOnUiThread
                if (v != prefs.panelPwd()) {
                    prefs.setPanelPwd(v)
                    toast("面板密码已记住，下次免登录")
                }
            }
        }
    }

    companion object {
        private const val REQ_FILE = 4711
    }
}

/** 一次等待用户确认的工具调用。 */
data class ApprovalRequest(
    val summary: String,
    val warning: String?,
    /** 后台线程在等这个结果。 */
    val answer: java.util.concurrent.CompletableFuture<Boolean>,
)
