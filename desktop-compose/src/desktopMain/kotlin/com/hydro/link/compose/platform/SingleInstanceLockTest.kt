package com.hydro.link.compose.platform

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

fun main() {
    println("[Test] 正在测试 SingleInstanceLock 单进程锁与跨进程 IPC 唤醒机制...")

    val secondInstanceLatch = CountDownLatch(1)

    // 1. 模拟首个主实例获取锁
    val lock1 = SingleInstanceLock.tryAcquire {
        println("[Test] 首个实例收到第二实例唤醒通知！")
        secondInstanceLatch.countDown()
    }

    if (lock1 == null) {
        System.err.println("[FAIL] 首个实例未能获取到锁！")
        System.exit(1)
        return
    }
    println("[Test] 首个实例已成功持有排他锁并开启 IPC 唤醒服务。")

    // 2. 模拟第二个实例尝试获取锁
    println("[Test] 正在模拟第二个实例尝试加锁并唤醒...")
    val lock2 = SingleInstanceLock.tryAcquire {
        System.err.println("[FAIL] 第二实例不应当触发自身回调！")
    }

    if (lock2 != null) {
        System.err.println("[FAIL] 第二实例不应当成功获取到锁！")
        lock2.close()
        lock1.close()
        System.exit(1)
        return
    }
    println("[Test] 第二实例获取锁返回 null（符合预期）。")

    // 3. 等待首个实例收到 IPC 唤醒信号
    val received = secondInstanceLatch.await(3, TimeUnit.SECONDS)
    if (!received) {
        System.err.println("[FAIL] 首个实例超时未能收到 IPC 唤醒信号！")
        lock1.close()
        System.exit(1)
        return
    }
    println("[Test] 首个实例成功收到唤醒通知！")

    // 4. 释放首个实例的锁
    println("[Test] 正在释放首个实例的锁...")
    lock1.close()
    Thread.sleep(200)

    // 5. 验证释放锁之后，新的实例可以重新获取锁
    val lock3 = SingleInstanceLock.tryAcquire {
        // no-op
    }
    if (lock3 == null) {
        System.err.println("[FAIL] 锁释放后新实例未能成功获取锁！")
        System.exit(1)
        return
    }
    println("[Test] 锁释放后新实例再次成功获取排他锁！")
    lock3.close()

    println("[PASS] SingleInstanceLock 单进程锁与 IPC 唤醒测试全部通过！")
    System.exit(0)
}
