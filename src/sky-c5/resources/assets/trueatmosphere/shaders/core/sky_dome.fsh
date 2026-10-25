#version 150

in vec4 v_colour;

uniform vec4 ColorModulator;

out vec4 fragColor;

void main() {

    fragColor = vec4(v_colour.rgb, ColorModulator.a);
}
