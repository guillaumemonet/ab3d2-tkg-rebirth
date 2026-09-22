#import "Common/ShaderLib/GLSLCompat.glsllib"

uniform sampler2D m_IndexMap;
uniform sampler2D m_Lut;
uniform sampler2D m_Waves;
uniform float m_Phase;
uniform float m_Scroll;

varying vec2 texCoord;
varying float depth;

/*
 * Le jeu ne « peint » pas l'eau : il REPREND le pixel deja dessine sous la surface et le
 * re-teinte (draw_WaterSurface) —
 *     pixel = palette[256*16 + distance + (vague*2 & $ff00) + index du pixel];
 * la vague vient du fichier waterfile (8 phases) et le tout defile avec wateroff.
 *
 * Ici le pixel « deja dessine » sous une surface d'eau est celui du SOL de la zone : on
 * echantillonne donc directement sa tuile (meme index de palette), et on applique la meme
 * formule de teinte. La distance ajoute 0 a 10 blocs, la vague 0 a 18.
 */
void main() {
    vec2 uv = texCoord + vec2(m_Scroll, m_Scroll);
    float wave = floor(texture2D(m_Waves, vec2(uv.x + uv.y * 0.5,
                                               (m_Phase + 0.5) / 8.0)).r * 255.0 + 0.5);
    // Le jeu echantillonne l'ecran DECALE verticalement (a6) : ici le decalage s'applique a la
    // texture du sol, ce qui donne le meme tremblement de l'image sous la surface.
    // Le decalage du jeu est en LIGNES D'ECRAN : a angle rasant il represente une grande
    // distance dans le monde. On le fait donc croitre avec la profondeur, ce qui etire le
    // reflet au loin comme dans l'original.
    uv += vec2(0.0, (wave - 9.0) * 0.004 * (1.0 + depth));
    float texel = floor(texture2D(m_IndexMap, uv).r * 255.0 + 0.5);
    // distance : (dist & $3f00) * 2 borne a 5*512, ajoute en octets -> 0..10 blocs.
    // 1 unite jME = 64 unites monde -> dist>>8 = depth/4, double = depth/2.
    float dist = clamp(floor(depth * 0.5), 0.0, 10.0);
    float row = clamp(wave + dist, 0.0, 28.0);
    gl_FragColor = texture2D(m_Lut, vec2((texel + 0.5) / 256.0, (row + 0.5) / 29.0));
}
