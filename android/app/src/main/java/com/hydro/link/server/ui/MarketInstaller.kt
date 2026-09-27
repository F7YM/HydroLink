package com.hydro.link.server.ui

import android.app.Activity
import android.widget.Toast
import com.hydro.link.server.market.MarketClient
import com.hydro.link.server.market.MarketEntry
import java.io.File

/**
 * 从市场装一个插件：下载 → 校验 sha256/size → 读清单 → 确认弹窗 → 安装（热注册）。
 *
 * 市场和「插件更新」两条路共用这一份（同一套校验、同一段文案、同一套弹窗分流），
 * 只差 [positiveLabel] 与 [onDone] 回调。两处各写一遍必然长歪——之前就长歪过一次。
 */
object MarketInstaller {

    fun install(
        activity: Activity,
        client: MarketClient,
        base: String,
        entry: MarketEntry,
        positiveLabel: String = "安装",
        /** 下载/安装期间的忙碌态：回调插件 id，开始与结束各一次。 */
        onBusy: (String) -> Unit = {},
        /** 主线程回调一句话结果（成功/失败原因），由调用方决定怎么提示。 */
        onDone: (String) -> Unit = {},
    ) {
        val srv = PluginUi.server()
        if (srv == null) {
            onDone("服务未运行，无法安装")
            return
        }
        onBusy(entry.id)
        Thread {
            val tmp = File(activity.cacheDir, "market-${entry.id}-${entry.version}.hpk")
            val problem = client.download(base, entry, tmp)
            if (problem != null) {
                tmp.delete()
                activity.runOnUiThread {
                    onBusy("")
                    onDone(problem)
                }
                return@Thread
            }
            val kit = srv.hydroKit
            val preview = try {
                kit.store.inspect(tmp)
            } catch (e: Exception) {
                tmp.delete()
                activity.runOnUiThread {
                    onBusy("")
                    onDone("读取安装包失败：${e.message}")
                }
                return@Thread
            }
            val perms = if (preview.permissions.isEmpty()) "未申请任何权限" else preview.permissions.joinToString(", ")
            val message = buildString {
                append("来源：").append(base).append('\n')
                append("包：").append(entry.url).append('\n')
                append("版本：v").append(entry.version).append("（").append(entry.runtime).append("）\n")
                append("指纹：").append(entry.sha256.take(12)).append('\n')
                append("权限：").append(perms).append('\n')
                append('\n')
                append("插件代码以本应用同等权限运行，可访问本机服务与数据目录，请只安装可信来源的插件。")
            }
            activity.runOnUiThread {
                onBusy("")
                // 回调显式命名给 onConfirm：尾随 lambda 会落到否定钮上
                EngineDialog.confirm(
                    activity = activity,
                    title = "${positiveLabel} ${entry.name}",
                    message = message,
                    positive = positiveLabel,
                    negative = "取消",
                    onConfirm = {
                        Thread {
                            val msg = try {
                                val info = kit.install(tmp)
                                "已安装 ${info.name} v${info.version}"
                            } catch (e: Exception) {
                                "安装失败：${e.message}"
                            } finally {
                                tmp.delete()
                            }
                            activity.runOnUiThread { onDone(msg) }
                        }.start()
                    },
                )
            }
        }.start()
    }

    fun toast(activity: Activity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
