import assert from 'node:assert/strict';
import { compileHydroUi, DESIGN_WIDTH } from './uiLayout.js';
import { createUiPublisher } from './uiPublisher.js';

console.log('--- 测试 1: HydroBili 页面编译与打平 ---');
{
  const biliTree = {
    type: 'scroll',
    style: { padding: 18, backgroundColor: '#000000' },
    media: { circle: { padding: 32 } },
    children: [
      { type: 'text', props: { text: 'HydroBili' }, style: { fontSize: 30, color: '#FB7299', marginBottom: 8 } },
      { type: 'text', props: { text: '首页推荐' }, style: { fontSize: 24, color: '#FFFFFF', marginBottom: 8 } },
      { type: 'button', props: { label: '换一批' }, events: { tap: 'refresh' }, style: { width: '100%', height: 52, backgroundColor: '#FB7299', borderRadius: 18, marginBottom: 16 } },
      {
        type: 'column',
        style: { padding: 12, backgroundColor: '#111111', borderRadius: 16, marginBottom: 16 },
        children: [
          { type: 'image', props: { src: 'https://i0.hdslb.com/bfs/archive/cover.jpg' }, style: { width: '100%', height: 166, borderRadius: 12, marginBottom: 10 } },
          { type: 'text', props: { text: '【全网首发】小米手环运行 JavaScript 开发环境实测' }, style: { fontSize: 22, color: '#FFFFFF', marginBottom: 8 } },
          { type: 'text', props: { text: '极客UP主' }, style: { fontSize: 18, color: '#B7BCCB', marginBottom: 8 } }
        ]
      }
    ]
  };

  const res = compileHydroUi(biliTree, { shape: 'circle', width: 466, height: 466, dpr: 2 });
  assert.ok(res.isScroll, '应识别为可滚动视图');
  assert.ok(res.totalHeight > 480, `总高度应超出单屏高度以支持安全区滚动: ${res.totalHeight}`);
  assert.ok(res.flatNodes.length >= 6, `应包含标题、按钮、卡片背景及内部子节点，实得 ${res.flatNodes.length}`);

  // 检查标题与按钮的绝对坐标
  const titleNode = res.flatNodes.find(n => n.text === 'HydroBili');
  assert.ok(titleNode, '应有标题节点');
  assert.equal(titleNode.x, 32, '圆屏 padding: 32 时标题 X 坐标应为 32');
  assert.equal(titleNode.color, '#FB7299');

  // 检查按钮
  const btnNode = res.flatNodes.find(n => n.label === '换一批');
  assert.ok(btnNode, '应有换一批按钮');
  assert.equal(btnNode.kind, 'button');
  assert.equal(btnNode.tap, 'refresh');
  assert.equal(btnNode.w, DESIGN_WIDTH - 64, '按钮应撑满可用宽度');

  // 检查卡片背景
  const cardBg = res.flatNodes.find(n => n.kind === 'container_bg');
  assert.ok(cardBg, '容器背景应被发射为独立背景节点');
  assert.equal(cardBg.background, '#111111');
  assert.equal(cardBg.radius, 16);

  console.log('✓ HydroBili 编译通过, 节点数:', res.flatNodes.length, '总高度:', res.totalHeight);
}

console.log('--- 测试 2: Weather 复杂行与 Flex Space-Between ---');
{
  const weatherTree = {
    type: 'scroll',
    style: { padding: 16 },
    media: { circle: { padding: 24 } },
    children: [
      { type: 'nav', props: { action: 'back', label: '‹ 返回' }, style: { fontSize: 26, color: '#FFFFFF' } },
      {
        type: 'row',
        style: { marginBottom: 16, justifyContent: 'space-between' },
        children: [
          { type: 'text', props: { text: '省电模式' }, style: { fontSize: 22, color: '#D6DCE8' } },
          { type: 'switch', props: { checked: true }, events: { change: 'toggle' } }
        ]
      }
    ]
  };

  const res = compileHydroUi(weatherTree, { shape: 'circle', width: 466, height: 466 });
  const textNode = res.flatNodes.find(n => n.text === '省电模式');
  const switchNode = res.flatNodes.find(n => n.kind === 'switch');
  assert.ok(textNode && switchNode, '行内的文字与开关应均被打平输出');
  assert.ok(switchNode.x > textNode.x + textNode.w, 'space-between 布局下开关 X 坐标应在文字右侧');
  assert.equal(switchNode.change, 'toggle');
  console.log('✓ Weather 行布局打平通过');
}

console.log('--- 测试 3: DeskBuddy 绝对定位 ---');
{
  const deskTree = {
    type: 'scroll',
    style: { backgroundColor: '#0b0d12' },
    children: [
      {
        type: 'text',
        props: { text: '12:30' },
        style: { position: 'absolute', top: 0, left: 0, width: 400, fontSize: 34, color: '#FFFFFF' }
      },
      {
        type: 'image',
        props: { src: 'cover.jpg' },
        style: { position: 'absolute', top: 120, left: 4, width: 135, height: 135 }
      }
    ]
  };

  const res = compileHydroUi(deskTree, { shape: 'circle', width: 466, height: 466 });
  const clock = res.flatNodes.find(n => n.text === '12:30');
  const img = res.flatNodes.find(n => n.kind === 'image');
  assert.ok(clock && img);
  assert.equal(clock.y, 30, '圆屏默认 baseInsetTop 30 + top 0 应为 30');
  assert.equal(img.x, 46, '圆屏 baseInsetX 42 + left 4 应为 46 (对齐设计稿常量 D_COVER_LEFT)');
  console.log('✓ DeskBuddy 绝对定位对齐通过');
}

console.log('--- 测试 4: uiPublisher 增量原地补丁 (In-Place Patching) ---');
{
  const publisher = createUiPublisher();
  const v1Nodes = [
    { id: 't1', kind: 'text', text: '12:00', x: 0, y: 0, w: 100, h: 30, wrapStyle: 'left:0;' },
    { id: 'btn', kind: 'button', label: '播放', x: 0, y: 40, w: 200, h: 50, wrapStyle: 'top:40;' }
  ];

  let observed = [];
  observed = publisher.update(v1Nodes, observed);
  assert.equal(observed.length, 2);
  assert.equal(observed[0].text, '12:00');

  const ref0 = observed[0];
  const ref1 = observed[1];
  const refArray = observed;

  // 仅更新时间文字
  const v2Nodes = [
    { id: 't1', kind: 'text', text: '12:01', x: 0, y: 0, w: 100, h: 30, wrapStyle: 'left:0;' },
    { id: 'btn', kind: 'button', label: '播放', x: 0, y: 40, w: 200, h: 50, wrapStyle: 'top:40;' }
  ];

  observed = publisher.update(v2Nodes, observed);
  assert.equal(observed, refArray, '数组引用必须保持一致以防止模板重构');
  assert.equal(observed[0], ref0, '节点对象必须保持原引用以防止 DOM 重建');
  assert.equal(observed[1], ref1);
  assert.equal(observed[0].text, '12:01', '属性应被原地更新');

  console.log('✓ uiPublisher In-Place 原地补丁验证通过');
}

console.log('--- 测试 5: normalizeUrl 与 toCssStyle 图片与背景 URL 归一化 ---');
{
  import('./uiLayout.js').then(({ normalizeUrl, toCssStyle }) => {
    const ctx = { host: '192.168.1.100', port: 9000, shape: 'circle' };

    // 1. 协议相对 URL 补充 https:
    assert.equal(normalizeUrl('//i0.hdslb.com/bfs/archive/pic.jpg', ctx), 'https://i0.hdslb.com/bfs/archive/pic.jpg');

    // 2. 本地 RPK / 内部协议保留
    assert.equal(normalizeUrl('/common/icons/play.png', ctx), '/common/icons/play.png');
    assert.equal(normalizeUrl('internal://files/test.png', ctx), 'internal://files/test.png');
    assert.equal(normalizeUrl('data:image/png;base64,xxxx', ctx), 'data:image/png;base64,xxxx');

    // 3. 服务端相对路径转为手表可达绝对地址
    assert.equal(normalizeUrl('/api/music/art?size=466', ctx), 'http://192.168.1.100:9000/api/music/art?size=466');

    // 4. 127.0.0.1 / localhost 替换为真实远端 host
    assert.equal(
      normalizeUrl('http://127.0.0.1:9000/api/music/art?size=466', ctx),
      'http://192.168.1.100:9000/api/music/art?size=466'
    );
    assert.equal(
      normalizeUrl('http://localhost:9000/api/music/art?size=466', ctx),
      'http://192.168.1.100:9000/api/music/art?size=466'
    );

    // 5. toCssStyle 自动补齐 url(...) 包装并重写 localhost
    const bgStyle = toCssStyle({
      backgroundImage: 'http://127.0.0.1:9000/api/music/art?w=466',
      backgroundColor: '#0b0d12'
    }, ctx);
    assert.ok(bgStyle.includes('background-image: url(http://192.168.1.100:9000/api/music/art?w=466)'), '应自动补齐 url() 包装与 host 重写');
    assert.ok(bgStyle.includes('background-color: #0b0d12'));

    console.log('✓ normalizeUrl 与 toCssStyle 背景/图片测试全部通过');
  });
}
