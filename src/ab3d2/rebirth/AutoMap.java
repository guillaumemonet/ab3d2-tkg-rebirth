package ab3d2.rebirth;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

/**
 * La CARTE du jeu (TAB), portee de {@code DoTheMapWotNastyCharlesIsForcingMeToDo}
 * (modules/draw/draw_map.s). Le rasterizer de Bresenham d'origine est remplace par un maillage de
 * segments que le moteur trace ; tout le reste — quels murs, quelles couleurs, quelle orientation,
 * quels zooms, quelles touches — suit le port.
 *
 * <h2>Ce que le jeu stocke</h2>
 * Deux tables, remises a zero au chargement du niveau (hires.s) :
 * <ul>
 *   <li>{@code Lvl_BigMap_vl}, 40 octets par zone : dix emplacements de deux index de points
 *       (gauche, droite) — les extremites du segment ;</li>
 *   <li>{@code Lvl_CompactMap_vl}, 4 octets par zone : trois bits d'etat par emplacement.</li>
 * </ul>
 * L'octet FORT du mot de commande du flux zone-graph porte l'identite du mur sur la carte : bit 7
 * = il n'y figure pas, bits 0..3 = son emplacement, bit 4 = c'est une PORTE. L'extraction les
 * ressort en {@code Wall.mapSlot} / {@code Wall.mapDoor}.
 *
 * <h2>Ecart assume : la revelation</h2>
 * Le jeu inscrit un mur sur la carte au moment ou il le RASTERISE ({@code Hireswall}, bloc
 * « put into 2D map ») : la carte se decouvre mur par mur, a mesure qu'on les voit. Le remake
 * n'a pas ce crochet — les murs y sont groupes par texture et c'est jME qui decide de ce qu'il
 * dessine. On revele donc par ZONE VISITEE : entrer dans une piece decouvre ses dix murs d'un
 * coup. La carte en montre donc un peu plus que l'original, et un peu moins en ceci qu'une piece
 * apercue de loin sans y entrer reste vide.
 *
 * <p>Les deux etats « connu mais pas vu » ({@code $b00} / {@code $e00}, bits 010 et 011) du
 * lecteur d'origine sont du code MORT : rien n'ecrit jamais le bit 1 du triplet. Ils ne sont donc
 * pas portes.
 */
public final class AutoMap {

    /** Pen 255 de la palette : mur plein, vert vif. */
    private static final ColorRGBA SOLID = new ColorRGBA(0f, 1f, 0f, 1f);
    /** Pen 254 : porte / passage, vert sombre. */
    private static final ColorRGBA DOOR = new ColorRGBA(0f, 131 / 255f, 0f, 1f);
    /** Pen 250 : le chevron du joueur, rouge. */
    private static final ColorRGBA ARROW = new ColorRGBA(1f, 0f, 0f, 1f);

    /** L'ecran d'origine, auquel se rapportent l'echelle et le pas de defilement. */
    private static final int SCREEN_W = 320;
    /** {@code Draw_MapZoomLevel_w} : 0 = au plus pres, 7 = au plus loin, 3 au depart. */
    private static final int ZOOM_MIN = 0;
    private static final int ZOOM_MAX = 7;
    private static final int ZOOM_START = 3;
    /**
     * Unites editeur par pixel a zoom 0. Le jeu fait {@code asr.l #7} sur les coordonnees de
     * {@code Rotated_vl} puis {@code asr.w d5} du niveau de zoom ; mesure sur les niveaux, une
     * carte couvre environ 2560 unites editeur pour 320 pixels a zoom 3, soit 1 unite par pixel
     * a zoom 0.
     */
    private static final float UNITS_PER_PIXEL_AT_ZOOM0 = 1f;
    /** {@code shown_map} : le chevron fait 128 unites de large et 32 de haut. */
    private static final int ARROW_HALF_W = 64;
    private static final int ARROW_H = 32;
    /** Le defilement au pave numerique : {@code 1 << (zoom+2)} unites, soit 4 pixels constants. */
    private static final float PAN_PIXELS = 4f;
    /**
     * Mode TRANSPARENT ({@code Draw_MapTransparent_b}, pave numerique Entree). Le jeu fait passer
     * le pen par une table de fondu de la palette, entamee a {@code +256*25}, et enfonce les
     * PORTES de deux crans de plus ({@code add.w #256*2,a4}). Le remake n'a plus de palette : on
     * rend ces deux niveaux par un alpha, un cran valant environ 1/32.
     */
    private static final float ALPHA_SOLID = 25 / 32f;
    private static final float ALPHA_DOOR = 27 / 32f * (25 / 32f);

    private final Node node = new Node("carte");
    private final Geometry lines;
    private final Geometry arrow;
    private final float[] segX0;
    private final float[] segZ0;
    private final float[] segX1;
    private final float[] segZ1;
    private final int[] segZone;
    private final boolean[] segDoor;
    private final BitSet seen = new BitSet();
    private final float centreX;
    private final float centreY;
    private final float uiScale;

    private int zoom = ZOOM_START;
    private int drawn = -1;
    private float panX;
    private float panY;
    private boolean visible;
    private boolean transparent;

    private AutoMap(AssetManager assets, Node gui, LevelData lvl, int width, int height) {
        centreX = width * 0.5f;
        centreY = height * 0.5f;
        uiScale = width / (float) SCREEN_W;

        List<LevelData.Wall> keep = new ArrayList<>();
        for (LevelData.Wall w : lvl.walls) {
            if (w.mapSlot >= 0) {
                keep.add(w);
            }
        }
        int n = keep.size();
        segX0 = new float[n];
        segZ0 = new float[n];
        segX1 = new float[n];
        segZ1 = new float[n];
        segZone = new int[n];
        segDoor = new boolean[n];
        for (int i = 0; i < n; i++) {
            LevelData.Wall w = keep.get(i);
            LevelData.Point a = lvl.points.get(w.leftPt);
            LevelData.Point b = lvl.points.get(w.rightPt);
            segX0[i] = a.x;
            segZ0[i] = a.z;
            segX1[i] = b.x;
            segZ1[i] = b.z;
            segZone[i] = w.zone;
            segDoor[i] = w.mapDoor;
        }

        lines = new Geometry("carte_murs", new Mesh());
        lines.setMaterial(lineMaterial(assets));
        arrow = new Geometry("carte_joueur", chevron());
        arrow.setMaterial(lineMaterial(assets));
        node.attachChild(lines);
        node.attachChild(arrow);
        node.setQueueBucket(RenderQueue.Bucket.Gui);
        node.setCullHint(Spatial.CullHint.Always);
        gui.attachChild(node);
    }

    /** Cree la carte si le niveau porte les emplacements (extraction a jour), sinon {@code null}. */
    public static AutoMap create(AssetManager assets, Node gui, LevelData lvl,
                                 int width, int height) {
        if (lvl.walls == null || lvl.points == null) {
            return null;
        }
        boolean any = false;
        for (LevelData.Wall w : lvl.walls) {
            if (w.mapSlot >= 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            System.out.println("[Main] carte indisponible "
                    + "(gradle -p rebirth extract -Pwhat=levels)");
            return null;
        }
        return new AutoMap(assets, gui, lvl, width, height);
    }

    /** Detache la carte : elle appartient au niveau et se refait au suivant. */
    public void detach() {
        node.removeFromParent();
    }

    /** TAB : bascule la carte (MAPON). */
    public void toggle() {
        visible = !visible;
        node.setCullHint(visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
    }

    public boolean visible() {
        return visible;
    }

    /** F1 (delta -1) / F2 (delta +1) : {@code Draw_MapZoomLevel_w}. */
    public void zoom(int delta) {
        zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom + delta));
    }

    /** Pave numerique : defilement de la vue, 4 pixels par frame comme l'original. */
    public void pan(int dx, int dy) {
        panX += dx * PAN_PIXELS * uiScale;
        panY += dy * PAN_PIXELS * uiScale;
    }

    /** Fixe directement le niveau de zoom (validation : {@code -Pmap=<0..7>}). */
    public void setZoom(int level) {
        zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, level));
    }

    /**
     * Revele TOUT le niveau ({@code -PmapAll}). Sert a comparer le trace a la geometrie du
     * niveau sans avoir a le parcourir ; ce n'est pas un etat que le jeu peut atteindre.
     */
    public void revealAll() {
        for (int zone : segZone) {
            seen.set(zone);
        }
        drawn = -1;
    }

    /** Pave numerique Entree : bascule le fondu ({@code Draw_MapTransparent_b}). */
    public void toggleTransparent() {
        transparent = !transparent;
        drawn = -1;                                    // les couleurs changent : on refait le trace
    }

    /** Pave numerique 5 : recentre sur le joueur. */
    public void centre() {
        panX = 0f;
        panY = 0f;
    }

    /**
     * Une frame. La zone du joueur devient connue meme carte fermee — le jeu aussi inscrit les
     * murs pendant le rendu normal, pas seulement quand la carte est affichee.
     *
     * @param x     position du joueur, en unites editeur
     * @param angle {@code plr1_Angle_w} : unites de la table sinus, 8192 = tour complet
     */
    public void update(int x, int z, int angle, int zone) {
        if (zone >= 0) {
            seen.set(zone);
        }
        if (!visible) {
            return;
        }
        if (drawn != seen.cardinality()) {
            drawn = seen.cardinality();
            rebuild();
        }
        // Le joueur regarde toujours vers le HAUT : on annule le lacet de la camera. Celui-ci
        // vaut PI - angle*2PI/8192 (PlayerSim.yawRadians) ; l'annuler revient a tourner la carte
        // de angle*2PI/8192 - PI.
        float theta = angle / 8192f * FastMath.TWO_PI - FastMath.PI;
        float scale = uiScale / (UNITS_PER_PIXEL_AT_ZOOM0 * (1 << zoom));
        node.setLocalScale(scale);
        node.setLocalRotation(new Quaternion().fromAngles(0f, 0f, theta));
        node.setLocalTranslation(centreX + panX, centreY + panY, 0f);
        // Repere local = unites editeur, origine au joueur ; Y = Z editeur (cf. l'entete).
        lines.setLocalTranslation(-x, -z, 0f);
        // Le chevron ne doit PAS tourner avec la carte : on lui applique la rotation inverse pour
        // qu'il pointe toujours vers le haut de l'ecran.
        arrow.setLocalRotation(new Quaternion().fromAngles(0f, 0f, -theta));
    }

    /** Refait le maillage avec les seules zones connues. */
    private void rebuild() {
        int n = 0;
        for (int i = 0; i < segZone.length; i++) {
            if (seen.get(segZone[i])) {
                n++;
            }
        }
        float[] pos = new float[n * 6];
        float[] col = new float[n * 8];
        int p = 0;
        int c = 0;
        for (int i = 0; i < segZone.length; i++) {
            if (!seen.get(segZone[i])) {
                continue;
            }
            pos[p++] = segX0[i];
            pos[p++] = segZ0[i];
            pos[p++] = 0f;
            pos[p++] = segX1[i];
            pos[p++] = segZ1[i];
            pos[p++] = 0f;
            ColorRGBA k = segDoor[i] ? DOOR : SOLID;
            float a = !transparent ? 1f : (segDoor[i] ? ALPHA_DOOR : ALPHA_SOLID);
            for (int v = 0; v < 2; v++) {
                col[c++] = k.r;
                col[c++] = k.g;
                col[c++] = k.b;
                col[c++] = a;
            }
        }
        lines.setMesh(lineMesh(pos, col));
    }

    /** {@code shown_map} : deux segments formant un chevron vers l'avant. */
    private static Mesh chevron() {
        float[] pos = {
            0f, ARROW_H, 0f,   -ARROW_HALF_W, 0f, 0f,
            0f, ARROW_H, 0f,    ARROW_HALF_W, 0f, 0f,
        };
        float[] col = new float[4 * 4];
        for (int i = 0; i < 4; i++) {
            col[i * 4] = ARROW.r;
            col[i * 4 + 1] = ARROW.g;
            col[i * 4 + 2] = ARROW.b;
            col[i * 4 + 3] = ARROW.a;
        }
        return lineMesh(pos, col);
    }

    private static Mesh lineMesh(float[] pos, float[] col) {
        Mesh m = new Mesh();
        m.setMode(Mesh.Mode.Lines);
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        m.setBuffer(VertexBuffer.Type.Color, 4, BufferUtils.createFloatBuffer(col));
        m.updateBound();
        return m;
    }

    /**
     * Materiau des traits. Comme pour le panneau de statut, le test de profondeur doit etre COUPE
     * dans le guiNode, sans quoi rien ne s'affiche.
     */
    private static Material lineMaterial(AssetManager assets) {
        Material mat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setBoolean("VertexColor", true);
        mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        mat.getAdditionalRenderState().setDepthTest(false);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return mat;
    }
}
