#version 330

#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in vec3 Position;

out vec4 v_far;

void main() {
    mat4 invViewProj = inverse(ProjMat * ModelViewMat);
    v_far = invViewProj * vec4(Position.xy, 1.0, 1.0);
    gl_Position = vec4(Position.xy, 1.0, 1.0);
}
