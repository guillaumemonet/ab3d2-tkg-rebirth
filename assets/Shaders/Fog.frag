#import "Common/ShaderLib/GLSLCompat.glsllib"
#import "Common/ShaderLib/MultiSample.glsllib"

uniform COLORTEXTURE m_Texture;
uniform DEPTHTEXTURE m_DepthTexture;
varying vec2 texCoord;

uniform vec4 m_FogColor;
uniform float m_FogStart;
uniform float m_FogEnd;
uniform float m_Near;
uniform float m_Far;

void main() {
    vec4 couleur = getColor(m_Texture, texCoord);
    float z = getDepth(m_DepthTexture, texCoord).r;

    // LE CIEL EST EPARGNE. Rien n'a ete dessine la : la profondeur y vaut le fond du tampon,
    // et le fondre reviendrait a peindre le fond d'une couleur unie -- ce qui efface le ciel.
    if (z >= 0.9999) {
        gl_FragColor = couleur;
        return;
    }

    // La profondeur du tampon n'est pas une distance : elle est serree pres de la camera. On
    // la remet en unites du monde avant de s'en servir, sinon le brouillard se comporte
    // autrement selon les plans de la camera.
    float ndc = z * 2.0 - 1.0;
    float distance = (2.0 * m_Near * m_Far) / (m_Far + m_Near - ndc * (m_Far - m_Near));

    float part = clamp((distance - m_FogStart) / max(0.001, m_FogEnd - m_FogStart), 0.0, 1.0);
    // Courbe douce : une rampe droite fait une frontiere visible la ou elle commence.
    part = part * part * (3.0 - 2.0 * part);
    gl_FragColor = vec4(mix(couleur.rgb, m_FogColor.rgb, part), couleur.a);
}
