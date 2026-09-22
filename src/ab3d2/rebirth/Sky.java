package ab3d2.rebirth;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.FastMath;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.texture.Texture2D;

/**
 * Ciel de fond (backdrop), porte de Newanims.Draw_SkyBackdrop (Newanims.java:2133).
 *
 * <p>Mapping d'origine : la colonne de l'image vient de
 * {@code ((angPos & 4095) * 648) >> 12}, donc la panoramique de 648 px couvre un DEMI-tour
 * (le tour complet vaut 8192 unites d'angle) : elle se repete deux fois par revolution.
 * Verticalement, les 240 lignes sont copiees 1 pixel ecran par texel avec l'horizon a
 * {@code Vid_CentreY}, soit environ 72,6 degres de champ vertical a la resolution d'origine
 * (320x240 pour ~88,9 degres horizontaux = 320 des 648 colonnes).
 *
 * <p>Ici : un CYLINDRE centre sur la camera (donc a l'infini), U repete deux fois par tour,
 * V couvrant ce meme champ vertical. Rendu dans le bucket Sky (ni test ni ecriture de
 * profondeur), visible la ou le niveau n'a pas de plafond.
 */
public final class Sky {

    /** Demi-champ vertical couvert par l'image, en radians (cf. explication ci-dessus). */
    private static final float HALF_V_FOV = 36.3f * FastMath.DEG_TO_RAD;
    private static final float RADIUS = 500f;
    private static final int SEGMENTS = 64;

    private Sky() {
    }

    /** Renvoie la geometrie du ciel, ou null si le backdrop n'est pas extrait. */
    public static Geometry create(AssetManager assetManager) {
        Texture2D tex = Assets.texture("textures/sky/backdrop.png");
        if (tex == null) {
            return null;
        }
        // Le mapping vertical d'origine est PLANAIRE (une ligne de texel par ligne d'ecran),
        // donc v est lineaire en y sur le cylindre. On prolonge le cylindre bien au-dela de la
        // bande d'image et on laisse le mode EdgeClamp etirer la premiere/derniere ligne :
        // sans cela un trou apparait au-dessus de l'horizon quand on leve la tete.
        float half = RADIUS * FastMath.tan(HALF_V_FOV);
        float ext = 4f;                                // hauteur du cylindre = 4x la bande
        tex.setWrap(com.jme3.texture.Texture.WrapAxis.S, com.jme3.texture.Texture.WrapMode.Repeat);
        tex.setWrap(com.jme3.texture.Texture.WrapAxis.T, com.jme3.texture.Texture.WrapMode.EdgeClamp);

        MeshBuilder mb = new MeshBuilder();
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = FastMath.TWO_PI * i / SEGMENTS;
            float a1 = FastMath.TWO_PI * (i + 1) / SEGMENTS;
            // Sens : la camera est a l'interieur -> on regarde la face interne.
            float x0 = FastMath.sin(a0) * RADIUS, z0 = -FastMath.cos(a0) * RADIUS;
            float x1 = FastMath.sin(a1) * RADIUS, z1 = -FastMath.cos(a1) * RADIUS;
            // U : deux repetitions par tour (648 px = un demi-tour).
            float u0 = 2f * i / SEGMENTS;
            float u1 = 2f * (i + 1) / SEGMENTS;
            float yTop = half * ext;
            float yBot = -half * ext;
            float vTop = 0.5f - ext / 2f;              // v lineaire en y (0..1 sur la bande)
            float vBot = 0.5f + ext / 2f;
            mb.vertex(x0, yTop, z0, u0, vTop);
            mb.vertex(x1, yTop, z1, u1, vTop);
            mb.vertex(x1, yBot, z1, u1, vBot);
            mb.vertex(x0, yTop, z0, u0, vTop);
            mb.vertex(x1, yBot, z1, u1, vBot);
            mb.vertex(x0, yBot, z0, u0, vBot);
        }

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setTexture("ColorMap", tex);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        mat.getAdditionalRenderState().setDepthWrite(false);

        Geometry sky = new Geometry("Sky", mb.build());
        sky.setMaterial(mat);
        sky.setQueueBucket(RenderQueue.Bucket.Sky);
        sky.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        return sky;
    }
}
