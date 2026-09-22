#import "Common/ShaderLib/GLSLCompat.glsllib"

attribute vec4 inPosition;
attribute vec2 inTexCoord;

varying vec2 texCoord;

// Quad plein ecran du FilterPostProcessor : meme forme que le Post.vert de jME (qui vit dans
// jme3-effects, dependance qu'on n'a pas).
void main() {
    texCoord = inTexCoord;
    gl_Position = vec4(sign(inPosition.xy - vec2(0.5)), 0.0, 1.0);
}
