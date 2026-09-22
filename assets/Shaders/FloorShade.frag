#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_IndexMap;
uniform sampler2D m_Lut;

varying vec2 texCoord;
varying float bright;
varying float depth;

void main() {
    // Le texel d'une tuile de sol EST un index de palette (0..255).
    float texel = floor(texture2D(m_IndexMap, texCoord).r * 255.0 + 0.5);

    // niveau = luminosite du coin + distance>>9, borne a [0,30] (Hires.java:756-764).
    // 1 unite jME = 64 unites monde -> dist>>9 = depth/8.
    float level = clamp(bright + floor(depth * 0.125), 0.0, 30.0);

    gl_FragColor = texture2D(m_Lut, vec2((texel + 0.5) / 256.0, (level + 0.5) / 32.0));
}
