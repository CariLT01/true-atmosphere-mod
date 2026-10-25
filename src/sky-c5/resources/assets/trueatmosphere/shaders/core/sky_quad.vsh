#version 150

in vec3 Position;

uniform mat4 ProjMat;
uniform mat4 ModelViewMat;

out vec4 v_far;

void main() {
    mat4 invViewProj = inverse(ProjMat * ModelViewMat);
    v_far = invViewProj * vec4(Position.xy, 1.0, 1.0);
    gl_Position = vec4(Position.xy, 1.0, 1.0);
}
