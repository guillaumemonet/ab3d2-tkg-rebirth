package ab3d2.rebirth;

import java.util.List;

/**
 * Modele des niveaux extraits (rebirth/assets/levels/&lt;lettre&gt;.json), produits par
 * rebirth.LevelExport depuis les donnees d'origine.
 *
 * <p>Unites : X/Z en unites editeur brutes ; hauteurs de zones/flats en virgule fixe
 * (cf. LevelBuilder pour les conversions). Les champs sont ceux emis par l'extracteur :
 * ne pas renommer sans mettre a jour LevelExport.
 */
public final class LevelData {

    public String level;
    public int index;
    public int numZones;
    public int exitZone;
    public List<Start> playerStarts;
    public List<Point> points;
    public List<Zone> zones;
    public List<Edge> edges;
    public List<Wall> walls;
    public List<Flat> flats;
    public List<Liftable> doors;
    public List<Liftable> lifts;
    public List<Obj> objects;
    /** Les 10 textes du niveau (LVLT_MESSAGE_COUNT x 160) : narration du ramassage et des morts. */
    public List<String> messages;
    /** Maillage de navigation de l'IA (Lvl_ControlPointCoordsPtr_l). */
    public int numControlPoints;
    public List<ControlPoint> controlPoints;
    /** Tables « prochain saut » a pied / en vol, pas de 100 (cf. {@link ab3d2.rebirth.sim.Nav}). */
    public List<Integer> walkLinks;
    public List<Integer> flyLinks;
    /** Points de decoupe des portails, par groupes : gauches, -1, droits, -2 (ligne de vue). */
    public List<Integer> clips;

    public static final class Start {
        public int x, z, zone;
    }

    /** Point de controle : sommet du maillage de navigation des aliens. */
    public static final class ControlPoint {
        public int x, z, y, w3;
    }

    /** Entree de la liste des zones potentiellement visibles (PVST). */
    public static final class Pvs {
        public int zone;
        public int clip;             // index MOT dans clips, ou -1
    }

    public static final class Point {
        public int x, z;
    }

    /** Secteur. floorH/roofH en .8 (>>8 = unites editeur) ; upperFloor/Roof pour le room-over-room. */
    public static final class Zone {
        public int id;
        public int floorH, roofH, upperFloorH, upperRoofH;
        public int water;                // niveau d'eau (Y) ; au-dessus du sol = zone noyee
        public int brightness;           // mot SIGNE ; >0 avec octet fort = lumiere animee
        public int upperBrightness;
        public boolean drawBackdrop;     // ciel visible depuis cette zone
        public int echo;
        public int telZone, telX, telZ;  // teleporteur (telZone < 0 = aucun)
        public int floorNoise, upperFloorNoise;
        /** ZoneT_BackSFXMask_w : bits des bruits d'ambiance possibles (bas / haut). */
        public int backSfxMask, upperBackSfxMask;
        public int points;
        /** ZoneT_ControlPoint_w : point de controle de l'etage bas, puis du haut. */
        public int controlPoint, upperControlPoint;
        public List<Pvs> pvs;            // zones potentiellement visibles + decoupe
        public List<Integer> pointBrights;   // 40 valeurs (10 points x 4 slots), cf. CurrentPointBrights
        public List<Integer> borderPoints;   // points de bordure (liste tronquee au terminateur)
        public List<Integer> borderPointsRaw; // les 10 mots bruts (la liste peut DEBORDER, cf. LightRings)
        public List<Integer> pointBrightsRaw; // mots bruts : octet fort = lumiere animee
        public List<ZoneEdge> edges;
        public List<Integer> edgeList;   // liste brute (-1 fin de groupe, -2 fin) pour la collision
    }

    /** Arete telle que vue depuis une zone (contour du secteur). */
    public static final class ZoneEdge {
        public int edge;
        public int x, z, dx, dz;
        public int joinZone;
    }

    /** Arete globale (table Lvl_ZoneEdgePtr) : la base de la collision (MoveObject). */
    public static final class Edge {
        public int x, z, dx, dz;
        public int join;
        public int w5;
        public int b12, b13;
    }

    /**
     * Mur (commande type 0 du flux ZoneGraph, record 28 o).
     * top/bottom deja convertis en unites editeur par l'extraction (.8 &gt;&gt; 8).
     * texIndex bit15 = portail. heightMask+1 = hauteur texture, widthMask+1 = largeur SOUS-TUILE.
     */
    public static final class Wall {
        public int zone;
        /** Etage HAUT de la zone (second flux zone-graph) : deux sols empiles dans un secteur. */
        public boolean upper;
        /**
         * Offset du record dans le flux graphique, MOT DE TYPE COMPRIS. C'est par lui que les
         * portes et les ascenseurs designent les murs qu'ils DEFORMENT chaque frame.
         */
        public int gfxOfs;
        public int leftPt, rightPt;
        /**
         * Slot du mur sur la CARTE (0..9 dans les 10 emplacements de la zone), ou -1 s'il n'y
         * figure pas. {@code mapDoor} : il s'y trace comme une PORTE (MAP_STEP_WALL_PEN).
         */
        public int mapSlot = -1;
        public boolean mapDoor;
        public int whichLeft, whichRight;
        public int wallLen;      // longueur en texels (U)
        public int fromTile;     // colonne de depart de la sous-tuile
        public int yOffset;      // ancrage V monde
        /** Mur DEFORME : la base dont le jeu retranche {@code position >> 2} chaque frame. */
        public int yOffsetBase;
        public int texIndex;
        public int heightMask;   // texH - 1
        public int widthMask;    // texW - 1
        public int whichPBR;     // slot de luminosite : bas = quartet faible, haut = quartet fort
        public int brightOffset; // decalage de luminosite propre au mur (octet signe)
        public int top, bottom;
        public int otherZone;
    }

    /** Sol (kind="floor") ou plafond (kind="ceiling") d'une zone. y en unites &lt;&lt;6. */
    public static final class Flat {
        public int zone;
        /** Etage HAUT de la zone : sa luminosite se lit aux slots 2 et 3, pas 0 et 1. */
        public boolean upper;
        public String kind;
        public int y;
        public List<Integer> points;
        public List<Integer> pointSlots;   // index du point DANS LA ZONE (= rang de son arete)
        public int tile;
    }

    /** Porte ou ascenseur (meme format "liftable" cote donnees d'origine). */
    public static final class Liftable {
        public int index;
        public int bottom, top;
        public int openingSpeed, closingSpeed, openDuration;
        /** Bruitages de la porte/ascenseur ; le jeu leur RETIRE 1 a l'usage (0 = aucun). */
        public int openingSFX, closingSFX, openedSFX, closedSFX;
        public int x, z;
        public int position, speed;
        public int zone;
        public int openBits, closeBits;
        public List<Integer> walls;   // index d'aretes du panneau
        /** Les murs que la porte/l'ascenseur DEFORME chaque frame (cf. {@link WallPatch}). */
        public List<WallPatch> wallPatches;
    }

    /**
     * Un mur deforme par une porte ou un ascenseur (newanims.s, {@code .simplecheck}) : le jeu y
     * ecrit chaque frame {@code yOffset = yBase - (position >> 2)} et, selon le cas,
     * {@code bottom} (porte : le battant PEND du plafond, son bord bas monte) ou {@code top}
     * (ascenseur : la jupe de la plate-forme, son bord haut suit le sol qui monte).
     */
    public static final class WallPatch {
        public int edge;
        /** Le mur vise, par son {@code Wall.gfxOfs}. */
        public int gfxOfs;
        public int yBase;
    }

    /** Objet place : sprite (billboard), modele vectoriel, ou "other" (rien a afficher). */
    public static final class Obj {
        public int x, z, zone;
        public int typeId, def;
        public String gclass;        // "sprite" | "vector" | "other"
        public int sheet, frame, pal;
        public int model;
        public int angle;
        public boolean ceiling, wall, additive;
        public boolean lit;              // sprite « lightsource » (monstres) : eclaire par l'anneau
        public int bright;               // luminosite propre de l'objet (draw_BrightToAdd_w)
        public int doorsHeld;            // EntT_DoorsAndLiftsHeld_l : portes dont il tient la cle
        public boolean upperZone;        // ShotT_InUpperZone_b : etage haut de la zone
        /**
         * Decalage vertical du PAS D'ANIMATION courant (octet 4 du script, applique ×2).
         * Le jeu recalcule chaque frame {@code ObjT_ZPos_l = worryHeight + 2*delta} : c'est la
         * hauteur de dessin de l'objet ET celle que teste le ramassage.
         */
        public int animDelta;
        /** EntT_WhichAnim_b : l'objet joue son animation ACTIVE (interrupteur enclenché). */
        public boolean activated;
        /** EntT_Timer2_w : durée d'activation écoulée. */
        public int activeTimer;
        /** EntT_Timer1_w : pas courant du script d'animation (remis à 0 aux changements d'état). */
        public int animStep;
        // --- champs d'ENTITE, aliens (typeId 0) ---
        public int hitPoints;            // EntT_HitPoints_b
        public int team;                 // EntT_TeamNumber_b (< 0 = sans equipe)
        public int mode;                 // EntT_CurrentMode_b
        public int controlPoint;         // EntT_CurrentControlPoint_w
        public int targetControlPoint;   // EntT_TargetControlPoint_w
        public int height;               // 4(a0) : hauteur de dessin/collision
        /** EntT_DisplayText_w : index du message de niveau a afficher (< 0 = aucun). */
        public int displayText = -1;
        public String name;
    }
}
