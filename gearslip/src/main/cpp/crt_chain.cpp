// Shader attribution: this file ports crt-guest-advanced-fast
// by guest(r) (2018-2025), originally released under the GNU
// General Public License, either version 2 of the License, or
// (at your option) any later version (GPLv2-or-later). The
// upstream shader files carry the GPL notice in their headers;
// this port is distributed under the same terms.
//
// CRT pass chain (Phase L, L2). Ports of the fast CRT
// preset's passes, translated from slang to GLSL ES
// 3.00: preset parameters are baked as constants (the
// preset overrides none of them); only the sizes and
// the frame count stay uniforms. Sampler bindings are
// named after the passes they feed.
//
// Chain (the preset's two stock copies are skipped -
// the core texture IS the stock pass): pre -> linearize
// -> pass1 -> bloom horizontal (from linearize) ->
// bloom vertical -> pass2 -> deconv -> screen quad.
// Lookup textures are not needed: the default path
// never takes the lookup branch, so those samplers
// sit on a 1x1 dummy texture.
//
// Framebuffer convention: every chain pass draws a
// fullscreen quad with v = 0 at the bottom, so each
// intermediate texture's row 0 is the image's top
// row, matching the core texture's layout. The
// presenter's existing quad mapping then draws the
// final texture upright.
#include "crt_chain.h"

#include <android/log.h>
#include <vector>

#define CRT_LOG(...) __android_log_print(ANDROID_LOG_ERROR, "autospace-crt", __VA_ARGS__)

namespace {

GLuint compileShader(GLenum type, const char* src) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        CRT_LOG("shader: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint linkProgram(GLuint vs, GLuint fs) {
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[1024];
        glGetProgramInfoLog(program, sizeof(log), nullptr, log);
        CRT_LOG("link: %s", log);
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

const char* kCrtVert =
    "#version 300 es\n"
    "in vec2 aPos;\n"
    "in vec2 aUv;\n"
    "out vec2 vUv;\n"
    "void main() {\n"
    "  vUv = aUv;\n"
    "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
    "}\n";

// Same quad, with the 1.00001 nudge the later passes
// apply to their coordinates (keeps linear sampling
// off exact texel boundaries).
const char* kCrtVertFudge =
    "#version 300 es\n"
    "in vec2 aPos;\n"
    "in vec2 aUv;\n"
    "out vec2 vUv;\n"
    "void main() {\n"
    "  vUv = aUv * 1.00001;\n"
    "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
    "}\n";

// Shared fragment prologue pieces are inlined per
// pass below; every pass declares the samplers it
// reads plus uSourceSize (its main input's size),
// uOriginalSize (the core frame's size) and
// uOutputSize (its own framebuffer's size).

// ---- pre: color tweaks + profiles (defaults:
// TNTC 0, CP 0, CS 0, WP 0, saturation 1, contrast 0,
// brightness 1, gamma-correct 1, black level 0,
// vignette 0, Sega fix off).
const char* kFragPre =
    "#version 300 es\n"
    "precision highp float;\n"
    "in vec2 vUv;\n"
    "uniform sampler2D uSource;\n"
    "uniform sampler2D uLut1;\n"
    "uniform sampler2D uLut2;\n"
    "uniform sampler2D uLut3;\n"
    "uniform sampler2D uLut4;\n"
    "uniform vec4 uOriginalSize;\n"
    "out vec4 oColor;\n"
    "const float TNTC = 0.0;\n"
    "const float LS = 32.0;\n"
    "const float LUTLOW = 5.0;\n"
    "const float LUTBR = 1.0;\n"
    "uniform float CP;\n"
    "uniform float CS;\n"
    "uniform float BP;\n"
    "uniform float WP;\n"
    "uniform float wp_saturation;\n"
    "const float vigstr = 0.0;\n"
    "const float vigdef = 1.0;\n"
    "uniform float sega_fix;\n"
    "uniform float pre_bb;\n"
    "const float contr = 0.0;\n"
    "uniform float pre_gc;\n"
    "const mat3 Profile0 = mat3(\n"
    " 0.412391,  0.212639,  0.019331,\n"
    " 0.357584,  0.715169,  0.119195,\n"
    " 0.180481,  0.072192,  0.950532);\n"
    "const mat3 Profile1 = mat3(\n"
    " 0.430554,  0.222004,  0.020182,\n"
    " 0.341550,  0.706655,  0.129553,\n"
    " 0.178352,  0.071341,  0.939322);\n"
    "const mat3 Profile2 = mat3(\n"
    " 0.396686,  0.210299,  0.006131,\n"
    " 0.372504,  0.713766,  0.115356,\n"
    " 0.181266,  0.075936,  0.967571);\n"
    "const mat3 Profile3 = mat3(\n"
    " 0.393521,  0.212376,  0.018739,\n"
    " 0.365258,  0.701060,  0.111934,\n"
    " 0.191677,  0.086564,  0.958385);\n"
    "const mat3 Profile4 = mat3(\n"
    " 0.392258,  0.209410,  0.016061,\n"
    " 0.351135,  0.725680,  0.093636,\n"
    " 0.166603,  0.064910,  0.850324);\n"
    "const mat3 Profile5 = mat3(\n"
    " 0.377923,  0.195679,  0.010514,\n"
    " 0.317366,  0.722319,  0.097826,\n"
    " 0.207738,  0.082002,  1.076960);\n"
    "const mat3 ToSRGB = mat3(\n"
    " 3.240970, -0.969244,  0.055630,\n"
    "-1.537383,  1.875968, -0.203977,\n"
    "-0.498611,  0.041555,  1.056972);\n"
    "const mat3 ToModern = mat3(\n"
    " 2.791723, -0.894766,  0.041678,\n"
    "-1.173165,  1.815586, -0.130886,\n"
    "-0.440973,  0.032000,  1.002034);\n"
    "const mat3 ToDCI = mat3(\n"
    " 2.973422, -0.867605,  0.045031,\n"
    "-1.110433,  1.843757, -0.095697,\n"
    "-0.480247,  0.024743,  1.201215);\n"
    "const mat3 ToAdobe = mat3(\n"
    " 2.041588, -0.969244,  0.013444,\n"
    "-0.565007,  1.875968, -0.11836,\n"
    "-0.344731,  0.041555,  1.015175);\n"
    "const mat3 ToREC = mat3(\n"
    " 1.716651, -0.666684,  0.017640,\n"
    "-0.355671,  1.616481, -0.042771,\n"
    "-0.253366,  0.015769,  0.942103);\n"
    "const mat3 ToP3 = mat3(\n"
    " 2.493509, -0.829473,  0.0358512,\n"
    "-0.931388,  1.762630, -0.0761839,\n"
    "-0.402712,  0.023624,  0.9570296);\n"
    "const mat3 D65_to_D55 = mat3(\n"
    " 0.4850339153,  0.2500956126,  0.0227359648,\n"
    " 0.3488957224,  0.6977914447,  0.1162985741,\n"
    " 0.1302823568,  0.0521129427,  0.6861537456);\n"
    "const mat3 D65_to_D93 = mat3(\n"
    " 0.3412754080,  0.1759701322,  0.0159972847,\n"
    " 0.3646170520,  0.7292341040,  0.1215390173,\n"
    " 0.2369894093,  0.0947957637,  1.2481442225);\n"
    "vec3 fix_lut(vec3 lutcolor, vec3 ref) {\n"
    "  float r = length(ref);\n"
    "  float l = length(lutcolor);\n"
    "  float m = max(max(ref.r, ref.g), ref.b);\n"
    "  ref = normalize(lutcolor + 0.0000001) * mix(r, l, pow(m, 1.25));\n"
    "  return mix(lutcolor, ref, LUTBR);\n"
    "}\n"
    "float vignette(vec2 pos) {\n"
    "  vec2 b = vec2(vigdef, vigdef) * vec2(1.0, uOriginalSize.x / uOriginalSize.y) * 0.125;\n"
    "  pos = clamp(pos, 0.0, 1.0);\n"
    "  pos = abs(2.0 * (pos - 0.5));\n"
    "  vec2 res = mix(vec2(0.0), vec2(1.0), smoothstep(vec2(1.0), vec2(1.0) - b, sqrt(pos)));\n"
    "  res = pow(res, vec2(0.70));\n"
    "  return max(mix(1.0, sqrt(res.x * res.y), vigstr), 0.0);\n"
    "}\n"
    "vec3 plant(vec3 tar, float r) {\n"
    "  float t = max(max(tar.r, tar.g), tar.b) + 0.00001;\n"
    "  return tar * r / t;\n"
    "}\n"
    "float contrast(float x) {\n"
    "  return max(mix(x, smoothstep(0.0, 1.0, x), contr), 0.0);\n"
    "}\n"
    "vec3 pgc(vec3 c) {\n"
    "  float mc = max(max(c.r, c.g), c.b);\n"
    "  float mg = pow(mc, 1.0 / pre_gc);\n"
    "  return c * mg / (mc + 1e-8);\n"
    "}\n"
    "void main() {\n"
    "  vec4 imgColor = texture(uSource, vUv.xy);\n"
    "  float w = float((imgColor.r + imgColor.g + imgColor.b) < 1.5 / 255.0);\n"
    "  float bp = w * BP / 255.0;\n"
    "  if (sega_fix > 0.5) imgColor.rgb = imgColor.rgb * (255.0 / 239.0);\n"
    "  imgColor.rgb = min(imgColor.rgb, 1.0);\n"
    "  vec3 color = imgColor.rgb;\n"
    "  if (int(TNTC) == 0) {\n"
    "    color.rgb = imgColor.rgb;\n"
    "  } else {\n"
    "    float lutlow = LUTLOW / 255.0;\n"
    "    float invLS = 1.0 / LS;\n"
    "    vec3 lut_ref = imgColor.rgb + lutlow * (1.0 - pow(imgColor.rgb, vec3(0.333)));\n"
    "    float lutb = lut_ref.b * (1.0 - 0.5 * invLS);\n"
    "    lut_ref.rg = lut_ref.rg * (1.0 - invLS) + 0.5 * invLS;\n"
    "    float tile1 = ceil(lutb * (LS - 1.0));\n"
    "    float tile0 = max(tile1 - 1.0, 0.0);\n"
    "    float f = fract(lutb * (LS - 1.0));\n"
    "    if (f == 0.0) f = 1.0;\n"
    "    vec2 coord0 = vec2(tile0 + lut_ref.r, lut_ref.g) * vec2(invLS, 1.0);\n"
    "    vec2 coord1 = vec2(tile1 + lut_ref.r, lut_ref.g) * vec2(invLS, 1.0);\n"
    "    vec4 color1, color2, res;\n"
    "    if (int(TNTC) == 1) {\n"
    "      color1 = texture(uLut1, coord0);\n"
    "      color2 = texture(uLut1, coord1);\n"
    "      res = mix(color1, color2, f);\n"
    "    } else if (int(TNTC) == 2) {\n"
    "      color1 = texture(uLut2, coord0);\n"
    "      color2 = texture(uLut2, coord1);\n"
    "      res = mix(color1, color2, f);\n"
    "    } else if (int(TNTC) == 3) {\n"
    "      color1 = texture(uLut3, coord0);\n"
    "      color2 = texture(uLut3, coord1);\n"
    "      res = mix(color1, color2, f);\n"
    "    } else if (int(TNTC) == 4) {\n"
    "      color1 = texture(uLut4, coord0);\n"
    "      color2 = texture(uLut4, coord1);\n"
    "      res = mix(color1, color2, f);\n"
    "    }\n"
    "    res.rgb = fix_lut(res.rgb, imgColor.rgb);\n"
    "    color = mix(imgColor.rgb, res.rgb, min(TNTC, 1.0));\n"
    "  }\n"
    "  vec3 c = clamp(color, 0.0, 1.0);\n"
    "  float p;\n"
    "  mat3 m_out;\n"
    "  if (CS == 0.0) { p = 2.2; m_out = ToSRGB; } else\n"
    "  if (CS == 1.0) { p = 2.2; m_out = ToModern; } else\n"
    "  if (CS == 2.0) { p = 2.6; m_out = ToDCI; } else\n"
    "  if (CS == 3.0) { p = 2.2; m_out = ToAdobe; } else\n"
    "  if (CS == 4.0) { p = 2.4; m_out = ToREC; } else\n"
    "  if (CS == 5.0) { p = 2.2; m_out = ToP3; }\n"
    "  color = pow(c, vec3(p));\n"
    "  mat3 m_in = Profile0;\n"
    "  if (CP == 0.0) { m_in = Profile0; } else\n"
    "  if (CP == 1.0) { m_in = Profile1; } else\n"
    "  if (CP == 2.0) { m_in = Profile2; } else\n"
    "  if (CP == 3.0) { m_in = Profile3; } else\n"
    "  if (CP == 4.0) { m_in = Profile4; } else\n"
    "  if (CP == 5.0) { m_in = Profile5; }\n"
    "  color = m_in * color;\n"
    "  color = m_out * color;\n"
    "  color = clamp(color, 0.0, 1.0);\n"
    "  color = pow(color, vec3(1.0 / p));\n"
    "  if (CP == -1.0) color = c;\n"
    "  vec3 scolor1 = plant(pow(color, vec3(wp_saturation)), max(max(color.r, color.g), color.b));\n"
    "  float luma = dot(color, vec3(0.299, 0.587, 0.114));\n"
    "  vec3 scolor2 = mix(vec3(luma), color, wp_saturation);\n"
    "  color = (wp_saturation > 1.0) ? scolor1 : scolor2;\n"
    "  color = plant(color, contrast(max(max(color.r, color.g), color.b)));\n"
    "  p = 2.2;\n"
    "  color = clamp(color, 0.0, 1.0);\n"
    "  color = pow(color, vec3(p));\n"
    "  vec3 warmer = D65_to_D55 * color;\n"
    "  warmer = ToSRGB * warmer;\n"
    "  vec3 cooler = D65_to_D93 * color;\n"
    "  cooler = ToSRGB * cooler;\n"
    "  float m = abs(WP) / 100.0;\n"
    "  vec3 comp = (WP < 0.0) ? cooler : warmer;\n"
    "  color = mix(color, comp, m);\n"
    "  color = pow(max(color, 0.0), vec3(1.0 / p));\n"
    "  color = pgc(color);\n"
    "  if (BP > -0.5) color = color + bp; else {\n"
    "    color = max(color + BP / 255.0, 0.0) / (1.0 + BP / 255.0 * step(-BP / 255.0, max(max(color.r, color.g), color.b)));\n"
    "  }\n"
    "  color = min(color * pre_bb, 1.0);\n"
    "  oColor = vec4(color, vignette(vUv.xy));\n"
    "}\n";

// ---- linearize: gamma + interlace handling
// (defaults: gamma in/out 2.4, interlace trigger
// 375, mode 1, effect 0.20, internal-res 0,
// downsample 0, saturation 0.25, VGA off, hiscan off).
const char* kFragLinearize =
    "#version 300 es\n"
    "precision highp float;\n"
    "in vec2 vUv;\n"
    "uniform sampler2D uPrePass;\n"
    "uniform vec4 uSourceSize;\n"
    "uniform vec4 uOriginalSize;\n"
    "uniform float uFrameCount;\n"
    "out vec4 oColor;\n"
    "const float GAMMA_INPUT = 2.4;\n"
    "const float gamma_out = 2.4;\n"
    "const float inter = 375.0;\n"
    "const float interm = 1.0;\n"
    "const float iscan = 0.20;\n"
    "const float intres = 0.0;\n"
    "const float downsample_levelx = 0.0;\n"
    "const float downsample_levely = 0.0;\n"
    "const float iscans = 0.25;\n"
    "const float vga_mode = 0.0;\n"
    "const float hiscan = 0.0;\n"
    "vec3 plant(vec3 tar, float r) {\n"
    "  float t = max(max(tar.r, tar.g), tar.b) + 0.00001;\n"
    "  return tar * r / t;\n"
    "}\n"
    "vec3 fetch_pixel(vec2 coord) {\n"
    "  vec2 dx = vec2(uSourceSize.z, 0.0) * downsample_levelx;\n"
    "  vec2 dy = vec2(0.0, uSourceSize.w) * downsample_levely;\n"
    "  vec2 d1 = dx + dy;\n"
    "  vec2 d2 = dx - dy;\n"
    "  float sum = 15.0;\n"
    "  vec3 result = 3.0 * texture(uPrePass, coord).rgb +\n"
    "    2.0 * texture(uPrePass, coord + dx).rgb +\n"
    "    2.0 * texture(uPrePass, coord - dx).rgb +\n"
    "    2.0 * texture(uPrePass, coord + dy).rgb +\n"
    "    2.0 * texture(uPrePass, coord - dy).rgb +\n"
    "    texture(uPrePass, coord + d1).rgb +\n"
    "    texture(uPrePass, coord - d1).rgb +\n"
    "    texture(uPrePass, coord + d2).rgb +\n"
    "    texture(uPrePass, coord - d2).rgb;\n"
    "  return result / sum;\n"
    "}\n"
    "void main() {\n"
    "  vec3 c1 = texture(uPrePass, vUv).rgb;\n"
    "  vec3 c2 = texture(uPrePass, vUv + vec2(0.0, uOriginalSize.w)).rgb;\n"
    "  if ((downsample_levelx + downsample_levely) > 0.025) {\n"
    "    c1 = fetch_pixel(vUv);\n"
    "    c2 = fetch_pixel(vUv + vec2(0.0, uOriginalSize.w));\n"
    "  }\n"
    "  vec3 c = c1;\n"
    "  float intera = 1.0;\n"
    "  float gamma_in = GAMMA_INPUT;\n"
    "  float m1 = max(max(c1.r, c1.g), c1.b);\n"
    "  float m2 = max(max(c2.r, c2.g), c2.b);\n"
    "  vec3 df = abs(c1 - c2);\n"
    "  float d = max(max(df.r, df.g), df.b);\n"
    "  if (interm == 2.0) d = mix(0.1 * d, 10.0 * d, step(m1 / (m2 + 0.0001), m2 / (m1 + 0.0001)));\n"
    "  float r = m1;\n"
    "  float yres_div = 1.0;\n"
    "  if (intres > 1.25) yres_div = intres;\n"
    "  bool hscan = (hiscan > 0.5);\n"
    "  if (inter <= uOriginalSize.y / yres_div && interm > 0.5 && intres != 1.0 && intres != 0.5 && vga_mode < 0.5) {\n"
    "    intera = 0.25;\n"
    "    float line_no = floor(mod(uOriginalSize.y * vUv.y, 2.0));\n"
    "    float frame_no = floor(mod(uFrameCount, 2.0));\n"
    "    float ii = abs(line_no - frame_no);\n"
    "    if (interm < 3.5 || interm > 5.5) {\n"
    "      if (interm == 6.0) c = mix(c2, c1, ii); else {\n"
    "        c2 = plant(mix(c2, c2 * c2, iscans), max(max(c2.r, c2.g), c2.b));\n"
    "        r = max(m1 * ii, (1.0 - iscan) * min(m1, m2));\n"
    "        c = plant(mix(mix(c1, c2, min(mix(m1, 1.0 - m2, min(m1, 1.0 - m1)) / (d + 0.00001), 1.0)), c1, ii), r);\n"
    "        if (interm == 3.0) c = (1.0 - 0.5 * iscan) * mix(c2, c1, ii);\n"
    "      }\n"
    "    }\n"
    "    if (interm == 4.0) { c = plant(mix(c, c * c, 0.5 * iscans), max(max(c.r, c.g), c.b)) * (1.0 - 0.5 * iscan); }\n"
    "    if (interm == 5.0) { c = mix(c2, c1, 0.5); c = plant(mix(c, c * c, 0.5 * iscans), max(max(c.r, c.g), c.b)) * (1.0 - 0.5 * iscan); }\n"
    "    if (hscan) c = c1;\n"
    "  }\n"
    "  if (vga_mode > 0.5) {\n"
    "    c = c1;\n"
    "    if (inter <= uOriginalSize.y) intera = 0.75; else intera = 0.5;\n"
    "  }\n"
    "  c = pow(c, vec3(gamma_in));\n"
    "  if (vUv.x > 0.5) gamma_in = intera; else gamma_in = 1.0 / gamma_in;\n"
    "  oColor = vec4(c, gamma_in);\n"
    "}\n";

// ---- pass1: horizontal sharpness (defaults:
// sharpness 5.20, subtractive 0.50, ringing 0,
// spike removal 1.0).
const char* kFragPass1 =
    "#version 300 es\n"
    "precision highp float;\n"
    "in vec2 vUv;\n"
    "uniform sampler2D uLinearizePass;\n"
    "uniform vec4 uOriginalSize;\n"
    "out vec4 oColor;\n"
    "const float h_sharp = 5.20;\n"
    "const float s_sharp = 0.50;\n"
    "const float ring = 0.0;\n"
    "const float spike = 1.0;\n"
    "void main() {\n"
    "  vec2 prescalex = vec2(textureSize(uLinearizePass, 0)) / uOriginalSize.xy;\n"
    "  vec4 SourceSize = uOriginalSize * vec4(prescalex.x, prescalex.y, 1.0 / prescalex.x, 1.0 / prescalex.y);\n"
    "  float intera = texture(uLinearizePass, vec2(0.75, 0.25)).a;\n"
    "  bool interb = (intera < 0.5);\n"
    "  vec2 texcoord = vUv.xy;\n"
    "  vec2 pos = texcoord;\n"
    "  vec2 coffset = vec2(0.5, 0.5);\n"
    "  vec2 ps = SourceSize.zw;\n"
    "  vec2 OGL2Pos = pos * SourceSize.xy - coffset;\n"
    "  float fpx = fract(OGL2Pos.x);\n"
    "  vec2 offx = vec2(ps.x, 0.0);\n"
    "  vec2 x2 = 2.0 * offx;\n"
    "  vec2 pC4 = floor(OGL2Pos) * ps + 0.5 * ps;\n"
    "  float zero = exp2(-h_sharp);\n"
    "  float sharp1 = s_sharp * zero;\n"
    "  float idiv = clamp(SourceSize.x / 400.0, 1.0, 2.0);\n"
    "  float fdivider = max(min(prescalex.x, 2.0), idiv * float(interb));\n"
    "  fdivider = 1.0 / max(fdivider, 1.0);\n"
    "  float wl3 = (2.0 + fpx) * fdivider;\n"
    "  float wl2 = (1.0 + fpx) * fdivider;\n"
    "  float wl1 = (fpx) * fdivider;\n"
    "  float wr1 = (1.0 - fpx) * fdivider;\n"
    "  float wr2 = (2.0 - fpx) * fdivider;\n"
    "  float wr3 = (3.0 - fpx) * fdivider;\n"
    "  wl3 *= wl3; wl3 = exp2(-h_sharp * wl3);\n"
    "  wl2 *= wl2; wl2 = exp2(-h_sharp * wl2);\n"
    "  wl1 *= wl1; wl1 = exp2(-h_sharp * wl1);\n"
    "  wr1 *= wr1; wr1 = exp2(-h_sharp * wr1);\n"
    "  wr2 *= wr2; wr2 = exp2(-h_sharp * wr2);\n"
    "  wr3 *= wr3; wr3 = exp2(-h_sharp * wr3);\n"
    "  float fp1 = 1.0 - fpx;\n"
    "  float twl3 = max(wl3 - sharp1, 0.0);\n"
    "  float twl2 = max(wl2 - sharp1, mix(-0.12, 0.0, 1.0 - fp1 * fp1));\n"
    "  float twl1 = max(wl1 - sharp1, -0.12);\n"
    "  float twr1 = max(wr1 - sharp1, -0.12);\n"
    "  float twr2 = max(wr2 - sharp1, mix(-0.12, 0.0, 1.0 - fpx * fpx));\n"
    "  float twr3 = max(wr3 - sharp1, 0.0);\n"
    "  bool sharp = (sharp1 > 0.0);\n"
    "  vec3 l3, l2, l1, r1, r2, r3, color1, colmin, colmax;\n"
    "  l3 = texture(uLinearizePass, pC4 - x2).rgb;\n"
    "  l2 = texture(uLinearizePass, pC4 - offx).rgb;\n"
    "  l1 = texture(uLinearizePass, pC4).rgb;\n"
    "  r1 = texture(uLinearizePass, pC4 + offx).rgb;\n"
    "  r2 = texture(uLinearizePass, pC4 + x2).rgb;\n"
    "  r3 = texture(uLinearizePass, pC4 + offx + x2).rgb;\n"
    "  colmin = min(min(l1, r1), min(l2, r2));\n"
    "  colmax = max(max(l1, r1), max(l2, r2));\n"
    "  color1 = (l3 * twl3 + l2 * twl2 + l1 * twl1 + r1 * twr1 + r2 * twr2 + r3 * twr3) / (twl3 + twl2 + twl1 + twr1 + twr2 + twr3);\n"
    "  if (sharp) color1 = clamp(mix(clamp(color1, colmin, colmax), color1, ring), 0.0, 1.0);\n"
    "  float ts = 0.025;\n"
    "  vec3 luma = vec3(0.2126, 0.7152, 0.0722);\n"
    "  float lm2 = max(max(l2.r, l2.g), l2.b);\n"
    "  float lm1 = max(max(l1.r, l1.g), l1.b);\n"
    "  float rm1 = max(max(r1.r, r1.g), r1.b);\n"
    "  float rm2 = max(max(r2.r, r2.g), r2.b);\n"
    "  float swl2 = max(twl2, 0.0) * (dot(l2, luma) + ts);\n"
    "  float swl1 = max(twl1, 0.0) * (dot(l1, luma) + ts);\n"
    "  float swr1 = max(twr1, 0.0) * (dot(r1, luma) + ts);\n"
    "  float swr2 = max(twr2, 0.0) * (dot(r2, luma) + ts);\n"
    "  float fscolor1 = (lm2 * swl2 + lm1 * swl1 + rm1 * swr1 + rm2 * swr2) / (swl2 + swl1 + swr1 + swr2);\n"
    "  float sresult = clamp(mix(max(max(color1.r, color1.g), color1.b), fscolor1, spike), 0.0, 1.0);\n"
    "  oColor = vec4(color1, sresult);\n"
    "}\n";

// ---- bloom horizontal (defaults: fine 1.0,
// radius 3.0, sigma 0.75). Samples linearize, not
// pass1 - the bloom is computed from the linearized
// image by design.
const char* kFragBloomH =
    "#version 300 es\n"
    "precision highp float;\n"
    "in vec2 vUv;\n"
    "uniform sampler2D uLinearizePass;\n"
    "uniform vec4 uOriginalSize;\n"
    "out vec4 oColor;\n"
    "const float FINE_BLOOM = 1.0;\n"
    "const float SIZEHB = 3.0;\n"
    "const float SIGMA_HB = 0.75;\n"
    "float invsqrsigma = 1.0 / (2.0 * SIGMA_HB * SIGMA_HB);\n"
    "float gaussian(float x) {\n"
    "  return exp(-x * x * invsqrsigma);\n"
    "}\n"
    "void main() {\n"
    "  vec4 SourceSize1 = uOriginalSize * vec4(FINE_BLOOM, FINE_BLOOM, 1.0 / FINE_BLOOM, 1.0 / FINE_BLOOM);\n"
    "  float f = fract(SourceSize1.x * vUv.x);\n"
    "  f = 0.5 - f;\n"
    "  vec2 tex = floor(SourceSize1.xy * vUv) * SourceSize1.zw + 0.5 * SourceSize1.zw;\n"
    "  vec4 color = vec4(0.0);\n"
    "  vec2 dx = vec2(SourceSize1.z, 0.0);\n"
    "  float w;\n"
    "  float wsum = 0.0;\n"
    "  vec4 pixel;\n"
    "  float n = -SIZEHB;\n"
    "  do {\n"
    "    pixel = texture(uLinearizePass, tex + n * dx);\n"
    "    w = gaussian(n + f);\n"
    "    pixel.a = max(max(pixel.r, pixel.g), pixel.b);\n"
    "    pixel.a *= pixel.a * pixel.a;\n"
    "    color = color + w * pixel;\n"
    "    wsum = wsum + w;\n"
    "    n = n + 1.0;\n"
    "  } while (n <= SIZEHB);\n"
    "  color = color / wsum;\n"
    "  oColor = vec4(color.rgb, pow(color.a, 0.333333));\n"
    "}\n";

// ---- bloom vertical (defaults: fine 1.0,
// radius 3.0, sigma 0.60). Samples the horizontal
// bloom output.
const char* kFragBloomV =
    "#version 300 es\n"
    "precision highp float;\n"
    "in vec2 vUv;\n"
    "uniform sampler2D uSource;\n"
    "uniform vec4 uSourceSize;\n"
    "uniform vec4 uOriginalSize;\n"
    "out vec4 oColor;\n"
    "const float FINE_BLOOM = 1.0;\n"
    "const float SIZEVB = 3.0;\n"
    "const float SIGMA_VB = 0.60;\n"
    "float invsqrsigma = 1.0 / (2.0 * SIGMA_VB * SIGMA_VB);\n"
    "float gaussian(float x) {\n"
    "  return exp(-x * x * invsqrsigma);\n"
    "}\n"
    "void main() {\n"
    "  vec4 SourceSize1 = uSourceSize;\n"
    "  SourceSize1.yw = uOriginalSize.yw;\n"
    "  SourceSize1 = SourceSize1 * vec4(FINE_BLOOM, FINE_BLOOM, 1.0 / FINE_BLOOM, 1.0 / FINE_BLOOM);\n"
    "  float f = fract(SourceSize1.y * vUv.y);\n"
    "  f = 0.5 - f;\n"
    "  vec2 tex = floor(SourceSize1.xy * vUv) * SourceSize1.zw + 0.5 * SourceSize1.zw;\n"
    "  vec4 color = vec4(0.0);\n"
    "  vec2 dy = vec2(0.0, SourceSize1.w);\n"
    "  float w;\n"
    "  float wsum = 0.0;\n"
    "  vec4 pixel;\n"
    "  float n = -SIZEVB;\n"
    "  do {\n"
    "    pixel = texture(uSource, tex + n * dy);\n"
    "    w = gaussian(n + f);\n"
    "    pixel.a *= pixel.a * pixel.a;\n"
    "    color = color + w * pixel;\n"
    "    wsum = wsum + w;\n"
    "    n = n + 1.0;\n"
    "  } while (n <= SIZEVB);\n"
    "  color = color / wsum;\n"
    "  oColor = vec4(color.rgb, pow(color.a, 0.175));\n"
    "}\n";

// ---- pass2: scanlines + beam shape (defaults:
// interlace 1, hiscan 0, internal-res 0, integer
// scaling 0, curvature 0, overscan 0, shift 0,
// corner/border 0, border intensity 0.75, glow 0.08,
// bloom 0, mask bloom 0, bloom distribution 0,
// halation 0, bloom/halo mask 0 / 0.35, gamma 1 / 1,
// bright boost 1.40 / 1.10, clips 0, scanline type 0,
// beam center 6 / edges 8, beam min 1.30 / max 1.00,
// thinner dark 0, beam size 0.60, scanline
// saturation 0.50 (x1.5 in the shader), falloff 1.0,
// no-scanline mode 0).
const char* kFragPass2 =
    "#version 300 es\n"
    "precision highp float;\n"
    "in vec2 vUv;\n"
    "uniform sampler2D uPass1;\n"
    "uniform sampler2D uLinearizePass;\n"
    "uniform sampler2D uBloomPass;\n"
    "uniform sampler2D uPrePass;\n"
    "uniform vec4 uSourceSize;\n"
    "uniform vec4 uOriginalSize;\n"
    "uniform vec4 uOutputSize;\n"
    "uniform float uFrameCount;\n"
    "out vec4 oColor;\n"
    "const float interm = 1.0;\n"
    "const float hiscan = 0.0;\n"
    "const float intres = 0.0;\n"
    "const float IOS = 0.0;\n"
    "const float warpX = 0.0;\n"
    "const float warpY = 0.0;\n"
    "const float c_shape = 0.25;\n"
    "const float overscanX = 0.0;\n"
    "const float overscanY = 0.0;\n"
    "const float VShift = 0.0;\n"
    "const float csize = 0.0;\n"
    "const float bsize1 = 0.0;\n"
    "const float sborder = 0.75;\n"
    "const float barspeed = 50.0;\n"
    "const float barintensity = 0.0;\n"
    "const float bardir = 0.0;\n"
    "const float glow = 0.08;\n"
    "const float bloom = 0.0;\n"
    "const float mask_bloom = 0.0;\n"
    "const float bloom_dist = 0.0;\n"
    "const float halation = 0.0;\n"
    "const float bmask1 = 0.0;\n"
    "const float hmask1 = 0.35;\n"
    "const float gamma_c = 1.0;\n"
    "const float gamma_c2 = 1.0;\n"
    "const float brightboost = 1.40;\n"
    "const float brightboost1 = 1.10;\n"
    "const float clips = 0.0;\n"
    "const float gsl = 0.0;\n"
    "const float scanline1 = 6.0;\n"
    "const float scanline2 = 8.0;\n"
    "const float beam_min = 1.30;\n"
    "const float beam_max = 1.00;\n"
    "const float tds = 0.0;\n"
    "const float beam_size = 0.60;\n"
    "const float scans = 0.75;\n"
    "const float scan_falloff = 1.0;\n"
    "const float no_scanlines = 0.0;\n"
    "const float eps = 1e-10;\n"
    "float st(float x) { return exp2(-10.0 * x * x); }\n"
    "float st1(float x) { return exp2(-8.0 * x * x); }\n"
    "vec3 sw0(float x, float color, float scanline, vec3 c) {\n"
    "  float tmp = mix(beam_min, beam_max, color);\n"
    "  vec3 sat = mix(vec3(1.0) + scans, vec3(1.0), c);\n"
    "  float ex = x * tmp;\n"
    "  ex = (gsl > -0.5) ? ex * ex : mix(ex * ex, ex * ex * ex, 0.4);\n"
    "  return exp2(-scanline * ex * sat);\n"
    "}\n"
    "vec3 sw1(float x, float color, float scanline, vec3 c) {\n"
    "  x = mix(x, beam_min * x, max(x - 0.4 * color, 0.0));\n"
    "  vec3 sat = mix(vec3(1.0) + scans, vec3(1.0), c);\n"
    "  float tmp = mix(1.2 * beam_min, beam_max, color);\n"
    "  float ex = x * tmp;\n"
    "  return exp2(-scanline * ex * ex * sat);\n"
    "}\n"
    "vec3 sw2(float x, float color, float scanline, vec3 c) {\n"
    "  float tmp = mix((2.5 - 0.5 * color) * beam_min, beam_max, color);\n"
    "  vec3 sat = mix(vec3(1.0) + scans, vec3(1.0), c);\n"
    "  tmp = mix(beam_max, tmp, pow(x, color + 0.3));\n"
    "  float ex = x * tmp;\n"
    "  return exp2(-scanline * ex * ex * sat);\n"
    "}\n"
    "vec3 gc(vec3 c) {\n"
    "  float mc = max(max(c.r, c.g), c.b);\n"
    "  float mg = pow(mc, 1.0 / gamma_c);\n"
    "  return c * mg / (mc + eps);\n"
    "}\n"
    "vec2 Overscan(vec2 pos, float dx, float dy) {\n"
    "  pos = pos * 2.0 - 1.0;\n"
    "  pos *= vec2(dx, dy);\n"
    "  return pos * 0.5 + 0.5;\n"
    "}\n"
    "vec2 Warp(vec2 pos) {\n"
    "  pos = pos * 2.0 - 1.0;\n"
    "  pos = mix(pos, vec2(pos.x * inversesqrt(1.0 - c_shape * pos.y * pos.y), pos.y * inversesqrt(1.0 - c_shape * pos.x * pos.x)), vec2(warpX, warpY) / c_shape);\n"
    "  return pos * 0.5 + 0.5;\n"
    "}\n"
    "vec3 plant(vec3 tar, float r) {\n"
    "  float t = max(max(tar.r, tar.g), tar.b) + 0.00001;\n"
    "  return tar * r / t;\n"
    "}\n"
    "void main() {\n"
    "  vec2 prescalex = vec2(textureSize(uLinearizePass, 0)) / uOriginalSize.xy;\n"
    "  vec4 SourceSize = vec4(uSourceSize.x, uOriginalSize.y, uSourceSize.z, uSourceSize.w);\n"
    "  float gamma_in = 1.0 / texture(uLinearizePass, vec2(0.25, 0.25)).a;\n"
    "  float intera = texture(uLinearizePass, vec2(0.75, 0.25)).a;\n"
    "  bool hscan = (hiscan > 0.5);\n"
    "  bool inter6 = (intera < 0.35 && interm == 6.0);\n"
    "  bool interb = (((intera < 0.35 && !inter6) || (no_scanlines > 0.025)) && !hscan);\n"
    "  bool vgascan = ((abs(intera - 0.5) < 0.05) && (no_scanlines == 0.0));\n"
    "  float SourceY = SourceSize.y;\n"
    "  float sy = 1.0;\n"
    "  if (intres == 1.0) sy = max(round(SourceY / 224.0), 1.0);\n"
    "  if (intres > 0.25 && intres != 1.0) sy = intres;\n"
    "  if (inter6) sy *= 2.0;\n"
    "  if (vgascan) sy = 0.5; else if (abs(intera - 0.75) < 0.05) sy = 1.0;\n"
    "  SourceSize *= vec4(1.0, 1.0 / sy, 1.0, sy);\n"
    "  vec2 texcoord = vUv.xy;\n"
    "  if (IOS > 0.0 && !interb) {\n"
    "    vec2 ofactor = uOutputSize.xy / uOriginalSize.xy;\n"
    "    vec2 intfactor = (IOS < 2.5) ? floor(ofactor) : ceil(ofactor);\n"
    "    vec2 diff = ofactor / intfactor;\n"
    "    float scan = diff.y;\n"
    "    texcoord = Overscan(texcoord, scan, scan);\n"
    "    if (IOS == 1.0 || IOS == 3.0) texcoord = vec2(vUv.x, texcoord.y);\n"
    "  }\n"
    "  texcoord.y = texcoord.y - VShift * uOriginalSize.w;\n"
    "  texcoord = Overscan(texcoord, (uOriginalSize.x - overscanX) / uOriginalSize.x, (uOriginalSize.y - overscanY) / uOriginalSize.y);\n"
    "  vec2 pos = Warp(texcoord);\n"
    "  float ii = float(inter6) * (floor(mod(uFrameCount, 2.0)));\n"
    "  float coffset = 0.5 + 0.5 * ii;\n"
    "  vec2 ps = SourceSize.zw;\n"
    "  float OGL2Pos = pos.y * SourceSize.y - coffset;\n"
    "  float f = fract(OGL2Pos);\n"
    "  vec2 dx = vec2(ps.x, 0.0);\n"
    "  vec2 dy = vec2(0.0, ps.y);\n"
    "  vec2 pC4;\n"
    "  pC4.y = floor(OGL2Pos) * ps.y + 0.5 * ps.y;\n"
    "  pC4.y = pC4.y + float(inter6) * 0.25 * dy.y;\n"
    "  pC4.x = pos.x;\n"
    "  if ((intres == 0.5 && prescalex.y < 1.5) || vgascan) pC4.y = floor(pC4.y * uOriginalSize.y) * uOriginalSize.w + 0.5 * uOriginalSize.w;\n"
    "  if (interb && no_scanlines < 0.025 || hscan) pC4.y = pos.y; else if (interb) pC4.y = pC4.y + smoothstep(0.40 - 0.5 * no_scanlines, 0.60 + 0.5 * no_scanlines, f) * SourceSize.w;\n"
    "  vec3 color1 = texture(uPass1, pC4).rgb;\n"
    "  vec3 scolor1 = texture(uPass1, pC4).aaa;\n"
    "  pC4 += dy;\n"
    "  if ((intres == 0.5 && prescalex.y < 1.5) || vgascan) pC4.y = floor((pos.y + 0.33 * dy.y) * uOriginalSize.y) * uOriginalSize.w + 0.5 * uOriginalSize.w;\n"
    "  vec3 color2 = texture(uPass1, pC4).rgb;\n"
    "  vec3 scolor2 = texture(uPass1, pC4).aaa;\n"
    "  vec3 ctmp = color1;\n"
    "  float w3 = 1.0;\n"
    "  vec3 color = color1;\n"
    "  vec3 one = vec3(1.0);\n"
    "  if (hscan) { color2 = color1; scolor2 = scolor1; }\n"
    "  if (!interb) {\n"
    "    float shape1 = mix(scanline1, scanline2, f);\n"
    "    float shape2 = mix(scanline1, scanline2, 1.0 - f);\n"
    "    float wt1 = st(f);\n"
    "    float wt2 = st(1.0 - f);\n"
    "    vec3 color00 = color1 * wt1 + color2 * wt2;\n"
    "    vec3 scolor0 = scolor1 * wt1 + scolor2 * wt2;\n"
    "    ctmp = color00 / (wt1 + wt2);\n"
    "    vec3 sctmp = max(scolor0 / (wt1 + wt2), ctmp);\n"
    "    vec3 w1, w2;\n"
    "    vec3 cref1 = mix(sctmp, scolor1, beam_size);\n"
    "    float creff1 = pow(max(max(cref1.r, cref1.g), cref1.b), scan_falloff);\n"
    "    vec3 cref2 = mix(sctmp, scolor2, beam_size);\n"
    "    float creff2 = pow(max(max(cref2.r, cref2.g), cref2.b), scan_falloff);\n"
    "    if (tds > 0.5) { shape1 = mix(scanline2, shape1, creff1); shape2 = mix(scanline2, shape2, creff2); }\n"
    "    float f1 = f;\n"
    "    float f2 = 1.0 - f;\n"
    "    float mc1 = max(max(color1.r, color1.g), color1.b) + eps;\n"
    "    float mc2 = max(max(color2.r, color2.g), color2.b) + eps;\n"
    "    cref1 = color1 / mc1;\n"
    "    cref2 = color2 / mc2;\n"
    "    if (gsl < 0.5) { w1 = sw0(f1, creff1, shape1, cref1); w2 = sw0(f2, creff2, shape2, cref2); } else\n"
    "    if (gsl == 1.0) { w1 = sw1(f1, creff1, shape1, cref1); w2 = sw1(f2, creff2, shape2, cref2); } else\n"
    "    { w1 = sw2(f1, creff1, shape1, cref1); w2 = sw2(f2, creff2, shape2, cref2); }\n"
    "    vec3 w3 = w1 + w2;\n"
    "    float wf1 = max(max(w3.r, w3.g), w3.b);\n"
    "    if (wf1 > 1.0) { wf1 = 1.0 / wf1; w1 *= wf1; w2 *= wf1; }\n"
    "    if (abs(clips) > 0.005) {\n"
    "      sy = mc1; one = (clips > 0.0) ? w1 : vec3(1.0);\n"
    "      float sat = 1.0001 - min(min(cref1.r, cref1.g), cref1.b);\n"
    "      color1 = mix(color1, plant(pow(color1, vec3(0.70) - 0.325 * sat), sy), pow(sat, 0.3333) * one * abs(clips));\n"
    "      sy = mc2; one = (clips > 0.0) ? w2 : vec3(1.0);\n"
    "      sat = 1.0001 - min(min(cref2.r, cref2.g), cref2.b);\n"
    "      color2 = mix(color2, plant(pow(color2, vec3(0.70) - 0.325 * sat), sy), pow(sat, 0.3333) * one * abs(clips));\n"
    "    }\n"
    "    color = (gc(color1) * w1 + gc(color2) * w2);\n"
    "    color = min(color, 1.0);\n"
    "  }\n"
    "  if (interb) {\n"
    "    color = gc(color1);\n"
    "  }\n"
    "  float colmx = max(max(ctmp.r, ctmp.g), ctmp.b);\n"
    "  oColor = vec4(color, colmx);\n"
    "}\n";

// ---- deconv: mask, bloom/halation composite,
// deconvergence, borders, final gamma (defaults:
// interlace 1, hiscan 0, no-scanline 0, glow 0.08,
// bloom 0, mask bloom 0, bloom distribution 0,
// halation 0, bloom/halo mask 0 / 0.35, bright
// boost 1.40 / 1.10, gamma 1 / 1, integer scaling 0,
// corner/border 0, border intensity 0.75, hum bar
// speed 50 / intensity 0 / direction 0, curvature 0,
// overscan 0, shift 0, mask 0 (CGWG) / strength 0.3 /
// low 1.10 / boost 1 / size 1 / zoom 0 / sharpen 0 /
// shift 0 / layout RGB / dark 0.5 / light 1.5 /
// gamma 2.40, slot mask 0 / 0 / width 0 / height 2 /
// thickness 1, smooth 0, mitigate 0, base 0,
// preserve 0, preserve-scan 0.10, edge 0, gamma out
// 2.4, deconvergence all 0 / strength 1, noise 0 /
// resolution 2 / type 0, post brightness 1,
// original image 0).
const char* kFragDeconv =
    "#version 300 es\n"
    "precision highp float;\n"
    "in vec2 vUv;\n"
    "uniform sampler2D uPrePass;\n"
    "uniform sampler2D uLinearizePass;\n"
    "uniform sampler2D uBloomPass;\n"
    "uniform sampler2D uSource;\n"
    "uniform sampler2D uStockPass;\n"
    "uniform vec4 uOriginalSize;\n"
    "uniform vec4 uOutputSize;\n"
    "uniform float uFrameCount;\n"
    "out vec4 oColor;\n"
    "const float interm = 1.0;\n"
    "const float hiscan = 0.0;\n"
    "const float no_scanlines = 0.0;\n"
    "const float glow = 0.08;\n"
    "const float bloom = 0.0;\n"
    "const float mask_bloom = 0.0;\n"
    "const float bloom_dist = 0.0;\n"
    "const float halation = 0.0;\n"
    "const float bmask1 = 0.0;\n"
    "const float hmask1 = 0.35;\n"
    "const float brightboost = 1.40;\n"
    "const float brightboost1 = 1.10;\n"
    "const float gamma_c = 1.0;\n"
    "const float gamma_c2 = 1.0;\n"
    "const float IOS = 0.0;\n"
    "const float csize = 0.0;\n"
    "const float bsize1 = 0.0;\n"
    "const float sborder = 0.75;\n"
    "const float barspeed = 50.0;\n"
    "const float barintensity = 0.0;\n"
    "const float bardir = 0.0;\n"
    "const float warpX = 0.0;\n"
    "const float warpY = 0.0;\n"
    "const float c_shape = 0.25;\n"
    "const float overscanX = 0.0;\n"
    "const float overscanY = 0.0;\n"
    "const float VShift = 0.0;\n"
    "const float shadowMask = 0.0;\n"
    "const float maskstr = 0.3;\n"
    "const float mcut = 1.10;\n"
    "const float maskboost = 1.0;\n"
    "const float masksize = 1.0;\n"
    "const float mask_zoom = 0.0;\n"
    "const float mzoom_sh = 0.0;\n"
    "const float mshift = 0.0;\n"
    "const float mask_layout = 0.0;\n"
    "const float maskDark = 0.5;\n"
    "const float maskLight = 1.5;\n"
    "const float mask_gamma = 2.40;\n"
    "const float slotmask = 0.0;\n"
    "const float slotmask1 = 0.0;\n"
    "const float slotwidth = 0.0;\n"
    "const float double_slot = 2.0;\n"
    "const float slotms = 1.0;\n"
    "const float smoothmask = 0.0;\n"
    "const float smask_mit = 0.0;\n"
    "const float bmask = 0.0;\n"
    "const float mclip = 0.0;\n"
    "const float pr_scan = 0.10;\n"
    "const float edgemask = 0.0;\n"
    "const float gamma_out = 2.4;\n"
    "const float dctypex = 0.0;\n"
    "const float dctypey = 0.0;\n"
    "const float deconrr = 0.0;\n"
    "const float deconrg = 0.0;\n"
    "const float deconrb = 0.0;\n"
    "const float deconrry = 0.0;\n"
    "const float deconrgy = 0.0;\n"
    "const float deconrby = 0.0;\n"
    "const float decons = 1.0;\n"
    "const float addnoised = 0.0;\n"
    "const float noiseresd = 2.0;\n"
    "const float noisetype = 0.0;\n"
    "const float post_br = 1.0;\n"
    "const float oimage = 0.0;\n"
    "const float eps = 1e-10;\n"
    "vec3 Mask(vec2 pos, float mx, float mb) {\n"
    "  vec3 mask = vec3(maskDark, maskDark, maskDark);\n"
    "  vec3 one = vec3(1.0);\n"
    "  if (shadowMask == 0.0) {\n"
    "    float mc = 1.0 - max(maskstr, 0.0);\n"
    "    pos.x = fract(pos.x * 0.5);\n"
    "    if (pos.x < 0.49) { mask.r = 1.0; mask.g = mc; mask.b = 1.0; }\n"
    "    else { mask.r = mc; mask.g = 1.0; mask.b = mc; }\n"
    "  } else if (shadowMask == 1.0) {\n"
    "    float line = maskLight;\n"
    "    float odd = 0.0;\n"
    "    if (fract(pos.x / 6.0) < 0.49) odd = 1.0;\n"
    "    if (fract((pos.y + odd) / 2.0) < 0.49) line = maskDark;\n"
    "    pos.x = floor(mod(pos.x, 3.0));\n"
    "    if (pos.x < 0.5) mask.r = maskLight;\n"
    "    else if (pos.x < 1.5) mask.g = maskLight;\n"
    "    else mask.b = maskLight;\n"
    "    mask *= line;\n"
    "  } else if (shadowMask == 2.0) {\n"
    "    pos.x = floor(mod(pos.x, 3.0));\n"
    "    if (pos.x < 0.5) mask.r = maskLight;\n"
    "    else if (pos.x < 1.5) mask.g = maskLight;\n"
    "    else mask.b = maskLight;\n"
    "  } else if (shadowMask == 3.0) {\n"
    "    pos.x += pos.y * 3.0;\n"
    "    pos.x = fract(pos.x / 6.0);\n"
    "    if (pos.x < 0.3) mask.r = maskLight;\n"
    "    else if (pos.x < 0.6) mask.g = maskLight;\n"
    "    else mask.b = maskLight;\n"
    "  } else if (shadowMask == 4.0) {\n"
    "    pos.xy = floor(pos.xy * vec2(1.0, 0.5));\n"
    "    pos.x += pos.y * 3.0;\n"
    "    pos.x = fract(pos.x / 6.0);\n"
    "    if (pos.x < 0.3) mask.r = maskLight;\n"
    "    else if (pos.x < 0.6) mask.g = maskLight;\n"
    "    else mask.b = maskLight;\n"
    "  } else if (shadowMask == 5.0) {\n"
    "    mask = vec3(0.0);\n"
    "    pos.x = fract(pos.x / 2.0);\n"
    "    if (pos.x < 0.49) { mask.r = 1.0; mask.b = 1.0; }\n"
    "    else mask.g = 1.0;\n"
    "    mask = clamp(mix(mix(one, mask, mcut), mix(one, mask, maskstr), mx), 0.0, 1.0);\n"
    "  } else if (shadowMask == 6.0) {\n"
    "    mask = vec3(0.0);\n"
    "    pos.x = floor(mod(pos.x, 3.0));\n"
    "    if (pos.x < 0.5) mask.r = 1.0;\n"
    "    else if (pos.x < 1.5) mask.g = 1.0;\n"
    "    else mask.b = 1.0;\n"
    "    mask = clamp(mix(mix(one, mask, mcut), mix(one, mask, maskstr), mx), 0.0, 1.0);\n"
    "  } else if (shadowMask == 7.0) {\n"
    "    mask = vec3(0.0);\n"
    "    pos.x = fract(pos.x / 2.0);\n"
    "    if (pos.x < 0.49) { mask = vec3(0.0); }\n"
    "    else mask = vec3(1.0);\n"
    "    mask = clamp(mix(mix(one, mask, mcut), mix(one, mask, maskstr), mx), 0.0, 1.0);\n"
    "  } else if (shadowMask == 8.0) {\n"
    "    mask = vec3(0.0);\n"
    "    pos.x = fract(pos.x / 3.0);\n"
    "    if (pos.x < 0.3) mask = vec3(0.0);\n"
    "    else if (pos.x < 0.6) mask = vec3(1.0);\n"
    "    else mask = vec3(1.0);\n"
    "    mask = clamp(mix(mix(one, mask, mcut), mix(one, mask, maskstr), mx), 0.0, 1.0);\n"
    "  } else if (shadowMask == 9.0) {\n"
    "    mask = vec3(0.0);\n"
    "    pos.x = fract(pos.x / 3.0);\n"
    "    if (pos.x < 0.3) mask = vec3(0.0);\n"
    "    else if (pos.x < 0.6) mask.rb = vec2(1.0);\n"
    "    else mask.g = 1.0;\n"
    "    mask = clamp(mix(mix(one, mask, mcut), mix(one, mask, maskstr), mx), 0.0, 1.0);\n"
    "  } else if (shadowMask == 10.0) {\n"
    "    mask = vec3(0.0);\n"
    "    pos.x = fract(pos.x * 0.25);\n"
    "    if (pos.x < 0.2) mask = vec3(0.0);\n"
    "    else if (pos.x < 0.4) mask.r = 1.0;\n"
    "    else if (pos.x < 0.7) mask.g = 1.0;\n"
    "    else mask.b = 1.0;\n"
    "    mask = clamp(mix(mix(one, mask, mcut), mix(one, mask, maskstr), mx), 0.0, 1.0);\n"
    "  } else if (shadowMask == 11.0) {\n"
    "    mask = vec3(0.0);\n"
    "    pos.x = fract(pos.x * 0.25);\n"
    "    if (pos.x < 0.2) mask.r = 1.0;\n"
    "    else if (pos.x < 0.4) mask.rg = vec2(1.0);\n"
    "    else if (pos.x < 0.7) mask.gb = vec2(1.0);\n"
    "    else mask.b = 1.0;\n"
    "    mask = clamp(mix(mix(one, mask, mcut), mix(one, mask, maskstr), mx), 0.0, 1.0);\n"
    "  } else if (shadowMask == 12.0) {\n"
    "    mask = vec3(0.0);\n"
    "    pos.x = floor(mod(pos.x, 7.0));\n"
    "    if (pos.x < 0.5) mask = vec3(0.0);\n"
    "    else if (pos.x < 2.5) mask.r = 1.0;\n"
    "    else if (pos.x < 4.5) mask.g = 1.0;\n"
    "    else mask.b = 1.0;\n"
    "    mask = clamp(mix(mix(one, mask, mcut), mix(one, mask, maskstr), mx), 0.0, 1.0);\n"
    "  } else if (shadowMask == 13.0) {\n"
    "    mask = vec3(0.0);\n"
    "    pos.x = floor(mod(pos.x, 6.0));\n"
    "    if (pos.x < 0.5) mask = vec3(0.0);\n"
    "    else if (pos.x < 1.5) mask.r = 1.0;\n"
    "    else if (pos.x < 2.5) mask.rg = vec2(1.0);\n"
    "    else if (pos.x < 3.5) mask.rgb = vec3(1.0);\n"
    "    else if (pos.x < 4.5) mask.gb = vec2(1.0);\n"
    "    else mask.b = 1.0;\n"
    "    mask = clamp(mix(mix(one, mask, mcut), mix(one, mask, maskstr), mx), 0.0, 1.0);\n"
    "  } else {\n"
    "    mask = vec3(0.0);\n"
    "    pos.x = floor(mod(pos.x, 5.0));\n"
    "    if (pos.x < 0.5) mask = vec3(0.0);\n"
    "    else if (pos.x < 1.5) mask.r = 1.0;\n"
    "    else if (pos.x < 2.5) mask.rg = vec2(1.0);\n"
    "    else if (pos.x < 3.5) mask.gb = vec2(1.0);\n"
    "    else mask.b = 1.0;\n"
    "    mask = clamp(mix(mix(one, mask, mcut), mix(one, mask, maskstr), mx), 0.0, 1.0);\n"
    "  }\n"
    "  if (mask_layout > 0.5) mask = mask.rbg;\n"
    "  float maskmin = min(min(mask.r, mask.g), mask.b);\n"
    "  return (mask - maskmin) * (1.0 + (maskboost - 1.0) * mb) + maskmin;\n"
    "}\n"
    "float SlotMask(vec2 pos, float m, float swidth) {\n"
    "  if ((slotmask + slotmask1) == 0.0) return 1.0;\n"
    "  else {\n"
    "    pos.y = floor(pos.y / slotms);\n"
    "    float mlen = swidth * 2.0;\n"
    "    float px = floor(mod(pos.x, 0.99999 * mlen));\n"
    "    float py = floor(fract(pos.y / (2.0 * double_slot)) * 2.0 * double_slot);\n"
    "    float slot_dark = mix(1.0 - slotmask1, 1.0 - slotmask, m);\n"
    "    float slot = 1.0;\n"
    "    if (py == 0.0 && px < swidth) slot = slot_dark; else\n"
    "    if (py == double_slot && px >= swidth) slot = slot_dark;\n"
    "    return slot;\n"
    "  }\n"
    "}\n"
    "vec2 Warp(vec2 pos) {\n"
    "  pos = pos * 2.0 - 1.0;\n"
    "  pos = mix(pos, vec2(pos.x * inversesqrt(1.0 - c_shape * pos.y * pos.y), pos.y * inversesqrt(1.0 - c_shape * pos.x * pos.x)), vec2(warpX, warpY) / c_shape);\n"
    "  return pos * 0.5 + 0.5;\n"
    "}\n"
    "vec2 Overscan(vec2 pos, float dx, float dy) {\n"
    "  pos = pos * 2.0 - 1.0;\n"
    "  pos *= vec2(dx, dy);\n"
    "  return pos * 0.5 + 0.5;\n"
    "}\n"
    "float humbar(float pos) {\n"
    "  if (barintensity == 0.0) return 1.0; else {\n"
    "    pos = (barintensity >= 0.0) ? pos : (1.0 - pos);\n"
    "    pos = fract(pos + mod(uFrameCount, barspeed) / (barspeed - 1.0));\n"
    "    pos = (barintensity < 0.0) ? pos : (1.0 - pos);\n"
    "    return (1.0 - barintensity) + barintensity * pos;\n"
    "  }\n"
    "}\n"
    "float corner(vec2 pos) {\n"
    "  pos = abs(2.0 * (pos - 0.5));\n"
    "  vec2 aspect = vec2(1.0, uOutputSize.x / uOutputSize.y);\n"
    "  float b = bsize1 * 0.05 + 0.0005;\n"
    "  pos.y = pos.y + b * (aspect.y - 1.0);\n"
    "  vec2 crn = max(vec2(csize), 2.0 * b + 0.0015);\n"
    "  vec2 cp = max(pos - (1.0 - crn * aspect), 0.0) / aspect;\n"
    "  float cd = sqrt(dot(cp, cp));\n"
    "  pos = max(pos, 1.0 - crn + cd);\n"
    "  float res = mix(1.0, 0.0, smoothstep(1.0 - b, 1.0, sqrt(max(pos.x, pos.y))));\n"
    "  return pow(res, sborder);\n"
    "}\n"
    "vec3 plant(vec3 tar, float r) {\n"
    "  float t = max(max(tar.r, tar.g), tar.b) + 0.00001;\n"
    "  return tar * r / t;\n"
    "}\n"
    "vec3 declip(vec3 c, float b) {\n"
    "  float m = max(max(c.r, c.g), c.b);\n"
    "  if (m > b) c = c * b / m;\n"
    "  return c;\n"
    "}\n"
    "float igc(float mc) { return pow(mc, gamma_c); }\n"
    "vec3 gc2(vec3 c, float w3) {\n"
    "  float mc = max(max(c.r, c.g), c.b);\n"
    "  float gp = 1.0 / (1.0 + (gamma_c2 - 1.0) * mix(0.375, 1.0, w3));\n"
    "  float mg = pow(mc, gp);\n"
    "  return c * mg / (mc + eps);\n"
    "}\n"
    "vec3 noise(vec3 v) {\n"
    "  if (addnoised < 0.0) v.z = -addnoised; else v.z = mod(v.z, 6001.0) / 1753.0;\n"
    "  v = fract(v) + fract(v * 1e4) + fract(v * 1e-4);\n"
    "  v += vec3(0.12345, 0.6789, 0.314159);\n"
    "  v = fract(v * dot(v, v) * 123.456);\n"
    "  v = fract(v * dot(v, v) * 123.456);\n"
    "  v = fract(v * dot(v, v) * 123.456);\n"
    "  v = fract(v * dot(v, v) * 123.456);\n"
    "  return v;\n"
    "}\n"
    "void fetch_pixel(inout vec3 c, inout vec3 b, vec2 coord, vec2 bcoord) {\n"
    "  float stepx = uOutputSize.z;\n"
    "  float stepy = uOutputSize.w;\n"
    "  float ds = decons;\n"
    "  vec2 dx = vec2(stepx, 0.0);\n"
    "  vec2 dy = vec2(0.0, stepy);\n"
    "  float posx = 2.0 * coord.x - 1.0;\n"
    "  float posy = 2.0 * coord.y - 1.0;\n"
    "  if (dctypex > 0.025) {\n"
    "    posx = sign(posx) * pow(abs(posx), 1.05 - dctypex);\n"
    "    dx = posx * dx;\n"
    "  }\n"
    "  if (dctypey > 0.025) {\n"
    "    posy = sign(posy) * pow(abs(posy), 1.05 - dctypey);\n"
    "    dy = posy * dy;\n"
    "  }\n"
    "  vec2 rc = deconrr * dx + deconrry * dy;\n"
    "  vec2 gc = deconrg * dx + deconrgy * dy;\n"
    "  vec2 bc = deconrb * dx + deconrby * dy;\n"
    "  float r1 = texture(uSource, coord + rc).r;\n"
    "  float g1 = texture(uSource, coord + gc).g;\n"
    "  float b1 = texture(uSource, coord + bc).b;\n"
    "  vec3 d = vec3(r1, g1, b1);\n"
    "  c = clamp(mix(c, d, ds), 0.0, 1.0);\n"
    "  r1 = texture(uBloomPass, bcoord + rc).r;\n"
    "  g1 = texture(uBloomPass, bcoord + gc).g;\n"
    "  b1 = texture(uBloomPass, bcoord + bc).b;\n"
    "  d = vec3(r1, g1, b1);\n"
    "  b = mix(b, d, min(ds, 1.0));\n"
    "}\n"
    "void main() {\n"
    "    vec4 SourceSize = uOriginalSize;\n"
    "    float gamma_in = 1.0 / texture(uLinearizePass, vec2(0.25, 0.25)).a;\n"
    "    float intera = texture(uLinearizePass, vec2(0.75, 0.25)).a;\n"
    "    bool inter6 = (intera < 0.35 && interm == 5.0);\n"
    "    bool interb = (((intera < 0.35 && !inter6) || no_scanlines > 0.025) && hiscan < 0.5);\n"
    "    vec2 texcoord = vUv.xy;\n"
    "    if (IOS > 0.0 && !interb) {\n"
    "        vec2 ofactor = uOutputSize.xy / uOriginalSize.xy;\n"
    "        vec2 intfactor = (IOS < 2.5) ? floor(ofactor) : ceil(ofactor);\n"
    "        vec2 diff = ofactor / intfactor;\n"
    "        float scan = diff.y;\n"
    "        texcoord = Overscan(texcoord, scan, scan);\n"
    "        if (IOS == 1.0 || IOS == 3.0) texcoord = vec2(vUv.x, texcoord.y);\n"
    "    }\n"
    "    texcoord.y = texcoord.y - VShift * uOriginalSize.w;\n"
    "    texcoord = Overscan(texcoord, (uOriginalSize.x - overscanX) / uOriginalSize.x, (uOriginalSize.y - overscanY) / uOriginalSize.y);\n"
    "    vec2 pos1 = vUv.xy;\n"
    "    vec2 pos = Warp(texcoord);\n"
    "    vec2 pos0 = Warp(vUv.xy);\n"
    "    vec2 posb = 2.0 * (pos0 - 0.5);\n"
    "    posb = max(abs(posb), abs(2.0 * (pos - 0.5))) * sign(posb);\n"
    "    posb = 0.5 * posb + 0.5;\n"
    "    vec3 color = texture(uSource, pos1).rgb;\n"
    "    vec3 Bloom = texture(uBloomPass, pos).rgb;\n"
    "    if ((abs(deconrr) + abs(deconrry) + abs(deconrg) + abs(deconrgy) + abs(deconrb) + abs(deconrby)) > 0.2)\n"
    "        fetch_pixel(color, Bloom, pos1, pos);\n"
    "    float cm = igc(max(max(color.r, color.g), color.b));\n"
    "    float mx1 = texture(uSource, pos1).a;\n"
    "    float colmx = max(mx1, cm);\n"
    "    float w3 = min((max((cm - 0.0005) * 1.0005, 0.0) + 0.0001) / (colmx + 0.0005), 1.0);\n"
    "    if (interb) w3 = 1.0;\n"
    "    vec2 dx = vec2(0.001, 0.0);\n"
    "    float mx0 = texture(uSource, pos1 - dx).a;\n"
    "    float mx2 = texture(uSource, pos1 + dx).a;\n"
    "    float mxg = max(max(mx0, mx1), max(mx2, cm));\n"
    "    float mx = pow(mxg, 1.40 / gamma_in);\n"
    "    float cx = pow(colmx, 1.4 / gamma_in);\n"
    "    dx = vec2(uOriginalSize.z, 0.0) * 0.25;\n"
    "    mx0 = texture(uSource, pos1 - dx).a;\n"
    "    mx2 = texture(uSource, pos1 + dx).a;\n"
    "    float mb = (1.0 - min(abs(mx0 - mx2) / (0.5 + mx1), 1.0));\n"
    "    vec3 one = vec3(1.0);\n"
    "    vec3 orig1 = color;\n"
    "    vec3 cmask = one;\n"
    "    vec3 cmask1 = one;\n"
    "    vec3 cmask2 = one;\n"
    "    float mwidths[15] = float[15](2.0, 3.0, 3.0, 6.0, 6.0, 2.4, 3.4, 2.4, 3.25, 3.4, 4.4, 4.25, 7.4, 6.25, 5.25);\n"
    "    float mwidth = mwidths[int(shadowMask)];\n"
    "    float mask_compensate = fract(mwidth);\n"
    "    float mwidth1 = mwidth;\n"
    "    if (shadowMask > -0.5) {\n"
    "        vec2 maskcoord = vUv * uOutputSize.xy;\n"
    "        vec2 scoord = maskcoord;\n"
    "        mwidth = floor(mwidth) * masksize;\n"
    "        float swidth = mwidth;\n"
    "        bool zoomed = (abs(mask_zoom) > 0.75);\n"
    "        float mscale = 1.0;\n"
    "        vec2 maskcoord0 = maskcoord;\n"
    "        maskcoord.y = floor(maskcoord.y / masksize);\n"
    "        mwidth1 = max(mwidth + mask_zoom, 2.0);\n"
    "        if (mshift > 0.25) {\n"
    "            float stagg_lvl = 1.0;\n"
    "            if (fract(mshift) > 0.25) stagg_lvl = 2.0;\n"
    "            float next_line = float(floor(mod(maskcoord.y, 2.0 * stagg_lvl)) < stagg_lvl);\n"
    "            maskcoord0.x = maskcoord0.x + next_line * 0.5 * mwidth1;\n"
    "        }\n"
    "        maskcoord = maskcoord0 / masksize;\n"
    "        if (!zoomed)\n"
    "            cmask = Mask(floor(maskcoord), mx, mb);\n"
    "        else {\n"
    "            mscale = mwidth1 / mwidth;\n"
    "            float mlerp = fract(maskcoord.x / mscale);\n"
    "            if (mzoom_sh > 0.025) mlerp = clamp((1.0 + mzoom_sh) * mlerp - 0.5 * mzoom_sh, 0.0, 1.0);\n"
    "            float mcoord = floor(maskcoord.x / mscale);\n"
    "            if (shadowMask == 12.0 && mask_zoom == -2.0) mcoord = ceil(maskcoord.x / mscale);\n"
    "            cmask = mix(Mask(vec2(mcoord, maskcoord.y), mx, mb), Mask(vec2(mcoord + 1.0, maskcoord.y), mx, mb), mlerp);\n"
    "        }\n"
    "        float sm_offset = 0.0;\n"
    "        bool bsm_offset = (shadowMask == 0.0 || shadowMask == 2.0 || shadowMask == 5.0 || shadowMask == 6.0 || shadowMask == 8.0 || shadowMask == 11.0);\n"
    "        if (zoomed) { if (mask_layout < 0.5 && bsm_offset) sm_offset = 1.0; else if (bsm_offset) sm_offset = -1.0; }\n"
    "        swidth = round(mwidth1);\n"
    "        if (slotwidth > 0.5) swidth = slotwidth;\n"
    "        float smask = 1.0;\n"
    "        smask = SlotMask(scoord + vec2(sm_offset, 0.0), mx, swidth);\n"
    "        smask = clamp(smask + mix(smask_mit, 0.0, w3 * pow(colmx, 0.3)), 0.0, 1.0);\n"
    "        cmask2 = cmask;\n"
    "        cmask *= smask;\n"
    "        cmask1 = cmask;\n"
    "        if (abs(mask_bloom) > 0.025) {\n"
    "            float maxbl = max(max(max(Bloom.r, Bloom.g), Bloom.b), mxg);\n"
    "            maxbl = maxbl * max(mix(1.0, 2.0 - colmx, bloom_dist), 0.0);\n"
    "            if (mask_bloom > 0.025) cmask = max(min(cmask + maxbl * mask_bloom, 1.0), cmask); else cmask = max(mix(cmask, cmask * (1.0 - 0.5 * maxbl) + plant(pow(Bloom, vec3(0.35)), maxbl), -mask_bloom), cmask);\n"
    "        }\n"
    "        color = pow(color, vec3(mask_gamma / gamma_in));\n"
    "        color = color * cmask;\n"
    "        color = min(color, 1.0);\n"
    "        color = pow(color, vec3(gamma_in / mask_gamma));\n"
    "        cmask = min(cmask, 1.0);\n"
    "        cmask1 = min(cmask1, 1.0);\n"
    "    }\n"
    "    float dark_compensate = mix(max(clamp(mix(mcut, maskstr, mx), 0.0, 1.0) - 1.0 + mask_compensate, 0.0) + 1.0, 1.0, mx);\n"
    "    if (shadowMask < -0.5) dark_compensate = 1.0;\n"
    "    float bb = mix(brightboost, brightboost1, mx) * dark_compensate;\n"
    "    color *= bb;\n"
    "    vec3 Glow = texture(uBloomPass, pos).rgb;\n"
    "    vec3 Ref = texture(uLinearizePass, pos).rgb;\n"
    "    float maxb = texture(uBloomPass, pos).a;\n"
    "    float vig = texture(uPrePass, clamp(pos, 0.0 + 0.5 * uOriginalSize.zw, 1.0 - 0.5 * uOriginalSize.zw)).a;\n"
    "    if (pr_scan > 0.025) {\n"
    "        float mbl = max(max(Bloom.r, Bloom.g), Bloom.b);\n"
    "        Bloom = mix(Bloom, mix(Bloom, plant(orig1, mbl), min(2.5 * (1.0 - w3), 1.0)), min(2.0 * pr_scan, 1.0));\n"
    "    }\n"
    "    vec3 Bloom1 = Bloom;\n"
    "    vec3 bcmask = mix(one, cmask1, bmask1);\n"
    "    vec3 hcmask = mix(one, cmask1, hmask1);\n"
    "    if (abs(bloom) > 0.025) {\n"
    "        if (bloom < -0.01) Bloom1 = plant(Bloom, maxb);\n"
    "        Bloom1 = min(Bloom1 * (orig1 + color), max(0.5 * (colmx + orig1 - color), 0.001 * Bloom1));\n"
    "        Bloom1 = 0.5 * (Bloom1 + mix(Bloom1, mix(colmx * orig1, Bloom1, 0.5), 1.0 - color));\n"
    "        Bloom1 = bcmask * Bloom1 * max(mix(1.0, 2.0 - colmx, bloom_dist), 0.0);\n"
    "        color = pow(pow(color, vec3(mask_gamma / gamma_in)) + abs(bloom) * pow(Bloom1, vec3(mask_gamma / gamma_in)), vec3(gamma_in / mask_gamma));\n"
    "    }\n"
    "    if (halation > 0.01) {\n"
    "        Bloom = 0.5 * (Bloom + Bloom * Bloom);\n"
    "        float mbl = max(max(Bloom.r, Bloom.g), Bloom.b);\n"
    "        float cmxh = 0.5 * (colmx + colmx * colmx);\n"
    "        mbl = mix(mix(cmxh, mix(cmxh, mbl, mbl), colmx), mbl, mb);\n"
    "        Bloom = plant(Bloom, mix(sqrt(mbl * cmxh), max((mbl - 0.15 * (1.0 - colmx)), 0.4 * cmxh), pow(colmx, 0.25))) * mix(0.425, 1.0, colmx);\n"
    "        Bloom = (3.0 - colmx - color) * plant(0.325 + orig1 / w3, 0.5 * (1.0 + w3)) * hcmask * Bloom;\n"
    "        color = pow(pow(color, vec3(mask_gamma / gamma_in)) + halation * pow(Bloom, vec3(mask_gamma / gamma_in)), vec3(gamma_in / mask_gamma));\n"
    "    } else if (halation < -0.01) {\n"
    "        float mbl = max(max(Bloom.r, Bloom.g), Bloom.b);\n"
    "        Bloom = plant(Bloom + Ref + orig1 + Bloom * Bloom * Bloom, min(mbl * mbl, 0.75));\n"
    "        Bloom = 2.0 * mix(1.0, w3, 0.5 * colmx) * hcmask * Bloom;\n"
    "        color = color - halation * Bloom;\n"
    "    }\n"
    "    color = min(color, 1.0);\n"
    "    color = gc2(color, w3);\n"
    "    if (smoothmask > 0.125) { float w4 = pow(w3, 0.425 + 0.3 * smoothmask); w4 = max(w4 - 0.175 * colmx * smoothmask, 0.2); color = mix(min(color / w4, plant(orig1, 1.0 + 0.175 * colmx * smoothmask)) * w4, color, w4); }\n"
    "    Glow = mix(Glow, 0.25 * color, colmx);\n"
    "    if (glow >= 0.0) color = color + 0.5 * Glow * glow; else { cmask2 *= cmask2; color = color + (-glow) * min(cmask2, 1.0) * Glow; }\n"
    "    color = min(color, 1.0);\n"
    "    if (edgemask > 0.05) {\n"
    "        mx0 = texture(uSource, pos1 - dx).a; mx0 = texture(uSource, pos1 - dx * (1.0 - 0.75 * sqrt(mx0))).a;\n"
    "        mx2 = texture(uSource, pos1 + dx).a; mx2 = texture(uSource, pos1 + dx * (1.0 - 0.75 * sqrt(mx2))).a;\n"
    "        float mx3 = texture(uSource, pos1 - 4.0 * dx).a;\n"
    "        float mx4 = texture(uSource, pos1 + 4.0 * dx).a;\n"
    "        mx4 = max(pow(abs(mx3 - mx4), 0.55 - 0.40 * cx), min(max(mx3, mx4) / min(0.1 + cx, 1.0), 1.0));\n"
    "        mb = (1.0 - abs(pow(mx0, 1.0 - 0.65 * mx2) - pow(mx2, 1.0 - 0.65 * mx0)));\n"
    "        mb = mx4 * edgemask * (1.0001 - mb * mb);\n"
    "        vec3 temp = mix(color, orig1, mb);\n"
    "        color = max(temp + mix(3.5 * mb * mix(1.625 * temp, temp, cx), vec3(0.0), pow(color, vec3(0.75) - 0.5 * colmx)), color);\n"
    "    }\n"
    "    color = color * mix(1.0, mix(0.5 * (1.0 + w3), w3, mx), pr_scan);\n"
    "    color = min(color, max(orig1, color) * mix(one, cmask1, mclip));\n"
    "    color = pow(color, vec3(1.0 / gamma_out));\n"
    "    float rc = 0.6 * sqrt(max(max(color.r, color.g), color.b)) + 0.4;\n"
    "    if (abs(addnoised) > 0.01) {\n"
    "        vec3 noise0 = noise(vec3(floor(uOutputSize.xy * vUv / noiseresd), uFrameCount));\n"
    "        if (noisetype < 0.5) color = mix(color, noise0, 0.25 * abs(addnoised) * rc);\n"
    "        else color = min(color * mix(1.0, 1.5 * noise0.x, 0.5 * abs(addnoised)), 1.0);\n"
    "    }\n"
    "    colmx = max(max(orig1.r, orig1.g), orig1.b);\n"
    "    color = color + bmask * mix(cmask2, 0.125 * (1.0 - colmx) * color, min(20.0 * colmx, 1.0));\n"
    "    oColor = mix(vec4(color * vig * humbar(mix(pos.y, pos.x, bardir)) * post_br * corner(posb), 1.0),\n"
    "                 texture(uStockPass, (floor(pos1 * uOriginalSize.xy) + 0.5) * uOriginalSize.zw), step(pos1.x, oimage - 0.00025));\n"
    "}\n";

// ---------------------------------------------------------------- runner

struct CrtPass {
    GLuint prog = 0;
    GLint aPos = -1, aUv = -1;
    GLint uSourceSize = -1, uOriginalSize = -1, uOutputSize = -1, uFrameCount = -1;
};

struct CrtFbo {
    GLuint fbo = 0, tex = 0;
    int w = 0, h = 0;
};

CrtPass g_pass[7];
GLuint g_vs = 0, g_vsFudge = 0;
CrtFbo g_fbo[7];  // pre, linearize, pass1, bloomH, bloomV, pass2, deconv
GLuint g_dummyTex = 0, g_linearSampler = 0;
bool g_crtReady = false, g_crtFailed = false;
int g_curCoreW = 0, g_curCoreH = 0, g_curQuadW = 0, g_curQuadH = 0;

struct Vec4 {
    float x, y, z, w;
};

Vec4 sizeVec(int w, int h) {
    return Vec4{static_cast<float>(w), static_cast<float>(h),
                w > 0 ? 1.0f / w : 0.0f, h > 0 ? 1.0f / h : 0.0f};
}

bool makePass(int i, GLuint vs, const char* fs) {
    GLuint f = compileShader(GL_FRAGMENT_SHADER, fs);
    if (!f) return false;
    g_pass[i].prog = linkProgram(vs, f);
    glDeleteShader(f);
    if (!g_pass[i].prog) return false;
    g_pass[i].aPos = glGetAttribLocation(g_pass[i].prog, "aPos");
    g_pass[i].aUv = glGetAttribLocation(g_pass[i].prog, "aUv");
    g_pass[i].uSourceSize = glGetUniformLocation(g_pass[i].prog, "uSourceSize");
    g_pass[i].uOriginalSize = glGetUniformLocation(g_pass[i].prog, "uOriginalSize");
    g_pass[i].uOutputSize = glGetUniformLocation(g_pass[i].prog, "uOutputSize");
    g_pass[i].uFrameCount = glGetUniformLocation(g_pass[i].prog, "uFrameCount");
    return true;
}

bool makeFbo(CrtFbo& f, int w, int h) {
    glGenTextures(1, &f.tex);
    glBindTexture(GL_TEXTURE_2D, f.tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0,
                 GL_RGBA, GL_HALF_FLOAT, nullptr);
    glGenFramebuffers(1, &f.fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, f.fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, f.tex, 0);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    f.w = w;
    f.h = h;
    return ok;
}

void deleteFbos() {
    for (auto& f : g_fbo) {
        if (f.fbo) glDeleteFramebuffers(1, &f.fbo);
        if (f.tex) glDeleteTextures(1, &f.tex);
        f.fbo = 0;
        f.tex = 0;
        f.w = 0;
        f.h = 0;
    }
}

bool crtInit() {
    g_vs = compileShader(GL_VERTEX_SHADER, kCrtVert);
    g_vsFudge = compileShader(GL_VERTEX_SHADER, kCrtVertFudge);
    if (!g_vs || !g_vsFudge) return false;
    // pre + bloom use the plain quad; linearize, pass1,
    // pass2 and deconv use the nudged quad.
    if (!makePass(0, g_vs, kFragPre)) return false;
    if (!makePass(1, g_vsFudge, kFragLinearize)) return false;
    if (!makePass(2, g_vsFudge, kFragPass1)) return false;
    if (!makePass(3, g_vs, kFragBloomH)) return false;
    if (!makePass(4, g_vs, kFragBloomV)) return false;
    if (!makePass(5, g_vsFudge, kFragPass2)) return false;
    if (!makePass(6, g_vsFudge, kFragDeconv)) return false;
    // 1x1 dummy texture for the lookup samplers (the
    // default path never samples them).
    unsigned char white[4] = {255, 255, 255, 255};
    glGenTextures(1, &g_dummyTex);
    glBindTexture(GL_TEXTURE_2D, g_dummyTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, white);
    glGenSamplers(1, &g_linearSampler);
    glSamplerParameteri(g_linearSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glSamplerParameteri(g_linearSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glSamplerParameteri(g_linearSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glSamplerParameteri(g_linearSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    g_crtReady = true;
    return true;
}

void bindTex(GLuint prog, const char* name, int unit, GLuint tex) {
    GLint loc = glGetUniformLocation(prog, name);
    if (loc < 0) return;
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, tex);
    glBindSampler(unit, g_linearSampler);
    glUniform1i(loc, unit);
}

void setFloat(GLuint prog, const char* name, float v) {
    GLint loc = glGetUniformLocation(prog, name);
    if (loc >= 0) glUniform1f(loc, v);
}

void setVec(CrtPass& p, GLint loc, Vec4 v) {
    if (loc >= 0) glUniform4f(loc, v.x, v.y, v.z, v.w);
}

void drawQuad(CrtPass& p) {
    static const float pos[8] = {-1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f};
    static const float uvs[8] = {0.f, 0.f, 1.f, 0.f, 0.f, 1.f, 1.f, 1.f};
    glVertexAttribPointer(p.aPos, 2, GL_FLOAT, GL_FALSE, 0, pos);
    glEnableVertexAttribArray(p.aPos);
    glVertexAttribPointer(p.aUv, 2, GL_FLOAT, GL_FALSE, 0, uvs);
    glEnableVertexAttribArray(p.aUv);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

bool crtAllocFbos(int coreW, int coreH, int quadW, int quadH) {
    deleteFbos();
    const int ws[7] = {coreW, coreW, quadW, 640, 640, quadW, quadW};
    const int hs[7] = {coreH, coreH, coreH, coreH, 480, quadH, quadH};
    for (int i = 0; i < 7; i++) {
        if (!makeFbo(g_fbo[i], ws[i], hs[i])) {
            CRT_LOG("fbo %d (%dx%d) incomplete", i, ws[i], hs[i]);
            return false;
        }
    }
    g_curCoreW = coreW;
    g_curCoreH = coreH;
    g_curQuadW = quadW;
    g_curQuadH = quadH;
    return true;
}

}  // namespace

// Per-console color profile (beta.22), fed by
// the JNI setter. Neutral defaults reproduce
// the old baked constants exactly.
static float g_crtSat = 1.f, g_crtWarm = 0.f, g_crtGamma = 1.f;
static float g_crtBright = 1.f, g_crtBlack = 0.f;
static bool g_crtSega = false;

void crtSetColorProfile(float sat, float warm, float gamma,
                        float bright, float black, bool segaFix) {
    g_crtSat = sat; g_crtWarm = warm; g_crtGamma = gamma;
    g_crtBright = bright; g_crtBlack = black; g_crtSega = segaFix;
}

GLuint crtRun(GLuint coreTex, int coreW, int coreH,
              int quadW, int quadH, unsigned frameCount) {
    if (g_crtFailed) return 0;
    if (coreW <= 0 || coreH <= 0 || quadW <= 0 || quadH <= 0) return 0;
    if (!g_crtReady && !crtInit()) {
        g_crtFailed = true;
        return 0;
    }
    if (g_curCoreW != coreW || g_curCoreH != coreH ||
        g_curQuadW != quadW || g_curQuadH != quadH) {
        if (!crtAllocFbos(coreW, coreH, quadW, quadH)) {
            g_crtFailed = true;
            return 0;
        }
    }
    Vec4 coreSize = sizeVec(coreW, coreH);
    Vec4 quadSize = sizeVec(quadW, quadH);
    Vec4 pass1Size = sizeVec(quadW, coreH);
    Vec4 bloomHSize = sizeVec(640, coreH);
    float fc = static_cast<float>(frameCount);

    // pre (core -> pre)
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[0].fbo);
    glViewport(0, 0, coreW, coreH);
    glUseProgram(g_pass[0].prog);
    bindTex(g_pass[0].prog, "uSource", 0, coreTex);
    bindTex(g_pass[0].prog, "uLut1", 1, g_dummyTex);
    bindTex(g_pass[0].prog, "uLut2", 2, g_dummyTex);
    bindTex(g_pass[0].prog, "uLut3", 3, g_dummyTex);
    bindTex(g_pass[0].prog, "uLut4", 4, g_dummyTex);
    setVec(g_pass[0], g_pass[0].uOriginalSize, coreSize);
    setFloat(g_pass[0].prog, "CP", 0.f);
    setFloat(g_pass[0].prog, "CS", 0.f);
    setFloat(g_pass[0].prog, "WP", g_crtWarm);
    setFloat(g_pass[0].prog, "wp_saturation", g_crtSat);
    setFloat(g_pass[0].prog, "BP", g_crtBlack);
    setFloat(g_pass[0].prog, "pre_bb", g_crtBright);
    setFloat(g_pass[0].prog, "pre_gc", g_crtGamma);
    setFloat(g_pass[0].prog, "sega_fix", g_crtSega ? 1.f : 0.f);
    drawQuad(g_pass[0]);

    // linearize (pre -> linearize)
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[1].fbo);
    glViewport(0, 0, coreW, coreH);
    glUseProgram(g_pass[1].prog);
    bindTex(g_pass[1].prog, "uPrePass", 0, g_fbo[0].tex);
    setVec(g_pass[1], g_pass[1].uSourceSize, coreSize);
    setVec(g_pass[1], g_pass[1].uOriginalSize, coreSize);
    if (g_pass[1].uFrameCount >= 0) glUniform1f(g_pass[1].uFrameCount, fc);
    drawQuad(g_pass[1]);

    // pass1 (linearize -> pass1, viewport-wide x source-high)
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[2].fbo);
    glViewport(0, 0, quadW, coreH);
    glUseProgram(g_pass[2].prog);
    bindTex(g_pass[2].prog, "uLinearizePass", 0, g_fbo[1].tex);
    setVec(g_pass[2], g_pass[2].uOriginalSize, coreSize);
    drawQuad(g_pass[2]);

    // bloom horizontal (linearize -> bloomH, 640 x coreH)
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[3].fbo);
    glViewport(0, 0, 640, coreH);
    glUseProgram(g_pass[3].prog);
    bindTex(g_pass[3].prog, "uLinearizePass", 0, g_fbo[1].tex);
    setVec(g_pass[3], g_pass[3].uOriginalSize, coreSize);
    drawQuad(g_pass[3]);

    // bloom vertical (bloomH -> bloomV, 640 x 480)
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[4].fbo);
    glViewport(0, 0, 640, 480);
    glUseProgram(g_pass[4].prog);
    bindTex(g_pass[4].prog, "uSource", 0, g_fbo[3].tex);
    setVec(g_pass[4], g_pass[4].uSourceSize, bloomHSize);
    setVec(g_pass[4], g_pass[4].uOriginalSize, coreSize);
    drawQuad(g_pass[4]);

    // pass2 (pass1 + linearize + bloomV + pre -> pass2)
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[5].fbo);
    glViewport(0, 0, quadW, quadH);
    glUseProgram(g_pass[5].prog);
    bindTex(g_pass[5].prog, "uPass1", 0, g_fbo[2].tex);
    bindTex(g_pass[5].prog, "uLinearizePass", 1, g_fbo[1].tex);
    bindTex(g_pass[5].prog, "uBloomPass", 2, g_fbo[4].tex);
    bindTex(g_pass[5].prog, "uPrePass", 3, g_fbo[0].tex);
    setVec(g_pass[5], g_pass[5].uSourceSize, pass1Size);
    setVec(g_pass[5], g_pass[5].uOriginalSize, coreSize);
    setVec(g_pass[5], g_pass[5].uOutputSize, quadSize);
    if (g_pass[5].uFrameCount >= 0) glUniform1f(g_pass[5].uFrameCount, fc);
    drawQuad(g_pass[5]);

    // deconv (pass2 + bloomV + pre + linearize + core -> final)
    glBindFramebuffer(GL_FRAMEBUFFER, g_fbo[6].fbo);
    glViewport(0, 0, quadW, quadH);
    glUseProgram(g_pass[6].prog);
    bindTex(g_pass[6].prog, "uPrePass", 0, g_fbo[0].tex);
    bindTex(g_pass[6].prog, "uLinearizePass", 1, g_fbo[1].tex);
    bindTex(g_pass[6].prog, "uBloomPass", 2, g_fbo[4].tex);
    bindTex(g_pass[6].prog, "uSource", 3, g_fbo[5].tex);
    bindTex(g_pass[6].prog, "uStockPass", 4, coreTex);
    setVec(g_pass[6], g_pass[6].uOriginalSize, coreSize);
    setVec(g_pass[6], g_pass[6].uOutputSize, quadSize);
    if (g_pass[6].uFrameCount >= 0) glUniform1f(g_pass[6].uFrameCount, fc);
    drawQuad(g_pass[6]);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    // Leave GL state as the caller found it: the
    // chain binds texture units 0-4 and a sampler
    // per unit. Without this, the caller's final
    // draw binds its texture to the unit the chain
    // left active and samples a stale unit, so the
    // CRT output never reaches the screen (the
    // picture shown is the pre-pass output, which
    // looks almost identical to the raw game).
    for (int unit = 0; unit < 5; ++unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindSampler(unit, 0);
    }
    glActiveTexture(GL_TEXTURE0);
    return g_fbo[6].tex;
}

void crtDestroy() {
    deleteFbos();
    for (auto& p : g_pass) {
        if (p.prog) glDeleteProgram(p.prog);
        p.prog = 0;
    }
    if (g_vs) glDeleteShader(g_vs);
    if (g_vsFudge) glDeleteShader(g_vsFudge);
    g_vs = 0;
    g_vsFudge = 0;
    if (g_dummyTex) glDeleteTextures(1, &g_dummyTex);
    if (g_linearSampler) glDeleteSamplers(1, &g_linearSampler);
    g_dummyTex = 0;
    g_linearSampler = 0;
    g_crtReady = false;
    g_crtFailed = false;
    g_curCoreW = g_curCoreH = g_curQuadW = g_curQuadH = 0;
}
