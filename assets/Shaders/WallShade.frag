#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_IndexMap;
uniform sampler2D m_Lut;

varying vec2 texCoord;
varying float bright;
varying float depth;

void main() {
    // Selecteur de rampe du texel (5 bits), stocke tel quel dans le canal rouge.
    float sr = floor(texture2D(m_IndexMap, texCoord).r * 255.0 + 0.5);

    // L'attribut porte deja 2*(coin-300) tronque a l'octet (cf. LevelBuilder.cornerBright) ;
    // le rasterizer reprend l'octet BAS signe de la valeur INTERPOLEE a chaque bande.
    float b = mod(bright + 128.0, 256.0) - 128.0;

    // niveau = luminosite de coin + distance>>7 (unites monde ; 1 unite jME = 64 unites monde),
    // borne a [0,64] ; bloc = table SCALE = ceil(niveau/2), plafonne a 31.
    float level = clamp(b + floor(depth * 0.5), 0.0, 64.0);
    float block = min(ceil(level * 0.5), 31.0);

    gl_FragColor = texture2D(m_Lut, vec2((sr + 0.5) / 32.0, (block + 0.5) / 32.0));
}
