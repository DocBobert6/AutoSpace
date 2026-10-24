import math

def pt(cx, cy, r, deg):
    a = math.radians(deg - 90)
    return (cx + r*math.cos(a), cy + r*math.sin(a))

def gear_points(cx, cy, teeth, outer_r, inner_r, tooth_frac=0.5):
    slice_w = 360.0 / teeth
    gap = slice_w * (1 - tooth_frac)
    pts = []
    for i in range(teeth):
        start = i * slice_w
        a1 = start + gap/2
        a2 = a1 + slice_w*tooth_frac
        pts.append(pt(cx, cy, inner_r, start))
        pts.append(pt(cx, cy, outer_r, a1))
        pts.append(pt(cx, cy, outer_r, a2))
        pts.append(pt(cx, cy, inner_r, start + slice_w))
    return pts

def path_from_pts(pts):
    d = f"M {pts[0][0]:.2f},{pts[0][1]:.2f} "
    for p in pts[1:]:
        d += f"L {p[0]:.2f},{p[1]:.2f} "
    return d + "Z"

def rotate(pts, ocx, ocy, deg):
    a = math.radians(deg)
    out = []
    for x, y in pts:
        x0, y0 = x-ocx, y-ocy
        out.append((x0*math.cos(a)-y0*math.sin(a)+ocx, x0*math.sin(a)+y0*math.cos(a)+ocy))
    return out

def scale_pts(pts, ocx, ocy, s):
    return [((x-ocx)*s+ocx, (y-ocy)*s+ocy) for x, y in pts]

cx, cy = 54, 54
teeth = 8
outer_r = 36
inner_r = 27

all_pts = gear_points(cx, cy, teeth, outer_r, inner_r)
broken_idx = 1
body_pts = [p for i, p in enumerate(all_pts) if not (4*broken_idx <= i < 4*broken_idx+4)]

tooth_pts = all_pts[4*broken_idx:4*broken_idx+4]
tooth_cx = sum(p[0] for p in tooth_pts)/4
tooth_cy = sum(p[1] for p in tooth_pts)/4

dx, dy = 14, 2
final_pts = rotate([(x+dx, y+dy) for x, y in tooth_pts], tooth_cx+dx, tooth_cy+dy, 26)
final_cx, final_cy = tooth_cx+dx, tooth_cy+dy

trail_half_w_near = 1.2
trail_half_w_far = 7.5
perp = (-dy, dx)
plen = math.hypot(*perp)
perp = (perp[0]/plen, perp[1]/plen)

gap_angle = math.atan2(tooth_cy - cy, tooth_cx - cx)
extend_r = inner_r - 6
gap_x = cx + math.cos(gap_angle) * extend_r
gap_y = cy + math.sin(gap_angle) * extend_r

near1 = (gap_x + perp[0]*trail_half_w_near, gap_y + perp[1]*trail_half_w_near)
near2 = (gap_x - perp[0]*trail_half_w_near, gap_y - perp[1]*trail_half_w_near)
far1 = (final_cx + perp[0]*trail_half_w_far, final_cy + perp[1]*trail_half_w_far)
far2 = (final_cx - perp[0]*trail_half_w_far, final_cy - perp[1]*trail_half_w_far)
trail_pts = [near1, far1, far2, near2]

hub_r = 12

# --- scale everything down to fit the adaptive-icon safe zone (66dp diameter circle) ---
SCALE = 0.66
def S(pts):
    return scale_pts(pts, cx, cy, SCALE)

body_s = S(body_pts)
final_s = S(final_pts)
trail_s = S(trail_pts)
hub_r_s = hub_r * SCALE
gap_x_s, gap_y_s = scale_pts([(gap_x, gap_y)], cx, cy, SCALE)[0]
final_cx_s, final_cy_s = scale_pts([(final_cx, final_cy)], cx, cy, SCALE)[0]

body_path = path_from_pts(body_s)
final_path = path_from_pts(final_s)
trail_path = path_from_pts(trail_s)

GREY = "#8B9096"
RED = "#8C1F1F"
BG = "#0c0c0d"
RING = "#131415"

# ===== 1. Background: full-bleed, unscaled =====
bg_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M0,0h108v108h-108z"
        android:fillColor="{BG}"/>
    <path
        android:pathData="M54,2 A52,52 0 1,1 53.99,2 Z"
        android:fillColor="{RING}"/>
</vector>
'''

# ===== 2. Foreground: gear + broken piece + trail, scaled into the safe zone =====
# Trail uses a gradient (narrow near the gear, fading out) via an <aapt:attr> gradient,
# same visual as the mockup.
fg_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="{trail_path}">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="{gap_x_s:.2f}"
                android:startY="{gap_y_s:.2f}"
                android:endX="{final_cx_s:.2f}"
                android:endY="{final_cy_s:.2f}">
                <item android:offset="0" android:color="#00{RED[1:]}"/>
                <item android:offset="1" android:color="{RED}"/>
            </gradient>
        </aapt:attr>
    </path>
    <path
        android:pathData="{body_path}"
        android:fillColor="{GREY}"/>
    <path
        android:pathData="{final_path}"
        android:fillColor="{RED}"/>
    <path
        android:pathData="M{cx},{cy-hub_r_s:.2f} A{hub_r_s:.2f},{hub_r_s:.2f} 0 1,1 {cx-0.01:.2f},{cy-hub_r_s:.2f} Z"
        android:fillColor="{BG}"/>
</vector>
'''

# ===== 3. Monochrome: same silhouette, single alpha-bearing color for Material You
#     theming. The system substitutes its own tint - the fillColor value here is
#     irrelevant, only the shape/alpha matters. Trail kept at a lower alpha so the
#     fade effect survives tinting too.
mono_xml = f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="{trail_path}"
        android:fillColor="#FF000000"
        android:fillAlpha="0.35"/>
    <path
        android:pathData="{body_path}"
        android:fillColor="#FF000000"/>
    <path
        android:pathData="{final_path}"
        android:fillColor="#FF000000"/>
    <path
        android:pathData="M{cx},{cy-hub_r_s:.2f} A{hub_r_s:.2f},{hub_r_s:.2f} 0 1,1 {cx-0.01:.2f},{cy-hub_r_s:.2f} Z"
        android:fillColor="#00000000"/>
</vector>
'''

out = '/tmp/claude-1000/-home-seb-AABrowser/dadb0938-9338-4349-968f-4a89bc8de777/scratchpad/icon'
open(f'{out}/ic_launcher_background.xml', 'w').write(bg_xml)
open(f'{out}/ic_launcher_foreground.xml', 'w').write(fg_xml)
open(f'{out}/ic_launcher_monochrome.xml', 'w').write(mono_xml)
print("written")
