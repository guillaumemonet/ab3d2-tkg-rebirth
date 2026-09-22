#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_Texture;
uniform sampler2D m_Displace;
uniform float m_Frame;

varying vec2 texCoord;

/*
 * Le jeu recopie chaque groupe de 8 pixels depuis un endroit decale du tampon chunky :
 *     4 pixels depuis a0 + offA, 4 pixels depuis a0 + 4 + offB, puis a0 += 8.
 * Un offset est un index dans un tampon de 320 octets par ligne : il vaut dy*320 + dx. La table
 * est donc pre-decomposee en (dx, dy) a l'extraction, un couple par DEMI-groupe de 4 pixels.
 *
 * Ici on travaille en UV, donc a n'importe quelle resolution : les decalages restent exprimes en
 * pixels de l'ecran d'origine et sont ramenes en fractions d'ecran.
 */
void main() {
    float col = floor(texCoord.x * 80.0);
    // texCoord.y = 0 en BAS pour OpenGL, alors que la ligne 0 de l'ecran est en HAUT.
    float row = floor((1.0 - texCoord.y) * 256.0);
    vec2 d = texture2D(m_Displace, vec2((col + 0.5) / 80.0,
                                        (row + 0.5 + m_Frame * 256.0) / 2048.0)).rg;
    float dx = d.r * 255.0 - 128.0;
    float dy = d.g * 255.0 - 128.0;
    vec2 uv = clamp(texCoord + vec2(dx / 320.0, -dy / 256.0), 0.0, 1.0);
    gl_FragColor = texture2D(m_Texture, uv);
}
