import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

console.log('--- 测试 GifPlayer: 双缓冲窗口 / URL 直取 / 失败重试 ---');

const uxPath = path.join(path.dirname(fileURLToPath(import.meta.url)), 'GifPlayer.ux');
const uxSource = fs.readFileSync(uxPath, 'utf8');

/**
 * 从 .ux 里抠出 <script> 内容并求值成组件选项对象。
 *
 * 组件脚本是自包含的（没有 import），所以直接把 `export default {...}` 换成
 * `return {...}` 就能在 Node 里跑起来——这样这套"窗口 ≤2 段 / 失败重试"的
 * 行为不用上手表就能钉住。
 */
function loadComponentOptions() {
  const match = uxSource.match(/<script>([\s\S]*?)<\/script>/);
  assert.ok(match, 'GifPlayer.ux 里必须有 <script> 块');
  const body = match[1].replace(/export\s+default/, 'return');
  return new Function(body)();
}

/** 建一个"组件实例"：绑定方法 + 铺 data() + 记账用的 setter 间谍与 $emit。 */
const created = [];
function createPlayer(segments, extra = {}) {
  const options = loadComponentOptions();
  const instance = {};
  for (const key of Object.keys(options)) {
    if (typeof options[key] === 'function') instance[key] = options[key].bind(instance);
  }
  Object.assign(instance, options.data());

  instance.playerData = Object.assign({ segments, autoPlay: false }, extra);
  instance.events = [];
  instance.$emit = (name, payload) => instance.events.push({ name, payload });

  // src 赋值计数：src 没变就不该写回（写回 = Vela 重头加载 = 闪一下）
  instance.srcWrites = 0;
  for (const field of ['slot0Src', 'slot1Src']) {
    let value = instance[field];
    Object.defineProperty(instance, field, {
      get: () => value,
      set: (next) => { value = next; instance.srcWrites += 1; }
    });
  }
  created.push(instance);
  return instance;
}

// 收尾：把每个实例的定时器收掉，测试自己要能退出（_advanceSegment 尾部会 _scheduleNext，
// loop:true 时那条链会自我续期，进程永不退出 —— 用 timeout 杀等于把挂起当通过）。
function drainPlayers() {
  for (const p of created) {
    try { p.onDestroy(); } catch (e) {}
  }
  created.length = 0;
}

/** 当前常驻在双槽里的段（去重后就是显存里真实持有的切片集合）。 */
function resident(instance) {
  return [instance.slot0Src, instance.slot1Src].filter((src) => !!src);
}

// 1. URL 直取 + 双缓冲窗口：任何时刻常驻切片都 ≤ 2 段，且正好是 {当前, 下一段}
{
  const segments = [
    { src: 'http://192.168.1.5:9000/api/hydro/media/job42/0.gif', duration: 1200 },
    { src: 'http://192.168.1.5:9000/api/hydro/media/job42/1.gif', duration: 1200 },
    { src: 'http://192.168.1.5:9000/api/hydro/media/job42/2.gif', duration: 900 }
  ];
  const player = createPlayer(segments, { loop: true });

  player._setupPlayer();
  assert.equal(player.slot0Src, segments[0].src, '第一段必须**直取 URL**（不再转 base64 内联）');
  assert.equal(player.slot1Src, segments[1].src, 'Slot 1 应预加载第二段');
  assert.deepEqual(resident(player), [segments[0].src, segments[1].src]);
  assert.equal(player.totalSegments, 3);

  for (let step = 0; step < 6; step++) {
    player._advanceSegment();
    const live = resident(player);
    assert.ok(live.length <= 2, `常驻切片不得超过 2 段，实际 ${live.length} 段`);
    const current = segments[player.currentIndex];
    assert.ok(live.includes(current.src), '当前段必须在窗口里');
    // 下一段（回环时是第 0 段）必须已经在另一个槽里预热好了
    const nextIndex = (player.currentIndex + 1) % segments.length;
    assert.ok(live.includes(segments[nextIndex].src), '下一段必须已经预加载在闲置槽位里');
  }
}

// 2. src 未变不写回：避免 Vela 重头加载同一张图（闪屏 / 白耗流量）
{
  const segments = [
    { src: 'http://host/api/hydro/media/job9/0.gif', duration: 1200 },
    { src: 'http://host/api/hydro/media/job9/1.gif', duration: 1200 }
  ];
  const player = createPlayer(segments, { loop: true });
  player._setupPlayer();
  const afterFirst = player.srcWrites;
  assert.ok(afterFirst >= 2, '首次铺数据应当写入两个槽位');

  player._setupPlayer();
  assert.equal(player.srcWrites, afterFirst, '同一份播放列表重复铺数据不应写回 src（否则白重载一次）');

  // 单段时闲置槽位必须是空的（显存里只留 1 张）
  const single = createPlayer([{ src: 'http://host/only.gif', duration: 2000 }]);
  single._setupPlayer();
  assert.equal(single.slot1Src, '', '单段播放时闲置槽位必须置空释放显存');
}

// 3. 加载失败重试：同段换 `?r=N` 打散缓存重取，最多 2 次
{
  const url = 'http://host/api/hydro/media/job7/3.gif';
  const segments = [{ src: url, duration: 1200 }, { src: 'http://host/api/hydro/media/job7/4.gif', duration: 1200 }];
  const player = createPlayer(segments, { loop: true });
  player._setupPlayer();

  player.onSlotError(0);
  assert.equal(player.slot0Src, url + '?r=1', '首次失败应换带 r=1 的地址重取');
  player.onSlotError(0);
  assert.equal(player.slot0Src, url + '?r=2', '第二次失败应换带 r=2 的地址重取');
  const capped = player.slot0Src;
  player.onSlotError(0);
  assert.equal(player.slot0Src, capped, '重试必须封顶，不能无限打服务器');

  // 已有 query 的地址要接着用 & 拼
  const withQuery = 'http://host/api/hydro/media/job7/5.gif?a=1';
  const player2 = createPlayer([{ src: withQuery, duration: 1200 }]);
  player2._setupPlayer();
  player2.onSlotError(0);
  assert.equal(player2.slot0Src, withQuery + '&r=1', '已有 query 时必须用 & 追加');

  // 空槽位收到 error 回调不能乱动（段下标是 -1）
  player2.onSlotError(1);
  assert.equal(player2.slot1Src, '', '空槽位的 error 回调不应写入任何地址');
}

// 4. 销毁：卸载后不留地址、不留重试账本（定时器由 _clearTimer 收掉）
{
  const player = createPlayer([{ src: 'http://host/a.gif', duration: 1200 }]);
  player._setupPlayer();
  player.onDestroy();
  assert.equal(player.slot0Src, '');
  assert.equal(player.slot1Src, '');
  assert.deepEqual(player._slotSegIndex, [-1, -1]);
  assert.deepEqual(player._retries, {});
}

drainPlayers();
console.log('✓ GifPlayer 单元测试全部通过！');
