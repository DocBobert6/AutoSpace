// CRT pass chain (Phase L, L2): the multi-pass CRT look for
// the home consoles, rendered into framebuffers at the
// displayed image size and handed back as a texture for
// the presenter's letterboxed quad.
//
// Returns the final chain texture, or 0 on any failure
// (caller falls back to the legacy single pass - never
// a black screen). coreTex is the core's RGBA texture
// (exact core size); quadW/quadH are the displayed
// image size in pixels; frameCount counts fresh core
// frames (interlace parity).
#pragma once
#include <GLES3/gl3.h>

// Per-console color profile for the pre pass
// (beta.22). Neutral values reproduce the old
// baked constants exactly.
void crtSetColorProfile(float sat, float warm, float gamma,
                        float bright, float black, bool segaFix);

GLuint crtRun(GLuint coreTex, int coreW, int coreH,
              int quadW, int quadH, unsigned frameCount);
void crtDestroy();
