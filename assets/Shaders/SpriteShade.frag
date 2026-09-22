#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_IndexMap;
uniform sampler2D m_Lut;
uniform float m_PalRow;          // variante de palette (WhichLightPal), 0..3
uniform float m_Levels[29];      // niveau 0..31 de chacun des 29 groupes de couleurs

varying vec2 texCoord;
varying float bright;
varying float depth;

void main() {
    vec4 texel = texture2D(m_IndexMap, texCoord);
    if (texel.a < 0.5) {
        discard;                                   // index 0 = transparent
    }
    float idx = floor(texel.r * 255.0 + 0.5);
    float group = floor(idx / 8.0);
    float slot = idx - group * 8.0;

    // Niveau du groupe (les groupes au-dela de 28 gardent leur teinte d'origine).
    float level = floor(idx / 8.0);                // repli : niveau encode dans l'index
    for (int i = 0; i < 29; i++) {
        if (float(i) == group) {
            level = m_Levels[i];
        }
    }

    float u = (level * 8.0 + slot + 0.5) / 256.0;
    float v = (m_PalRow + 0.5) / 4.0;
    gl_FragColor = texture2D(m_Lut, vec2(u, v));
}
