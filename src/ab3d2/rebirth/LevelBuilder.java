package ab3d2.rebirth;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.control.BillboardControl;
import com.jme3.texture.Texture2D;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Construit la geometrie d'un niveau a partir de son JSON (rebirth/assets/levels/&lt;lettre&gt;.json).
 *
 * <p>Portage en Java/jME du LevelLoader valide cote Godot ; les regles de rendu viennent du
 * portage fidele (Hireswall.Draw_Wall, DrawFloor, Objdrawhires) qui reste l'ORACLE.
 *
 * <p>Repere Amiga -&gt; jME (verifie contre le port) :
 * <ul>
 *   <li>X/Z : /64 ({@link #XZ_SCALE}), avec Z INVERSE (z_jme = -z_amiga).</li>
 *   <li>Y : "plus negatif = plus haut" -&gt; y_jme = -h/32 ({@link #Y_SCALE}).</li>
 *   <li>Hauteurs de zone (floorH/roofH, virgule fixe .8) : y_jme = -h/8192.</li>
 *   <li>Hauteurs de flat (y &lt;&lt; 6) : y_jme = -y/128.</li>
 * </ul>
 *
 * <p>Texturage des murs (fidele a Draw_Wall) : la texture murale est une BANDE de sous-tuiles.
 * Chaque mur utilise la sous-tuile [fromTile, fromTile+texW) qui se REPETE ; U va de 0 a
 * wallLen/texW ; V est ancree au MONDE : v = (Yediteur + yOffset) / texH (c'est cet ancrage qui
 * evite les decalages sur les portes et les marches).
 */
public final class LevelBuilder {

    public static final float XZ_SCALE = 64f;
    public static final float Y_SCALE = 32f;

    /** Sprites : 32 px = 1 unite monde (meme echelle que Y). */
    private static final float SPRITE_PPU = 32f;
    /**
     * Modeles vectoriels : 1 unite du .obj = 1 unite monde, tiree de l'ASM et non de l'oeil.
     *
     * <p>Un point de modele et la position de l'objet qui le porte se rencontrent dans le meme
     * accumulateur, juste avant la division perspective ; leurs deux facteurs donnent donc le
     * rapport d'unites, sans dependre de la projection. Sur les trois axes il vaut 1/4
     * (objdrawhires.s, chemin petit ecran ; l'amplitude de SinCosTable_vw est 2^15) :
     * <pre>
     *   x : point = (x.sin - z.cos) &gt;&gt; 9        -> 64 par unite
     *       objet = ObjRotated+4 (x.128) * 2      -> 256 par mot de niveau
     *   y : point = y &lt;&lt; 6                       -> 64
     *       objet = (y &lt;&lt; 7 - Plr_YOff) * 2       -> 256
     *   z : point = swap(x.cos + z.sin)           -> 1/2
     *       objet = ObjRotated+2 * 2              -> 2
     * </pre>
     * Soit 1 unite de modele = 1/4 mot de niveau, et le mot de niveau vaut 1/{@link #XZ_SCALE}
     * d'unite monde : 1/256. Comme {@code VectObjExport} ecrit les .obj en coordonnees brutes
     * divisees par 128, il reste 128/256 = 0,5. (La valeur precedente, 0,75, etait reglee a
     * l'oeil : les modeles etaient de moitie trop gros.)
     */
    private static final float MODEL_SCALE = 0.5f;

    /**
     * L'arme en main echappe a {@link #MODEL_SCALE} : le jeu d'origine la traite a part.
     *
     * <p>Elle est bien un objet du monde (l'entite ENT_NEXT_2), mais {@code draw_PolygonModel}
     * detecte ce cas et force sa profondeur a 1 au lieu de la vraie (objdrawhires.s,
     * DRAW_VECTOR_NEAR_PLANE). Sa taille a l'ecran ne vient donc PAS de l'echelle du monde : la
     * division perspective par z=1 la fixe a elle seule. Ici l'arme est posee a l'oeil de la
     * camera, sans recul ; son facteur est le seul reglage disponible et il se regle a l'image.
     */
    private static final float WEAPON_SCALE = 0.75f;
    private final AssetManager assetManager;
    private final GlfData glf;
    private final float vSign;

    private final Map<String, Material> materials = new HashMap<>();
    private final Map<Integer, BufferedImage> wallImages = new HashMap<>();
    private final Map<Integer, BufferedImage> wallIndexImages = new HashMap<>();
    private final Map<Integer, LevelData.Zone> zonesById = new HashMap<>();
    /** -Drebirth.fullbright : ignore l'eclairage d'origine (textures a plat), pour comparer. */
    private final boolean fullbright = System.getProperty("rebirth.fullbright") != null;
    /**
     * Rendu MOTEUR (par defaut) : textures couleur + {@code Lighting.j3md} + vraies lumieres jME.
     * {@code -Dretro} rebascule sur l'emulation du rasterizer d'origine (palette + LUT).
     */
    private final boolean modern = System.getProperty("rebirth.retro") == null;

    /** Panneaux mobiles des zones-portes/lifts : zone -&gt; noeud + course (pour DoorRoutine). */
    public final Map<Integer, DoorPanel> doorPanels = new LinkedHashMap<>();
    /** Sol des zones-ascenseurs : zone -&gt; noeud deplacable (pour LiftRoutine). */
    public final Map<Integer, Node> liftFloors = new LinkedHashMap<>();
    /** Plafond des zones-portes : zone -&gt; noeud deplacable (le DESSOUS du battant). */
    public final Map<Integer, Node> doorCeilings = new LinkedHashMap<>();
    /** Hauteur jME d'origine de ces plafonds, pour en deduire le deplacement. */
    private final Map<Integer, Float> doorCeilBase = new LinkedHashMap<>();

    private java.util.Set<Integer> liftZones = java.util.Set.of();
    private java.util.Set<Integer> doorZones = java.util.Set.of();
    private ab3d2.rebirth.sim.SinCos sinCos;        // table d'origine, pour l'angle des anneaux
    private int modelLevelMin = 99, modelLevelMax = -1;   // plage de niveaux des faces eclairees
    private LightAnim lights;
    private int refreshCount;
    private final List<LitMesh> litMeshes = new ArrayList<>();
    private final List<LitModel> litModels = new ArrayList<>();
    private final List<LitSprite> litSprites = new ArrayList<>();
    private final List<AnimObject> animObjects = new ArrayList<>();
    private final Map<LevelData.Obj, Geometry> objectGeometries = new HashMap<>();
    private final List<Geometry> shotGeoms = new ArrayList<>();
    private Node shotsNode;

    /** Objet dont le graphique change au fil de son script d'animation (ventilateurs, glares...). */
    private static final class AnimObject {
        final Geometry geometry;
        final LevelData.Obj obj;
        final ObjectAnim anim;
        final boolean vector;

        AnimObject(Geometry geometry, LevelData.Obj obj, ObjectAnim anim, boolean vector) {
            this.geometry = geometry;
            this.obj = obj;
            this.anim = anim;
            this.vector = vector;
        }
    }

    /** Sprite eclaire (monstre) : ses 29 niveaux de couleur sont recalcules a chaque frame. */
    private static final class LitSprite {
        final Geometry geometry;
        final Material material;
        final LevelData.Obj obj;
        LightRings rings;

        LitSprite(Geometry geometry, Material material, LevelData.Obj obj, LightRings rings) {
            this.geometry = geometry;
            this.material = material;
            this.obj = obj;
            this.rings = rings;
        }
    }

    /** Surface dont la luminosite par sommet vient de points de niveau (murs, sols, plafonds). */
    private record LitMesh(Geometry geometry, int[][] source, boolean wall) {
    }

    /** Instance de modele vectoriel : son maillage est reconstruit quand les lumieres bougent. */
    private record LitModel(Geometry geometry, LevelData.Obj obj, ObjModels.Model model) {
    }
    private LevelData level;

    /**
     * Les murs PROPRES a une zone-porte ou zone-ascenseur : ce sont les JAMBAGES (les cotes de
     * l'embrasure), pas le battant. Le jeu ne les bouge pas — le battant, lui, est un mur des
     * pieces voisines, qu'il DEFORME (cf. {@link #updateDeformed}). On les garde groupes par zone
     * parce qu'ils forment un ensemble a part dans la scene.
     */
    public static final class DoorPanel {
        public final Node node;

        DoorPanel(Node node) {
            this.node = node;
        }
    }

    public LevelBuilder(AssetManager assetManager, GlfData glf) {
        this.assetManager = assetManager;
        this.glf = glf;
        // Sens de V : +1 = convention "v=0 en haut" (cf. Assets.toTexture). Reglable pour valider.
        this.vSign = Float.parseFloat(System.getProperty("rebirth.vsign", "1"));
    }

    public Node build(LevelData lvl) {
        level = lvl;
        litMeshes.clear();
        litModels.clear();
        litSprites.clear();
        animObjects.clear();
        objectGeometries.clear();
        liftZones = new java.util.HashSet<>();
        for (LevelData.Liftable lf : safe(lvl.lifts)) {
            liftZones.add(lf.zone);                    // sol anime : mesh separe
        }
        doorZones = new java.util.HashSet<>();
        doorCeilings.clear();
        doorCeilBase.clear();
        for (LevelData.Liftable dr : safe(lvl.doors)) {
            doorZones.add(dr.zone);                    // plafond anime : mesh separe
        }
        zonesById.clear();
        for (LevelData.Zone z : safe(lvl.zones)) {
            zonesById.put(z.id, z);
        }
        lights = new LightAnim(zonesById);             // lumieres animees (brightanim)
        if (sinCos == null) {
            sinCos = ab3d2.rebirth.sim.SinCos.load();
        }
        dynLights = new DynLights(lvl, zonesById, lights, sinCos);   // projectiles, torches
        Node root = new Node("Level_" + lvl.level);
        root.attachChild(buildWalls(lvl));
        root.attachChild(buildFlats(lvl));
        root.attachChild(buildObjects(lvl));
        return root;
    }

    // ------------------------------------------------------------------ murs

    private Node buildWalls(LevelData lvl) {
        Node node = new Node("Walls");

        // Zones-portes / zones-ascenseurs : leurs murs propres (les jambages) vont a part.
        java.util.Set<Integer> liftableZones = new java.util.HashSet<>();
        for (List<LevelData.Liftable> arr : List.of(safe(lvl.doors), safe(lvl.lifts))) {
            for (LevelData.Liftable d : arr) {
                liftableZones.add(d.zone);
            }
        }

        // Murs DEFORMES par une porte ou un ascenseur : ils ne peuvent pas aller dans les
        // surfaces statiques, leur maillage est refait a chaque frame de mouvement. On pose ici
        // leur etat de depart (position initiale du liftable), comme la 1re frame du jeu.
        Map<Integer, LevelData.Wall> byGfxOfs = new HashMap<>();
        for (LevelData.Wall w : safe(lvl.walls)) {
            byGfxOfs.put(w.gfxOfs, w);
        }
        deformed.clear();
        List<List<LevelData.Wall>> patchedOf = new ArrayList<>();
        java.util.Set<LevelData.Wall> patchedAll = new java.util.HashSet<>();
        for (int kind = 0; kind < 2; kind++) {         // 0 = portes, 1 = ascenseurs
            boolean isDoor = kind == 0;
            for (LevelData.Liftable d : safe(isDoor ? lvl.doors : lvl.lifts)) {
                List<LevelData.Wall> ws = new ArrayList<>();
                int v0 = d.position >> 2;
                for (LevelData.WallPatch wp : safe(d.wallPatches)) {
                    LevelData.Wall w = byGfxOfs.get(wp.gfxOfs);
                    if (w == null) {
                        continue;
                    }
                    w.yOffsetBase = wp.yBase;
                    w.yOffset = (short) (wp.yBase - v0);
                    if (isDoor) {
                        w.bottom = v0;
                    } else {
                        w.top = v0;
                    }
                    ws.add(w);
                    patchedAll.add(w);
                }
                patchedOf.add(ws);
            }
        }

        // Regroupement par (texIndex, fromTile, texW) : un materiau = une sous-tuile.
        Map<String, List<LevelData.Wall>> groups = new LinkedHashMap<>();
        Map<Integer, Map<String, List<LevelData.Wall>>> doorGroups = new LinkedHashMap<>();
        for (LevelData.Wall w : safe(lvl.walls)) {
            if (patchedAll.contains(w)) {
                continue;                              // mur deforme : traite plus bas
            }
            String key = (w.texIndex & 0x7FFF) + "_" + w.fromTile + "_" + (w.widthMask + 1);
            if (liftableZones.contains(w.zone)) {
                doorGroups.computeIfAbsent(w.zone, k -> new LinkedHashMap<>())
                        .computeIfAbsent(key, k -> new ArrayList<>()).add(w);
            } else {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
            }
        }

        int surfaces = 0;
        for (Map.Entry<String, List<LevelData.Wall>> e : groups.entrySet()) {
            Geometry g = wallSurface(e.getValue(), lvl.points, e.getKey());
            if (g != null) {
                node.attachChild(g);
                surfaces++;
            }
        }

        doorPanels.clear();
        for (Map.Entry<Integer, Map<String, List<LevelData.Wall>>> e : doorGroups.entrySet()) {
            Node panel = new Node("door_zone_" + e.getKey());
            for (Map.Entry<String, List<LevelData.Wall>> g : e.getValue().entrySet()) {
                Geometry geo = wallSurface(g.getValue(), lvl.points, g.getKey());
                if (geo != null) {
                    panel.attachChild(geo);
                }
            }
            node.attachChild(panel);
            doorPanels.put(e.getKey(), new DoorPanel(panel));
        }

        // Un groupe par liftable (portes d'abord, puis ascenseurs : l'ordre de patchedOf).
        int deformedWalls = 0;
        for (List<LevelData.Wall> ws : patchedOf) {
            List<Deformed> parts = new ArrayList<>();
            Map<String, List<LevelData.Wall>> byKey = new LinkedHashMap<>();
            for (LevelData.Wall w : ws) {
                String key = (w.texIndex & 0x7FFF) + "_" + w.fromTile + "_" + (w.widthMask + 1);
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
            }
            for (Map.Entry<String, List<LevelData.Wall>> e : byKey.entrySet()) {
                Geometry geo = wallSurface(e.getValue(), lvl.points, e.getKey(), true);
                if (geo != null) {
                    // Nom DISTINCT des surfaces statiques (qui s'appellent wall_<cle>) : c'est
                    // par lui qu'une scene .j3o rechargee retrouve quel groupe de murs telle
                    // porte ou tel ascenseur deforme.
                    geo.setName("deform_" + deformed.size() + "_" + e.getKey());
                    node.attachChild(geo);
                    parts.add(new Deformed(geo, e.getValue(), e.getKey()));
                    deformedWalls += e.getValue().size();
                }
            }
            deformed.add(parts);
        }
        deformedPos = new int[deformed.size()];
        java.util.Arrays.fill(deformedPos, Integer.MIN_VALUE);

        System.out.printf("[Level] murs : %d murs -> %d surfaces + %d zones-portes"
                + " + %d murs deformes%n",
                safe(lvl.walls).size(), surfaces, doorPanels.size(), deformedWalls);
        return node;
    }


    /**
     * Reprend une scène de décor DÉJÀ CONSTRUITE (un `.j3o` relu) au lieu de la rebâtir.
     *
     * <p>C'est ce qui rend les niveaux modifiables : la géométrie affichée devient celle du
     * fichier, qu'on peut ouvrir et corriger dans le SDK jME. Le jeu ne perd rien au passage —
     * tout ce que le runtime demande ensuite au constructeur (portes qui montent, sols
     * d'ascenseurs, plafonds de zones-portes, surfaces d'eau) est <b>ré-attaché par NOM de
     * nœud</b>, et les données qui vont avec (quels murs telle porte déforme, à quelle hauteur
     * est le plafond) se relisent dans le JSON du niveau, qui reste de toute façon chargé pour
     * la simulation.
     *
     * <p>Les OBJETS ne sont pas dans la scène : ramassages, décor animé et monstres sont des
     * entités que le jeu place et retire en cours de partie, pas du décor figé. Ils sont donc
     * construits ici comme d'habitude.
     *
     * @param decor le nœud relu du `.j3o` (celui qu'a écrit {@code extract.SceneExport})
     * @return la racine du niveau, prête à être attachée
     */
    public Node buildFromScene(Node decor, LevelData lvl) {
        level = lvl;
        litMeshes.clear();
        litModels.clear();
        litSprites.clear();
        animObjects.clear();
        objectGeometries.clear();
        waterGeoms.clear();
        doorPanels.clear();
        liftFloors.clear();
        doorCeilings.clear();
        doorCeilBase.clear();
        deformed.clear();

        liftZones = new java.util.HashSet<>();
        for (LevelData.Liftable lf : safe(lvl.lifts)) {
            liftZones.add(lf.zone);
        }
        doorZones = new java.util.HashSet<>();
        for (LevelData.Liftable dr : safe(lvl.doors)) {
            doorZones.add(dr.zone);
        }
        zonesById.clear();
        for (LevelData.Zone z : safe(lvl.zones)) {
            zonesById.put(z.id, z);
        }
        lights = new LightAnim(zonesById);
        if (sinCos == null) {
            sinCos = ab3d2.rebirth.sim.SinCos.load();
        }
        dynLights = new DynLights(lvl, zonesById, lights, sinCos);

        // --- les parties MOBILES du decor, retrouvees par leur nom ---
        for (LevelData.Liftable lf : safe(lvl.lifts)) {
            com.jme3.scene.Spatial n = decor.getChild("lift_zone_" + lf.zone);
            if (n instanceof Node node) {
                liftFloors.put(lf.zone, node);
            }
        }
        Map<Integer, LevelData.Flat> ceilOf = new HashMap<>();
        for (LevelData.Flat f : safe(lvl.flats)) {
            if (!"floor".equals(f.kind) && !"water".equals(f.kind) && !f.upper) {
                ceilOf.putIfAbsent(f.zone, f);
            }
        }
        for (LevelData.Liftable dr : safe(lvl.doors)) {
            com.jme3.scene.Spatial n = decor.getChild("door_zone_" + dr.zone);
            LevelData.Flat c = ceilOf.get(dr.zone);
            if (n instanceof Node node && c != null) {
                doorCeilings.put(dr.zone, node);
                doorCeilBase.put(dr.zone, -c.y / 128f);
            }
        }
        for (LevelData.Zone z : safe(lvl.zones)) {
            if (decor.getChild("water_" + z.id) instanceof Geometry g) {
                waterGeoms.add(g);
            }
        }

        // --- les murs DEFORMES : meme regroupement qu'a la construction, geometrie par nom ---
        Map<Integer, LevelData.Wall> byGfxOfs = new HashMap<>();
        for (LevelData.Wall w : safe(lvl.walls)) {
            byGfxOfs.put(w.gfxOfs, w);
        }
        int bound = 0;
        for (int kind = 0; kind < 2; kind++) {         // 0 = portes, 1 = ascenseurs
            boolean isDoor = kind == 0;
            for (LevelData.Liftable d : safe(isDoor ? lvl.doors : lvl.lifts)) {
                List<LevelData.Wall> ws = new ArrayList<>();
                int v0 = d.position >> 2;
                for (LevelData.WallPatch wp : safe(d.wallPatches)) {
                    LevelData.Wall w = byGfxOfs.get(wp.gfxOfs);
                    if (w == null) {
                        continue;
                    }
                    w.yOffsetBase = wp.yBase;
                    w.yOffset = (short) (wp.yBase - v0);
                    if (isDoor) {
                        w.bottom = v0;
                    } else {
                        w.top = v0;
                    }
                    ws.add(w);
                }
                Map<String, List<LevelData.Wall>> byKey = new LinkedHashMap<>();
                for (LevelData.Wall w : ws) {
                    String key = (w.texIndex & 0x7FFF) + "_" + w.fromTile + "_" + (w.widthMask + 1);
                    byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(w);
                }
                List<Deformed> parts = new ArrayList<>();
                for (Map.Entry<String, List<LevelData.Wall>> e : byKey.entrySet()) {
                    String name = "deform_" + deformed.size() + "_" + e.getKey();
                    if (decor.getChild(name) instanceof Geometry g) {
                        parts.add(new Deformed(g, e.getValue(), e.getKey()));
                        bound++;
                    }
                }
                deformed.add(parts);
            }
        }
        deformedPos = new int[deformed.size()];
        java.util.Arrays.fill(deformedPos, Integer.MIN_VALUE);

        // Les materiaux reviennent par le chargeur de scene, pas par notre j3m() : il faut leur
        // reposer le filtrage « plus proche voisin », que le format .j3m ne sait pas exprimer.
        // Sans ca jME interpole et tout le decor est legerement flou.
        decor.depthFirstTraversal(sp -> {
            if (sp instanceof Geometry g && g.getMaterial() != null) {
                pixelate(g.getMaterial());
            }
        });

        Node root = new Node("Level_" + lvl.level);
        root.attachChild(decor);
        root.attachChild(buildObjects(lvl));
        System.out.printf("[Level] scene relue : %d sols d'ascenseur, %d plafonds de porte,"
                + " %d groupes de murs deformes (%d surfaces), %d surfaces d'eau%n",
                liftFloors.size(), doorCeilings.size(), deformed.size(), bound, waterGeoms.size());
        return root;
    }

    /** Un groupe de murs qu'une porte ou un ascenseur deforme (meme sous-tuile). */
    private record Deformed(Geometry geometry, List<LevelData.Wall> walls, String key) { }

    /** Les groupes deformes, dans l'ordre {portes..., ascenseurs...}. */
    private final List<List<Deformed>> deformed = new ArrayList<>();
    /** Derniere position appliquee : un liftable immobile ne fait rien refaire. */
    private int[] deformedPos = new int[0];

    /**
     * Applique une frame de mouvement de porte ou d'ascenseur aux murs qu'elle DEFORME
     * (newanims.s, {@code .simplecheck} / {@code liftwalls}).
     *
     * <p>Le jeu ecrit dans le record du mur {@code yOffset = yBase - (position >> 2)} puis, pour
     * une PORTE, {@code bottom = position >> 2} — le battant PEND du plafond et c'est son bord
     * BAS qui monte — ou, pour un ASCENSEUR, {@code top = position >> 2} : la jupe de la
     * plate-forme, dont le bord HAUT suit le sol qui monte. On refait le maillage du groupe,
     * quelques quads.
     *
     * @param index    porte 0..n-1, puis ascenseur n..
     * @param position position courante du liftable (mot brut)
     * @param door     vrai pour une porte, faux pour un ascenseur
     */
    public void updateDeformed(int index, int position, boolean door) {
        if (index < 0 || index >= deformed.size() || level == null) {
            return;
        }
        if (deformedPos.length > index && deformedPos[index] == position) {
            return;                                    // immobile : rien a refaire
        }
        if (deformedPos.length > index) {
            deformedPos[index] = position;
        }
        int v = position >> 2;                         // asr.w #2,d3
        for (Deformed d : deformed.get(index)) {
            for (LevelData.Wall w : d.walls()) {
                if (door) {
                    w.bottom = v;                      // move.l d3,24(a1) -> bottom@22
                } else {
                    w.top = v;                         // move.l d3,20(a1) -> top@18
                }
                // adda.w d0,a2 ; move.w a2,12(a1). NE PAS inverser ce signe : verifie EN JEU, la
                // texture monte bien avec le battant telle quelle. (Le calcul de d0 que j'avais
                // lu dans l'ASM est celui de la branche ASCENSEUR, pas celui des portes.)
                w.yOffset = (short) (w.yOffsetBase - v);
            }
            if (System.getProperty("rebirth.deformLog") != null) {
                LevelData.Wall w0 = d.walls().get(0);
                System.out.printf("[deform] %s %d pos=%5d -> top=%5d bottom=%5d yOffset=%d%n",
                        door ? "porte" : "lift", index, position, w0.top, w0.bottom, w0.yOffset);
            }
            MeshBuilder mb = wallMesh(d.walls(), level.points, d.key(), true);
            if (mb != null && !mb.isEmpty()) {
                d.geometry().setMesh(mb.build());
            }
        }
    }

    /**
     * Le plafond d'une zone-porte suit le battant : c'est son DESSOUS.
     *
     * <p>Le port ecrit la position courante de la porte dans le mot Y du flat de plafond de sa
     * zone ({@code move.w d3,2(a1)}, Newanims.java:1146) en meme temps qu'il deforme les murs.
     * Ici le maillage est fige : on deplace le noeud qui le porte, ce qui revient au meme.
     *
     * @param zone zone de la porte ({@code LevelData.Liftable.zone})
     * @param y    hauteur jME visee
     */
    public void setDoorCeilingHeight(int zone, float y) {
        Node n = doorCeilings.get(zone);
        Float base = doorCeilBase.get(zone);
        if (n != null && base != null) {
            n.setLocalTranslation(0f, y - base, 0f);
        }
    }

    private Geometry wallSurface(List<LevelData.Wall> walls, List<LevelData.Point> points, String key) {
        return wallSurface(walls, points, key, false);
    }

    private Geometry wallSurface(List<LevelData.Wall> walls, List<LevelData.Point> points,
                                 String key, boolean keepDegenerate) {
        MeshBuilder mb = wallMesh(walls, points, key, keepDegenerate);
        if (mb == null || mb.isEmpty()) {
            return null;
        }
        String[] kp = key.split("_");
        Geometry g = new Geometry("wall_" + key, mb.build());
        g.setMaterial(wallMaterial(Integer.parseInt(kp[0]), Integer.parseInt(kp[1]),
                Math.max(1, Integer.parseInt(kp[2])), key));
        if (mb.litSources() != null) {
            litMeshes.add(new LitMesh(g, mb.litSources(), true));
        }
        return g;
    }

    /**
     * Le maillage d'un groupe de murs (meme sous-tuile) — a part pour pouvoir le REFAIRE.
     *
     * <p>{@code keepDegenerate} sert aux murs DEFORMES : une porte grande ouverte ou un ascenseur
     * au repos ont un mur de hauteur NULLE, et si on le sautait le nombre de sommets changerait
     * d'une frame a l'autre — or l'eclairage indexe ces sommets. On emet donc un quad plat, qui
     * ne dessine rien.
     */
    private MeshBuilder wallMesh(List<LevelData.Wall> walls, List<LevelData.Point> points,
                                 String key, boolean keepDegenerate) {
        String[] parts = key.split("_");
        int texIndex = Integer.parseInt(parts[0]);
        int fromTile = Integer.parseInt(parts[1]);
        int texW = Math.max(1, Integer.parseInt(parts[2]));

        MeshBuilder mb = new MeshBuilder();
        for (LevelData.Wall w : walls) {
            if (w.leftPt < 0 || w.leftPt >= points.size() || w.rightPt < 0 || w.rightPt >= points.size()) {
                continue;
            }
            if (!keepDegenerate && (w.top == w.bottom || Math.abs(w.top - w.bottom) > 8192)) {
                continue;                              // mur degenere / valeur sentinelle
            }
            int texH = w.heightMask + 1;
            int wallLen = w.wallLen == 0 ? texW : w.wallLen;

            LevelData.Point p0 = points.get(w.leftPt);
            LevelData.Point p1 = points.get(w.rightPt);
            float x0 = p0.x / XZ_SCALE, z0 = -p0.z / XZ_SCALE;
            float x1 = p1.x / XZ_SCALE, z1 = -p1.z / XZ_SCALE;
            float yTop = -w.top / Y_SCALE;
            float yBot = -w.bottom / Y_SCALE;

            float u1 = (float) wallLen / texW;
            float vTop = vSign * (w.top + w.yOffset) / texH;
            float vBot = vSign * (w.bottom + w.yOffset) / texH;

            // Luminosite des 4 coins (cf. cant_tell) : le bas et le haut ont chacun leur slot PBR.
            int[] srcBotL = cornerSource(w, w.whichLeft, false);
            int[] srcBotR = cornerSource(w, w.whichRight, false);
            int[] srcTopL = cornerSource(w, w.whichLeft, true);
            int[] srcTopR = cornerSource(w, w.whichRight, true);
            float bBotL = wallBright(srcBotL);
            float bBotR = wallBright(srcBotR);
            float bTopL = wallBright(srcTopL);
            float bTopR = wallBright(srcTopR);

            // Ordre des sommets identique a quad() : TL, TR, BR puis TL, BR, BL.
            mb.vertexLit(x0, yTop, z0, 0f, vTop, bTopL, srcTopL[0], srcTopL[1], srcTopL[2]);
            mb.vertexLit(x1, yTop, z1, u1, vTop, bTopR, srcTopR[0], srcTopR[1], srcTopR[2]);
            mb.vertexLit(x1, yBot, z1, u1, vBot, bBotR, srcBotR[0], srcBotR[1], srcBotR[2]);
            mb.vertexLit(x0, yTop, z0, 0f, vTop, bTopL, srcTopL[0], srcTopL[1], srcTopL[2]);
            mb.vertexLit(x1, yBot, z1, u1, vBot, bBotR, srcBotR[0], srcBotR[1], srcBotR[2]);
            mb.vertexLit(x0, yBot, z0, 0f, vBot, bBotL, srcBotL[0], srcBotL[1], srcBotL[2]);
        }
        return mb;
    }

    private static void quad(MeshBuilder mb, float[] tl, float[] tr, float[] br, float[] bl) {
        mb.triangle(tl, tr, br);
        mb.triangle(tl, br, bl);
    }

    /**
     * Luminosite d'un coin de mur. Chaine d'origine, VERIFIEE en instrumentant le port
     * (zone 2, pbr=16 : coin 324 -> 24 -> accumulateur 48 -> niveau 50 -> bloc 25) :
     * <ol>
     *   <li>cant_tell (Hireswall.java:1112-1190) :
     *       {@code coin = |CurrentPointBrights[zone*40 + point*4 + slot]| + brightOffset} ;
     *       le slot vient du quartet FAIBLE de whichPBR pour le bas du mur, du quartet FORT pour
     *       le haut, et dans chaque quartet le bit 3 renvoie a la zone voisine.</li>
     *   <li>OTHERHALF (Hireswall.java:896) : {@code b = ext.w(coin - 300)} (octet bas signe).</li>
     *   <li>Doleftend (Hireswall.java:128-138) : l'accumulateur gouraud part de {@code 2*b}.</li>
     * </ol>
     * On renvoie donc {@code 2*b} ; le fragment reprend l'octet bas signe apres interpolation,
     * comme le fait le rasterizer a chaque bande.
     */
    private int[] cornerSource(LevelData.Wall w, int whichPoint, boolean top) {
        int nibble = top ? (w.whichPBR >> 4) : w.whichPBR;
        int slot = nibble & 7;
        int zoneId = (nibble & 8) != 0 ? w.otherZone : w.zone;
        LevelData.Zone z = zonesById.get(zoneId);
        int idx = (whichPoint << 2) + slot;
        if (z == null || idx < 0 || idx >= 40) {
            return new int[] { -1, -1, 0 };
        }
        return new int[] { zoneId, idx, w.brightOffset };
    }

    /** Formule des murs : {@code 2 * ext.w(|luminosite du point| + decalage - 300)}. */
    private float wallBright(int[] src) {
        if (src[0] < 0) {
            return 0f;
        }
        int corner = Math.abs(lights.pointBright(src[0], src[1])) + src[2];
        return 2 * (byte) (corner - 300);
    }

    /** Formule des sols/plafonds : {@code |luminosite du point| - 300}. */
    private float flatBright(int[] src) {
        if (src[0] < 0) {
            return 0f;
        }
        return Math.abs(lights.pointBright(src[0], src[1])) - 300;
    }

    /**
     * Le matériau écrit en `.j3m` par {@code extract.MaterialExport}, s'il existe.
     *
     * <p>C'est la voie normale : un fichier texte que l'on peut retoucher à la main ou dans le
     * SDK jME (texture, couleurs, mélange, face cullée) sans recompiler. La construction en code
     * qui suit chaque appel n'est plus qu'un REPLI — pour le rendu rétro, le mode fullbright, ou
     * tant que les `.j3m` n'ont pas été générés.
     *
     * @param key clé du matériau, qui est aussi le nom du fichier ({@code wall_3_0_64}, {@code flat_5}…)
     */
    /**
     * Repose le filtrage « plus proche voisin » sur les textures d'un matériau chargé.
     *
     * <p>Le format `.j3m` sait décrire l'enroulement ({@code Repeat}) et le retournement
     * ({@code Flip}), mais pas les filtres d'échantillonnage. Sans ce rappel, jME interpole et
     * les textures 1996 deviennent floues.
     */
    private static void pixelate(Material m) {
        for (com.jme3.material.MatParam p : m.getParams()) {
            if (p instanceof com.jme3.material.MatParamTexture t
                    && t.getTextureValue() != null) {
                t.getTextureValue().setMagFilter(com.jme3.texture.Texture.MagFilter.Nearest);
                t.getTextureValue().setMinFilter(
                        com.jme3.texture.Texture.MinFilter.NearestNoMipMaps);
            }
        }
    }

    private int j3mLoaded;
    private int j3mMissing;

    private Material j3m(String key) {
        if (!modern || fullbright) {
            return null;                               // ces rendus ont leurs propres materiaux
        }
        try {
            Material m = assetManager.loadMaterial("materials/" + key + ".j3m");
            pixelate(m);                               // le .j3m ne sait pas dire « plus proche voisin »
            j3mLoaded++;
            return m;
        } catch (RuntimeException absent) {
            if (j3mMissing == 0) {                     // une fois suffit a diagnostiquer
                System.err.println("[materiau] AVERTISSEMENT " + key + " : " + absent);
            }
            j3mMissing++;
            return null;                               // pas encore genere : on retombe sur le code
        }
    }

    private Material wallMaterial(int texIndex, int fromTile, int texW, String key) {
        Material cached = materials.get(key);
        if (cached != null) {
            return cached;
        }
        // La cle des murs n'inclut pas son type : le fichier, lui, s'appelle wall_<cle>.j3m.
        Material mat = j3m("wall_" + key);
        if (mat == null && modern && !fullbright) {
            BufferedImage full = wallImage(texIndex);
            if (full != null) {
                mat = litMaterial(Assets.toTexture(subTile(full, fromTile, texW)));
            }
        } else if (mat == null && !fullbright) {
            mat = shadedWallMaterial(texIndex, fromTile, texW);
        }
        if (mat == null) {
            BufferedImage full = wallImage(texIndex);
            mat = full == null
                    ? solidMaterial(new ColorRGBA(0.6f, 0.3f, 0.5f, 1f))   // texture absente
                    : texturedMaterial(Assets.toTexture(subTile(full, fromTile, texW)));
        }
        materials.put(key, mat);
        return mat;
    }

    /**
     * Materiau du rendu MOTEUR : texture couleur eclairee par les lumieres de la scene
     * ({@code Common/MatDefs/Light/Lighting.j3md}).
     *
     * <p>La COULEUR PAR SOMMET porte le light map d'origine (cf. MeshBuilder.bakedColours) : elle
     * module le terme diffus, si bien que l'ambiance de chaque piece reste celle voulue par les
     * auteurs, et les vraies lumieres de jME (lampes, tirs, explosions) s'ajoutent par-dessus.
     */
    private Material litMaterial(Texture2D tex) {
        return litMaterial(tex, false);
    }

    private Material litMaterial(Texture2D tex, boolean alphaCut) {
        if (tex == null) {
            return null;
        }
        Material mat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        if (alphaCut) {                                // sprites et atlas : le fond est transparent
            mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            mat.setFloat("AlphaDiscardThreshold", 0.5f);
        }
        mat.setTexture("DiffuseMap", tex);
        mat.setBoolean("UseMaterialColors", true);
        // Pas de UseVertexColor : en rendu MOTEUR ce sont les vraies lumieres qui eclairent, le
        // light map d'origine ne sert plus qu'a les POSER (cf. Lights3D.brightnessFactor).
        // L'y remettre double-assombrirait les murs et brulerait les zones deja claires.
        mat.setColor("Diffuse", ColorRGBA.White);
        mat.setColor("Ambient", ColorRGBA.White);
        mat.setColor("Specular", ColorRGBA.Black);
        mat.setFloat("Shininess", 1f);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return mat;
    }

    /**
     * Pose la texture de base d'un materiau : {@code DiffuseMap} pour le rendu MOTEUR,
     * {@code ColorMap} pour les materiaux non eclaires (Unshaded, glares).
     */
    private static void setAlbedo(Material mat, Texture2D tex) {
        if (mat == null || tex == null) {
            return;
        }
        if (mat.getMaterialDef().getMaterialParam("DiffuseMap") != null) {
            mat.setTexture("DiffuseMap", tex);
        } else if (mat.getMaterialDef().getMaterialParam("ColorMap") != null) {
            mat.setTexture("ColorMap", tex);
        }
    }

    /** Vrai si le rendu MOTEUR est actif (par opposition a l'emulation du rasterizer d'origine). */
    public boolean modern() {
        return modern;
    }

    /** Materiau eclaire : carte d'indices (sr) + LUT (32 sr x 32 blocs) de la texture. */
    private Material shadedWallMaterial(int texIndex, int fromTile, int texW) {
        String name = glf == null ? null : glf.wallTexture(texIndex);
        if (name == null) {
            return null;
        }
        BufferedImage idx = wallIndexImage(texIndex);
        BufferedImage lut = Assets.image("textures/walls/" + name + ".lut.png");
        if (idx == null || lut == null) {
            return null;                               // extraction ancienne : repli non eclaire
        }
        Material mat = new Material(assetManager, "Shaders/WallShade.j3md");
        mat.setTexture("IndexMap", Assets.toTexture(subTile(idx, fromTile, texW)));
        Texture2D lutTex = Assets.toTexture(lut);
        lutTex.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
        mat.setTexture("Lut", lutTex);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return mat;
    }

    private BufferedImage wallIndexImage(int texIndex) {
        if (wallIndexImages.containsKey(texIndex)) {
            return wallIndexImages.get(texIndex);
        }
        String name = glf == null ? null : glf.wallTexture(texIndex);
        BufferedImage img = name == null ? null : Assets.image("textures/walls/" + name + ".idx.png");
        wallIndexImages.put(texIndex, img);
        return img;
    }

    private BufferedImage wallImage(int texIndex) {
        if (wallImages.containsKey(texIndex)) {
            return wallImages.get(texIndex);
        }
        String name = glf == null ? null : glf.wallTexture(texIndex);
        BufferedImage img = name == null ? null : Assets.image("textures/walls/" + name + ".png");
        wallImages.put(texIndex, img);
        return img;
    }

    /**
     * Decoupe la sous-tuile [fromTile, fromTile+texW) sur toute la hauteur, avec repli (wrap)
     * horizontal quand elle deborde du PNG (raster : colonne = (perspU &amp; widthMask) + fromTile).
     */
    public static BufferedImage subTile(BufferedImage full, int fromTile, int texW) {
        int pw = full.getWidth();
        int ph = full.getHeight();
        if (fromTile == 0 && texW == pw) {
            return full;
        }
        BufferedImage sub = new BufferedImage(texW, ph, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < texW; x++) {
            int sx = Math.floorMod(fromTile + x, pw);
            for (int y = 0; y < ph; y++) {
                sub.setRGB(x, y, full.getRGB(sx, y));
            }
        }
        return sub;
    }

    // --------------------------------------------------------- sols/plafonds

    private Node buildFlats(LevelData lvl) {
        Node node = new Node("Flats");
        Map<Integer, LevelData.Flat> floorOf = new HashMap<>();
        Map<Integer, LevelData.Flat> ceilOf = new HashMap<>();
        Map<Integer, LevelData.Flat> waterOf = new HashMap<>();
        // ETAGE HAUT : une poignee de zones portent un SECOND sol et un second plafond,
        // au-dessus des premiers (les escaliers superposes du niveau C). Le jeu les tient dans
        // un flux zone-graph separe, rendu apres le bas avec Draw_DoUpper_b.
        Map<Integer, LevelData.Flat> upFloorOf = new HashMap<>();
        Map<Integer, LevelData.Flat> upCeilOf = new HashMap<>();
        for (LevelData.Flat f : safe(lvl.flats)) {
            if ("water".equals(f.kind)) {
                waterOf.put(f.zone, f);       // le flux graph peut la decouper : un seul suffit
            } else if ("floor".equals(f.kind)) {
                (f.upper ? upFloorOf : floorOf).put(f.zone, f);
            } else {
                (f.upper ? upCeilOf : ceilOf).put(f.zone, f);
            }
        }

        Map<Integer, MeshBuilder> byTile = new LinkedHashMap<>();
        int fanned = 0;
        for (LevelData.Zone z : safe(lvl.zones)) {
            List<LevelData.ZoneEdge> edges = safe(z.edges);
            if (edges.size() < 3) {
                continue;
            }
            // Contour = points de depart des aretes de la zone (anneau ferme fiable ; la liste
            // de points du flat sous-couvre parfois le secteur).
            float[] ring = new float[edges.size() * 2];
            for (int i = 0; i < edges.size(); i++) {
                ring[i * 2] = edges.get(i).x;
                ring[i * 2 + 1] = edges.get(i).z;
            }
            // Luminosite de chaque sommet du contour : le rang de l'arete EST l'index du point
            // dans la zone (verifie : le quartet fort des points de flat vaut ce rang), et le slot
            // vaut 0 pour un sol, 1 pour un plafond (FloorPtBrightsPtr = ... + zone*80 [+2]).
            int[][] srcFloor = ringSources(z, 0);
            int[][] srcCeil = ringSources(z, 1);

            // Les luminosites de l'etage haut se lisent 4 octets plus loin (= 2 mots, donc les
            // slots 2 et 3) : draw_RenderCurrentZone pose Draw_PointBrightsPtr_l a
            // CurrentPointBrights_vl+4 avant de rendre le flux du haut.
            int[][] srcUpFloor = ringSources(z, 2);
            int[][] srcUpCeil = ringSources(z, 3);

            int[] tri = Poly2.triangulate(ring);
            if (tri == null) {
                tri = Poly2.fan(edges.size());
                fanned++;
            }
            LevelData.Flat f = floorOf.get(z.id);
            if (f != null) {
                if (liftZones.contains(z.id)) {
                    // zone-ascenseur : son sol bouge -> geometrie a part, deplacee par LiftRoutine
                    MeshBuilder mb = new MeshBuilder();
                    emitFlatInto(mb, ring, tri, -f.y / 128f, srcFloor);
                    Geometry g = new Geometry("lift_floor_" + z.id, mb.build());
                    g.setMaterial(flatMaterial(f.tile));
                    if (mb.litSources() != null) {
                        litMeshes.add(new LitMesh(g, mb.litSources(), false));
                    }
                    Node holder = new Node("lift_zone_" + z.id);
                    holder.attachChild(g);
                    node.attachChild(holder);
                    liftFloors.put(z.id, holder);
                } else {
                    emitFlat(byTile, ring, tri, -f.y / 128f, f.tile, srcFloor);
                }
            }
            // Un plafond se dessine des qu'un flat existe dans le flux graph : les zones ouvertes
            // sur le ciel n'en ont tout simplement pas (ne PAS filtrer sur roofH, la plupart des
            // zones interieures ont roofH = -32768 tout en ayant un vrai plafond).
            LevelData.Flat c = ceilOf.get(z.id);
            if (c != null) {
                if (doorZones.contains(z.id)) {
                    // zone-porte : son PLAFOND est le dessous du battant, il monte avec lui
                    // (DoorRoutine ecrit la position de la porte dans ce flat). Mesh a part.
                    MeshBuilder mb = new MeshBuilder();
                    float base = -c.y / 128f;
                    emitFlatInto(mb, ring, tri, base, srcCeil);
                    Geometry g = new Geometry("door_ceil_" + z.id, mb.build());
                    g.setMaterial(flatMaterial(c.tile));
                    if (mb.litSources() != null) {
                        litMeshes.add(new LitMesh(g, mb.litSources(), false));
                    }
                    Node holder = new Node("door_zone_" + z.id);
                    holder.attachChild(g);
                    node.attachChild(holder);
                    doorCeilings.put(z.id, holder);
                    doorCeilBase.put(z.id, base);
                } else {
                    emitFlat(byTile, ring, tri, -c.y / 128f, c.tile, srcCeil);
                }
            }
            // Etage haut : geometrie statique ordinaire, une zone a etage n'etant jamais une
            // zone-porte ni une zone-ascenseur.
            LevelData.Flat uf = upFloorOf.get(z.id);
            if (uf != null) {
                emitFlat(byTile, ring, tri, -uf.y / 128f, uf.tile, srcUpFloor);
            }
            LevelData.Flat uc = upCeilOf.get(z.id);
            if (uc != null) {
                emitFlat(byTile, ring, tri, -uc.y / 128f, uc.tile, srcUpCeil);
            }
            // Surface d'eau : le jeu la dessine comme un flat de type 7, a SA hauteur propre,
            // au-dessus du sol de la zone. Une par zone inondee (le contour de la zone couvre
            // les morceaux que le flux graph decoupe en eventail).
            LevelData.Flat w = waterOf.get(z.id);
            if (w != null) {
                MeshBuilder mb = new MeshBuilder();
                emitFlatInto(mb, ring, tri, -w.y / 128f, null);
                Geometry g = new Geometry("water_" + z.id, mb.build());
                g.setMaterial(waterMaterial(w.tile));
                if (modern) {
                    // Surface transparente : elle doit passer APRES le decor qu'on voit au
                    // travers, d'ou le bucket transparent, et elle ne projette pas d'ombre.
                    g.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
                    g.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Receive);
                }
                waterGeoms.add(g);
                node.attachChild(g);
            }
        }

        for (Map.Entry<Integer, MeshBuilder> e : byTile.entrySet()) {
            Geometry g = new Geometry("flat_tile" + e.getKey(), e.getValue().build());
            g.setMaterial(flatMaterial(e.getKey()));
            if (e.getValue().litSources() != null) {
                litMeshes.add(new LitMesh(g, e.getValue().litSources(), false));
            }
            node.attachChild(g);
        }
        System.out.printf("[Level] materiaux : %d .j3m charges, %d bâtis en code%n",
                j3mLoaded, j3mMissing);
        System.out.printf("[Level] sols/plafonds : %d zones (%d a etage), %d tuiles (%d en eventail)%s%n",
                safe(lvl.zones).size(), upFloorOf.size(), byTile.size(), fanned,
                waterGeoms.isEmpty() ? "" : ", " + waterGeoms.size() + " surfaces d'eau");
        return node;
    }

    /**
     * Luminosite des sommets du contour d'une zone, portee de sideLoopGouraud
     * (Hires.java:1101-1109) : {@code |CurrentPointBrights[zone*40 + point*4 + slot]| - 300},
     * slot = 0 pour un sol, 1 pour un plafond. Pas de doublement ni de troncature ici,
     * contrairement aux murs : le rasterizer des sols borne directement a [0,30].
     */
    private int[][] ringSources(LevelData.Zone z, int slot) {
        int n = z.edges == null ? 0 : z.edges.size();
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            int idx = i * 4 + slot;
            out[i] = idx < 40 ? new int[] { z.id, idx, 0 } : new int[] { -1, -1, 0 };
        }
        return out;
    }

    private void emitFlat(Map<Integer, MeshBuilder> byTile, float[] ring, int[] tri, float y, int tile,
                          int[][] sources) {
        emitFlatInto(byTile.computeIfAbsent(tile, k -> new MeshBuilder()), ring, tri, y, sources);
    }

    private void emitFlatInto(MeshBuilder mb, float[] ring, int[] tri, float y, int[][] sources) {
        for (int i = 0; i + 2 < tri.length; i += 3) {
            for (int j = 0; j < 3; j++) {
                int v = tri[i + j];
                float rx = ring[v * 2];
                float rz = ring[v * 2 + 1];
                int[] src = sources != null && v < sources.length ? sources[v] : new int[] { -1, -1, 0 };
                // UV planaire : 64 unites brutes = 1 tuile.
                mb.vertexLit(rx / XZ_SCALE, y, -rz / XZ_SCALE, rx / 64f, rz / 64f,
                        flatBright(src), src[0], src[1], src[2]);
            }
        }
    }

    private Material flatMaterial(int tile) {
        String key = "flat_" + tile;
        Material cached = materials.get(key);
        if (cached != null) {
            return cached;
        }
        Material mat = j3m(key);                       // le .j3m d'abord
        if (mat == null && modern && !fullbright) {
            mat = litMaterial(Assets.texture(String.format("textures/floors/floor_%02d.png", tile)));
        } else if (mat == null && !fullbright) {
            mat = shadedFlatMaterial(tile);
        }
        if (mat == null) {
            Texture2D tex = Assets.texture(String.format("textures/floors/floor_%02d.png", tile));
            mat = tex != null ? texturedMaterial(tex)
                    : solidMaterial(new ColorRGBA(0.3f, 0.4f, 0.5f, 1f));
        }
        materials.put(key, mat);
        return mat;
    }

    /** Materiau eclaire des sols/plafonds : index de palette du texel + LUT 256 x 31. */
    private Material shadedFlatMaterial(int tile) {
        Texture2D idx = Assets.texture(String.format("textures/floors/floor_%02d.idx.png", tile));
        Texture2D lut = Assets.texture("textures/floors/floor_shade.lut.png");
        if (idx == null || lut == null) {
            return null;                               // extraction ancienne : repli non eclaire
        }
        Material mat = new Material(assetManager, "Shaders/FloorShade.j3md");
        mat.setTexture("IndexMap", idx);
        lut.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
        mat.setTexture("Lut", lut);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return mat;
    }

    // ---------------------------------------------------------------- objets

    private Node buildObjects(LevelData lvl) {
        Node node = new Node("Objects");
        Map<Integer, Float> floorY = new HashMap<>();
        Map<Integer, Float> roofY = new HashMap<>();
        for (LevelData.Zone z : safe(lvl.zones)) {
            floorY.put(z.id, -z.floorH / 8192f);
            roofY.put(z.id, -z.roofH / 8192f);
        }

        int nSprites = 0;
        int nModels = 0;
        for (LevelData.Obj o : safe(lvl.objects)) {
            if (o.typeId == 0) {
                continue;                              // les ALIENS sont dynamiques (cf. buildAliens)
            }
            // Hauteur de dessin : le pas 0 du script pose deja son decalage (animObj).
            GlfData.ObjDef odef = glf == null || o.typeId != 1 ? null : glf.object(o.def);
            if (odef != null && odef.defAnim != null && !odef.defAnim.isEmpty()) {
                o.animDelta = odef.defAnim.get(0).delta;
            }
            float fy = floorY.getOrDefault(o.zone, 0f);
            float wx = o.x / XZ_SCALE;
            float wz = -o.z / XZ_SCALE;
            float oy = objectY(o, odef, floorY, roofY);
            if ("sprite".equals(o.gclass)) {
                Geometry spr = sprite(o, wx, oy, wz);
                if (spr != null) {
                    node.attachChild(spr);
                    objectGeometries.put(o, spr);
                    nSprites++;
                }
            } else if ("vector".equals(o.gclass)) {
                Geometry geo = model(o, wx, wz, fy, roofY.getOrDefault(o.zone, 0f));
                if (geo != null) {
                    node.attachChild(geo);
                    objectGeometries.put(o, geo);
                    nModels++;
                }
            }
        }
        System.out.printf("[Level] objets : %d sprites (dont %d eclaires) + %d modeles"
                + " (%d animes)%s%n",
                nSprites, litSprites.size(), nModels, animObjects.size(),
                modelLevelMax >= 0
                        ? String.format(" (niveaux de face eclaires %d..%d)", modelLevelMin, modelLevelMax)
                        : "");
        return node;
    }

    /** Panneau centre de w x h unites (l'origine du Quad jME est en bas a gauche). */
    /**
     * Orientation d'un modele vectoriel, DEDUITE de {@code draw_PolygonModel}
     * (Objdrawhires.java:1469-1500) — plus aucune constante calibree.
     *
     * <p>Le jeu tourne le modele de {@code theta = angle - 2048 - angle de vue} et projette
     * {@code x' ∝ x·sin(theta) - z·cos(theta)} (droite ecran),
     * {@code z' ∝ x·cos(theta) + z·sin(theta)} (profondeur).
     *
     * <p>Ici l'OBJ a son Z INVERSE a l'export, la camera d'angle V regarde
     * {@code F = (sin V, 0, -cos V)} et a pour droite {@code R = (cos V, 0, sin V)}. Une rotation
     * de {@code psi} autour de +Y donne une profondeur {@code x·sin(psi+V) + z·cos(psi+V)} et une
     * composante laterale {@code x·cos(psi+V) - z·sin(psi+V)}. L'identification avec le port
     * impose {@code psi + V = 2048 - theta}, soit
     * <pre>psi = 2048 - (angle - 2048 - V) - V = 4096 - angle</pre>
     * — les deux composantes concordent, donc pas de miroir.
     */
    private static float modelYaw(int angle) {
        return (4096f - angle) / 8192f * FastMath.TWO_PI;
    }

    private static Mesh spriteQuad(float w, float h) {
        MeshBuilder mb = new MeshBuilder();
        quad(mb,
                new float[] { -w / 2f, h / 2f, 0f, 0f, 0f },
                new float[] { w / 2f, h / 2f, 0f, 1f, 0f },
                new float[] { w / 2f, -h / 2f, 0f, 1f, 1f },
                new float[] { -w / 2f, -h / 2f, 0f, 0f, 1f });
        return mb.build();
    }

    private Geometry sprite(LevelData.Obj o, float wx, float fy, float wz) {
        String sheet = glf == null ? null : glf.spriteSheet(o.sheet);
        if (sheet == null) {
            return null;
        }
        BufferedImage img = spriteImage(o, sheet);
        if (img == null) {
            return null;
        }
        float w = img.getWidth() / SPRITE_PPU;
        float h = img.getHeight() / SPRITE_PPU;
        Geometry g = new Geometry("spr_" + o.name, spriteQuad(w, h));
        Material lit = null;
        if (modern && !fullbright && !o.additive) {
            // Rendu MOTEUR : la texture couleur de la frame, eclairee par les lumieres de la
            // scene. Le panneau etant billboarde, sa normale regarde toujours la camera.
            lit = litMaterial(Assets.texture(spritePath(o, sheet)), true);
        } else if (!fullbright) {
            lit = litSpriteMaterial(o, sheet);
        }
        g.setMaterial(lit != null ? lit : spriteMaterial(spritePath(o, sheet), o.additive));
        if (lit != null && !modern) {                  // l'anneau LUT n'existe qu'en rendu d'origine
            litSprites.add(new LitSprite(g, lit, o, ringsFor(o)));
        }
        ObjectAnim anim = objectAnim(o);
        if (anim != null) {
            animObjects.add(new AnimObject(g, o, anim, false));
        }
        g.setLocalTranslation(wx, fy, wz);             // fy = hauteur de dessin du jeu (centre)
        // Un panneau billboarde est un QUAD a fond transparent : s'il projette, c'est le quad
        // entier qui fait de l'ombre, en grands rectangles noirs. Il recoit, il ne projette pas.
        g.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Receive);
        BillboardControl bb = new BillboardControl();
        bb.setAlignment(BillboardControl.Alignment.AxialY);   // sprites d'origine : rotation Y seule
        g.addControl(bb);
        return g;
    }

    /**
     * Hauteur de dessin d'un objet, en unites jME : le jeu pose
     * {@code (sol ou plafond de la zone) >> 7 + 2*delta du pas d'animation} et projette ce mot
     * multiplie par 128 — soit, a notre echelle, {@code -mot/64}.
     */
    private float objectY(LevelData.Obj o, GlfData.ObjDef def,
                          Map<Integer, Float> floorY, Map<Integer, Float> roofY) {
        LevelData.Zone z = zonesById.get(o.zone);
        if (z == null) {
            return floorY.getOrDefault(o.zone, 0f);
        }
        int h;
        if (def != null && def.floorCeiling != 0) {
            h = o.upperZone ? z.upperRoofH : z.roofH;
        } else {
            h = o.upperZone ? z.upperFloorH : z.floorH;
        }
        int word = (short) ((h >> 7) + 2 * o.animDelta);
        return -word / 64f;
    }

    /** Chemin de la frame de sprite a afficher (variante de palette si l'objet en a une). */
    private String spritePath(LevelData.Obj o, String sheet) {
        if (o.pal > 0) {
            String p = String.format("textures/objects/%s/frame_%02d_p%d.png", sheet, o.frame, o.pal);
            if (Assets.exists(p)) {
                return p;
            }
        }
        return String.format("textures/objects/%s/frame_%02d.png", sheet, o.frame);
    }

    /**
     * Materiau d'un sprite non eclaire. Les « glares » (ODefT_GFXType == 2) sont additifs, comme
     * dans le jeu (drawBitmapAdditive) ; les autres utilisent une simple decoupe alpha.
     */
    private Material spriteMaterial(String path, boolean additive) {
        Texture2D tex = Assets.texture(path);
        if (tex == null) {
            return solidMaterial(new ColorRGBA(1f, 0f, 1f, 1f));
        }
        Material mat = texturedMaterial(tex);
        if (additive) {
            mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.AlphaAdditive);
            mat.getAdditionalRenderState().setDepthWrite(false);
            glow(mat, tex);                            // halo : ces graphismes SONT des sources
        } else {
            mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            mat.setFloat("AlphaDiscardThreshold", 0.5f);
        }
        return mat;
    }

    /** Image d'une frame de sprite : variante de palette si l'objet en a une, sinon la base. */
    private BufferedImage spriteImage(LevelData.Obj o, String sheet) {
        BufferedImage img = null;
        if (o.pal > 0) {
            img = Assets.image(String.format("textures/objects/%s/frame_%02d_p%d.png",
                    sheet, o.frame, o.pal));
        }
        if (img == null) {
            img = Assets.image(String.format("textures/objects/%s/frame_%02d.png", sheet, o.frame));
        }
        return img;
    }

    /**
     * Materiau d'un sprite « lightsource » (monstre) : carte d'indices de la frame + palette de
     * la feuille (4 variantes x 256). Les niveaux des 29 groupes sont poses a chaque frame par
     * {@link #updateSpriteLights}. Renvoie null si l'objet n'est pas eclaire ou si les assets
     * « eclairables » manquent (extraction ancienne).
     */
    private Material litSpriteMaterial(LevelData.Obj o, String sheet) {
        if (!o.lit || !SpriteLight.available()) {
            return null;
        }
        Texture2D idx = Assets.texture(String.format("textures/objects/%s/frame_%02d.idx.png", sheet, o.frame));
        Texture2D lut = Assets.texture("textures/objects/" + sheet + "/pal.png");
        if (idx == null || lut == null) {
            return null;
        }
        Material mat = new Material(assetManager, "Shaders/SpriteShade.j3md");
        mat.setTexture("IndexMap", idx);
        lut.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
        mat.setTexture("Lut", lut);
        mat.setFloat("PalRow", o.pal);
        mat.setParam("Levels", com.jme3.shader.VarType.FloatArray, new float[29]);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return mat;
    }

    /**
     * Anneau utilisé par les SPRITES éclairés : variante « zone courante seulement », car
     * drawBitmapLighted n'appelle que draw_ResetAngleBrights (pas de murs, pas de voisins,
     * pas d'interpolation) — contrairement aux modèles vectoriels.
     */
    private LightRings ringsFor(LevelData.Obj o) {
        if (level == null || level.points == null || level.edges == null) {
            return null;
        }
        if (sinCos == null) {
            sinCos = ab3d2.rebirth.sim.SinCos.load();
        }
        return LightRings.zoneOnly(zonesById, level.points, level.edges, sinCos, lights,
                o.x, o.z, o.zone);
    }

    /**
     * Met a jour l'eclairage des sprites de monstres : il depend de l'angle de VUE (la grille 7x7
     * tourne avec le joueur) et de la distance, donc se recalcule a chaque frame.
     *
     * @param viewAngle angle du joueur en unites de la table sinus
     * @param cam       camera, pour la profondeur de vue de chaque sprite
     */
    public void updateSpriteLights(int viewAngle, com.jme3.renderer.Camera cam) {
        for (LitSprite ls : litSprites) {
            if (ls.rings == null) {
                continue;
            }
            com.jme3.math.Vector3f p = ls.geometry.getWorldTranslation();
            float depthJme = cam.getDirection().dot(p.subtract(cam.getLocation()));
            int depthWorld = (int) (depthJme * XZ_SCALE);
            int brightToAdd = (short) (ls.obj.bright + (depthWorld >> 6));
            float[] levels = SpriteLight.levels(ls.rings, viewAngle, brightToAdd, false);
            ls.material.setParam("Levels", com.jme3.shader.VarType.FloatArray, levels);
        }
    }

    private Geometry model(LevelData.Obj o, float wx, float wz, float fy, float ry) {
        String name = glf == null ? null : glf.vectorModel(o.model);
        if (name == null) {
            return null;
        }
        // Frame du modele = variante/couleur propre a l'instance (cles, indicateurs).
        ObjModels.Model m = ObjModels.load(String.format("models/%s/frame_%03d.obj", name, o.frame));
        if (m == null) {
            m = ObjModels.load(String.format("models/%s/frame_000.obj", name));
        }
        if (m == null) {
            return null;
        }
        Mesh mesh = buildModelMesh(m, o);
        Geometry g = new Geometry("obj_" + o.name, mesh);
        Material mat = null;
        if (modern && !fullbright && !o.additive) {
            mat = litMaterial(Assets.texture("textures/texturemaps_atlas.png"), true);
        } else if (!fullbright && !o.additive) {
            mat = shadedModelMaterial();
        }
        g.setMaterial(mat != null ? mat : atlasMaterial(o.additive));
        if (mat != null && !modern) {                  // l'anneau LUT n'existe qu'en rendu d'origine
            litModels.add(new LitModel(g, o, m));
        }
        ObjectAnim anim = objectAnim(o);
        if (anim != null) {
            animObjects.add(new AnimObject(g, o, anim, true));
        }
        g.setLocalScale(MODEL_SCALE);
        float yaw = modelYaw(o.angle);
        g.setLocalRotation(new Quaternion().fromAngles(0f, yaw, 0f));
        float y = fy;
        if (o.ceiling) {
            y = ry;
        } else if (o.wall) {
            y = (fy + ry) * 0.5f;
        }
        g.setLocalTranslation(new Vector3f(wx, y, wz));
        return g;
    }

    /**
     * Maillage d'une INSTANCE de modele : chaque face porte son niveau de lumiere, somme de sa
     * luminosite de base et de l'anneau directionnel calcule a la position de l'objet
     * (Objdrawhires.java:1866-1888) :
     * <pre>
     *   secteur = (octet d'angle de la face + angle objet &gt;&gt; 9) &amp; 15
     *   vpos    = octet d'angle &gt;&gt; 4
     *   d5      = bas[secteur] + ((haut[secteur] - bas[secteur]) * (vpos + 73) &gt;&gt; 10)
     *   niveau  = clamp(base de la face + d5, 0, 31)
     * </pre>
     * L'anneau est calcule une fois (objets statiques) ; le jeu le refait a chaque frame.
     */
    private Mesh buildModelMesh(ObjModels.Model m, LevelData.Obj o) {
        LightRings rings = null;
        if (!fullbright && !o.additive && level != null && level.points != null && level.edges != null) {
            if (sinCos == null) {
                sinCos = ab3d2.rebirth.sim.SinCos.load();
            }
            rings = LightRings.compute(zonesById, level.points, level.edges, sinCos, lights,
                    o.x, o.z, o.zone);
        }
        int objSector = (o.angle >> 9) & 15;           // asr.w #8 puis #1

        MeshBuilder mb = new MeshBuilder();
        for (int i = 0; i < m.tris.length; i++) {
            int f = m.triFace[i];
            float bright = 0f;
            if (rings != null && f < m.faceAng.length) {
                int a = m.faceAng[f];
                int vpos = (a >> 4) & 15;
                int sector = (a + objSector) & 15;
                int bot = rings.bottom(sector);
                int top = rings.top(sector);
                int d5 = M68kShort(top - bot);
                d5 = (d5 * (vpos + 73)) >> 8 >> 2;
                d5 = d5 + bot;
                bright = Math.max(0, Math.min(31, m.faceLevel[f] + d5));
                modelLevelMin = Math.min(modelLevelMin, (int) bright);
                modelLevelMax = Math.max(modelLevelMax, (int) bright);
            } else if (f < m.faceLevel.length) {
                bright = m.faceLevel[f];
            }
            float[] t = m.tris[i];
            for (int k = 0; k < 3; k++) {
                mb.vertex(t[k * 5], t[k * 5 + 1], t[k * 5 + 2], t[k * 5 + 3], t[k * 5 + 4], bright);
            }
        }
        return mb.build();
    }

    private static int M68kShort(int v) {
        return (short) v;
    }

    /** Materiau eclaire des modeles : carte d'indices de l'atlas + LUT (la meme que les sols). */
    private Material shadedModelMaterial() {
        Material cached = materials.get("model_shaded");
        if (cached != null) {
            return cached;
        }
        Texture2D idx = Assets.texture("textures/texturemaps_atlas.idx.png");
        Texture2D lut = Assets.texture("textures/floors/floor_shade.lut.png");
        if (idx == null || lut == null) {
            return null;
        }
        Material mat = new Material(assetManager, "Shaders/ModelShade.j3md");
        mat.setTexture("IndexMap", idx);
        lut.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
        mat.setTexture("Lut", lut);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        materials.put("model_shaded", mat);
        return mat;
    }

    /**
     * Script d'animation par defaut d'un objet place, ou null s'il est fige.
     * Seuls les objets (typeId 1) ont une definition ODefT ; les aliens ont leurs propres tables.
     */
    private ObjectAnim objectAnim(LevelData.Obj o) {
        if (glf == null || o.typeId != 1) {
            return null;
        }
        GlfData.ObjDef def = glf.object(o.def);
        if (def == null) {
            return null;
        }
        // Les ACTIVABLES gardent une animation même figée au repos : leur script actif s'anime.
        return def.type == 1 ? ObjectAnim.always(def.defAnim) : ObjectAnim.of(def.defAnim);
    }

    /**
     * Bascule l'animation d'un objet entre son script par DEFAUT et son script ACTIF
     * (DEFANIMOBJ / ACTANIMOBJ) : c'est ce que fait le jeu quand un interrupteur est enclenche.
     */
    private void syncObjectAnimScript(AnimObject ao) {
        GlfData.ObjDef def = glf == null ? null : glf.object(ao.obj.def);
        if (def == null) {
            return;
        }
        List<ObjectAnim.Step> wanted = ao.obj.activated ? def.actAnim : def.defAnim;
        ao.anim.retarget(wanted);
    }

    /**
     * Avance d'une frame les animations d'objets (DEFANIMOBJ) et applique le nouveau graphique :
     * nouvelle frame de modele vectoriel, ou nouvelle texture de sprite.
     */
    public void stepObjectAnims() {
        for (AnimObject ao : animObjects) {
            syncObjectAnimScript(ao);
            if (!ao.anim.advance()) {
                continue;                              // meme graphique : rien a refaire
            }
            ObjectAnim.Step st = ao.anim.step();
            ao.obj.animDelta = st.delta;
            if (ao.vector) {
                ao.obj.model = st.gfx;
                ao.obj.frame = st.frame;
                if (st.word2 != 0) {                   // add.w word2,EntT_CurrentAngle_w : ca TOURNE
                    ao.obj.angle = (short) (ao.obj.angle + st.word2);
                    ao.geometry.setLocalRotation(
                            new Quaternion().fromAngles(0f, modelYaw(ao.obj.angle), 0f));
                }
                String name = glf.vectorModel(st.gfx);
                ObjModels.Model m = name == null ? null
                        : ObjModels.load(String.format("models/%s/frame_%03d.obj", name, st.frame));
                if (m != null) {
                    ao.geometry.setMesh(buildModelMesh(m, ao.obj));
                }
            } else {
                ao.obj.sheet = st.gfx;
                ao.obj.frame = st.frame;
                String sheet = glf.spriteSheet(st.gfx);
                if (sheet == null) {
                    continue;
                }
                // Materiau eclaire : c'est la carte d'indices qui porte la frame ; sinon la couleur.
                // Textures prises dans le cache par chemin : sinon on en recreerait une par frame.
                Material mat = ao.geometry.getMaterial();
                boolean shaded = mat.getMaterialDef().getAssetName().contains("SpriteShade");
                if (shaded) {
                    Texture2D idx = Assets.texture(String.format(
                            "textures/objects/%s/frame_%02d.idx.png", sheet, st.frame));
                    if (idx != null) {
                        mat.setTexture("IndexMap", idx);
                    }
                } else {
                    Texture2D tex = Assets.texture(spritePath(ao.obj, sheet));
                    if (tex != null) {
                        setAlbedo(mat, tex);
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- eau

    private final List<Geometry> waterGeoms = new ArrayList<>();

    /**
     * Materiau d'une surface d'eau : la tuile du SOL sous l'eau (c'est le pixel que le jeu
     * reprend a l'ecran), la palette de teinte de l'eau et la table de vagues d'origine.
     */
    private Material waterMaterial(int tile) {
        String key = "water_" + tile + (modern ? "_3d" : "");
        Material cached = materials.get(key);
        if (cached != null) {
            return cached;
        }
        if (modern) {
            Material m = modernWaterMaterial();
            if (m != null) {
                materials.put(key, m);
                return m;
            }
        }
        Texture2D idx = Assets.texture(String.format("textures/floors/floor_%02d.idx.png", tile));
        Texture2D lut = Assets.texture("textures/water_shade.lut.png");
        Texture2D waves = Assets.texture("textures/water_waves.png");
        if (idx == null || lut == null || waves == null) {
            return solidMaterial(new ColorRGBA(0.1f, 0.3f, 0.45f, 1f));   // extraction ancienne
        }
        Material mat = new Material(assetManager, "Shaders/WaterShade.j3md");
        mat.setTexture("IndexMap", idx);
        lut.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
        mat.setTexture("Lut", lut);
        waves.setWrap(com.jme3.texture.Texture.WrapMode.Repeat);
        mat.setTexture("Waves", waves);
        mat.setFloat("Phase", 0f);
        mat.setFloat("Scroll", 0f);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        materials.put(key, mat);
        return mat;
    }

    /**
     * Materiau d'eau du rendu MOTEUR : une surface transparente, eclairee par les lumieres de la
     * scene, dont le relief vient d'une NORMAL MAP derivee de la table de vagues du jeu
     * (cf. rebirth.Water). Le speculaire est ce qui la fait lire comme de l'eau : les lampes de
     * zone y accrochent des reflets, et ils se deplacent avec le joueur.
     */
    private Material modernWaterMaterial() {
        Texture2D nrm = Assets.texture("textures/water_normal.png");
        if (nrm == null) {
            return null;                               // extraction ancienne : repli sur le shader
        }
        nrm.setWrap(com.jme3.texture.Texture.WrapMode.Repeat);
        // Une NORMAL MAP s'interpole : le filtre « au plus proche » du reste du jeu (look retro,
        // Assets.toTexture) donnerait des facettes de normales et une eau en damier.
        nrm.setMagFilter(com.jme3.texture.Texture.MagFilter.Bilinear);
        nrm.setMinFilter(com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps);
        Material mat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
        mat.setTexture("NormalMap", nrm);
        mat.setBoolean("UseMaterialColors", true);
        // TEINTE : celle du JEU, pas une couleur inventee. Le rendu d'origine ne peint pas
        // l'eau en bleu — il repasse le sol qu'on voit au travers par un bloc de palette
        // (water_shade.lut.png), ce qui donne un gris chaud qui s'assombrit avec la distance.
        // On prend la rangee mediane de cette LUT comme couleur de la surface.
        ColorRGBA tint = lutTint("textures/water_shade.lut.png", 10,
                new ColorRGBA(0.49f, 0.45f, 0.39f, 1f));
        mat.setColor("Diffuse", new ColorRGBA(tint.r, tint.g, tint.b, 0.74f));
        mat.setColor("Ambient", tint.mult(0.55f));
        mat.setColor("Specular", new ColorRGBA(0.8f, 0.8f, 0.78f, 1f));
        mat.setFloat("Shininess", 20f);
        mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        mat.getAdditionalRenderState().setDepthWrite(false);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return mat;
    }

    /** Couleur moyenne d'une rangee d'une LUT extraite, ou {@code fallback} si elle manque. */
    private static ColorRGBA lutTint(String path, int row, ColorRGBA fallback) {
        BufferedImage img = Assets.image(path);
        if (img == null || row >= img.getHeight()) {
            return fallback;
        }
        long r = 0;
        long g = 0;
        long b = 0;
        int n = 0;
        for (int x = 0; x < img.getWidth(); x += 4) {
            int c = img.getRGB(x, row);
            r += (c >> 16) & 0xFF;
            g += (c >> 8) & 0xFF;
            b += c & 0xFF;
            n++;
        }
        return n == 0 ? fallback
                : new ColorRGBA(r / (n * 255f), g / (n * 255f), b / (n * 255f), 1f);
    }

    /**
     * Anime l'eau : le jeu passe a la phase de vagues suivante a chaque VBL (8 en boucle) et
     * fait defiler la texture d'un cran ({@code wateroff += 1}, soit un texel toutes les 256
     * frames).
     *
     * <p>En rendu MOTEUR il n'y a plus de phase ni de bloc de palette : on fait DEFILER les
     * coordonnees de texture du maillage, ce qui promene la normal map sur la surface. C'est
     * quelques dizaines de sommets par surface d'eau, une poignee par niveau.
     */
    public void updateWater(int frames) {
        if (waterGeoms.isEmpty()) {
            return;
        }
        if (modern) {
            scrollWater(frames);
            return;
        }
        float phase = frames % 8;
        float scroll = (frames % (256 * 64)) / (256f * 64f);
        for (Geometry g : waterGeoms) {
            Material m = g.getMaterial();
            if (m.getMaterialDef().getAssetName().contains("WaterShade")) {
                m.setFloat("Phase", phase);
                m.setFloat("Scroll", scroll);
            }
        }
    }

    /** Coordonnees de texture d'origine de chaque surface d'eau (avant defilement). */
    private final Map<Geometry, float[]> waterUv = new HashMap<>();

    /**
     * Echelle des UV de la normal map. Les coordonnees du maillage sont a l'echelle du MONDE :
     * telles quelles, les rides font un pixel et l'eau scintille. On les divise pour avoir une
     * houle a la taille d'un personnage.
     */
    private static final float WATER_UV_SCALE = 0.22f;

    private void scrollWater(int frames) {
        float du = (frames % 600) / 600f;              // deux vitesses : la houle ne boucle pas
        float dv = (frames % 431) / 431f * 0.6f;
        for (Geometry g : waterGeoms) {
            com.jme3.scene.VertexBuffer vb =
                    g.getMesh().getBuffer(com.jme3.scene.VertexBuffer.Type.TexCoord);
            if (vb == null) {
                continue;
            }
            java.nio.FloatBuffer buf = (java.nio.FloatBuffer) vb.getData();
            float[] base = waterUv.get(g);
            if (base == null) {                        // 1er passage : on garde les UV d'origine
                base = new float[buf.capacity()];
                for (int i = 0; i < base.length; i++) {
                    base[i] = buf.get(i);
                }
                waterUv.put(g, base);
            }
            for (int i = 0; i + 1 < base.length; i += 2) {
                buf.put(i, base[i] * WATER_UV_SCALE + du);
                buf.put(i + 1, base[i + 1] * WATER_UV_SCALE + dv);
            }
            vb.updateData(buf);
        }
    }

    /** Vrai si le niveau a au moins une surface d'eau. */
    public boolean hasWater() {
        return !waterGeoms.isEmpty();
    }

    // -------------------------------------------------------- arme en main

    private Geometry weaponGeom;
    private LevelData.Obj weaponProxy;
    private int weaponModel = -1, weaponFrame = -1, weaponZone = -1;

    /**
     * Arme tenue par le joueur. Le jeu n'a pas de « couche HUD » pour elle : c'est une ENTITE
     * ordinaire (celle qui suit l'objet joueur, {@code ENT_NEXT_2}), un modele vectoriel pose a la
     * position du joueur, tourne de 180 degres pour lui faire face, a hauteur d'yeux moins un
     * quart de sa taille, et qui suit le balancement de la marche (Hires.java:3921-3958).
     */
    public void updateWeapon(Node parent, int modelIdx, int frame, int x, int z, int heightWord,
                             int angle, int zone, com.jme3.renderer.Camera cam,
                             float viewYaw, float drop) {
        if (modelIdx < 0 || glf == null) {
            if (weaponGeom != null) {
                weaponGeom.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
            }
            return;
        }
        if (weaponProxy == null) {
            weaponProxy = new LevelData.Obj();
            weaponProxy.typeId = 1;
            weaponProxy.gclass = "vector";
            weaponProxy.name = "arme";
        }
        weaponProxy.x = x;
        weaponProxy.z = z;
        weaponProxy.zone = zone;
        weaponProxy.model = modelIdx;
        weaponProxy.frame = frame;
        weaponProxy.angle = angle;
        if (weaponGeom == null || modelIdx != weaponModel || frame != weaponFrame
                || zone != weaponZone) {
            String name = glf.vectorModel(modelIdx);
            ObjModels.Model m = name == null ? null
                    : ObjModels.load(String.format("models/%s/frame_%03d.obj", name, frame));
            if (m == null) {
                if (weaponGeom != null) {
                    weaponGeom.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
                }
                return;
            }
            Mesh mesh = buildModelMesh(m, weaponProxy);
            if (weaponGeom == null) {
                weaponGeom = new Geometry("arme", mesh);
                Material mat = null;
                if (modern && !fullbright) {
                    mat = litMaterial(Assets.texture("textures/texturemaps_atlas.png"), true);
                } else if (!fullbright) {
                    mat = shadedModelMaterial();
                }
                weaponGeom.setMaterial(mat != null ? mat : atlasMaterial(false));
                weaponGeom.setLocalScale(WEAPON_SCALE);
                // Collee a la camera : si elle projette, son ombre couvre la piece entiere.
                weaponGeom.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
                parent.attachChild(weaponGeom);
            } else {
                weaponGeom.setMesh(mesh);
            }
            weaponModel = modelIdx;
            weaponFrame = frame;
            weaponZone = zone;
        }
        weaponGeom.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
        // L'arme est tenue : elle se pose dans le repere de la CAMERA, pas dans le monde.
        //
        // Le jeu d'origine la dessinait comme un objet du monde, a l'aplomb du joueur et sous
        // son oeil : il n'avait pas de vraie visee verticale — regarder en haut ou en bas y
        // DECALE l'image, sans incliner la vue. Le remake, lui, fait tourner la camera ; une
        // arme laissee dans le monde sortait donc du cadre des qu'on levait les yeux.
        //
        // On garde les valeurs de la simulation (le lacet du modele, la hauteur sous l'oeil et
        // son balancement), mais on les exprime PAR RAPPORT a la vue : le lacet devient un
        // ecart au lacet de la camera, et la hauteur un decalage vers le bas de la camera.
        if (cam != null) {
            com.jme3.math.Quaternion camRot = cam.getRotation();
            com.jme3.math.Quaternion rel =
                    new Quaternion().fromAngles(0f, modelYaw(angle) - viewYaw, 0f);
            weaponGeom.setLocalRotation(camRot.mult(rel));
            weaponGeom.setLocalTranslation(cam.getLocation()
                    .add(camRot.mult(new com.jme3.math.Vector3f(0f, -drop, 0f))));
            return;
        }
        float yaw = modelYaw(angle);
        weaponGeom.setLocalRotation(new Quaternion().fromAngles(0f, yaw, 0f));
        weaponGeom.setLocalTranslation(x / XZ_SCALE, -heightWord / 64f, -z / XZ_SCALE);
    }

    /** DIAG (-PweaponLog) : ou se trouve l'arme en main par rapport a la camera. */
    public void logWeapon(com.jme3.renderer.Camera cam) {
        if (weaponGeom == null) {
            System.out.println("[arme] aucune geometrie");
            return;
        }
        com.jme3.bounding.BoundingVolume bv = weaponGeom.getWorldBound();
        com.jme3.math.Vector3f c = bv == null ? new com.jme3.math.Vector3f() : bv.getCenter();
        com.jme3.math.Vector3f d = cam.getDirection();
        com.jme3.math.Vector3f rel = c.subtract(cam.getLocation());
        System.out.printf("[arme] modele=%d frame=%d centre=(%.2f,%.2f,%.2f) cam=(%.2f,%.2f,%.2f)"
                + " dir=(%.2f,%.2f,%.2f) | devant=%.2f droite=%.2f haut=%.2f%n",
                weaponModel, weaponFrame, c.x, c.y, c.z,
                cam.getLocation().x, cam.getLocation().y, cam.getLocation().z, d.x, d.y, d.z,
                rel.dot(d), rel.dot(cam.getLeft().negate()), rel.dot(cam.getUp()));
    }

    // ------------------------------------------------------------------ aliens

    /** Lien entre une entite de la simulation et son panneau, avec son objet proxy eclaire. */
    private static final class AlienView {
        final ab3d2.rebirth.sim.Aliens.Alien alien;
        final LevelData.Obj proxy;
        final Geometry geometry;
        /** Vrai pour une entite dessinee en MODELE et non en panneau (cf. buildAliens). */
        final boolean vector;
        int lastZone = -1;
        /** Panneau du graphique AUXILIAIRE (eclat de tir), cree a la premiere frame qui en a un. */
        Geometry aux;
        int auxSheet = -1;
        int auxFrame = -1;
        float auxOx = Float.NaN;

        AlienView(ab3d2.rebirth.sim.Aliens.Alien a, LevelData.Obj o, Geometry g, boolean vector) {
            this.alien = a;
            this.proxy = o;
            this.geometry = g;
            this.vector = vector;
        }
    }

    private final List<AlienView> alienViews = new ArrayList<>();
    private Node aliensNode;

    /**
     * Cree les panneaux des monstres. Chaque alien recoit un objet PROXY ({@link LevelData.Obj})
     * qui porte sa feuille/frame courante et sa position : c'est lui qu'on donne a la machinerie
     * d'eclairage des sprites deja en place (carte d'indices + palette + anneau directionnel).
     */
    public Node buildAliens(List<ab3d2.rebirth.sim.Aliens.Alien> aliens, LevelData lvl) {
        Node node = new Node("Aliens");
        aliensNode = node;
        // Monstres : panneaux billboardes, ils recoivent l'ombre sans projeter leur quad.
        node.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Receive);
        alienViews.clear();
        for (ab3d2.rebirth.sim.Aliens.Alien a : aliens) {
            LevelData.Obj src = null;
            for (LevelData.Obj o : safe(lvl.objects)) {  // l'instance d'origine porte pal/lit/bright
                if (o.typeId == 0 && o.x == a.x && o.z == a.z && o.zone == a.zone
                        && o.def == a.type) {
                    src = o;
                    break;
                }
            }
            LevelData.Obj proxy = new LevelData.Obj();
            proxy.typeId = 0;
            proxy.def = a.type;
            proxy.gclass = "sprite";
            proxy.x = a.x;
            proxy.z = a.z;
            proxy.zone = a.zone;
            proxy.upperZone = a.upperZone;
            proxy.sheet = src != null ? src.sheet : 0;
            proxy.frame = 0;
            proxy.pal = src != null ? src.pal : 0;
            proxy.lit = src != null && src.lit;
            proxy.bright = src != null ? src.bright : 0;
            proxy.name = src != null ? src.name : "alien";
            if (src == null) {                         // emplacement d'alien ENGENDRE (vide au depart)
                proxy.sheet = 0;
                proxy.lit = true;
            }
            // Toutes les entites « alien » ne sont pas des monstres, et toutes ne sont pas des
            // panneaux : l'octet GFXType+1 de leur definition vaut 1 pour celles que le jeu
            // dessine en POLYGONES (draw_Object teste ce cas avant d'appeler draw_Bitmap). Ce
            // sont les interrupteurs muraux -- neuf en tout, dans H, L, O et P. Les traiter en
            // sprite leur donnait la feuille 0 faute de champ `sheet`, donc un graphisme faux.
            boolean vec = src != null && "vector".equals(src.gclass);
            Geometry g;
            if (vec) {
                proxy.gclass = "vector";
                proxy.model = src.model;
                proxy.frame = src.frame;
                proxy.angle = src.angle;
                proxy.ceiling = src.ceiling;
                proxy.wall = src.wall;
                g = model(proxy, a.x / XZ_SCALE, -a.z / XZ_SCALE, 0f, 0f);
            } else {
                g = sprite(proxy, a.x / XZ_SCALE, -a.height / 64f, -a.z / XZ_SCALE);
            }
            if (g == null) {
                continue;
            }
            g.setLocalTranslation(a.x / XZ_SCALE, -a.height / 64f, -a.z / XZ_SCALE);
            node.attachChild(g);
            alienViews.add(new AlienView(a, proxy, g, vec));
        }
        long nv = alienViews.stream().filter(v -> v.vector).count();
        System.out.printf("[Level] aliens : %d entites%s%n", alienViews.size(),
                nv == 0 ? "" : " (dont " + nv + " en modele)");
        return node;
    }

    /** Applique l'etat de la simulation aux panneaux des monstres (position, frame, miroir). */
    /** Zones potentiellement visibles depuis celle du joueur (le PVS du jeu d'origine). */
    private boolean[] visible;

    /**
     * Prépare le tri de visibilité de la frame.
     *
     * <p>Le moteur d'origine ne dessine que les zones du PVS ; ici c'est jME qui décide, par
     * tronc de vue. Or un monstre peut être dans le tronc ET derrière un mur : il est alors mis
     * à jour et soumis pour rien. Le PVS, lui, tient compte des murs. On s'en sert pour écarter
     * ces entités-là — ce qui économise à la fois le travail de mise à jour et l'appel de dessin.
     *
     * @param zone zone du joueur ; &lt; 0 ou inconnue = tout est considéré visible (repli sûr)
     */
    public void setVisibleFrom(int zone) {
        if (level == null || level.zones == null) {
            visible = null;
            return;
        }
        LevelData.Zone z = zonesById.get(zone);
        if (z == null || z.pvs == null) {
            visible = null;                            // on ne cache rien si on ne sait pas
            return;
        }
        int max = 0;
        for (LevelData.Zone zz : level.zones) {
            max = Math.max(max, zz.id);
        }
        if (visible == null || visible.length < max + 1) {
            visible = new boolean[max + 1];
        } else {
            java.util.Arrays.fill(visible, false);
        }
        visible[zone] = true;
        for (LevelData.Pvs v : z.pvs) {
            if (v.zone >= 0 && v.zone < visible.length) {
                visible[v.zone] = true;
            }
        }
    }

    /** Cette zone peut-elle se voir depuis celle du joueur ? */
    private boolean visible(int zone) {
        return visible == null || zone < 0 || zone >= visible.length || visible[zone];
    }

    public void updateAliens() {
        for (AlienView v : alienViews) {
            ab3d2.rebirth.sim.Aliens.Alien a = v.alien;
            if (!a.alive || a.zone < 0 || !visible(a.zone)) {
                v.geometry.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
                if (v.aux != null) {
                    v.aux.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
                }
                continue;
            }
            v.geometry.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
            v.proxy.x = a.x;
            v.proxy.z = a.z;
            v.proxy.zone = a.zone;
            v.proxy.upperZone = a.upperZone;
            if (v.proxy.def != a.type) {               // alien ENGENDRE : autre definition
                v.proxy.def = a.type;
                GlfData.Alien def = glf == null ? null : glf.alien(a.type);
                int vec = def == null ? 0 : (def.gfxType & 0xFF);
                v.proxy.pal = Math.max(0, Math.min(3, vec - 2));
                v.proxy.lit = vec >= 2 && vec < 6;
            }
            if (v.vector) {                            // un modele n'a ni feuille ni miroir
                v.geometry.setLocalTranslation(a.x / XZ_SCALE, -a.height / 64f, -a.z / XZ_SCALE);
                updateAux(v, a);
                continue;
            }
            if (v.proxy.sheet != a.sheet || v.proxy.frame != a.frame) {
                v.proxy.sheet = a.sheet;
                v.proxy.frame = a.frame;
                setSpriteFrame(v.geometry, v.proxy);
            }
            // Le sprite est dessine en miroir pour les vues de droite (le jeu n'a que la gauche).
            float sx = Math.abs(v.geometry.getLocalScale().x) * (a.flip ? -1f : 1f);
            v.geometry.setLocalScale(sx, v.geometry.getLocalScale().y, 1f);
            v.geometry.setLocalTranslation(a.x / XZ_SCALE, -a.height / 64f, -a.z / XZ_SCALE);
            updateAux(v, a);
            if (v.lastZone != a.zone) {                // l'anneau d'eclairage depend de la zone
                v.lastZone = a.zone;
                for (LitSprite ls : litSprites) {
                    if (ls.obj == v.proxy) {
                        ls.rings = ringsFor(v.proxy);
                    }
                }
            }
        }
    }

    /**
     * Graphique AUXILIAIRE d'un monstre (ai.s:1938) : un second panneau accroche a sa frame
     * courante — en pratique les eclats de tir des gardes armes (objet {@code GunGlares}).
     *
     * <p>Le jeu decale ce graphique de {@code draw_AuxX_w} sur le X DEJA TOURNE (donc a l'ECRAN,
     * vers la droite) et de {@code draw_AuxY_w} sur la hauteur. Ici le panneau est billboarde
     * comme celui du monstre : il suffit de decaler son quad de {@code auxX/64} en X LOCAL, ce
     * qui est exactement un decalage ecran, et de poser sa hauteur a {@code height + auxY}.
     * Comme dans le jeu, le decalage ne suit PAS le miroir du sprite du monstre.
     */
    private void updateAux(AlienView v, ab3d2.rebirth.sim.Aliens.Alien a) {
        if (a.auxFrame < 0) {
            if (v.aux != null) {
                v.aux.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
            }
            return;
        }
        GlfData.Alien ad = glf == null ? null : glf.alien(a.type);
        GlfData.ObjDef def = ad == null ? null : glf.object(ad.auxiliary);
        if (def == null || def.defAnim == null || a.auxFrame >= def.defAnim.size()) {
            return;
        }
        ObjectAnim.Step st = def.defAnim.get(a.auxFrame);
        String sheet = glf.spriteSheet(st.gfx);
        if (sheet == null) {
            return;
        }
        float ox = a.auxX / XZ_SCALE;
        if (v.aux == null || v.auxSheet != st.gfx || v.auxFrame != st.frame || v.auxOx != ox) {
            String path = String.format("textures/objects/%s/frame_%02d.png", sheet, st.frame);
            BufferedImage img = Assets.image(path);
            if (img == null) {
                return;
            }
            float w = img.getWidth() / SPRITE_PPU;
            float h = img.getHeight() / SPRITE_PPU;
            if (v.aux == null) {
                v.aux = new Geometry("aux_" + a.index, offsetSpriteQuad(w, h, ox));
                // GFXType 2 = glare : melange additif, comme les autres eclats du jeu.
                v.aux.setMaterial(spriteMaterial(path, def.gfxType >= 2));
                BillboardControl bb = new BillboardControl();
                bb.setAlignment(BillboardControl.Alignment.AxialY);
                v.aux.addControl(bb);
                if (aliensNode != null) {
                    aliensNode.attachChild(v.aux);
                }
            } else {
                v.aux.setMesh(offsetSpriteQuad(w, h, ox));
                Texture2D tex = Assets.texture(path);
                if (tex != null) {
                    setAlbedo(v.aux.getMaterial(), tex);
                }
            }
            v.auxSheet = st.gfx;
            v.auxFrame = st.frame;
            v.auxOx = ox;
        }
        v.aux.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
        v.aux.setLocalTranslation(a.x / XZ_SCALE, -(a.height + a.auxY) / 64f, -a.z / XZ_SCALE);
    }

    /** Quad de sprite decale de {@code ox} en X local (decalage ecran du graphique auxiliaire). */
    private static Mesh offsetSpriteQuad(float w, float h, float ox) {
        MeshBuilder mb = new MeshBuilder();
        quad(mb,
                new float[] { ox - w / 2f, h / 2f, 0f, 0f, 0f },
                new float[] { ox + w / 2f, h / 2f, 0f, 1f, 0f },
                new float[] { ox + w / 2f, -h / 2f, 0f, 1f, 1f },
                new float[] { ox - w / 2f, -h / 2f, 0f, 0f, 1f });
        return mb.build();
    }

    /** Change la texture (ou la carte d'indices) d'un panneau de sprite deja construit. */
    private void setSpriteFrame(Geometry g, LevelData.Obj o) {
        String sheet = glf == null ? null : glf.spriteSheet(o.sheet);
        if (sheet == null) {
            return;
        }
        Material mat = g.getMaterial();
        boolean shaded = mat.getMaterialDef().getAssetName().contains("SpriteShade");
        if (shaded) {
            Texture2D idx = Assets.texture(String.format(
                    "textures/objects/%s/frame_%02d.idx.png", sheet, o.frame));
            if (idx != null) {
                mat.setTexture("IndexMap", idx);
            }
        } else {
            Texture2D tex = Assets.texture(spritePath(o, sheet));
            if (tex != null) {
                setAlbedo(mat, tex);
            }
        }
        BufferedImage img = spriteImage(o, sheet);
        if (img != null) {
            float w = img.getWidth() / SPRITE_PPU;
            float h = img.getHeight() / SPRITE_PPU;
            g.setMesh(spriteQuad(w, h));
        }
    }

    // ------------------------------------------------------------- projectiles

    /**
     * Cree le noeud des projectiles : un panneau par emplacement du pool de tirs, masque tant que
     * l'emplacement est libre. Le jeu dessine les balles comme n'importe quel objet (meme table
     * Lvl_ObjectData), simplement avec des donnees de dessin reposees a chaque frame.
     */
    public Node buildShots(int pool) {
        shotsNode = new Node("shots");
        // Projectiles et impacts : des panneaux transparents, souvent additifs — ils n'ombrent pas.
        shotsNode.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
        MeshBuilder mb = new MeshBuilder();
        quad(mb,
                new float[] { -0.5f, 0.5f, 0f, 0f, 0f },
                new float[] { 0.5f, 0.5f, 0f, 1f, 0f },
                new float[] { 0.5f, -0.5f, 0f, 1f, 1f },
                new float[] { -0.5f, -0.5f, 0f, 0f, 1f });
        Mesh unit = mb.build();
        for (int i = 0; i < pool; i++) {
            Geometry g = new Geometry("shot_" + i, unit);
            g.setMaterial(solidMaterial(ColorRGBA.White));
            g.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
            // Les balles sont souvent ADDITIVES et n'ecrivent pas la profondeur : dans la file
            // opaque elles seraient recouvertes par les murs dessines apres. File transparente.
            g.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
            BillboardControl bb = new BillboardControl();
            bb.setAlignment(BillboardControl.Alignment.AxialY);
            g.addControl(bb);
            shotGeoms.add(g);
            shotsNode.attachChild(g);
        }
        return shotsNode;
    }

    /**
     * Applique l'etat de la simulation aux panneaux de projectiles : position, feuille/frame du
     * pas d'animation courant et mode de melange (les « glares », GraphicType 1, ne sont pas
     * encore rendus : le jeu les dessine par une routine de halo dediee, sans bitmap).
     */
    public void updateShots(List<ab3d2.rebirth.sim.Shots.Shot> shots) {
        for (int i = 0; i < shotGeoms.size() && i < shots.size(); i++) {
            Geometry g = shotGeoms.get(i);
            ab3d2.rebirth.sim.Shots.Shot s = shots.get(i);
            String sheet = s.active && glf != null ? glf.spriteSheet(s.sheet) : null;
            BufferedImage img = sheet == null ? null
                    : Assets.image(String.format("textures/objects/%s/frame_%02d.png", sheet, s.frame));
            // NB : `glareSize` n'est pose que pour BulT_GraphicType == 1, et AUCUNE balle du jeu
            // n'a ce type (verifie : seuls 0 = bitmap et 2 = additif existent). Les projectiles
            // lumineux passent donc par le chemin additif, avec le bloom par-dessus.
            if (img == null || s.glareSize != 0) {
                g.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
                continue;
            }
            String path = String.format("textures/objects/%s/frame_%02d.png", sheet, s.frame);
            String key = "shot_" + path + "_" + s.additive;
            Material mat = materials.get(key);
            if (mat == null) {
                mat = spriteMaterial(path, s.additive);
                materials.put(key, mat);
            }
            if (g.getMaterial() != mat) {
                g.setMaterial(mat);
            }
            g.setLocalScale(img.getWidth() / SPRITE_PPU, img.getHeight() / SPRITE_PPU, 1f);
            // Position : mots monde (le mot fort du 16.16) et hauteur de dessin 4(a0), comme les
            // objets -> -mot/64.
            g.setLocalTranslation((s.x >> 16) / XZ_SCALE, -s.heightWord / 64f,
                    -(s.z >> 16) / XZ_SCALE);
            g.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
            if (System.getProperty("rebirth.shotLog") != null) {
                System.out.printf("[balle %d] %s f%02d additif=%b pos=%s%n", i, sheet, s.frame,
                        s.additive, g.getLocalTranslation());
            }
        }
    }

    /** Retire de la scene un objet ramasse (ObjT_ZoneID_w = -1 cote simulation). */
    public void removeObject(LevelData.Obj o) {
        Geometry g = objectGeometries.get(o);
        if (g != null) {
            g.removeFromParent();
        }
        animObjects.removeIf(ao -> ao.obj == o);
        litSprites.removeIf(ls -> ls.obj == o);
        litModels.removeIf(lm -> lm.obj() == o);
    }

    /** Lumieres animees du niveau (a avancer une fois par frame de simulation). */
    public LightAnim lights() {
        return lights;
    }

    private DynLights dynLights;

    /** Lumieres DYNAMIQUES (projectiles lumineux, explosions, torches des monstres). */
    public DynLights dynLights() {
        return dynLights;
    }

    /**
     * Recalcule l'eclairage quand les lumieres animees ont bouge : luminosite par sommet des murs
     * et des sols, puis maillages des modeles (dont l'anneau directionnel depend des memes points).
     */
    public void refreshLighting() {
        if (lights == null || !lights.isDirty()) {
            return;
        }
        for (LitMesh lm : litMeshes) {
            com.jme3.scene.VertexBuffer vb =
                    lm.geometry().getMesh().getBuffer(com.jme3.scene.VertexBuffer.Type.TexCoord2);
            if (vb == null) {
                continue;
            }
            java.nio.FloatBuffer buf = (java.nio.FloatBuffer) vb.getData();
            int n = Math.min(lm.source().length, buf.capacity() / 2);
            for (int i = 0; i < n; i++) {
                int[] src = lm.source()[i];
                float b = src[0] < 0 ? buf.get(i * 2)
                        : (lm.wall() ? wallBright(src) : flatBright(src));
                buf.put(i * 2, b);
            }
            vb.updateData(buf);
        }
        if (System.getProperty("rebirth.lightLog") != null && refreshCount++ % 25 == 0) {
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (LitMesh lm : litMeshes) {
                for (int[] src : lm.source()) {
                    if (src[0] < 0) {
                        continue;
                    }
                    float b = lm.wall() ? wallBright(src) : flatBright(src);
                    min = Math.min(min, b);
                    max = Math.max(max, b);
                }
            }
            System.out.printf("[lumieres] refresh #%d : luminosites de sommet %.0f..%.0f%n",
                    refreshCount, min, max);
        }
        for (LitModel lm : litModels) {
            lm.geometry().setMesh(buildModelMesh(lm.model(), lm.obj()));
        }
        for (LitSprite ls : litSprites) {
            ls.rings = ringsFor(ls.obj);
        }
        lights.clearDirty();
    }

    // ------------------------------------------------------------- materiaux

    private Material texturedMaterial(Texture2D tex) {
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setTexture("ColorMap", tex);                      // textures extraites "a plat"
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return mat;
    }

    /**
     * Marque un materiau comme EMISSIF pour le {@code BloomFilter} en mode
     * {@code GlowMode.Objects} : le halo prend la forme et la couleur du graphisme lui-meme
     * (GlowMap), pas une teinte uniforme.
     *
     * <p>Ne concerne que les « glares » du jeu — les seuls graphismes qui representent vraiment
     * une source : lampes de plafond, puits de lumiere, eclats de tir.
     */
    private void glow(Material mat, Texture2D tex) {
        if (mat == null || tex == null || !modern) {
            return;
        }
        if (mat.getMaterialDef().getMaterialParam("GlowMap") != null) {
            mat.setTexture("GlowMap", tex);
            mat.setColor("GlowColor", ColorRGBA.White);
        }
    }

    private Material solidMaterial(ColorRGBA color) {
        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", color);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        return mat;
    }

    /** Sprites : decoupe alpha (0 = transparent) sans tri de transparence. */
    private Material cutoutMaterial(Texture2D tex) {
        Material mat = texturedMaterial(tex);
        mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        mat.setFloat("AlphaDiscardThreshold", 0.5f);
        return mat;
    }

    private Material atlasMaterial(boolean additive) {
        String key = "atlas_" + additive;
        Material cached = materials.get(key);
        if (cached != null) {
            return cached;
        }
        Texture2D tex = Assets.texture("textures/texturemaps_atlas.png");
        Material mat = tex != null ? texturedMaterial(tex) : solidMaterial(new ColorRGBA(0.7f, 0.7f, 0.8f, 1f));
        if (additive) {
            // Glares : melange additif.
            mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.AlphaAdditive);
            mat.getAdditionalRenderState().setDepthWrite(false);
            glow(mat, tex);
        } else if (tex != null) {
            mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            mat.setFloat("AlphaDiscardThreshold", 0.5f);
        }
        materials.put(key, mat);
        return mat;
    }

    /** Position de depart du joueur 1 (yeux ~1.7 m au-dessus du sol de sa zone). */
    public static Vector3f playerStart(LevelData lvl) {
        List<LevelData.Start> starts = safe(lvl.playerStarts);
        if (starts.isEmpty()) {
            return new Vector3f(0f, 2f, 0f);
        }
        LevelData.Start s = starts.get(0);
        float floorH = -6144f;
        for (LevelData.Zone z : safe(lvl.zones)) {
            if (z.id == s.zone) {
                floorH = z.floorH;
                break;
            }
        }
        return new Vector3f(s.x / XZ_SCALE, -floorH / 8192f + 1.7f, -s.z / XZ_SCALE);
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
