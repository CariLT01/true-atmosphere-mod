#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec3 Position;

layout(location = 0) out vec4 v_far;

void main() {
    mat4 invViewProj = inverse(ProjMat * ModelViewMat);
    v_far = invViewProj * vec4(Position.xy, 1.0, 1.0);
    gl_Position = vec4(Position.xy, 1.0, 1.0);
}
