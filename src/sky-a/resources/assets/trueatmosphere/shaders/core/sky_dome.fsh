#version 150

in vec3 v_colour;

uniform float u_alpha;

out vec4 fragColor;

void main() {
    fragColor = vec4(v_colour, u_alpha);
}
