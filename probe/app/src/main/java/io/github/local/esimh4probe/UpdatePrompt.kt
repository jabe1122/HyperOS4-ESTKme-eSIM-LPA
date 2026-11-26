@file:JvmName("UpdatePrompt")

package io.github.local.esimh4probe

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import io.github.local.esimh4ui.AppMiuixTheme
import io.github.local.esimh4ui.UiKit
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

fun show(activity: Activity, title: String, details: String, download: Runnable) {
    if (activity.isFinishing || activity.isDestroyed) return
    val open = {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(details)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(activity, R.string.snack_open_link_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val actionLabel = if (UpdateChecker.autoInstall(activity)) R.string.update_install else R.string.update_download
    val label = activity.getString(R.string.update_details, "GitHub")
    if (!UiKit.isMiuix()) {
        val message = SpannableString(label)
        message.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) { open() }
            override fun updateDrawState(ds: android.text.TextPaint) {
                super.updateDrawState(ds)
                ds.color = if ((activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES) 0xFF8AB4F8.toInt() else 0xFF1565C0.toInt()
                ds.isUnderlineText = true
            }
        }, 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(actionLabel) { _, _ -> download.run() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            movementMethod = LinkMovementMethod.getInstance()
            gravity = android.view.Gravity.LEFT
            textAlignment = View.TEXT_ALIGNMENT_GRAVITY
            setPadding(paddingLeft, UiKit.dp(activity, 16f), paddingRight, UiKit.dp(activity, 24f))
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            val value = android.util.TypedValue()
            activity.theme.resolveAttribute(android.R.attr.colorAccent, value, true)
            backgroundTintList = android.content.res.ColorStateList.valueOf(value.data)
            val color = value.data
            val luminance = (0.2126 * android.graphics.Color.red(color) +
                0.7152 * android.graphics.Color.green(color) +
                0.0722 * android.graphics.Color.blue(color)) / 255.0
            setTextColor(if (luminance > 0.6) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            (layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.marginEnd = UiKit.dp(activity, 12f)
                layoutParams = params
            }
        }
        return
    }
    val parent = activity.window.decorView as ViewGroup
    val view = ComposeView(activity)
    val visible = mutableStateOf(true)
    val dismiss = {
        visible.value = false
        parent.post { parent.removeView(view) }
        Unit
    }
    view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    view.setContent {
        val controller = androidx.compose.runtime.remember {
            top.yukonga.miuix.kmp.theme.ThemeController(
                if (io.github.local.esimh4ui.ThemeConfig.dynamicColor)
                    top.yukonga.miuix.kmp.theme.ColorSchemeMode.MonetSystem
                else top.yukonga.miuix.kmp.theme.ColorSchemeMode.System,
            )
        }
        AppMiuixTheme(controller = controller) {
            if (visible.value) {
                Dialog(onDismissRequest = dismiss) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(text = title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = label,
                        color = if (isSystemInDarkTheme()) Color(0xFF8AB4F8) else Color(0xFF1565C0),
                        textDecoration = TextDecoration.Underline,
                        textAlign = TextAlign.Left,
                        modifier = Modifier.clickable { open() },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            text = activity.getString(android.R.string.cancel),
                            onClick = dismiss,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = activity.getString(actionLabel),
                            onClick = { dismiss(); download.run() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
                    }
                }
            }
        }
    }
    parent.addView(view, ViewGroup.LayoutParams(0, 0))
}
