// MIT License

// Copyright (c) 2019-2021 bloc97
// All rights reserved.

// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:

// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.

// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

precision highp float;
varying vec2 vTexCoord;

      uniform sampler2D uTexture0;
      uniform vec2 uSize0;
      #define MAIN_pos vTexCoord
      #define MAIN_pt (vec2(1.0) / uSize0)
      #define MAIN_size uSize0
      vec4 MAIN_tex(vec2 position) {
        vec4 c = texture2D(uTexture0, position);
        return c;
      }
      #define MAIN_texOff(offset) MAIN_tex(vTexCoord + vec2(offset) / uSize0)
    

      uniform sampler2D uTexture1;
      uniform vec2 uSize1;
      #define conv2d_last_tf_pos vTexCoord
      #define conv2d_last_tf_pt (vec2(1.0) / uSize1)
      #define conv2d_last_tf_size uSize1
      vec4 conv2d_last_tf_tex(vec2 position) {
        vec4 c = texture2D(uTexture1, position);
        return c;
      }
      #define conv2d_last_tf_texOff(offset) conv2d_last_tf_tex(vTexCoord + vec2(offset) / uSize1)
    vec4 hook() {
    vec2 f0 = fract(conv2d_last_tf_pos * conv2d_last_tf_size);
    ivec2 i0 = ivec2(f0 * vec2(2.0));
    vec4 features = conv2d_last_tf_tex((vec2(0.5) - f0) * conv2d_last_tf_pt + conv2d_last_tf_pos);
float c0 = i0.y == 0 ? (i0.x == 0 ? features.r : features.g) : (i0.x == 0 ? features.b : features.a);
    float c1 = c0;
    float c2 = c1;
    float c3 = c2;
    return vec4(c0, c1, c2, c3) + MAIN_tex(MAIN_pos);
}

void main() { vec4 c = hook(); gl_FragColor = vec4(clamp(c.rgb, 0.0, 1.0), texture2D(uTexture0, vTexCoord).a); }
