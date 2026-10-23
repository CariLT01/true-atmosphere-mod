#version 150

in vec2 v_ndc;

uniform mat4 u_invViewProj;
uniform sampler2D TableCurrent;
uniform sampler2D TableNext;
uniform sampler2D TableMoonCurrent;
uniform sampler2D TableMoonNext;
uniform float u_t;
uniform float u_gain;
uniform float u_night;
uniform float u_moonGainR;
uniform float u_moonGainG;
uniform float u_moonGainB;
uniform int u_azSamples;
uniform int u_altSamples;

out vec4 fragColor;

const float INV_TAU = 0.15915494309189535;

vec3 sample_table(sampler2D table, float u, float fv) {
    float fu = u * float(u_azSamples);
    int x0 = int(floor(fu));
    int x1 = (x0 + 1) % u_azSamples;
    int y0 = clamp(int(floor(fv)), 0, u_altSamples - 1);
    int y1 = min(y0 + 1, u_altSamples - 1);
    float tx = fu - floor(fu);
    float ty = fv - float(y0);

    vec3 c00 = texelFetch(table, ivec2(x0, y0), 0).rgb;
    vec3 c10 = texelFetch(table, ivec2(x1, y0), 0).rgb;
    vec3 c01 = texelFetch(table, ivec2(x0, y1), 0).rgb;
    vec3 c11 = texelFetch(table, ivec2(x1, y1), 0).rgb;
    return mix(mix(c00, c10, tx), mix(c01, c11, tx), ty);
}

vec3 sky_colour(vec3 dir, sampler2D current, sampler2D next) {
    float u = atan(dir.z, dir.x) * INV_TAU;
    if (u < 0.0) {
        u += 1.0;
    }
    float fv = (clamp(dir.y, -1.0, 1.0) * 0.5 + 0.5) * float(u_altSamples - 1);

    vec3 a = sample_table(current, u, fv);
    vec3 b = sample_table(next, u, fv);
    return mix(a, b, u_t);
}

const float MOON_GLARE_KNEE = 8.0;
const float MOON_SATURATION = 0.5;

vec3 moonlit_sky(vec3 dir) {
    vec3 c = sky_colour(dir, TableMoonCurrent, TableMoonNext) *
             vec3(u_moonGainR, u_moonGainG, u_moonGainB);
    float peak = max(max(c.r, c.g), c.b);
    c /= 1.0 + peak * MOON_GLARE_KNEE;
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return mix(vec3(lum), c, MOON_SATURATION);
}

vec3 srgb_encode(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    vec3 low = 12.92 * c;
    vec3 high = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    return mix(low, high, step(vec3(0.0031308), c));
}

void main() {
    vec4 far = u_invViewProj * vec4(v_ndc, 1.0, 1.0);
    vec3 dir = normalize(far.xyz / far.w);

    vec3 colour = sky_colour(dir, TableCurrent, TableNext);
    colour += u_night * moonlit_sky(dir);
    colour *= u_gain;

    fragColor = vec4(srgb_encode(colour), 1.0);
}
