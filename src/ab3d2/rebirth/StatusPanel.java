package ab3d2.rebirth;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.texture.Texture2D;

import ab3d2.rebirth.sim.Inventory;

/**
 * Le PANNEAU DE STATUT d'origine : le bandeau du bas de {@code newborderpacked}, avec ses dix
 * pastilles d'arme, son compteur de munitions et son compteur d'energie, dessines avec les
 * chiffres du jeu ({@code bordercharsraw}).
 *
 * <p>Seul le BANDEAU est repris : le cadre complet encadrait une vue 3D de 288×232 et n'a pas de
 * sens en plein ecran. Il est etire sur toute la largeur de la fenetre, a l'echelle
 * {@code largeur / 320}, et les positions des chiffres sont celles du jeu (draw.h) :
 * <ul>
 *   <li>pastilles d'arme : x = 24, y = -16, dix chiffres de 8×5 ;</li>
 *   <li>munitions : x = 160, y = -18, trois chiffres de 8×7 ;</li>
 *   <li>energie : x = 272, y = -18.</li>
 * </ul>
 * Un compteur passe au jeu de chiffres ROUGE sous 10 ({@code LOW_*_COUNT_WARN_LIMIT}).
 */
public final class StatusPanel {

    /** L'ecran d'origine, auquel se rapportent toutes les positions de draw.h. */
    private static final int SCREEN_W = 320;
    private static final int SCREEN_H = 256;
    /** Lignes du bandeau dans la bordure (cf. rebirth.HudExport). */
    private static final int BAR_TOP = 232;
    private static final int BAR_H = 16;

    private static final int ITEM_X = 24;
    private static final int ITEM_Y = -16;
    private static final int AMMO_X = 160;
    private static final int ENERGY_X = 272;
    private static final int COUNT_Y = -18;
    private static final int CHAR_W = 8;
    private static final int SMALL_H = 5;
    private static final int BIG_H = 7;
    private static final int SLOTS = 10;
    private static final int WARN_LIMIT = 9;

    /** Rangees de digits.png : arme absente / possedee / selectionnee, compteur bas / normal. */
    private static final int ROW_ITEM = 0;
    private static final int ROW_ITEM_FOUND = 1;
    private static final int ROW_ITEM_SELECTED = 2;
    private static final int ROW_COUNT_WARN = 3;
    private static final int ROW_COUNT_GOOD = 4;
    /** Hauteur d'une rangee dans digits.png (le max des jeux, cf. HudExport). */
    private static final float ROW_H = 7f;
    private static final float SHEET_W = 80f;
    private static final float SHEET_H = 35f;

    private final Node node = new Node("panneau");
    private final float scale;
    private final float barTopY;
    private Geometry digits;
    private final MeshBuilder mb = new MeshBuilder();

    /** Dernier etat dessine : on ne refait le maillage que s'il change. */
    private int lastAmmo = -1;
    private int lastEnergy = -1;
    private int lastGun = -1;
    private int lastOwned = -1;

    private StatusPanel(AssetManager assets, Node gui, int width, int height) {
        scale = width / (float) SCREEN_W;
        barTopY = 0f;                                  // le bandeau est colle en bas de l'ecran
        MeshBuilder bmb = new MeshBuilder();
        quad(bmb, 0f, barTopY, width, barTopY + BAR_H * scale, 0f, 0f, 1f, 1f);
        Geometry bar = new Geometry("bandeau", bmb.build());
        bar.setMaterial(guiMaterial(assets, Assets.texture("hud/bar.png")));
        node.attachChild(bar);

        Material mat = guiMaterial(assets, Assets.texture("hud/digits.png"));
        // Maillage VIDE des le depart : la geometrie entre dans le graphe tout de suite, et jME
        // refuse d'en calculer les bornes tant qu'elle n'en a pas un (le menu peut s'afficher
        // avant le premier update()).
        digits = new Geometry("chiffres", new com.jme3.scene.Mesh());
        digits.setMaterial(mat);
        node.attachChild(digits);
        node.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Gui);
        gui.attachChild(node);
    }

    /** Cree le panneau si ses images ont ete extraites, sinon {@code null}. */
    public static StatusPanel create(AssetManager assets, Node gui, int width, int height) {
        if (!Assets.exists("hud/bar.png") || !Assets.exists("hud/digits.png")) {
            System.out.println("[Main] panneau de statut absent "
                    + "(gradle -p rebirth extract -Pwhat=hud)");
            return null;
        }
        return new StatusPanel(assets, gui, width, height);
    }

    /**
     * Met a jour les compteurs. Le maillage n'est REFAIT que si une valeur change — le jeu
     * d'origine ne redessine lui aussi que les compteurs modifies
     * ({@code draw_LastDisplayAmmoCount_w}, DrawC.java:633).
     */
    public void update(Inventory inv, int gun, int ammo) {
        int energy = Math.max(0, Math.min(999, inv.health()));
        int a = Math.max(0, Math.min(999, ammo));
        int owned = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (inv.hasGun(i)) {
                owned |= 1 << i;
            }
        }
        if (a == lastAmmo && energy == lastEnergy && gun == lastGun && owned == lastOwned) {
            return;
        }
        lastAmmo = a;
        lastEnergy = energy;
        lastGun = gun;
        lastOwned = owned;
        rebuild(a, energy, gun, owned);
    }

    private void rebuild(int ammo, int energy, int gun, int owned) {
        mb.reset();
        for (int i = 0; i < SLOTS; i++) {              // pastilles d'arme : 1..9 puis 0
            int row = i == gun ? ROW_ITEM_SELECTED
                    : ((owned & (1 << i)) != 0 ? ROW_ITEM_FOUND : ROW_ITEM);
            glyph(ITEM_X + i * CHAR_W, ITEM_Y, i, row, SMALL_H);
        }
        counter(AMMO_X, ammo);
        counter(ENERGY_X, energy);
        if (!mb.isEmpty()) {
            digits.setMesh(mb.build());
        }
    }

    /** Trois chiffres, le jeu ROUGE si la valeur est basse (LOW_*_COUNT_WARN_LIMIT). */
    private void counter(int x, int value) {
        int row = value <= WARN_LIMIT ? ROW_COUNT_WARN : ROW_COUNT_GOOD;
        int v = value;
        for (int d = 2; d >= 0; d--) {
            glyph(x + d * CHAR_W, COUNT_Y, v % 10, row, BIG_H);
            v /= 10;
        }
    }

    /**
     * Un chiffre : {@code sx,sy} en coordonnees de l'ecran d'origine ({@code sy} negatif = depuis
     * le BAS), {@code digit} 0..9, {@code row} le jeu de chiffres.
     */
    private void glyph(int sx, int sy, int digit, int row, int h) {
        float x0 = sx * scale;
        float x1 = (sx + CHAR_W) * scale;
        // sy < 0 se compte depuis le bas de l'ecran d'origine (draw_ScreenYPos).
        int top = sy < 0 ? SCREEN_H + sy : sy;
        float y1 = barTopY + (BAR_TOP + BAR_H - top) * scale;          // haut du glyphe
        float y0 = barTopY + (BAR_TOP + BAR_H - top - h) * scale;      // bas
        float u0 = digit * CHAR_W / SHEET_W;
        float u1 = (digit + 1) * CHAR_W / SHEET_W;
        float v0 = row * ROW_H / SHEET_H;                              // haut dans la feuille
        float v1 = (row * ROW_H + h) / SHEET_H;
        quad(mb, x0, y0, x1, y1, u0, v0, u1, v1);
    }

    /**
     * Un quad du HUD. {@code y0/y1} sont en repere GUI (Y monte), {@code v0/v1} en repere IMAGE
     * (v=0 en haut, nos textures etant chargees sans retournement) : le sommet HAUT prend donc
     * {@code v0}.
     *
     * <p>Le bandeau est dessine par ce quad et NON par un {@link com.jme3.ui.Picture} : Picture
     * applique sa propre convention de retournement et sortait le bandeau a l'envers.
     */
    private static void quad(MeshBuilder m, float x0, float y0, float x1, float y1,
                             float u0, float v0, float u1, float v1) {
        m.triangle(new float[] { x0, y1, 0f, u0, v0 },
                   new float[] { x1, y1, 0f, u1, v0 },
                   new float[] { x1, y0, 0f, u1, v1 });
        m.triangle(new float[] { x0, y1, 0f, u0, v0 },
                   new float[] { x1, y0, 0f, u1, v1 },
                   new float[] { x0, y0, 0f, u0, v1 });
    }

    private static Material guiMaterial(AssetManager assets, Texture2D tex) {
        Material mat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setTexture("ColorMap", tex);
        mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        mat.setFloat("AlphaDiscardThreshold", 0.1f);
        // Quads du HUD : pas de test de profondeur, et faces visibles des deux cotes — l'ordre
        // des sommets d'un quad 2D n'a pas de sens « avant/arriere ».
        mat.getAdditionalRenderState().setDepthTest(false);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return mat;
    }

    /** Detache du graphe : le HUD est rebati quand la taille de fenetre change. */
    public void detach() {
        node.removeFromParent();
    }

    /** Hauteur occupee a l'ecran, pour que le reste du HUD se place au-dessus. */
    public float height() {
        return BAR_H * scale;
    }

    public void setVisible(boolean on) {
        node.setCullHint(on ? com.jme3.scene.Spatial.CullHint.Inherit
                : com.jme3.scene.Spatial.CullHint.Always);
    }
}
