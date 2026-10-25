#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ProjMat;
uniform mat4 ModelViewMat;

out vec4 v_colour;

void main() {
    v_colour = Color;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
