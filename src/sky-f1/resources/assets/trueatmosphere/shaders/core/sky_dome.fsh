#version 330

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 v_colour;

out vec4 fragColor;

void main() {

    fragColor = vec4(v_colour.rgb, ColorModulator.a);
}
