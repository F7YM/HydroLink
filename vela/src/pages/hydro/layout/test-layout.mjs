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

    console.log('--- 测试 6: video 视频组件编译与排版 ---');
    const videoTree = {
      type: 'scroll',
      children: [
        {
          id: 'bili_player',
          type: 'video',
          props: {
            src: 'https://upos-sz-mirrorcos.bilivideo.com/test.mp4',
            poster: 'https://i0.hdslb.com/cover.jpg',
            autoplay: true,
            controls: true,
            width: '100%',
            height: 180
          },
          style: {
            borderRadius: 12
          },
          events: {
            start: 'onStart',
            finish: 'onFinish',
            error: 'onError'
          }
        }
      ]
    };
    const vRes = compileHydroUi(videoTree, ctx);
    const videoNode = vRes.flatNodes.find(n => n.kind === 'video');
    assert.ok(videoNode, '应发射 video 节点');
    assert.equal(videoNode.id, 'bili_player');
    assert.equal(videoNode.src, 'https://upos-sz-mirrorcos.bilivideo.com/test.mp4');
    assert.equal(videoNode.poster, 'https://i0.hdslb.com/cover.jpg');
    assert.equal(videoNode.autoplay, true);
    assert.equal(videoNode.controls, true);
    assert.equal(videoNode.h, 180);
    assert.ok(videoNode.videoStyle.includes('width:'), 'videoStyle 应包含 width');
    assert.ok(videoNode.videoStyle.includes('height: 180px'), 'videoStyle 应包含 height: 180px');
    assert.ok(videoNode.videoStyle.includes('border-radius: 12px'), 'videoStyle 应包含 border-radius');
    assert.equal(videoNode.events.start, 'onStart');
    assert.equal(videoNode.events.finish, 'onFinish');
    console.log('✓ video 组件测量与样式编译全部通过');

    console.log('--- 测试 7: 容器 tap 事件继承打平传递 ---');
    const cardTree = {
      type: 'scroll',
      children: [
        {
          id: 'card_col',
          type: 'column',
          events: { tap: 'open_video:BV12345' },
          style: { padding: 12 },
          children: [
            { id: 'card_img', type: 'image', props: { src: 'https://i0.hdslb.com/cover.jpg' } },
            { id: 'card_title', type: 'text', props: { text: '点击卡片标题' } },
            { id: 'card_owner', type: 'text', props: { text: 'UP主名字' } }
          ]
        }
      ]
    };
    const cardRes = compileHydroUi(cardTree, ctx);
    const bgNode = cardRes.flatNodes.find(n => n.id === 'card_col:bg');
    const titleNode = cardRes.flatNodes.find(n => n.id === 'card_title');
    const imgNode = cardRes.flatNodes.find(n => n.id === 'card_img');
    const ownerNode = cardRes.flatNodes.find(n => n.id === 'card_owner');

    assert.ok(bgNode, '应发射卡片背景节点');
    assert.equal(bgNode.tap, 'open_video:BV12345', '背景节点必须具备 tap');
    assert.equal(titleNode.tap, 'open_video:BV12345', '标题文字必须继承父容器 tap');
    assert.equal(imgNode.tap, 'open_video:BV12345', '图片必须继承父容器 tap');
    assert.equal(ownerNode.tap, 'open_video:BV12345', 'UP主文字必须继承父容器 tap');
    console.log('✓ 容器 tap 事件向下继承打平验证通过');

    console.log('--- 测试 8: 列容器 alignItems 交叉轴对齐（桌搭经典版式封面居中回归）---');
    {
      // 真机现象：桌搭经典版式（不开歌词集成）在根 scroll 上写 alignItems:'center'
      // 让 200px 封面居中——引擎此前只在 row 里实现 alignItems，列容器一律左对齐，
      // 于是封面被钉在内容盒左缘（设计空间 x=16），比设计稿的 46 还左。
      const classicTree = {
        type: 'scroll',
        style: { padding: 16, alignItems: 'center', backgroundColor: '#0b0d12' },
        children: [
          { id: 'clk', type: 'text', props: { text: '12:30' }, style: { fontSize: 36, textAlign: 'center', width: '100%' } },
          { id: 'cover', type: 'image', props: { src: 'http://192.168.1.100:9000/api/music/art?size=466' }, style: { width: 200, height: 200 } }
        ]
      };
      const classicRes = compileHydroUi(classicTree, ctx);
      const coverNode = classicRes.flatNodes.find(n => n.id === 'cover');
      const clockNode = classicRes.flatNodes.find(n => n.id === 'clk');
      // 480 设计空间：根 padding 16 → 内容盒 [16, 464]，宽 448；200 宽封面居中
      // → x = 16 + (448-200)/2 = 140，封面中心 240 = 设计空间中心。
      assert.equal(coverNode.x, 140, `居中时封面 x 应为 140（实得 ${coverNode.x}）`);
      assert.equal(coverNode.x + coverNode.w / 2, DESIGN_WIDTH / 2, '封面中心应落在设计空间中心');
      assert.equal(clockNode.x, 16, 'width:100% 的子节点占满内容盒，不受 alignItems 影响');

      // flex-end：右边缘贴内容盒右缘（16 + 448 = 464）
      const endRes = compileHydroUi({
        type: 'scroll',
        style: { padding: 16, alignItems: 'flex-end' },
        children: [{ id: 'img', type: 'image', props: { src: 'https://i0.hdslb.com/cover.jpg' }, style: { width: 200, height: 100 } }]
      }, ctx);
      const endNode = endRes.flatNodes.find(n => n.id === 'img');
      assert.equal(endNode.x + endNode.w, 16 + 448, `flex-end 时右边缘应贴内容盒右缘（实得 ${endNode.x + endNode.w}）`);

      // 缺省（没写 alignItems）：保持左对齐，既有树的行为一个像素都不动
      const plainRes = compileHydroUi({
        type: 'scroll',
        style: { padding: 16 },
        children: [{ id: 'img', type: 'image', props: { src: 'https://i0.hdslb.com/cover.jpg' }, style: { width: 200, height: 100 } }]
      }, ctx);
      assert.equal(plainRes.flatNodes.find(n => n.id === 'img').x, 16, '未写 alignItems 时应保持左对齐（不改变既有行为）');
      // 显式 flex-start 同理
      const startRes = compileHydroUi({
        type: 'scroll',
        style: { padding: 16, alignItems: 'flex-start' },
        children: [{ id: 'img', type: 'image', props: { src: 'https://i0.hdslb.com/cover.jpg' }, style: { width: 200, height: 100 } }]
      }, ctx);
      assert.equal(startRes.flatNodes.find(n => n.id === 'img').x, 16, 'flex-start 应等价于现在的左对齐');

      // absolute 子节点：设计稿版式靠 left 定位，不受交叉轴对齐影响
      const absRes = compileHydroUi({
        type: 'scroll',
        style: { alignItems: 'center' },
        children: [{ id: 'img', type: 'image', props: { src: 'https://i0.hdslb.com/cover.jpg' }, style: { position: 'absolute', left: 4, top: 10, width: 135, height: 135 } }]
      }, ctx);
      assert.equal(absRes.flatNodes.find(n => n.id === 'img').x, 42 + 4, 'absolute 子节点仍按 left 定位（圆屏内边距 42 + 4）');

      // 无显式宽度的图片在居中列里也应居中（宽度取默认 120）
      const autoRes = compileHydroUi({
        type: 'scroll',
        style: { padding: 16, alignItems: 'center' },
        children: [{ id: 'img', type: 'image', props: { src: 'https://i0.hdslb.com/cover.jpg' } }]
      }, ctx);
      const autoNode = autoRes.flatNodes.find(n => n.id === 'img');
      assert.equal(autoNode.w, 120, '无宽度图片默认 120');
      assert.equal(autoNode.x, 16 + (448 - 120) / 2, '无宽度图片在居中列里同样居中');

      console.log('✓ 列容器 alignItems 对齐（居中 / flex-end / 缺省不变 / absolute 不受影响）验证通过');
    }
  });
}
