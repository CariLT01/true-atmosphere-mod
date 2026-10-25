#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 v_colour;

void main() {
    v_colour = Color;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
