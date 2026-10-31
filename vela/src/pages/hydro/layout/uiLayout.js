// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 float11
// 本文件改编自 JSLab (https://github.com/cciccicu/JSLab)，Copyright (C) 2026 cciccicu，
// 原作品以 GNU General Public License v3.0 发布；本文件同样以 GPL-3.0-only 发布。
//
// HydroKit 纯 JS 布局编译器 (uiLayout.js)
// 借鉴 JSLab 核心架构：纯 JS 计算绝对像素坐标，将嵌套组件树彻底拍平成一维绝对定位数组。
// 彻底杜绝快应用原生 Flex 多层递归塌陷、手势穿透异常与 OOM 隐患。

export const DESIGN_WIDTH = 480;

export const ICON_MAP = {
  play: '/common/icons/play-custom.png',
  pause: '/common/icons/pause-custom.png',
  next: '/common/icons/skip-next-custom.png',
  prev: '/common/icons/skip-previous-custom.png',
  music: '/common/icons/music-circle-custom.png',
  lock: '/common/icons/lock-custom.png',
  gauge: '/common/icons/gauge-custom.png',
  battery: '/common/icons/battery-custom.png',
  laptop: '/common/icons/laptop.png',
  desktop: '/common/icons/desktop.png',
  mobile: '/common/icons/mobile.png'
};

const UNITLESS_KEYS = {
  lines: true, opacity: true, flexGrow: true, flexShrink: true, flex: true,
  order: true, weight: true, fontWeight: true, zIndex: true
};

export function number(value, min, max, fallback) {
  if (value === undefined || value === null) return fallback;
  const n = Number(value);
  return isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
}

export function parseUnit(val, available, fallback) {
  if (val === undefined || val === null || val === '') return fallback;
  if (typeof val === 'number') return isFinite(val) ? val : fallback;
  const str = String(val).trim();
  const pct = /^(\d+(?:\.\d+)?)%$/.exec(str);
  if (pct) return Math.round(Number(pct[1]) * available / 100);
  const px = /^(\d+(?:\.\d+)?)(?:px)?$/.exec(str);
  if (px) return Number(px[1]);
  const num = Number(str);
  return isFinite(num) ? num : fallback;
}

export function charWidth(c) {
  const cp = c.charCodeAt(0);
  const wide = (cp >= 0x1100 && cp <= 0x115f) || (cp >= 0x2e80 && cp <= 0xa4cf) ||
    (cp >= 0xac00 && cp <= 0xd7a3) || (cp >= 0xf900 && cp <= 0xfaff) ||
    (cp >= 0xfe30 && cp <= 0xfe6f) || (cp >= 0xff00 && cp <= 0xff60) ||
    (cp >= 0xffe0 && cp <= 0xffe6) || cp >= 0x1f300;
  if (wide) return 1.0;
  if (cp === 0x20) return 0.3;
  return 0.55;
}

export function measureTextWidth(str, fontSize) {
  const s = String(str == null ? '' : str);
  let total = 0;
  for (let i = 0; i < s.length; i++) {
    total += charWidth(s[i]) * fontSize;
  }
  return total;
}

export function resolveStyle(node, screenCtx) {
  const base = Object.assign({}, (node && node.style) || {});
  const media = node && node.media;
  if (!media || !screenCtx) return base;
  if (screenCtx.shape && media[screenCtx.shape]) {
    Object.assign(base, media[screenCtx.shape]);
  }
  return base;
}

export function normalizeUrl(url, screenCtx) {
  if (!url || typeof url !== 'string') return '';
  let u = url.trim();
  if (u.startsWith('//')) {
    u = 'https:' + u;
  }
  if (u.startsWith('/common/') || u.startsWith('internal://') || u.startsWith('data:')) {
    return u;
  }
  if (screenCtx && screenCtx.host && screenCtx.host !== 'localhost' && screenCtx.host !== '127.0.0.1') {
    if (u.startsWith('/')) {
      u = 'http://' + screenCtx.host + (screenCtx.port ? ':' + screenCtx.port : '') + u;
    } else if (/^http:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?\//.test(u)) {
      u = u.replace(/^http:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?/, 'http://' + screenCtx.host + (screenCtx.port ? ':' + screenCtx.port : ''));
    }
  }
  return u;
}

export function toCssStyle(obj, screenCtx) {
  if (!obj || typeof obj !== 'object') return '';
  const out = [];
  for (const k in obj) {
    let v = obj[k];
    if (v === undefined || v === null || v === '') continue;
    let kebab = k === 'weight' ? 'font-weight' : k.replace(/([A-Z])/g, '-$1').toLowerCase();
    if (kebab === 'background-image' && typeof v === 'string') {
      let trimmed = v.trim();
      if (!trimmed.startsWith('url(') && !trimmed.startsWith('linear-gradient') && !trimmed.startsWith('radial-gradient')) {
        trimmed = normalizeUrl(trimmed, screenCtx);
        v = 'url(' + trimmed + ')';
      }
    }
    if (typeof v === 'number') {
      out.push(kebab + ': ' + v + (UNITLESS_KEYS[k] ? '' : 'px'));
    } else {
      out.push(kebab + ': ' + v);
    }
  }
  return out.join('; ');
}

export function sameNode(a, b) {
  if (!a || !b) return false;
  if (a.id !== b.id || a.kind !== b.kind) return false;
  if (a.text !== b.text || a.background !== b.background || a.wrapStyle !== b.wrapStyle) return false;
  const ka = Object.keys(a);
  const kb = Object.keys(b);
  if (ka.length !== kb.length) return false;
  for (let i = 0; i < ka.length; i++) {
    const k = ka[i];
    if (a[k] !== b[k]) return false;
  }
  return true;
}

/**
 * 将 HydroKit 协议的 UI 树拍平成绝对坐标节点列表
 */
export function compileHydroUi(root, screenCtx, previous, cache) {
  const nodes = [];
  const handlers = Object.create(null);
  const nextCache = Object.create(null);
  let idCounter = 0;

  const shape = (screenCtx && screenCtx.shape) || '';
  const isCircle = shape === 'circle';
  const sw = screenCtx && screenCtx.width > 0 ? screenCtx.width : DESIGN_WIDTH;
  const sh = screenCtx && screenCtx.height > 0 ? screenCtx.height : DESIGN_WIDTH;
  const viewportHeight = Math.round(DESIGN_WIDTH * sh / sw);

  if (!root) {
    return {
      flatNodes: [],
      totalHeight: viewportHeight,
      viewportHeight,
      isScroll: false,
      handlers,
      cache: nextCache
    };
  }

  const isScroll = root.type === 'scroll';
  const rootStyle = resolveStyle(root, screenCtx);

  // 默认圆屏安全区内边距与硬件最小保底
  const defaultPadX = isCircle ? 42 : 0;
  const defaultPadTop = isCircle ? 30 : 0;
  const defaultPadBottom = isScroll ? (isCircle ? 120 : 40) : (isCircle ? 48 : 0);

  const minPadT = isCircle ? 12 : 0;
  const minPadB = isScroll ? (isCircle ? 120 : 40) : (isCircle ? 48 : 0);

  const rootPadL = parseUnit(rootStyle.paddingLeft !== undefined ? rootStyle.paddingLeft : rootStyle.padding, DESIGN_WIDTH, defaultPadX);
  const rootPadR = parseUnit(rootStyle.paddingRight !== undefined ? rootStyle.paddingRight : rootStyle.padding, DESIGN_WIDTH, defaultPadX);
  const rawPadT = parseUnit(rootStyle.paddingTop !== undefined ? rootStyle.paddingTop : rootStyle.padding, viewportHeight, defaultPadTop);
  const rawPadB = parseUnit(rootStyle.paddingBottom !== undefined ? rootStyle.paddingBottom : rootStyle.padding, viewportHeight, defaultPadBottom);

  const rootPadT = Math.max(minPadT, rawPadT);
  const rootPadB = Math.max(minPadB, rawPadB);

  function makeId(node, path) {
    idCounter += 1;
    if (node && node.id) return String(node.id);
    return path || ('$node_' + idCounter);
  }

  // 测量与布局计算
  function layoutNode(node, containerInnerWidth, path, inRow, isRoot, inheritedTap) {
    if (!node) return null;
    const type = node.type || 'box';
    const id = makeId(node, path);
    const style = resolveStyle(node, screenCtx);
    const props = node.props || {};
    const events = node.events || {};

    const effectiveTap = events.tap || (isRoot ? '' : (inheritedTap || ''));

    if (events.tap) handlers[id] = events.tap;
    else if (effectiveTap) handlers[id] = effectiveTap;
    if (events.change) handlers[id] = events.change;

    const isAbs = style.position === 'absolute';
    const marginL = parseUnit(style.marginLeft || style.margin, containerInnerWidth, 0);
    const marginR = parseUnit(style.marginRight || style.margin, containerInnerWidth, 0);
    const marginT = parseUnit(style.marginTop || style.margin, viewportHeight, 0);
    const marginB = parseUnit(style.marginBottom || style.margin, viewportHeight, 0);

    const hasChildren = Array.isArray(node.children) && node.children.length > 0;

    // 容器类组件 (row, column, scroll, card, 或带 children 的 box)
    if (type === 'row' || type === 'column' || type === 'scroll' || type === 'card' || hasChildren) {
      const isRow = type === 'row';

      const padL = isRoot ? rootPadL : parseUnit(style.paddingLeft !== undefined ? style.paddingLeft : style.padding, containerInnerWidth, 0);
      const padR = isRoot ? rootPadR : parseUnit(style.paddingRight !== undefined ? style.paddingRight : style.padding, containerInnerWidth, 0);
      const padT = isRoot ? rootPadT : parseUnit(style.paddingTop !== undefined ? style.paddingTop : style.padding, viewportHeight, 0);
      const padB = isRoot ? rootPadB : parseUnit(style.paddingBottom !== undefined ? style.paddingBottom : style.padding, viewportHeight, 0);
      const gap = parseUnit(style.gap, containerInnerWidth, 0);

      let boxW = isRoot ? DESIGN_WIDTH : parseUnit(style.width, containerInnerWidth, inRow ? null : containerInnerWidth);
      if (boxW === null) boxW = containerInnerWidth;

      const innerW = Math.max(0, boxW - padL - padR);
      const childBoxes = [];
      const children = (node.children || []).filter(c => !!c);

      if (isRow) {
        // ---- 行布局 ----
        let fixedTotal = 0;
        let totalFlex = 0;
        const measured = [];

        children.forEach((c, idx) => {
          const cStyle = resolveStyle(c, screenCtx);
          const flex = Number(cStyle.flexGrow !== undefined ? cStyle.flexGrow : cStyle.flex);
          const cBox = layoutNode(c, innerW, id + '.' + idx, true, false, effectiveTap);
          if (!cBox) return;
          measured.push({ child: c, box: cBox, flex: isFinite(flex) && flex > 0 ? flex : 0 });
          if (isFinite(flex) && flex > 0) {
            totalFlex += flex;
          } else {
            fixedTotal += cBox.w + cBox.marginL + cBox.marginR;
          }
        });

        const totalGaps = Math.max(0, measured.length - 1) * gap;
        let remaining = innerW - fixedTotal - totalGaps;

        if (totalFlex > 0 && remaining > 0) {
          measured.forEach(m => {
            if (m.flex > 0) {
              const allocated = Math.max(1, Math.round(remaining * (m.flex / totalFlex)));
              m.box.w = allocated;
            }
          });
          remaining = 0;
        }

        const justify = style.justifyContent || '';
        let startX = padL;
        let itemSpacing = gap;

        if (totalFlex === 0 && remaining > 0) {
          if (justify === 'space-between' && measured.length > 1) {
            itemSpacing = gap + remaining / (measured.length - 1);
          } else if (justify === 'center') {
            startX += remaining / 2;
          } else if (justify === 'flex-end' || justify === 'end') {
            startX += remaining;
          } else if (justify === 'space-around') {
            const step = remaining / measured.length;
            startX += step / 2;
            itemSpacing = gap + step;
          }
        }

        let maxChildH = 0;
        measured.forEach(m => {
          const ch = m.box.h + m.box.marginT + m.box.marginB;
          if (ch > maxChildH) maxChildH = ch;
        });

        const boxH = parseUnit(style.height, viewportHeight, maxChildH + padT + padB);
        let curX = startX;

        measured.forEach(m => {
          const b = m.box;
          b.x = Math.round(curX + b.marginL);
          // 垂直对齐
          const align = style.alignItems || 'center';
          if (align === 'flex-start' || align === 'top') {
            b.y = Math.round(padT + b.marginT);
          } else if (align === 'flex-end' || align === 'bottom') {
            b.y = Math.round(boxH - padB - b.h - b.marginB);
          } else {
            b.y = Math.round(padT + (boxH - padT - padB - b.h) / 2);
          }
          curX += b.w + b.marginL + b.marginR + itemSpacing;
          childBoxes.push(b);
        });

        return {
          id, kind: 'container', type, isAbs, node, style, isRoot, effectiveTap,
          w: boxW, h: boxH, marginL, marginR, marginT, marginB,
          padL, padT, children: childBoxes
        };
      } else {
        // ---- 列布局 ----
        // 交叉轴（水平）对齐：与行布局的 alignItems 语义对称。
        // 缺省不写 = 左对齐（历史行为，保持不变）；只有显式 center / flex-end
        // 才偏移 x。占满内容宽的子节点（width:'100%'、无宽 text）free=0，不受影响。
        const crossAlign = style.alignItems || '';
        const alignCenter = crossAlign === 'center';
        const alignEnd = crossAlign === 'flex-end' || crossAlign === 'right' || crossAlign === 'end';
        let curY = padT;
        children.forEach((c, idx) => {
          const cBox = layoutNode(c, innerW, id + '.' + idx, false, false, effectiveTap);
          if (!cBox) return;
          if (cBox.isAbs) {
            cBox.x = parseUnit(cBox.style.left, innerW, 0) + padL;
            cBox.y = parseUnit(cBox.style.top, viewportHeight, 0) + padT;
          } else {
            let x = padL + cBox.marginL;
            if (alignCenter || alignEnd) {
              const free = innerW - cBox.marginL - cBox.marginR - cBox.w;
              if (free > 0) x += alignCenter ? free / 2 : free;
            }
            cBox.x = x;
            cBox.y = curY + cBox.marginT;
            curY = cBox.y + cBox.h + cBox.marginB + gap;
          }
          childBoxes.push(cBox);
        });

        const naturalH = curY;
        const boxH = isRoot
          ? naturalH
          : parseUnit(style.height, viewportHeight, Math.max(0, naturalH + padB));

        return {
          id, kind: 'container', type, isAbs, node, style, isRoot, effectiveTap,
          w: boxW, h: boxH, marginL, marginR, marginT, marginB,
          padL, padT, padB, children: childBoxes
        };
      }
    }

    // 叶子组件测量
    return measureLeaf(node, type, id, style, props, events, containerInnerWidth, isAbs, marginL, marginR, marginT, marginB, inRow, effectiveTap);
  }

  function measureLeaf(node, type, id, style, props, events, containerInnerWidth, isAbs, marginL, marginR, marginT, marginB, inRow, inheritedTap) {
    let w = parseUnit(style.width, containerInnerWidth, null);
    let h = parseUnit(style.height, viewportHeight, 0);

    const leaf = {
      id, kind: type, type, isAbs, node, style, props, events,
      w, h, marginL, marginR, marginT, marginB
    };

    if (type === 'text') {
      const textVal = String(props.text !== undefined ? props.text : (props.value !== undefined ? props.value : ''));
      const fontSize = number(style.fontSize, 12, 80, 22);
      const lineHeight = number(style.lineHeight, fontSize, 120, Math.max(fontSize + 4, Math.round(fontSize * 1.3)));
      const linesProp = number(style.lines, 1, 32, 0);

      leaf.text = textVal;
      leaf.color = style.color || '#FFFFFF';
      leaf.size = fontSize;
      leaf.lineHeight = lineHeight;
      leaf.align = style.textAlign || 'left';
      leaf.bold = style.fontWeight === 'bold' || style.fontWeight >= 600 || style.weight >= 600 ? 'bold' : 'normal';
      leaf.tap = events.tap || inheritedTap || '';

      const visualWidth = measureTextWidth(textVal, fontSize);
      if (w === null) {
        w = inRow ? Math.ceil(visualWidth) : containerInnerWidth;
      }
      leaf.w = w;

      if (!leaf.h) {
        const autoLines = Math.max(1, Math.ceil(visualWidth / Math.max(1, w)));
        const finalLines = linesProp > 0 ? Math.min(linesProp, autoLines) : autoLines;
        leaf.lines = finalLines;
        leaf.h = finalLines * lineHeight;
      } else {
        leaf.lines = linesProp || 1;
      }
    } else if (type === 'button') {
      leaf.label = String(props.label || props.text || '');
      leaf.btnColor = style.color || '#FFFFFF';
      leaf.btnSize = number(style.fontSize, 14, 40, 24);
      leaf.background = style.backgroundColor || style.background || '#BAC3FF';
      leaf.radius = number(style.borderRadius, 0, 80, 26);
      leaf.tap = events.tap || inheritedTap || '';
      if (w === null) {
        leaf.w = inRow ? Math.ceil(measureTextWidth(leaf.label, leaf.btnSize) + 48) : containerInnerWidth;
      } else {
        leaf.w = w;
      }
      if (!leaf.h) leaf.h = 52;
    } else if (type === 'image') {
      const rawSrc = String(props.src || props.url || (node && (node.src || node.url)) || '');
      leaf.src = normalizeUrl(rawSrc, screenCtx);
      leaf.radius = number(style.borderRadius, 0, 80, 0);
      leaf.tap = events.tap || inheritedTap || '';
      leaf.w = w !== null ? w : 120;
      if (!leaf.h) leaf.h = 120;
    } else if (type === 'icon') {
      const iconName = props.name || '';
      leaf.iconSrc = ICON_MAP[iconName] || '';
      leaf.tap = events.tap || inheritedTap || '';
      leaf.w = w !== null ? w : 28;
      if (!leaf.h) leaf.h = 28;
    } else if (type === 'badge') {
      const label = String(props.label || props.value || '');
      leaf.badgeText = label;
      leaf.background = style.backgroundColor || '#434659';
      leaf.color = style.color || '#FFFFFF';
      leaf.radius = number(style.borderRadius, 0, 40, 16);
      leaf.tap = events.tap || inheritedTap || '';
      if (!leaf.h) leaf.h = 28;
      leaf.w = w !== null ? w : Math.round(measureTextWidth(label, 20) + 28);
    } else if (type === 'switch') {
      leaf.checked = !!props.checked;
      leaf.change = events.change || '';
      leaf.w = w !== null ? w : 64;
      leaf.h = h || 40;
    } else if (type === 'spinner') {
      leaf.src = '/common/icons/progress-activity.png';
      leaf.w = w !== null ? w : 40;
      if (!leaf.h) leaf.h = 40;
    } else if (type === 'input') {
      leaf.value = String(props.value || '');
      leaf.placeholder = String(props.placeholder || '点击输入');
      leaf.change = events.change || '';
      leaf.w = w !== null ? w : (inRow ? 160 : containerInnerWidth);
      if (!leaf.h) leaf.h = 52;
    } else if (type === 'progress') {
      leaf.percent = number(props.percent, 0, 100, 0);
      leaf.variant = props.variant === 'arc' ? 'arc' : 'horizontal';
      leaf.w = w !== null ? w : containerInnerWidth;
      if (!leaf.h) leaf.h = 12;
    } else if (type === 'nav') {
      leaf.navAction = String(props.action || 'back');
      leaf.navLabel = String(props.label || '‹ 返回');
      leaf.navSurface = props.surface || 'app';
      leaf.navItem = props.item || '';
      leaf.color = style.color || '#FFFFFF';
      leaf.size = number(style.fontSize, 16, 40, 26);
      leaf.w = w !== null ? w : (inRow ? Math.ceil(measureTextWidth(leaf.navLabel, leaf.size) + 16) : containerInnerWidth);
      if (!leaf.h) leaf.h = 36;
    } else if (type === 'video') {
      leaf.src = normalizeUrl(props.src || props.url || '', screenCtx);
      leaf.poster = normalizeUrl(props.poster || '', screenCtx);
      leaf.autoplay = props.autoplay !== undefined ? !!props.autoplay : true;
      leaf.controls = props.controls !== undefined ? !!props.controls : true;
      leaf.muted = !!props.muted;
      leaf.loop = !!props.loop;
      leaf.radius = number(style.borderRadius, 0, 80, 0);
      leaf.tap = events.tap || inheritedTap || '';
      leaf.events = events;
      leaf.w = w !== null ? w : containerInnerWidth;
      if (!leaf.h) leaf.h = parseUnit(props.height, viewportHeight, 200);
    } else {
      // 纯样式盒子或占位
      leaf.background = style.backgroundColor || style.background || 'transparent';
      leaf.radius = number(style.borderRadius, 0, 80, 0);
      leaf.tap = events.tap || inheritedTap || '';
      leaf.w = w !== null ? w : (inRow ? 20 : containerInnerWidth);
      if (!leaf.h) leaf.h = 8;
    }

    return leaf;
  }

  // 递归收集发射绝对节点
  function emitNodes(box, absLeft, absTop) {
    if (!box) return;
    const x = Math.round(absLeft);
    const y = Math.round(absTop);
    const w = Math.round(box.w);
    const h = Math.round(box.h);

    if (box.kind === 'container') {
      const st = box.style;
      const bg = st.backgroundColor || st.background || '';
      const hasBg = (bg && bg !== 'transparent') || !!st.backgroundImage;
      const hasBorder = !!(st.borderWidth || st.borderColor);
      const hasTap = !!((box.node.events && box.node.events.tap) || box.effectiveTap);

      // 非根容器如果具有背景、边框或点击事件，发射背景矩形节点
      if (!box.isRoot && (hasBg || hasBorder || hasTap)) {
        let bgStyle = '';
        if (bg) bgStyle += ` background-color: ${bg};`;
        if (st.backgroundImage) {
          const bgImg = normalizeUrl(st.backgroundImage, screenCtx);
          bgStyle += ` ${toCssStyle({ backgroundImage: bgImg }, screenCtx)};`;
          if (st.backgroundSize) bgStyle += ` background-size: ${st.backgroundSize};`;
          if (st.backgroundPosition) bgStyle += ` background-position: ${st.backgroundPosition};`;
        }
        if (st.borderRadius) bgStyle += ` border-radius: ${st.borderRadius}px;`;
        if (st.borderWidth) bgStyle += ` border-width: ${st.borderWidth}px; border-style: solid;`;
        if (st.borderColor) bgStyle += ` border-color: ${st.borderColor};`;

        const bgNode = {
          id: box.id + ':bg',
          kind: 'container_bg',
          type: 'box',
          x, y, w, h,
          background: bg || 'transparent',
          radius: number(st.borderRadius, 0, 80, 0),
          borderWidth: number(st.borderWidth, 0, 20, 0),
          borderColor: st.borderColor || '',
          tap: (box.node.events && box.node.events.tap) || box.effectiveTap || '',
          wrapStyle: `position: absolute; left: ${x}px; top: ${y}px; width: ${w}px; height: ${h}px;` + bgStyle
        };
        nodes.push(bgNode);
      }

      if (box.children) {
        box.children.forEach(child => {
          emitNodes(child, x + child.x, y + child.y);
        });
      }
    } else {
      // 叶子节点
      const radiusStyle = box.radius ? ` border-radius: ${box.radius}px; overflow: hidden;` : '';
      const wrapStyle = `position: absolute; left: ${x}px; top: ${y}px; width: ${w}px; height: ${h}px;` + radiusStyle;
      const painted = Object.assign({}, box, {
        x, y, w, h,
        wrapStyle
      });

      // 预生成组件内联样式字符串，模板直接绑定，避免运行时解析损耗
      if (box.kind === 'text') {
        painted.textStyle = `color: ${box.color}; font-size: ${box.size}px; line-height: ${box.lineHeight}px; text-align: ${box.align}; font-weight: ${box.bold}; width: 100%; height: 100%;` +
          (box.lines ? ` lines: ${box.lines}; text-overflow: ellipsis;` : '');
      } else if (box.kind === 'button') {
        painted.btnStyle = `background-color: ${box.background}; border-radius: ${box.radius}px; width: 100%; height: 100%; justify-content: center; align-items: center;`;
        painted.btnTextStyle = `color: ${box.btnColor}; font-size: ${box.btnSize}px; font-weight: bold;`;
      } else if (box.kind === 'image') {
        painted.imgStyle = `width: ${w}px; height: ${h}px;` + (box.radius ? ` border-radius: ${box.radius}px;` : '');
      } else if (box.kind === 'icon') {
        painted.iconStyle = `width: ${w}px; height: ${h}px;`;
      } else if (box.kind === 'badge') {
        painted.badgeStyle = `background-color: ${box.background}; border-radius: ${box.radius}px; width: 100%; height: 100%; justify-content: center; align-items: center;`;
        painted.badgeTextStyle = `color: ${box.color}; font-size: 20px;`;
      } else if (box.kind === 'input') {
        painted.inputStyle = `border-width: 2px; border-style: solid; border-color: #5a6270; border-radius: 40px; width: 100%; height: 100%; justify-content: center; align-items: center;`;
        painted.inputTextStyle = `color: #bac3ff; font-size: 22px;`;
      } else if (box.kind === 'nav') {
        const jc = box.style && box.style.justifyContent ? box.style.justifyContent : 'flex-start';
        painted.navStyle = `width: 100%; height: 100%; justify-content: ${jc}; align-items: center;`;
        painted.navTextStyle = `color: ${box.color}; font-size: ${box.size}px;`;
      } else if (box.kind === 'progress') {
        painted.progressStyle = `width: 100%; height: 100%;`;
      } else if (box.kind === 'video') {
        painted.videoStyle = `width: ${w}px; height: ${h}px;` + (box.radius ? ` border-radius: ${box.radius}px; overflow: hidden;` : '');
      } else if (box.kind === 'box') {
        painted.innerStyle = `width: 100%; height: 100%;` +
          (box.background ? ` background-color: ${box.background};` : '') +
          (box.radius ? ` border-radius: ${box.radius}px;` : '');
      }

      nodes.push(painted);
    }
  }

  // 启动编译
  const rootBox = layoutNode(root, DESIGN_WIDTH, 'root', false, true, '');
  let totalContentH = viewportHeight;

  if (rootBox) {
    emitNodes(rootBox, 0, 0);
    totalContentH = isScroll ? Math.max(viewportHeight, rootBox.h + rootPadB) : viewportHeight;
  }

  return {
    flatNodes: nodes,
    totalHeight: totalContentH,
    viewportHeight,
    isScroll,
    handlers,
    cache: nextCache
  };
}
