#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec3 v_dir;

uniform sampler2D TableCurrent;
uniform sampler2D TableNext;

layout(location = 0) out vec4 fragColor;

const float INV_TAU = 0.15915494309189535;

vec3 sample_table(sampler2D table, float u, float fv) {
    float tx = (u * float(SKY_AZ_SAMPLES) + 0.5) / float(SKY_AZ_SAMPLES + 1);
    float ty = (fv + 0.5) / float(SKY_ALT_SAMPLES);
    return texture(table, vec2(tx, ty)).rgb;
}

void main() {
    vec3 dir = normalize(v_dir);

    float u = atan(dir.z, dir.x) * INV_TAU;
    if (u < 0.0) {
        u += 1.0;
    }
    float fv = (clamp(dir.y, -1.0, 1.0) * 0.5 + 0.5) * float(SKY_ALT_SAMPLES - 1);

    vec3 colour = mix(sample_table(TableCurrent, u, fv), sample_table(TableNext, u, fv), ColorModulator.a);
    fragColor = vec4(colour, 1.0);
}
