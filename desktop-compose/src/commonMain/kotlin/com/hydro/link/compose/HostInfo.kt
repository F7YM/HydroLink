package com.hydro.link.compose

/** 平台与宿主信息（两端同名字段，桌面先占位）。 */
data class HostInfo(
    val platform: String,
    val osVersion: String,
    val appVersion: String,
)

/** 桌面宿主信息占位实现——真接上 Go 服务端后再换成实际探测。 */
fun currentHostInfo(): HostInfo = HostInfo(
    platform = "desktop",
    osVersion = System.getProperty("os.name") + " " + System.getProperty("os.version"),
    appVersion = "1.3.0",
)
