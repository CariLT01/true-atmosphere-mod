#version 150

in vec2 v_ndc;

uniform mat4 u_invViewProj;
uniform sampler2D TableCurrent;
uniform sampler2D TableNext;
uniform float u_t;
uniform int u_azSamples;
uniform int u_altSamples;

out vec4 fragColor;

const float INV_TAU = 0.15915494309189535;

vec3 sample_table(sampler2D table, float u, float fv) {
    float tx = (u * float(u_azSamples) + 0.5) / float(u_azSamples + 1);
    float ty = (fv + 0.5) / float(u_altSamples);
    return texture(table, vec2(tx, ty)).rgb;
}

void main() {
    vec4 far = u_invViewProj * vec4(v_ndc, 1.0, 1.0);
    vec3 dir = normalize(far.xyz / far.w);

    float u = atan(dir.z, dir.x) * INV_TAU;
    if (u < 0.0) {
        u += 1.0;
    }
    float fv = (clamp(dir.y, -1.0, 1.0) * 0.5 + 0.5) * float(u_altSamples - 1);

    vec3 colour = mix(sample_table(TableCurrent, u, fv), sample_table(TableNext, u, fv), u_t);
    fragColor = vec4(colour, 1.0);
}
