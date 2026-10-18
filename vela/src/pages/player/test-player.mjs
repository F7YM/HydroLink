import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

console.log('--- 测试 player 页: 全局播放器服务请求编排 ---');

// 页面脚本里 router 是系统模块（import 被剥掉），这里给个全局替身：
// 顺带把 back/push 记账，退出路径断言用。
const routerCalls = [];
globalThis.router = {
  push: (args) => routerCalls.push(['push', args]),
  back: () => routerCalls.push(['back'])
};

const uxPath = path.join(path.dirname(fileURLToPath(import.meta.url)), 'player.ux');
const uxSource = fs.readFileSync(uxPath, 'utf8');

/**
 * 从 .ux 里抠出 <script> 求值成组件选项对象。
 * 页面脚本有 `import router from '@system.router'`——Node 里没有系统路由，
 * 把 import 行剥掉、export default 换成 return 即可（同 test-gif-player 的手法）。
 */
function loadComponentOptions() {
  const match = uxSource.match(/<script>([\s\S]*?)<\/script>/);
  assert.ok(match, 'player.ux 里必须有 <script> 块');
  const body = match[1]
    .replace(/^\s*import\s[^\n]*$/gm, '')
    .replace(/export\s+default/, 'return');
  return new Function(body)();
}

/** 造一个页面实例：绑定方法 + 铺 private + 假 $app/$def。 */
function createPage(appDef) {
  const options = loadComponentOptions();
  const page = {};
  for (const key of Object.keys(options)) {
    if (typeof options[key] === 'function') page[key] = options[key].bind(page);
  }
  // public + private 两段都铺成实例字段（Vela 运行时也是这样展开的）
  Object.assign(page, options.public || {}, options.private || {});
  page.$app = { $def: appDef || {} };
  return page;
}

// 1. 正常请求：段清单走 $def.playerRequest（URL 数组不走 router 参数，免截断/转义）
{
  const appDef = {
    playerRequest: {
      title: '测试视频',
      index: 1,
      loop: true,
      segments: [
        { src: 'http://h/api/hydro/media/j/0.gif', duration: 1200 },
        { src: 'http://h/api/hydro/media/j/1.gif', duration: 900 },
        { src: 'http://h/api/hydro/media/j/2.gif', duration: 900 }
      ]
    }
  };
  const page = createPage(appDef);
  page.onInit();
  assert.equal(page.ready, true, '有分段就该进播放态');
  assert.equal(page.title, '测试视频');
  assert.equal(page.totalSegments, 3);
  assert.equal(page.currentIndex, 1, '起播下标应按请求给的 index');
  assert.equal(appDef.playerRequest, null, '请求取出后必须清掉（防下次 onShow 误用）');
  assert.equal(page.playerData.segments.length, 3);
  assert.equal(page.playerData.loop, true);
  assert.equal(page.playerData.autoPlay, true);
}

// 2. 字符串段自动归一成 {src, duration}
{
  const page = createPage({
    playerRequest: { segments: ['http://h/a.gif', 'http://h/b.gif'] }
  });
  page.onInit();
  assert.equal(page.ready, true);
  assert.deepEqual(
    page.playerData.segments.map(s => s.src),
    ['http://h/a.gif', 'http://h/b.gif']
  );
  for (const s of page.playerData.segments) {
    assert.equal(typeof s.duration, 'number', '字符串段应补默认时长（播放器靠它切段）');
  }
}

// 3. index 越界要夹取，不能把播放器推到不存在的段上
{
  const page = createPage({
    playerRequest: { index: 99, segments: [{ src: 'http://h/a.gif', duration: 1000 }] }
  });
  page.onInit();
  assert.equal(page.currentIndex, 0, 'index 超界应夹到最后一段内（单段=0）');
  const page2 = createPage({
    playerRequest: { index: -5, segments: [{ src: 'http://h/a.gif', duration: 1000 }, { src: 'http://h/b.gif', duration: 1000 }] }
  });
  page2.onInit();
  assert.equal(page2.currentIndex, 0, '负 index 应夹回 0');
}

// 4. 空请求/坏请求：如实报错，不装作能播
{
  const page = createPage({});
  page.onInit();
  assert.equal(page.ready, false, '没有分段不能进播放态');
  assert.ok(page.errorText, '空请求要给出错误文案');

  const page2 = createPage({ playerRequest: { segments: [] } });
  page2.onInit();
  assert.equal(page2.ready, false, '空数组同样不能播');

  // router 参数兜底通道：JSON 串也要能解析
  const page3 = createPage({});
  page3.segments = JSON.stringify({ segments: [{ src: 'http://h/x.gif', duration: 500 }] });
  page3.onInit();
  assert.equal(page3.ready, true, '页面参数里的 JSON 串请求也要接得住');
}

// 5. 退出写回 playerResult（对应 keyboardResult 惯例）
{
  const appDef = {
    playerRequest: { segments: [{ src: 'http://h/a.gif', duration: 1000 }, { src: 'http://h/b.gif', duration: 1000 }] }
  };
  const page = createPage(appDef);
  page.onInit();
  page._controller = { getIndex: () => 1 };
  page.goBack();
  assert.ok(appDef.playerResult, '退出必须写回 playerResult');
  assert.equal(appDef.playerResult.index, 1);
  assert.equal(appDef.playerResult.finished, false, '手动退出不是播完');
  assert.ok(routerCalls.some(c => c[0] === 'back'), '写回结果后必须 router.back() 回调用方');

  // 播完（playerfinish）→ finished=true
  const appDef2 = {
    playerRequest: { segments: [{ src: 'http://h/a.gif', duration: 1000 }] }
  };
  const page2 = createPage(appDef2);
  page2.onInit();
  page2._controller = { getIndex: () => 0 };
  page2.onPlayerFinish({ detail: { index: 0 } });
  assert.equal(appDef2.playerResult.finished, true, 'playerfinish 后退出应标 finished');
}

console.log('✓ player 页单元测试全部通过！');
