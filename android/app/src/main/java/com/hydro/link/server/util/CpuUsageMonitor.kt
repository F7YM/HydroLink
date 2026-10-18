package com.hydro.link.server.util

import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Android CPU 占用率采集器
 *
 * 解决 Android 8.0+ (API 26~36+) 下 SELinux 对普通应用封锁 /proc/stat 与 /proc/loadavg
 * 导致 CPU 占用率始终为 0% 的核心缺陷。
 *
 * 渐进降级策略：
 * 1. 【高精度 Ticks】：直接读取 /proc/stat。若被 SELinux 拦截且具备 Root 或 Shizuku 授权，
 *    通过 Authorizer.exec 提取第一行 "cpu ..." 计算 delta 占用率；
 * 2. 【内核频率动态加权（免 Root 原生支持）】：读取 /sys/devices/system/cpu 核心频点，
 *    现代 Android 内核默认 schedutil 调度器会根据瞬时负载动态调整各核工作频率，
 *    通过 (cur_freq - min_freq) / (max_freq - min_freq) 综合计算多核动态负载率；
 * 3. 【top / loadavg 兜底】：尝试执行 top -n 1 或读取 /proc/loadavg；
 * 4. 【缓存平滑】：避免在 HTTP 请求主线程中执行长等待 Thread.sleep(500)，保证端点秒回。
 */
object CpuUsageMonitor {

    private data class CpuTicks(val total: Long, val idle: Long, val timestamp: Long)

    private val lastTicks = AtomicReference<CpuTicks?>(null)
    private var cachedCpuPercent: Float = 0f
    private var lastSampleTime: Long = 0L
    private const val CACHE_EXPIRE_MS = 1500L

    /** 获取当前系统 CPU 占用率百分比 (0.0 ~ 100.0) */
    @Synchronized
    fun getCpuUsage(): Float {
        val now = System.currentTimeMillis()
        if (now - lastSampleTime < CACHE_EXPIRE_MS && cachedCpuPercent > 0f) {
            return cachedCpuPercent
        }

        // 策略 1：读取 /proc/stat 计算时间差占用率
        val currentTicks = readCpuTicks()
        if (currentTicks != null) {
            val prev = lastTicks.get()
            if (prev != null && currentTicks.total > prev.total && (currentTicks.timestamp - prev.timestamp) in 200..10000) {
                val totalDelta = currentTicks.total - prev.total
                val idleDelta = currentTicks.idle - prev.idle
                if (totalDelta > 0) {
                    val percent = ((totalDelta - idleDelta).toFloat() / totalDelta.toFloat() * 100f).coerceIn(0f, 100f)
                    cachedCpuPercent = (Math.round(percent * 10f) / 10f)
                    lastTicks.set(currentTicks)
                    lastSampleTime = now
                    return cachedCpuPercent
                }
            } else {
                lastTicks.set(currentTicks)
                // 首次采样或间隔过长时，短暂采样计算
                try {
                    Thread.sleep(150)
                    val nextTicks = readCpuTicks()
                    if (nextTicks != null && nextTicks.total > currentTicks.total) {
                        val totalDelta = nextTicks.total - currentTicks.total
                        val idleDelta = nextTicks.idle - currentTicks.idle
                        val percent = ((totalDelta - idleDelta).toFloat() / totalDelta.toFloat() * 100f).coerceIn(0f, 100f)
                        cachedCpuPercent = (Math.round(percent * 10f) / 10f)
                        lastTicks.set(nextTicks)
                        lastSampleTime = System.currentTimeMillis()
                        return cachedCpuPercent
                    }
                } catch (_: Exception) {}
            }
        }

        // 策略 2：通过内核 CPU 核心实时频率动态估算（Android 8+ 免 Root 可读）
        val freqPercent = readCpuFrequencyUsage()
        if (freqPercent != null && freqPercent > 0f) {
            cachedCpuPercent = freqPercent
            lastSampleTime = now
            return cachedCpuPercent
        }

        // 策略 3：通过 loadavg 或 top 命令兜底
        val loadAvgPercent = readLoadAvgUsage()
        if (loadAvgPercent != null && loadAvgPercent > 0f) {
            cachedCpuPercent = loadAvgPercent
            lastSampleTime = now
            return cachedCpuPercent
        }

        // 策略 4：top -n 1 提取
        val topPercent = readTopUsage()
        if (topPercent != null && topPercent > 0f) {
            cachedCpuPercent = topPercent
            lastSampleTime = now
            return cachedCpuPercent
        }

        return cachedCpuPercent.coerceAtLeast(0f)
    }

    /** 读取 /proc/stat 中的第一行全局 CPU 时间片 */
    private fun readCpuTicks(): CpuTicks? {
        // 1. 尝试直接文件读取（部分定制 ROM / root / Android 7 可行）
        val directLine = try {
            File("/proc/stat").useLines { lines ->
                lines.firstOrNull { it.startsWith("cpu ") }
            }
        } catch (_: Exception) {
            null
        }

        val line = directLine ?: run {
            // 2. 若直接读取被 SELinux 拒绝，且具备 Shizuku 或 Root 授权，通过授权执行提取
            if (Authorizer.shizukuGranted()) {
                val res = Authorizer.exec("head -n 1 /proc/stat", Authorizer.Kind.SHIZUKU, timeoutMs = 800)
                if (res.ok && res.output.startsWith("cpu ")) res.output.trim() else null
            } else if (RootShell.isAvailable()) {
                val res = Authorizer.exec("head -n 1 /proc/stat", Authorizer.Kind.ROOT, timeoutMs = 800)
                if (res.ok && res.output.startsWith("cpu ")) res.output.trim() else null
            } else {
                null
            }
        } ?: return null

        return parseStatLine(line)
    }

    private fun parseStatLine(line: String): CpuTicks? {
        return try {
            val parts = line.trim().split(Regex("\\s+")).drop(1)
            if (parts.size < 4) return null
            val user = parts[0].toLongOrNull() ?: 0L
            val nice = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val system = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val idle = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            val iowait = parts.getOrNull(4)?.toLongOrNull() ?: 0L
            val irq = parts.getOrNull(5)?.toLongOrNull() ?: 0L
            val softirq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
            val steal = parts.getOrNull(7)?.toLongOrNull() ?: 0L
            val total = user + nice + system + idle + iowait + irq + softirq + steal
            CpuTicks(total, idle + iowait, System.currentTimeMillis())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 免 Root 方案：读取所有可用核心的工作频率与峰值频率计算活跃度。
     * 绝大多数高通、联发科、三星猎户座与 Tensor 平台的 /sys/devices/system/cpu 核心频点目录为全局只读权限 (0444)。
     */
    private fun readCpuFrequencyUsage(): Float? {
        return try {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            var totalRatio = 0f
            var validCores = 0

            for (i in 0 until cores) {
                val basePath = "/sys/devices/system/cpu/cpu$i/cpufreq"
                val curFreq = readLongFile("$basePath/scaling_cur_freq")
                    ?: readLongFile("$basePath/cpuinfo_cur_freq")
                val maxFreq = readLongFile("$basePath/scaling_max_freq")
                    ?: readLongFile("$basePath/cpuinfo_max_freq")
                val minFreq = readLongFile("$basePath/scaling_min_freq") ?: 0L

                if (curFreq != null && maxFreq != null && maxFreq > minFreq) {
                    val ratio = (curFreq - minFreq).toFloat() / (maxFreq - minFreq).toFloat()
                    totalRatio += ratio.coerceIn(0f, 1f)
                    validCores++
                }
            }

            if (validCores > 0) {
                val usage = (totalRatio / validCores) * 100f
                Math.round(usage * 10f) / 10f
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readLongFile(path: String): Long? {
        return try {
            File(path).readText().trim().toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** 读取 /proc/loadavg 计算近似负载 */
    private fun readLoadAvgUsage(): Float? {
        return try {
            val text = File("/proc/loadavg").readText().trim()
            val load = text.split(" ").firstOrNull()?.toDoubleOrNull() ?: return null
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val percent = (load / cores.toDouble() * 100.0).toFloat().coerceIn(0f, 100f)
            Math.round(percent * 10f) / 10f
        } catch (_: Exception) {
            null
        }
    }

    /** 使用 top 命令解析瞬时 CPU 负载 */
    private fun readTopUsage(): Float? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "top -n 1 -m 1"))
            val output = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            val idleMatch = Regex("([0-9]+)%\\s*idle", RegexOption.IGNORE_CASE).find(output)
            if (idleMatch != null) {
                val idleVal = idleMatch.groupValues[1].toFloatOrNull() ?: return null
                val totalMatch = Regex("([0-9]+)%\\s*cpu", RegexOption.IGNORE_CASE).find(output)
                val totalCpu = totalMatch?.groupValues?.get(1)?.toFloatOrNull() ?: 100f
                val active = totalCpu - idleVal
                val percent = (active / totalCpu * 100f).coerceIn(0f, 100f)
                return Math.round(percent * 10f) / 10f
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
