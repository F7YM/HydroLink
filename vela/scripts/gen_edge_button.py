#!/usr/bin/env python3
"""MD3e 圆表底部按钮（Wear Compose Material3 EdgeButton）PNG 生成器。

形状逐行移植自 androidx wear/compose/material3/EdgeButton.kt 的 ShapeHelper + EdgeButtonShape：
上半是 1/4 圆圆角（半径 r = H - eH/2），下半是半个椭圆（Ry = eH/2，Rx = sqrt(Ry * 屏幕半径)），
两者在最左/最右点以竖直切线相接；低于 ExtraSmall(46dp) 时按官方逻辑渐变为圆角矩形。
输出图底部含官方 3dp 的透明边距，页面里 bottom: 0 贴屏幕底边即可。

    python3 vela/scripts/gen_edge_button.py --size xs --color '#bac3ff' \\
        --out vela/src/common/images/edge-btn-primary.png
"""
import argparse
import math

from PIL import Image, ImageDraw

SUPERSAMPLE = 4
SAMPLES = 256

# EdgeButton.kt 中的常量（dp）
SIZES = {'xs': (46, 24), 's': (56, 32), 'm': (70, 32), 'l': (96, 36)}  # 高度, 图标
EXTRA_SMALL_HEIGHT = 46
EXTRA_SMALL_ELLIPSIS_HEIGHT = 58
BUTTON_TO_ELLIPSIS_RATIO = 1.42
VERTICAL_PADDING = 3
CONTENT_PADDING_TOP, CONTENT_PADDING_BOTTOM = 6, 8


def parse_color(s):
    s = s.lstrip('#')
    if len(s) == 6:
        s += 'ff'
    if len(s) != 8:
        raise argparse.ArgumentTypeError('颜色应为 #RRGGBB 或 #RRGGBBAA')
    return tuple(int(s[i:i + 2], 16) for i in range(0, 8, 2))


def lerp(a, b, t):
    return a + (b - a) * t


def outline(width, height, k):
    """EdgeButtonShape.createOutline：返回按钮轮廓点（全屏宽坐标系，y 向下）。"""
    pad = VERTICAL_PADDING * k
    xs_h = EXTRA_SMALL_HEIGHT * k
    progress = max(1 - height / xs_h, 0.0)
    ellipsis_h = lerp(EXTRA_SMALL_ELLIPSIS_HEIGHT * k + (height - xs_h) * BUTTON_TO_ELLIPSIS_RATIO,
                      height, progress)

    screen_r = width / 2 - pad
    ecx, ecy = width / 2, height - ellipsis_h / 2
    ry = ellipsis_h / 2
    rx = min(math.sqrt(ry * screen_r), screen_r)

    cut = progress * math.pi / 2
    tpx, tpy = ecx + rx * math.cos(cut), ecy + ry * math.sin(cut)
    tx, ty = rx * math.sin(cut), -ry * math.cos(cut)
    tl = math.hypot(tx, ty)
    tx, ty = tx / tl, ty / tl
    cr = tpy / (1 + tx)
    ccx, ccy = tpx + ty * cr, tpy - tx * cr  # tangent 逆时针转 90°
    sweep = math.degrees(math.atan2(tpy - ccy, tpx - ccx)) + 90

    def arc(cx, cy, ax, ay, start, sw):
        return [(cx + ax * math.cos(math.radians(start + sw * i / SAMPLES)),
                 cy + ay * math.sin(math.radians(start + sw * i / SAMPLES)))
                for i in range(SAMPLES + 1)]

    ep = math.degrees(cut)
    pts = arc(ccx, ccy, cr, cr, 270, sweep)                     # 右上圆角
    pts += arc(ecx, ecy, rx, ry, ep, 180 - 2 * ep)              # 底部半椭圆
    pts += arc(width - ccx, ccy, cr, cr, 270 - sweep, sweep)    # 左上圆角
    return pts, dict(r=cr, rx=rx, ry=ry, pad=pad)


def render(pts, out_w, out_h, offset_x, color, scale):
    s = scale * SUPERSAMPLE
    big = (round(out_w * s), round(out_h * s))
    mask = Image.new('L', big, 0)
    ImageDraw.Draw(mask).polygon([((x - offset_x) * s, y * s) for x, y in pts], fill=255)
    mask = mask.resize((round(out_w * scale), round(out_h * scale)), Image.BOX)
    img = Image.new('RGBA', mask.size, color[:3] + (0,))
    img.putalpha(mask.point(lambda v: v * color[3] // 255))
    return img


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--size', choices=SIZES, default='xs', help='官方档位 xs/s/m/l（46/56/70/96dp），默认 xs')
    p.add_argument('--height-dp', type=float, help='自定义按钮高度（dp），覆盖 --size')
    p.add_argument('--screen', type=float, default=480, help='表盘直径（design px），默认 480')
    p.add_argument('--screen-dp', type=float, default=227, help='表盘直径对应的 dp，默认 227（约 1.4 英寸圆屏）')
    p.add_argument('--color', type=parse_color, default=parse_color('#bac3ff'), help='填充色，默认 #bac3ff')
    p.add_argument('--scale', type=float, default=1, help='输出倍率，默认 1')
    p.add_argument('--out', required=True, help='输出 PNG 路径')
    a = p.parse_args()

    k = a.screen / a.screen_dp
    height_dp, icon_dp = SIZES[a.size]
    if a.height_dp:
        height_dp = a.height_dp
    height = height_dp * k

    pts, g = outline(a.screen, height, k)
    left = min(x for x, _ in pts)
    out_w = math.ceil(a.screen - 2 * left)
    out_h = math.ceil(height + g['pad'])
    offset_x = left - (out_w - (a.screen - 2 * left)) / 2

    img = render(pts, out_w, out_h, offset_x, a.color, a.scale)
    img.save(a.out)

    icon = icon_dp * k
    icon_top = max((height - icon + (CONTENT_PADDING_TOP - CONTENT_PADDING_BOTTOM) * k) / 2,
                   CONTENT_PADDING_TOP * k)
    print(f'{a.out}: {img.size[0]}x{img.size[1]}（按钮高 {height:.1f}px + 底部边距 {g["pad"]:.1f}px，'
          f'1dp = {k:.3f}px）')
    print(f'  上圆角 r={g["r"]:.1f}px，底部椭圆 Rx={g["rx"]:.1f} Ry={g["ry"]:.1f}px')
    print(f'  页面：width {out_w}px; height {out_h}px; left {(a.screen - out_w) / 2:g}px（居中）')
    print(f'  图标：{icon:.0f}px，left {(out_w - icon) / 2:.0f}px，top {icon_top:.0f}px')


if __name__ == '__main__':
    main()
