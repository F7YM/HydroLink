package com.hydro.link.server.update

import com.hydro.link.server.market.MarketClient
import com.hydro.link.server.market.MarketSources
import com.hydro.link.server.market.UrlFetcher
import java.io.IOException

/**
 * 负责从镜像源获取 app.json 描述文件。
 *
 * 网络走 [MarketClient.Fetcher] 抽象，单测中可注入假实现；
 * 生产环境使用 [UrlFetcher] 依次尝试镜像源地址。
 */
class AppUpdateClient(
    private val fetcher: MarketClient.Fetcher = UrlFetcher(connectTimeout = 6_000, readTimeout = 8_000),
) {

    /**
     * 顺序尝试镜像基址拉取 app.json，成功返回首个解析结果，全部失败返回 null。
     */
    fun fetchUpdate(bases: List<String>): AppUpdateInfo? {
        for (base in bases) {
            val url = MarketSources.join(base, "app.json")
            try {
                val reply = fetcher.get(url, null)
                if (reply.code == 200 && !reply.body.isNullOrBlank()) {
                    return AppUpdateHelper.parse(reply.body)
                }
            } catch (_: IOException) {
                // 尝试下一个镜像源
            } catch (_: IllegalArgumentException) {
                // JSON 结构不合法，尝试下一个源
            }
        }
        return null
    }
}
