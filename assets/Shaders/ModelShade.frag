#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_IndexMap;
uniform sampler2D m_Lut;

varying vec2 texCoord;
varying float bright;      // niveau de la face (base + anneau), deja calcule cote CPU
varying float depth;

void main() {
    vec4 texel = texture2D(m_IndexMap, texCoord);
    if (texel.a < 0.5) {
        discard;                                   // rawIdx 0 = transparent
    }
    float idx = floor(texel.r * 255.0 + 0.5);
    float level = clamp(bright, 0.0, 31.0);
    gl_FragColor = texture2D(m_Lut, vec2((idx + 0.5) / 256.0, (level + 0.5) / 32.0));
}
