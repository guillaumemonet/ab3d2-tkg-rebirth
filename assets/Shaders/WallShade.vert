#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldViewMatrix;

attribute vec3 inPosition;
attribute vec2 inTexCoord;
attribute vec2 inTexCoord2;      // x = luminosite du coin (draw_LeftWallBright / RightWallBright)

varying vec2 texCoord;
varying float bright;
varying float depth;

void main() {
    texCoord = inTexCoord;
    bright = inTexCoord2.x;
    // Le jeu indexe la luminosite par la PROFONDEUR de vue (dist du strip), pas la distance
    // euclidienne : on reprend -z en espace camera.
    depth = -(g_WorldViewMatrix * vec4(inPosition, 1.0)).z;
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
}
