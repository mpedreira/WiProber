package com.mpedreira.nimwifi

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** RC4 · 2: runs [ServerCheck] off the main thread, cancellable, and shows each URL's outcome. */
object ServerCheckDialog {
    fun run(activity: AppCompatActivity, settings: ThroughputSettings) {
        val control = MeasurementControl()
        val progress = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.server_check_title)
            .setMessage(R.string.server_check_running)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> control.cancel() }
            .show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val credentials = WiproberConfigStore(activity).loadCredentials()
                    ThroughputProbe(activity).checkServer(settings, credentials, control)
                }
            }
            progress.dismiss()
            if (control.isCancelled) return@launch
            val message = result.fold(
                onSuccess = { checked -> checked?.let { (ssid, checks) -> format(activity, ssid, checks) } ?: activity.getString(R.string.server_check_no_wifi) },
                onFailure = { it.message ?: it.javaClass.simpleName }
            )
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.server_check_title)
                .setMessage(message)
                .setPositiveButton(R.string.action_ok, null)
                .show()
        }
    }

    fun format(activity: AppCompatActivity, ssid: String?, checks: List<ServerCheck.UrlCheck>): String = buildList {
        add(activity.getString(R.string.server_check_header, ssid ?: "?"))
        checks.forEach { c ->
            val outcome = when {
                c.ok -> activity.getString(R.string.server_check_ok, c.bytes.toString(), c.millis.toString())
                c.retryAfterMillis != null -> activity.getString(R.string.server_check_retry, (c.retryAfterMillis / 1000).toString())
                else -> activity.getString(R.string.server_check_failed, listOfNotNull(c.errorCode, c.detail).joinToString(" · "))
            }
            add(activity.getString(R.string.server_check_line,
                activity.getString(if (c.direction == HttpSpeedTest.UPLOAD) R.string.server_check_upload else R.string.server_check_download),
                c.target, c.scheme, proxyLabel(activity, c.proxyConfig), routeLabel(activity, c.route), c.httpStatus?.toString() ?: "—", outcome))
        }
    }.joinToString("\n\n")

    fun proxyLabel(activity: android.content.Context, kind: String?): String = activity.getString(when (kind) {
        ProxyKind.NONE -> R.string.proxy_kind_none
        ProxyKind.MANUAL -> R.string.proxy_kind_manual
        ProxyKind.PAC -> R.string.proxy_kind_pac
        else -> R.string.proxy_kind_unknown
    })

    fun routeLabel(activity: android.content.Context, route: String?): String = activity.getString(when (route) {
        HttpRoute.PROXY -> R.string.route_proxy
        HttpRoute.DIRECT -> R.string.route_direct
        else -> R.string.route_undetermined
    })
}
