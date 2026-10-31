// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 float11
//
// HydroKit 手表端轻量客户端微脚本沙盒运行时 (clientRuntime.js)
// 为手表端提供毫秒级本地响应式状态机 (Signal) 与生命周期防泄漏托管。
// 避免高频交互 (如切段、秒表、播放控制、本地滑动条) 频繁往返蓝牙/互联通道造成延迟。

const nextTurn = Promise.resolve();

export function createClientSandbox(host) {
  let active = true;
  const activeTimers = new Set();
  const signals = Object.create(null);
  let queued = false;
  let clientExports = Object.create(null);

  function scheduleRefresh() {
    if (!active || queued) return;
    queued = true;
    nextTurn.then(() => {
      queued = false;
      if (active && host && typeof host.onLocalStateChange === 'function') {
        host.onLocalStateChange();
      }
    });
  }

  // 受控定时器：页面退出或销毁时连根拔起，杜绝后台耗电与死锁
  const safeTimers = {
    setTimeout(fn, ms) {
      if (!active) return null;
      let timer = null;
      timer = setTimeout(() => {
        activeTimers.delete(timer);
        if (active) {
          try { fn(); } catch (err) { console.warn('[clientRuntime] timer error:', err); }
        }
      }, ms);
      activeTimers.add(timer);
      return timer;
    },
    clearTimeout(id) {
      if (!id) return;
      clearTimeout(id);
      activeTimers.delete(id);
    },
    setInterval(fn, ms) {
      if (!active) return null;
      const interval = setInterval(() => {
        if (!active) {
          clearInterval(interval);
          activeTimers.delete(interval);
          return;
        }
        try { fn(); } catch (err) { console.warn('[clientRuntime] interval error:', err); }
      }, ms);
      activeTimers.add(interval);
      return interval;
    },
    clearInterval(id) {
      if (!id) return;
      clearInterval(id);
      activeTimers.delete(id);
    }
  };

  // 响应式 Signal 工厂
  function signal(key, initial) {
    if (signals[key]) return signals[key];
    let val = initial;
    const s = {
      get() { return val; },
      set(next) {
        if (val === next) return val;
        val = next;
        scheduleRefresh();
        return val;
      },
      update(fn) {
        return s.set(typeof fn === 'function' ? fn(val) : fn);
      }
    };
    signals[key] = s;
    return s;
  }

  // 暴露给脚本的安全受限 API 集合
  const clientApi = {
    signal,
    storage: {
      get(key, fallback) {
        return host && host.getStore ? host.getStore(key, fallback) : fallback;
      },
      set(key, val) {
        if (host && host.setStore) host.setStore(key, val);
      }
    },
    emit(eventHandler, value) {
      if (host && host.sendServerEvent) {
        host.sendServerEvent({ handler: eventHandler, source: 'client', value });
      }
    },
    setTimeout: safeTimers.setTimeout,
    clearTimeout: safeTimers.clearTimeout,
    setInterval: safeTimers.setInterval,
    clearInterval: safeTimers.clearInterval,
    console: {
      log: (...args) => console.log('[clientScript]', ...args),
      info: (...args) => console.info('[clientScript]', ...args),
      warn: (...args) => console.warn('[clientScript]', ...args),
      error: (...args) => console.error('[clientScript]', ...args)
    }
  };

  return {
    /**
     * 执行客户端微脚本代码
     * @param {string} code 客户端 JS 源码
     */
    evaluate(code) {
      if (!active || typeof code !== 'string' || !code.trim()) return false;
      try {
        // 沙盒隔离：通过 Function 构造器注入白名单参数
        const runner = new Function(
          'hk', 'signal', 'storage', 'emit', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'console',
          `"use strict";\n${code}`
        );
        const result = runner(
          clientApi,
          clientApi.signal,
          clientApi.storage,
          clientApi.emit,
          safeTimers.setTimeout,
          safeTimers.clearTimeout,
          safeTimers.setInterval,
          safeTimers.clearInterval,
          clientApi.console
        );
        if (result && typeof result === 'object') {
          clientExports = Object.assign(clientExports, result);
        }
        return true;
      } catch (err) {
        console.warn('[clientRuntime] evaluate failed:', err);
        return false;
      }
    },

    /**
     * 调用客户端导出的函数处理程序 (本地优先拦截)
     * @param {string} handlerName 动作名
     * @param {*} value 参数
     * @returns {boolean} 是否被客户端脚本拦截处理成功
     */
    invoke(handlerName, value) {
      if (!active || !handlerName) return false;
      const fn = clientExports[handlerName];
      if (typeof fn === 'function') {
        try {
          fn(value);
          return true; // 拦截成功，无需上报服务端
        } catch (err) {
          console.warn(`[clientRuntime] handler ${handlerName} threw:`, err);
          return true;
        }
      }
      return false; // 未在本地拦截，交给服务端处理
    },

    /**
     * 取得已注册的 Signal
     */
    getSignal(key) {
      return signals[key];
    },

    /**
     * 获取所有当前激活的 Signal 快照
     */
    getSignalSnapshot() {
      const snap = {};
      for (const k in signals) snap[k] = signals[k].get();
      return snap;
    },

    /**
     * 销毁沙盒：物理清理所有未结束的定时器，解除所有闭包引用
     */
    destroy() {
      active = false;
      activeTimers.forEach(id => {
        clearTimeout(id);
        clearInterval(id);
      });
      activeTimers.clear();
      clientExports = Object.create(null);
    }
  };
}
