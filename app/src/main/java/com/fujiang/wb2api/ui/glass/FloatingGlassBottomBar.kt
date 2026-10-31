package com.fujiang.wb2api.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.fujiang.wb2api.WbAccent
import com.fujiang.wb2api.WbCard2
import com.fujiang.wb2api.WbMuted
import com.fujiang.wb2api.WbText
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * 悬浮玻璃胶囊底栏。
 *
 * 这是 NPatch-miuix 的 FloatingGlassBottomBar.kt 的原样移植
 * （manager/.../ui/component/FloatingGlassBottomBar.kt，448 行）。
 *
 * 只改了这几处，其余一行没动：
 *   · COUITheme.colorScheme.primary        → WbAccent
 *   · COUITheme.colorScheme.surfaceContainer → WbCard2
 *   · COUITheme.colorScheme.onSurface      → WbText
 *   · io.github.suqi8.coui 的 Icon/Text    → material3 的同名组件
 *   · isSystemInDarkTheme()                → 这个 App 只有深色，实际不用
 *
 * 玻璃效果是真的：kyant.backdrop 的 blur / lens / vibrancy /
 * Highlight / Shadow / InnerShadow 全部保留。上一版我拿渐变凑，
 * 那就是"不好看、没感觉"的根源 —— 那个库做的就是这件事，
 * 自己拿 Canvas 画是画不出来的（模糊要采样背景，渐变给不了）。
 *
 * 拖动也是真的：DampedDragAnimation 连速度跟踪、按压回弹阈值、
 * X/Y 不同阻尼一起搬过来了，滑块可以按住拖到任意页签。
 */

private val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }
private val FloatingBottomBarHorizontalPadding = 4.dp
private val FloatingBottomBarVerticalPadding = 4.dp

@Composable
fun RowScope.FloatingGlassBottomBarItem(
    onClick: () -> Unit,
    selected: Boolean = false,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalFloatingBottomBarTabScale.current
    /* WB-BAR-ALIGN
       原来这里写 defaultMinSize(minWidth = 76.dp)。
       5 个页签就是 380dp，而手机竖屏底栏内容宽只有约 344dp ——
       页签按 76dp 硬排会超出底栏，可选中药丸是按「可用宽/5」
       （约 68.8dp）步进的，两者对不上。
       结果：索引越大偏得越多，最后一个页签几乎跑到栏外，
       药丸落在空隙里，看着就像"一块白底飘在那儿"。

       现在宽度由调用方的 Modifier.weight(1f) 等分，
       和 tabWidthPx 的计算完全一致，药丸就不会漂了。 */
    Column(
        modifier
            .fillMaxHeight()
            .clip(CircleShape)
            .clearAndSetSemantics {
                this.selected = selected
                contentDescription = label
            }
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

/**
 * @param backdrop 要模糊的背景。调用方用 rememberLayerBackdrop { drawRect(bg); drawContent() }
 *                 把整页内容画进去，这里才能采样到。
 * @param isBlurEnabled 关掉就走不模糊的降级样式。
 */
@Composable
fun FloatingGlassBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop?,
    tabsCount: Int,
    isBlurEnabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    // 没有 backdrop 时不再走另一套 fallback 布局 —— 那套是原版给
    // 「彻底不要玻璃」的场景准备的，它的 Box 宽度靠 IntrinsicSize 算，
    // 跟这里 weight + defaultMinSize 的页签合不来，会算出 0 宽，
    // 屏幕上就是"底栏消失了"。
    //
    // 现在统一走同一条渲染路径，区别只在开不开模糊：
    //   backdrop 有 + isBlurEnabled  → 真玻璃（模糊、透镜、高光、阴影）
    //   backdrop 没有                → 同一条路径，但模糊动作为空
    //
    // 这样五个页签永远长得一样，不会出现某几页没有胶囊。
    // 现在无论有没有真实 backdrop 都能出玻璃效果：
    // 有真 backdrop 就模糊真实内容，没有就模糊假背景。
    // 所以这里只看 isBlurEnabled，不再要求 backdrop 非空。
    val canBlur = isBlurEnabled

    val accentColor = WbAccent

    // 底色一律用薄的（0.18）。原来的思路是"不模糊就把底加厚到 0.96"，
    // 结果后三页看起来就是一块死板的纯色板，没有玻璃的通透感。
    // 现在改成：底始终薄，靠假背景层提供"内容感"。
    val containerColor = WbCard2.copy(alpha = 0.18f)

    /**
     * 假背景。
     *
     * 后三页（看板 / 控制台 / 调试台）是 WebView，采样不到它的内容。
     * 但我们不是要"没有效果"，是要"有玻璃效果、但不显示背后的东西"。
     *
     * 玻璃的观感 90% 来自它自己的光学处理：
     *   · vibrancy   让采样到的颜色更饱和
     *   · lens       边缘折射（把背景压变形）
     *   · blur       磨砂
     *   · Highlight  上缘那条亮线
     *   · Shadow / InnerShadow  外投影和内部收边
     *
     * 这些作用在一层假的、有明暗层次的背景上，效果一样成立 ——
     * 就像对着磨砂玻璃看一面刷了漆的墙：墙后面什么都没有，
     * 但玻璃的高光、折射、磨砂质感全在。
     *
     * 所以这里画一层有纵向明暗变化的深色，代替真实内容。
     * 上层再叠一点点极淡的色块，让 lens 折射时能看出形变
     * （纯平的色块折射看不出来）。
     */
    val fakeBackdrop = rememberLayerBackdrop {
        // 主体：纵向明暗渐变，模拟"背后有个有层次的东西"
        drawRect(
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF1B222B),
                    Color(0xFF12171E),
                    Color(0xFF0D1117),
                )
            )
        )
        // 极淡的斜向色带。只为让 lens 有东西可折射 ——
        // 折射是"把采样到的像素按球面位移"，纯平色块位移了也看不出来，
        // 加点结构才能看出玻璃边是弯的。
        drawRect(
            Brush.linearGradient(
                colors = listOf(
                    WbAccent.copy(alpha = 0.06f),
                    Color.Transparent,
                    WbAccent.copy(alpha = 0.03f),
                )
            )
        )
    }

    val blurBackdrop = backdrop ?: fakeBackdrop

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                with(density) {
                    FloatingBottomBarHorizontalPadding.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
    }

    var currentIndex by remember(selectedIndex) { mutableIntStateOf(selectedIndex()) }

    class DampedDragAnimationHolder {
        var instance: DampedDragAnimation? = null
    }

    val holder = remember { DampedDragAnimationHolder() }
    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex().toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset ->
                val anim = holder.instance ?: return@DampedDragAnimation true
                if (tabWidthPx == 0f) return@DampedDragAnimation false

                val currentValue = anim.value
                val indicatorX = currentValue * tabWidthPx
                val padding = with(density) { FloatingBottomBarHorizontalPadding.toPx() }
                val globalTouchX = if (isLtr) {
                    padding + indicatorX + offset.x
                } else {
                    totalWidthPx - padding - tabWidthPx - indicatorX + offset.x
                }
                globalTouchX in 0f..totalWidthPx
            },
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            }
        ).also { holder.instance = it }
    }

    LaunchedEffect(selectedIndex) {
        snapshotFlow { selectedIndex() }.collectLatest { currentIndex = it }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
            dampedDragAnimation.animateToValue(index.toFloat())
            onSelected(index)
        }
    }

    val interactiveHighlight = remember(animationScope, tabWidthPx, isLtr) {
        // glowColor 用主题绿（WB-BAR-NO-WHITE）
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f
                )
            },
            glowColor = WbAccent,
        )
    }

    Box(
        // 不用 IntrinsicSize.Min。
        //
        // 原版用它是因为它的页签有固有宽度。这里页签是 weight(1f) +
        // defaultMinSize，IntrinsicSize 量不出宽度 —— 算成 0 的话
        // 整个底栏就宽度为零，屏幕上只剩文字和图标飘着，没有胶囊。
        // 宽度交给调用方给（外层 fillMaxWidth + padding），
        // 底栏自己撑满。
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier
                .clearAndSetSemantics {}
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { FloatingBottomBarHorizontalPadding.toPx() * 2f }
                    tabWidthPx = contentWidthPx / tabsCount
                }
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = blurBackdrop,
                    shape = { CircleShape },
                    effects = {
                        if (canBlur) {
                            vibrancy()
                            blur(4.dp.toPx())
                            lens(24.dp.toPx(), 24.dp.toPx())
                        }
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = if (canBlur) 1f else 0f)
                    },
                    shadow = {
                        Shadow.Default.copy(
                            color = Color.Black.copy(0.12f),
                        )
                    },
                    layerBlock = {
                        if (canBlur) {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(if (canBlur) interactiveHighlight.modifier else Modifier)
                .height(64.dp)
                .padding(horizontal = FloatingBottomBarHorizontalPadding, vertical = FloatingBottomBarVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalFloatingBottomBarTabScale provides {
                if (canBlur) lerp(1f, 1.2f, dampedDragAnimation.pressProgress) else 1f
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .semantics { invisibleToUser() }
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = blurBackdrop,
                        shape = { CircleShape },
                        effects = {
                            if (canBlur) {
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                                blur(4.dp.toPx())
                                lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = if (canBlur) dampedDragAnimation.pressProgress else 0f)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(if (canBlur) interactiveHighlight.modifier else Modifier)
                    .height(56.dp)
                    .padding(horizontal = FloatingBottomBarHorizontalPadding)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        if (tabWidthPx > 0f) {
            Box(
                Modifier
                    .padding(horizontal = FloatingBottomBarHorizontalPadding)
                    .graphicsLayer {
                        val contentWidth = totalWidthPx - with(density) { FloatingBottomBarHorizontalPadding.toPx() * 2f }
                        val singleTabWidth = contentWidth / tabsCount
                        val progressOffset = dampedDragAnimation.value * singleTabWidth

                        translationX = if (isLtr) {
                            progressOffset + panelOffset
                        } else {
                            -progressOffset + panelOffset
                        }
                    }
                    .then(if (canBlur) interactiveHighlight.gestureModifier else Modifier)
                    .then(dampedDragAnimation.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(blurBackdrop, tabsBackdrop),
                        shape = { CircleShape },
                        effects = {
                            if (canBlur) {
                                val progress = dampedDragAnimation.pressProgress
                                lens(10.dp.toPx() * progress, 14.dp.toPx() * progress, true)
                            }
                        },
                        /* WB-BAR-NO-WHITE 第二版：三个全关
                           
                           上一版把 Highlight 压到 0.30 还是白的。
                           原因是这三个效果的默认色在库里定好了，
                           我只能盖 alpha，盖不掉颜色：
                             · Highlight.Default  → 白色渐变（上缘高光）
                             · Shadow / InnerShadow → 默认色也不是我们能控的
                           而且它们不在 layerBlock 的缩放里 ——
                           一按药丸放大，它们还按原尺寸画，于是看起来
                           就是"下面又冒出一块一模一样的白影子"。

                           深色主题不需要那种"玻璃上缘亮线"，
                           索性三个全关：0 alpha。
                           
                           保留的部分：
                             · lens        按下时的球形折射（不是白色）
                             · onDrawSurface  主题绿底 + 6% 白
                             · layerBlock  缩放跟手
                           
                           观感是"一块淡绿色的玻璃被按下去、边缘折射变形"，
                           没有白。 */
                        highlight = { Highlight.Default.copy(alpha = 0f) },
                        shadow = {
                            Shadow.Default.copy(
                                color = Color.Black.copy(0.12f),
                                alpha = if (canBlur) dampedDragAnimation.pressProgress else 0f,
                            )
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 8.dp * dampedDragAnimation.pressProgress,
                                alpha = 0f
                            )
                        },
                        layerBlock = {
                            if (canBlur) {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            }
                        },
                        /* WB-BAR-NO-WHITE
                           选中态底色原来是 Color.White.copy(0.1f) —— 深色主题上
                           就是一块淡白。现在改主题绿的低透明度：
                           既表明"这个页签被选中"，又不会在暗底上突兀。
                           按下时叠一点点亮，模拟按压反馈，但只有 6% 白。 */
                        onDrawSurface = {
                            val progress = if (canBlur) dampedDragAnimation.pressProgress else 0f
                            drawRect(color = WbAccent.copy(alpha = 0.16f), alpha = 1f)
                            drawRect(color = Color.White.copy(alpha = 0.06f), alpha = progress)
                        }
                    )
                    .height(56.dp)
                    .width(
                        with(density) {
                            ((totalWidthPx - FloatingBottomBarHorizontalPadding.toPx() * 2f) / tabsCount).toDp()
                        }
                    )
            )
        }
    }
}

/**
 * 不模糊时的样式：纯色胶囊。
 *
 * 注意这里不能用 width(IntrinsicSize.Min)。
 *
 * 原版这么写是因为它的内容是有固有宽度的图标 + 文字。
 * 但这里的页签是 weight(1f) 等分，
 * weight 在 IntrinsicSize 测量里不生效，
 * 最后算出来是 0 或极窄 —— 屏幕上就是"没有胶囊"，只剩图标飘着。
 *
 * 所以宽度直接用 fillMaxWidth，由外层决定。
 */
@Composable
private fun FloatingGlassBottomBarFallback(
    tabsCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val barColor = WbCard2.copy(alpha = 0.96f)
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(barColor)
                .height(64.dp)
                .padding(
                    horizontal = FloatingBottomBarHorizontalPadding,
                    vertical = FloatingBottomBarVerticalPadding
                ),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun FloatingGlassBottomBarIcon(
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
) {
    Icon(
        imageVector = if (selected) selectedIcon else unselectedIcon,
        contentDescription = null,
        tint = WbText
    )
}

@Composable
fun FloatingGlassBottomBarLabel(
    label: String
) {
    Text(
        text = label,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = WbText,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible
    )
}
