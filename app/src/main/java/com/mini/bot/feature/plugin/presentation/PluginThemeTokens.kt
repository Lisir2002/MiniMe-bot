package com.mini.bot.feature.plugin.presentation

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.mini.bot.core.theme.AppSemanticColors
import com.mini.bot.core.theme.Radius
import com.mini.bot.core.theme.Spacing
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 插件页面可见的设计 token。颜色、间距、圆角都从当前 Compose 主题计算，
 * 页面只依赖 CSS 变量；主题切换时宿主更新变量即可，不重新加载页面。
 *
 * 变量名是插件作者可见的稳定接口，改动等于破坏插件页面，新增变量只能追加。
 */
object PluginThemeTokens {

    fun isDark(scheme: ColorScheme): Boolean = scheme.background.luminance() < 0.5f

    fun variables(
        scheme: ColorScheme,
        semantic: AppSemanticColors,
        fontScale: Float
    ): Map<String, String> = linkedMapOf(
        "--minibot-bg" to scheme.background.toCss(),
        "--minibot-surface" to scheme.surface.toCss(),
        "--minibot-surface-variant" to scheme.surfaceVariant.toCss(),
        "--minibot-on-surface" to scheme.onSurface.toCss(),
        "--minibot-on-surface-variant" to scheme.onSurfaceVariant.toCss(),
        "--minibot-primary" to scheme.primary.toCss(),
        "--minibot-on-primary" to scheme.onPrimary.toCss(),
        "--minibot-primary-container" to scheme.primaryContainer.toCss(),
        "--minibot-primary-soft" to scheme.primary.copy(alpha = 0.12f).toCss(),
        "--minibot-tertiary" to scheme.tertiary.toCss(),
        "--minibot-tertiary-soft" to scheme.tertiary.copy(alpha = 0.12f).toCss(),
        "--minibot-outline" to scheme.outline.toCss(),
        "--minibot-outline-soft" to scheme.outline.copy(alpha = 0.12f).toCss(),
        "--minibot-error" to scheme.error.toCss(),
        "--minibot-success" to semantic.success.toCss(),
        "--minibot-success-soft" to semantic.success.copy(alpha = 0.12f).toCss(),
        "--minibot-warning" to semantic.warning.toCss(),
        "--minibot-warning-soft" to semantic.warning.copy(alpha = 0.12f).toCss(),
        "--minibot-page-bg" to semantic.pageBackground.toCss(),
        "--minibot-card-surface" to semantic.cardSurface.toCss(),
        "--minibot-subtle-text" to semantic.subtleText.toCss(),
        "--minibot-subtle-border" to semantic.subtleBorder.toCss(),
        "--minibot-space-xs" to px(Spacing.xs.value),
        "--minibot-space-sm" to px(Spacing.sm.value),
        "--minibot-space-md" to px(Spacing.md.value),
        "--minibot-space-lg" to px(Spacing.lg.value),
        "--minibot-space-xl" to px(Spacing.xl.value),
        "--minibot-radius-xs" to px(Radius.xs.value),
        "--minibot-radius-sm" to px(Radius.sm.value),
        "--minibot-radius-md" to px(Radius.md.value),
        "--minibot-radius-md-large" to px(Radius.mdLarge.value),
        "--minibot-radius-lg" to px(Radius.lg.value),
        "--minibot-radius-xl" to px(Radius.xl.value),
        "--minibot-font-scale" to String.format(Locale.US, "%.2f", fontScale),
        "--minibot-touch-target" to "44px",
        "--minibot-switch-track" to if (isDark(scheme)) "#334155" else "#E2E8F0",
        "--minibot-switch-thumb" to if (isDark(scheme)) "#F8FAFC" else "#FFFFFF",
        "--minibot-font-sans" to "system-ui, -apple-system, \"Noto Sans CJK SC\", sans-serif",
        "--minibot-font-mono" to "ui-monospace, \"Roboto Mono\", monospace",
        "--minibot-text-field-border" to if (isDark(scheme)) {
            scheme.outlineVariant.copy(alpha = 0.6f).toCss()
        } else {
            scheme.outlineVariant.toCss()
        },
        "--minibot-text-field-focused-bg" to if (isDark(scheme)) {
            scheme.surfaceVariant.copy(alpha = 0.8f).toCss()
        } else {
            semantic.cardSurface.toCss()
        },
        "--minibot-text-field-disabled-border" to scheme.outlineVariant
            .copy(alpha = if (isDark(scheme)) 0.3f else 0.5f).toCss(),
        "--minibot-text-field-disabled-text" to scheme.onSurface.copy(alpha = 0.38f).toCss(),
        "--minibot-dialog-surface" to scheme.surfaceContainerHigh.toCss()
    )

    fun css(variables: Map<String, String>): String =
        ":root{" + variables.entries.joinToString("") { (key, value) -> "$key:$value;" } + "}"

    /** 主题变化时注入页面；页面侧由 [minibot.js] 的 applyTheme 写到根元素。 */
    fun applyScript(variables: Map<String, String>): String {
        val obj = variables.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\":\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        return "window.minibot&&window.minibot.applyTheme($obj)"
    }

    private fun px(value: Float): String = "${value.roundToInt()}px"

    private fun Color.toCss(): String {
        val r = (red * 255f).roundToInt().coerceIn(0, 255)
        val g = (green * 255f).roundToInt().coerceIn(0, 255)
        val b = (blue * 255f).roundToInt().coerceIn(0, 255)
        if (alpha >= 1f) return "#%02X%02X%02X".format(r, g, b)
        val a = String.format(Locale.US, "%.3f", alpha.coerceIn(0f, 1f))
        return "rgba($r,$g,$b,$a)"
    }
}
