import assert from 'node:assert/strict';
import { createClientSandbox } from './clientRuntime.js';

console.log('--- 测试 clientRuntime: 沙盒初始化与 Signal 状态机 ---');
{
  let stateChanged = 0;
  const host = {
    onLocalStateChange() {
      stateChanged += 1;
    },
    getStore(k, def) {
      return k === 'theme' ? 'dark' : def;
    },
    setStore(k, v) {}
  };

  const sandbox = createClientSandbox(host);

  // 1. 测试直接调用 signal
  const sig = sandbox.getSignal('counter') || sandbox.evaluate(`
    const count = signal('counter', 10);
    return {
      increment() { count.set(count.get() + 1); },
      decrement() { count.update(c => c - 1); }
    };
  `);

  assert.ok(sig, '代码应成功在沙盒中解析执行');

  const counterSignal = sandbox.getSignal('counter');
  assert.equal(counterSignal.get(), 10, '初始值应为 10');

  // 本地触发拦截执行
  const handled = sandbox.invoke('increment');
  assert.ok(handled, 'increment 应被本地沙盒拦截');
  assert.equal(counterSignal.get(), 11, '调用后值应变为 11');

  // 等待 microtask
  await Promise.resolve();
  assert.equal(stateChanged, 1, 'Signal 变更应调度通知宿主刷新视图');

  // 测试未声明的方法，不应被拦截，应返回 false
  const unhandled = sandbox.invoke('nonExistent');
  assert.equal(unhandled, false, '未声明的方法不应被拦截');

  // 2. 测试定时器托管与销毁安全
  let timerFired = false;
  sandbox.evaluate(`
    setTimeout(() => { timerFired = true; }, 50);
  `);

  sandbox.destroy();
  // 销毁后等待 60ms
  await new Promise(r => setTimeout(r, 60));
  assert.equal(timerFired, false, '沙盒销毁后所有未决定时器必须被物理连根拔起');

  console.log('✓ clientRuntime 单元测试全部通过！');
}
