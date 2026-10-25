#version 150

in vec3 Position;

out vec2 v_ndc;

void main() {
    v_ndc = Position.xy;
    gl_Position = vec4(Position.xy, 1.0, 1.0);
}
