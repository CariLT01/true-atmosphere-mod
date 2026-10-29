#version 330

#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in vec3 Position;

out vec3 v_dir;

void main() {
    vec3 viewDir = normalize(vec3(Position.x / ProjMat[0][0], Position.y / ProjMat[1][1], -1.0));
    mat4 invView = inverse(ModelViewMat);
    v_dir = (invView * vec4(viewDir, 0.0)).xyz;
    gl_Position = vec4(Position.xy, 1.0, 1.0);
}
