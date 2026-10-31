package com.hydro.link.compose.platform

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlin.concurrent.thread

/**
 * 桌面端跨平台单进程排他锁（基于内核文件锁 FileChannel.tryLock + 本地轻量 IPC 唤醒）。
 *
 * 对齐 Wails 的 SingleInstanceLock 机制与行为：
 * 1. 首个实例启动：持有 ~/.hydrolink/hydrolink.lock 排他文件锁，并绑定本地 127.0.0.1 随机 IPC 唤醒端口写入 ~/.hydrolink/hydrolink.ipc；
 * 2. 第二实例启动：尝试给 hydrolink.lock 加锁失败 -> 读取 hydrolink.ipc 中的端口，发送 activate 信号 -> 立即退出；
 * 3. 首个实例收到信号：从后台/最小化状态恢复并置顶现有主窗口，避免重复启动、多开与端口冲突；
 * 4. 故障容灾：若主实例被异常杀死（SIGKILL），操作系统内核会自动释放文件排他锁；下次启动可直接获取锁并覆盖 ipc 文件，绝不死锁。
 */
class SingleInstanceLock private constructor(
    private val raf: RandomAccessFile,
    private val channel: FileChannel,
    private val lock: FileLock,
    private val serverSocket: ServerSocket,
    private val ipcFile: File,
) : AutoCloseable {

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        runCatching { ipcFile.delete() }
        runCatching { serverSocket.close() }
        runCatching { lock.release() }
        runCatching { channel.close() }
        runCatching { raf.close() }
    }

    companion object {
        private const val LOCK_FILE_NAME = "hydrolink.lock"
        private const val IPC_FILE_NAME = "hydrolink.ipc"

        /**
         * 尝试获取单进程排他锁。
         *
         * @param onSecondInstance 收到第二实例启动通知时的回调（在后台线程触发）
         * @return 获取成功返回 [SingleInstanceLock]；若已有实例在运行则向其发送唤醒通知后返回 null
         */
        fun tryAcquire(onSecondInstance: () -> Unit): SingleInstanceLock? {
            val dir = File(System.getProperty("user.home"), ".hydrolink").apply { mkdirs() }
            val lockFile = File(dir, LOCK_FILE_NAME)
            val ipcFile = File(dir, IPC_FILE_NAME)

            val raf: RandomAccessFile
            val channel: FileChannel
            val lock: FileLock?

            try {
                raf = RandomAccessFile(lockFile, "rw")
                channel = raf.channel
                lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
            } catch (e: Exception) {
                System.err.println("[SingleInstanceLock] 尝试打开锁文件异常: ${e.message}")
                return null
            }

            if (lock == null) {
                // 已有其他实例持有文件排他锁 -> 当前是第二实例
                runCatching { channel.close() }
                runCatching { raf.close() }
                notifyExistingInstance(ipcFile)
                return null
            }

            // 获取锁成功 -> 当前是主实例
            val serverSocket = try {
                ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
            } catch (e: Exception) {
                System.err.println("[SingleInstanceLock] 无法创建本地 IPC 服务端: ${e.message}")
                runCatching { lock.release() }
                runCatching { channel.close() }
                runCatching { raf.close() }
                return null
            }

            // 写入本地 IPC 端口号到单独的 ipcFile，避免 Windows 上锁冲突
            try {
                ipcFile.writeText(serverSocket.localPort.toString(), Charsets.UTF_8)
            } catch (e: Exception) {
                System.err.println("[SingleInstanceLock] 写入 IPC 端口文件失败: ${e.message}")
            }

            val instanceLock = SingleInstanceLock(raf, channel, lock, serverSocket, ipcFile)

            // 注册退出钩子，确保进程退出时清理端口文件并释放资源
            Runtime.getRuntime().addShutdownHook(Thread {
                instanceLock.close()
            })

            // 启动守护线程监听第二实例唤醒指令
            thread(isDaemon = true, name = "SingleInstanceIpc") {
                while (!instanceLock.closed) {
                    try {
                        val client = serverSocket.accept()
                        client.use { s ->
                            val line = s.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                            if (line == "activate") {
                                println("[SingleInstanceLock] 收到第二实例启动通知，正在唤醒主窗口...")
                                onSecondInstance()
                            }
                        }
                    } catch (_: Exception) {
                        if (instanceLock.closed) break
                    }
                }
            }

            return instanceLock
        }

        /**
         * 作为第二实例：读取主实例的 IPC 端口并发送唤醒信号。
         */
        private fun notifyExistingInstance(ipcFile: File) {
            println("[SingleInstanceLock] 检测到已有 HydroLink 实例正在运行，正在通知其唤醒窗口...")
            var port = -1
            // 轮询几次读取端口（防止首个实例刚获得锁尚未写完文件的极微毫秒差）
            for (i in 0 until 10) {
                try {
                    if (ipcFile.exists()) {
                        val text = ipcFile.readText(Charsets.UTF_8).trim()
                        val p = text.toIntOrNull()
                        if (p != null && p > 0) {
                            port = p
                            break
                        }
                    }
                } catch (_: Exception) {}
                Thread.sleep(50)
            }

            if (port > 0) {
                try {
                    Socket(InetAddress.getByName("127.0.0.1"), port).use { socket ->
                        socket.soTimeout = 2000
                        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        writer.write("activate\n")
                        writer.flush()
                    }
                    println("[SingleInstanceLock] 已向现有实例 (127.0.0.1:$port) 发送唤醒指令。")
                } catch (e: Exception) {
                    System.err.println("[SingleInstanceLock] 连接现有实例 IPC 端口失败: ${e.message}")
                }
            } else {
                System.err.println("[SingleInstanceLock] 未能从 IPC 文件解析出有效的监听端口。")
            }
        }
    }
}
