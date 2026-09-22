package ab3d2.rebirth.extract;

import ab3d2.Defs;
import ab3d2.Mem;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ab3d2.bss.PlayerBss.*;

/**
 * Extraction des NIVEAUX vers JSON (géométrie secteurs + arêtes + points + objets).
 *
 * <p>Charge chaque niveau via le pipeline du portage (prologue de {@code Hires.Game_Begin} avec
 * {@code loopFrameLimit=0} → charge les données + dérive les pointeurs {@code Lvl_*}, sans boucle
 * de jeu ni intro), puis lit les structures décodées depuis {@code Mem} et les sérialise.
 */
public final class LevelExport {

    private LevelExport() {
    }

    static void extractAll(Path outDir) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();                          // Draw_Init + Game_Init (framebuffer, tables)

        // inits de _startup (méthode de contrôle, énergies) — nécessaires au prologue.
        Mem.ww(Plr1_Energy_w, 191);
        Mem.ww(Plr2_Energy_w, 191);
        Mem.wb(Plr1_Keys_b, 0); Mem.wb(Plr1_Mouse_b, 0xFF);
        Mem.wb(Plr2_Keys_b, 0); Mem.wb(Plr2_Mouse_b, 0xFF);
        // Plr_MultiplayerType = valeur factice ≠ 'n'/'m'/'s' → prologue saute l'intro (pas PLR_SINGLE)
        // ET SETPLAYERS prend la branche solo (AI_NoEnemies, pas de handshake).
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, 0);

        Path dir = outDir.resolve("levels");
        Files.createDirectories(dir);
        int done = 0;
        for (int lvl = 0; lvl < Defs.NUM_LEVELS; lvl++) {
            String letter = String.valueOf((char) ('a' + lvl));
            try {
                loadLevel(lvl);
                Map<String, Object> json = readLevel(lvl, letter);
                String out = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json);
                Files.writeString(dir.resolve(letter + ".json"), out);
                System.out.printf("  niveau %s : %d zones, %d objets%n",
                        letter, ((List<?>) json.get("zones")).size(), ((List<?>) json.get("objects")).size());
                done++;
            } catch (Throwable t) {
                System.out.println("  niveau " + letter + " : ÉCHEC " + t);
            }
        }
        System.out.println("[levels] " + done + "/" + Defs.NUM_LEVELS + " niveaux → " + dir);
    }

    /** Charge le niveau {@code lvl} (prologue Game_Begin, sans boucle ni rendu). */
    private static void loadLevel(int lvl) {
        Mem.ww(ab3d2.ControlloopData.Game_LevelNumber_w, lvl);
        ab3d2.Controlloop.DEFAULTGAME();
        for (int i = 0; i < 11; i++) {                       // template Plr_* → Plr1/Plr2
            int v = Mem.l(Plr_Health_w + i * 4);
            Mem.wl(Plr1_Health_w + i * 4, v);
            Mem.wl(Plr2_Health_w + i * 4, v);
        }
        ab3d2.Hires.loopFrameLimit = 0;                      // prologue seul (game_main_loop sort à 0 frame)
        ab3d2.Hires.Game_Begin();
    }

    private static Map<String, Object> readLevel(int lvl, String letter) {
        int nz = Mem.uw(ab3d2.bss.LevelBss.Lvl_NumZones_w);
        int zptrs = Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l);
        int edgeBase = Mem.l(ab3d2.bss.LevelBss.Lvl_ZoneEdgePtr_l);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("level", letter);
        root.put("index", lvl);
        root.put("numZones", nz);
        root.put("exitZone", Mem.w(ab3d2.HiresData.Lvl_ExitZoneID_w));
        root.put("playerStarts", playerStarts());
        root.put("points", points());
        root.put("zones", zones(nz, zptrs, edgeBase));
        root.put("edges", edges(edgeBase));
        List<Object> wallsL = new ArrayList<>();
        List<Object> flatsL = new ArrayList<>();
        parseGraph(nz, wallsL, flatsL);
        root.put("walls", wallsL);
        root.put("flats", flatsL);
        root.put("doors", liftables(Mem.l(ab3d2.bss.LevelBss.Lvl_DoorDataPtr_l)));
        root.put("lifts", liftables(Mem.l(ab3d2.bss.LevelBss.Lvl_LiftDataPtr_l)));
        root.put("objects", objects());
        // Navigation de l'IA : points de contrôle du niveau + tables de saut suivant
        // (Objectmove.GetNextCPt), chargées depuis les fichiers .map / .fly du niveau.
        root.put("messages", messages());
        root.put("numControlPoints", Mem.uw(ab3d2.bss.LevelBss.Lvl_NumControlPoints_w));
        root.put("controlPoints", controlPoints());
        root.put("clips", clips(nz, zptrs));
        root.put("walkLinks", links(Mem.l(ab3d2.HiresData.Lvl_WalkLinksPtr_l)));
        root.put("flyLinks", links(Mem.l(ab3d2.HiresData.Lvl_FlyLinksPtr_l)));
        return root;
    }

    /**
     * Résout le graphique par défaut d'un objet/alien depuis les tables d'anim du GLF (frame 0),
     * fidèle à la logique du port (plr_Use_AnimSetup / anim byte0). VALIDÉ contre les feuilles connues :
     *  - Alien (typeId 0) : anim byte0 = index de FEUILLE sprite (0 ALIEN2, 3 TRICLAW, 6 ASHNARG,
     *    11 GUARD, 12 PRIEST, 13 INSECT…) ; vecFlag (GFXType+1)==1 → modèle vectoriel.
     *  - Objet (typeId 1) : GFXType==1 → modèle vectoriel (index = anim byte0) ; sinon sprite
     *    (feuille = anim byte0, frame = anim byte1).
     */
    private static void resolveGfx(Map<String, Object> o, int glf, int typeId, int def, int objAddr) {
        // Octet 35 de l'instance = frame/couleur du modèle propre à l'OBJET (ex. clé/indicateur :
        // 1..12 = couleur ; 0 pour la plupart). Utilisé comme frame des modèles vectoriels.
        int objFrame = Mem.ub(objAddr + 35);
        if (typeId == 0) {                                   // ALIEN
            int vec = Mem.ub(glf + Defs.GLFT_AlienDefs_l + def * Defs.AlienT_SizeOf_l + Defs.AlienT_GFXType_w + 1);
            int an = glf + Defs.GLFT_AlienAnims_l + def * Defs.A_AnimLen; // option 0, frame 0
            if (vec == 1) {
                o.put("gclass", "vector");
                // Index de modele : l'OCTET 0 du pas d'animation, exactement comme pour un
                // objet vectoriel -- ai.s:1917 « move.b (a6,d1.w),9(a0) » ecrit ce meme octet
                // dans le champ que draw_PolygonModel relit. C'est le SEUL endroit : le mot
                // GFXType ne porte que le drapeau (octet bas = 1 = vectoriel), et le lire
                // entier donnait 1 pour toutes ces entites, soit le modele SWITCH a la place
                // du bon -- un boss Mantis dessine en interrupteur.
                o.put("model", Mem.ub(an));
                // Frame : l'octet 1, en valeur absolue moins un ; son SIGNE porte le miroir
                // (ai.s:1918-1927), qui n'a pas de sens pour un maillage.
                int f = Mem.b(an + 1);
                o.put("frame", Math.max(0, Math.abs(f) - 1));
                o.put("angle", Mem.uw(objAddr + Defs.EntT_CurrentAngle_w));
                o.put("ceiling", false);
            } else {
                o.put("gclass", "sprite");
                o.put("sheet", Mem.ub(an));                  // byte0 = index feuille sprite
                o.put("frame", 0);
                // Variante de palette (WhichLightPal en jeu) = vec-2, bornée 0..3. vec = octet
                // GFXType+1 du def alien (GUARD : p0 vert, p1 bleu, p2 rouge, p3 spécial).
                // Le jeu n'éclaire un sprite QUE si (octet4 & 127) - 2 est dans [0,4) : c'est ce
                // même `vec` (pastobjscale, Objdrawhires.java:583-592). Sinon : rendu à plat.
                o.put("pal", Math.max(0, Math.min(3, vec - 2)));
                o.put("lit", vec >= 2 && vec < 6);
            }
        } else if (typeId == 1) {                            // OBJET
            int gfxType = Mem.w(glf + Defs.GLFT_ObjectDefs + def * Defs.ODefT_SizeOf_l + Defs.ODefT_GFXType_w);
            int an = glf + Defs.GLFT_ObjectDefAnims_l + def * Defs.O_AnimSize; // frame 0 (6 o)
            if (gfxType == 1) {
                o.put("gclass", "vector");
                o.put("model", Mem.ub(an));                  // byte0 = index modèle vectoriel
                o.put("frame", objFrame);                    // clé/indicateur : couleur (octet 35)
                o.put("angle", Mem.uw(objAddr + Defs.EntT_CurrentAngle_w)); // orientation
                // Référence de hauteur : plafond si floorCeiling != 0, sinon sol (cf. worryHeight).
                o.put("ceiling", Mem.w(glf + Defs.GLFT_ObjectDefs + def * Defs.ODefT_SizeOf_l + Defs.ODefT_FloorCeiling_w) != 0);
                o.put("wall", Mem.w(glf + Defs.GLFT_ObjectDefs + def * Defs.ODefT_SizeOf_l + Defs.ODefT_LockToWall_w) != 0);
            } else {
                o.put("gclass", "sprite");
                o.put("sheet", Mem.ub(an));                  // byte0 = index feuille
                o.put("frame", Mem.ub(an + 1));              // byte1 = frame
            }
        } else {
            o.put("gclass", "other");                        // balles/aux/etc. — ignorés au rendu statique
        }
    }

    /** Départs joueurs (position monde = XOff/ZOff 16.16 → >>16 ; + zone). */
    private static List<Object> playerStarts() {
        List<Object> l = new ArrayList<>();
        // ATTENTION : Plr*_Zone_w n'est qu'un CACHE rempli par objmoveanim pendant la boucle de
        // jeu ; au chargement il vaut encore 0. La valeur autoritative est Plr*_ZonePtr_l, posée
        // par SETPLAYERS -> on lit l'ID dans la structure de zone (ZoneT_ID_w, offset 0).
        int[][] p = {
            {Mem.l(Plr1_XOff_l), Mem.l(Plr1_ZOff_l), Mem.w(Mem.l(ab3d2.bss.PlayerBss.Plr1_ZonePtr_l) + Defs.ZoneT_ID_w)},
            {Mem.l(Plr2_XOff_l), Mem.l(Plr2_ZOff_l), Mem.w(Mem.l(ab3d2.bss.PlayerBss.Plr2_ZonePtr_l) + Defs.ZoneT_ID_w)},
        };
        for (int[] s : p) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("x", s[0] >> 16);
            e.put("z", s[1] >> 16);
            e.put("zone", s[2]);
            l.add(e);
        }
        return l;
    }

    /**
     * Murs du niveau depuis le FLUX GRAPH (par zone). Chaque zone : stream = ZoneGraphAdds[zi] +
     * GraphicsPtr + 2 (saute le mot ID-zone). Commandes : {@code type = mot & 0xFF} (mot < 0 = fin),
     * {@code a0 += 2}. Type 0 = mur (28 o : leftPt@0, rightPt@2, texIndex@12, top@18 l, bot@22 l).
     * top/bot sont en virgule fixe .8 → hauteur éditeur = valeur >> 8 (cf. WallRenderEntry.topWallH()).
     * Autres tailles : 1/2 sol, 7 eau = 14+(sides-1)*2 ; 4 objet = 2 ; 12 backdrop = 0.
     */
    private static void parseGraph(int nz, List<Object> l, List<Object> flats) {
        int ga = Mem.l(ab3d2.bss.LevelBss.Lvl_ZoneGraphAddsPtr_l);
        int gfx = Mem.l(ab3d2.bss.LevelBss.Lvl_GraphicsPtr_l);
        int uppers = 0;
        for (int zi = 0; zi < nz; zi++) {
        // Une zone porte DEUX flux : l'etage BAS a (a0,zone*8) et, quand elle en a un, l'etage
        // HAUT a 4(a0,zone*8) — c'est ainsi que le jeu empile deux sols dans le meme secteur
        // (les escaliers superposes du niveau C). DrawZoneGraph les rend l'un apres l'autre, en
        // basculant Draw_DoUpper_b et en decalant les luminosites de points de +4 octets
        // (= +2 mots, donc les slots 2 et 3 au lieu de 0 et 1). Un pointeur nul = pas d'etage.
        for (int half = 0; half < 2; half++) {
            int rel = Mem.l(ga + zi * 8 + half * 4);
            if (half == 1 && rel == 0) {               // cmp.l Lvl_GraphicsPtr_l,a0 ; beq .lower_zone_only
                continue;
            }
            if (half == 1) {
                uppers++;
            }
            final boolean upper = half == 1;
            int a0 = rel + gfx + 2;
            for (int guard = 0; guard < 20000; guard++) {
                int type = Mem.w(a0) & 0xFF;                 // octet bas = type (haut = flags)
                a0 += 2;
                if ((byte) type < 0) {                       // type ≥ 128 = fin (comme DrawZoneGraph)
                    break;
                }
                if (type == 0) {                             // mur (record 28 o, cf. Hireswall.Draw_Wall)
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("zone", zi);
                    w.put("upper", upper);
                    // Offset du record DANS Lvl_GraphicsPtr_l, MOT DE TYPE COMPRIS : c'est la
                    // valeur que portent les listes de murs des portes/ascenseurs, qui patchent
                    // 12(a1) = yOffset@10 et 20/24(a1) = top@18 / bottom@22.
                    w.put("gfxOfs", a0 - 2 - gfx);
                    // Octet FORT du mot de commande = draw_WallID_w, l'identite du mur sur la
                    // CARTE (cf. Hireswall, « no_put_in_map ») : bit 7 = ne figure PAS sur la
                    // carte ; bits 0..3 = son slot (0..9) dans Lvl_BigMap_vl / Lvl_CompactMap_vl ;
                    // bit 4 = c'est une PORTE, tracee avec MAP_STEP_WALL_PEN et non SOLID.
                    int mapId = Mem.ub(a0 - 2);
                    boolean inMap = (mapId & 0x80) == 0;
                    w.put("mapSlot", inMap ? (mapId & 15) : -1);
                    w.put("mapDoor", inMap && (mapId & 0x10) != 0);
                    w.put("leftPt", Mem.uw(a0));             // @0  index point gauche
                    w.put("rightPt", Mem.uw(a0 + 2));        // @2  index point droit
                    w.put("whichLeft", Mem.ub(a0 + 4));      // @4  sous-point gauche
                    w.put("whichRight", Mem.ub(a0 + 5));     // @5  sous-point droit
                    w.put("wallLen", Mem.uw(a0 + 6));        // @6  longueur (texels U)
                    w.put("fromTile", Mem.uw(a0 + 8) << 4);  // @8  décalage U de départ (<<4)
                    w.put("yOffset", Mem.uw(a0 + 10));       // @10 décalage V (phase texture)
                    w.put("texIndex", Mem.w(a0 + 12));       // @12 index texture (bit15 = portail)
                    w.put("heightMask", Mem.ub(a0 + 14));    // @14 hauteur texture - 1
                    w.put("widthMask", Mem.ub(a0 + 16));     // @16 largeur texture - 1
                    w.put("whichPBR", Mem.ub(a0 + 17));      // @17 slot de luminosité de point (bit3 = zone voisine)
                    w.put("brightOffset", Mem.b(a0 + 26));   // @26 décalage de luminosité du mur (octet signé)
                    w.put("top", Mem.l(a0 + 18) >> 8);       // @18 virgule fixe .8 → unités éditeur
                    w.put("bottom", Mem.l(a0 + 22) >> 8);    // @22
                    w.put("otherZone", Mem.ub(a0 + 27));     // @27 zone voisine (0 = mur plein)
                    l.add(w);
                    a0 += 28;
                } else if (type == 1 || type == 2 || type == 7) { // sol / plafond / eau
                    int sides = Mem.uw(a0 + 2);
                    {                                        // sol=1, plafond=2, EAU=7 (meme record)
                        // Flat : @0 floorY, @2 sides, @4 points[sides] (&0xFFF), footer whichtile @8+sides*2.
                        Map<String, Object> fl = new LinkedHashMap<>();
                        fl.put("zone", zi);
                        fl.put("upper", upper);
                        fl.put("kind", type == 1 ? "floor" : (type == 2 ? "ceil" : "water"));
                        // @0 floorY : hauteur PROPRE du flat (raw = floorY<<6) → Y_godot = -floorY/128.
                        fl.put("y", Mem.w(a0));
                        // Chaque mot de point : 12 bits bas = index de point du niveau, quartet
                        // FORT = index du point DANS LA ZONE (sideLoopGouraud : rol.w #4 ; and #$f),
                        // qui sert à retrouver sa luminosité (CurrentPointBrights[zone*40+p*4]).
                        List<Integer> pts = new ArrayList<>();
                        List<Integer> slots = new ArrayList<>();
                        for (int i = 0; i < sides; i++) {
                            int wpt = Mem.uw(a0 + 4 + i * 2);
                            pts.add(wpt & 0xFFF);
                            slots.add((wpt >> 12) & 0xF);
                        }
                        fl.put("points", pts);
                        fl.put("pointSlots", slots);
                        // whichtile (footer) = b*256+k → index tuile 0..15 = b*4 + k (b=banque, k=entrelacement).
                        int wt = Mem.uw(a0 + 10 + sides * 2);
                        fl.put("tile", (wt >> 8) * 4 + (wt & 0xFF));
                        flats.add(fl);
                    }
                    a0 += 14 + sides * 2;
                } else if (type == 4) {                      // objet
                    a0 += 2;
                } else if (type == 12) {                     // backdrop
                    // 0 octet de données
                } else {
                    break;                                   // type inconnu → arrêt sûr
                }
            }
        }
        }
        System.out.printf("  %d zones a etage%n", uppers);
    }

    /** Points du niveau (Vec2W : x@0, z@2 ; stride 4). */
    private static List<Object> points() {
        List<Object> l = new ArrayList<>();
        int base = Mem.l(ab3d2.bss.LevelBss.Lvl_PointsPtr_l);
        int n = Mem.uw(ab3d2.bss.LevelBss.Lvl_NumPoints_w);
        for (int i = 0; i < n; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", Mem.w(base + i * 4));
            p.put("z", Mem.w(base + i * 4 + 2));
            l.add(p);
        }
        return l;
    }

    /** Secteurs : hauteurs (×1024, Y vers le bas), luminosité, points, liste d'arêtes (avec join). */
    /**
     * Tableau GLOBAL des arêtes (EdgeT, 16 o), indexé par edgeIndex — base commune aux listes
     * d'arêtes des zones, aux murs des portes/lifts, et au flag runtime EdgeT_Flags. Nécessaire au
     * portage de MoveObject (collision/traversée de zones) et au handshake des portes.
     * w5 = EdgeT_Word_5 (diviseur du test de côté). joinZone < 0 = mur plein.
     */
    private static List<Object> edges(int edgeBase) {
        List<Object> l = new ArrayList<>();
        int count = Math.abs(Mem.l(ab3d2.bss.LevelBss.Lvl_EdgeCount_l)) / Defs.EdgeT_SizeOf_l;
        for (int i = 0; i < count; i++) {
            int ed = edgeBase + i * Defs.EdgeT_SizeOf_l;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("x", Mem.w(ed + Defs.EdgeT_XPos_w));
            e.put("z", Mem.w(ed + Defs.EdgeT_ZPos_w));
            e.put("dx", Mem.w(ed + Defs.EdgeT_XLen_w));
            e.put("dz", Mem.w(ed + Defs.EdgeT_ZLen_w));
            e.put("join", Mem.w(ed + Defs.EdgeT_JoinZone_w));
            e.put("w5", Mem.w(ed + Defs.EdgeT_Word_5));
            e.put("b12", Mem.b(ed + Defs.EdgeT_Byte_12));    // offset a4 (mur) quand AwayFromWall>=0
            e.put("b13", Mem.b(ed + Defs.EdgeT_Byte_13));    // offset a6
            l.add(e);
        }
        return l;
    }

    /**
     * Luminosités des POINTS d'une zone (40 mots par zone à PointBrightsPtr_l : 10 points × 4 slots).
     * Reproduit le calcul statique de Hires.computeZoneBrightness :
     * {@code v = ext.w(octet bas) ; v = (v*397)>>8 ; si v<0 v -= 600 ; v += 300}.
     * Les lumières ANIMÉES (octet fort non nul → Anim_BrightTable) ne sont pas résolues ici : on
     * exporte la valeur de base, et `pointBrightsAnim` dit quels slots sont animés.
     */
    private static List<Object> pointBrights(int zone) {
        int base = Mem.l(ab3d2.bss.TablesBss.PointBrightsPtr_l);
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int raw = Mem.w(base + (zone * 40 + i) * 2);
            int v = (byte) raw;                              // ext.w : octet bas signé
            v = (v * 397) >> 8;
            if (v < 0) {
                v -= 600;
            }
            v += 300;
            l.add(v);
        }
        return l;
    }

    /**
     * Points de BORDURE d'une zone (Lvl_ZoneBorderPointsPtr, 20 octets = 10 mots par zone,
     * liste terminée par une valeur négative). C'est la base de l'éclairage directionnel des
     * objets : draw_CalcBrightsInZone parcourt ces points pour remplir l'anneau des 16 secteurs
     * (Objdrawhires.java:276). Le RANG dans cette liste donne aussi l'entrée de pointBrights
     * utilisée (rang*4 + 0 pour le bas, +1 pour le haut).
     */
    /**
     * Mots BRUTS de PointBrights (40 par zone). L'octet bas est la luminosité de base ; l'octet
     * fort, s'il est non nul (et l'octet bas ≥ 0), désigne une LUMIÈRE ANIMÉE :
     * quartet faible = index dans Anim_BrightTable (1..7), quartet fort = poids-1.
     * Nécessaire pour rejouer l'animation des lumières (Hires.computeZoneBrightness).
     */
    private static List<Object> pointBrightsRaw(int zone) {
        int base = Mem.l(ab3d2.bss.TablesBss.PointBrightsPtr_l);
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            l.add(Mem.w(base + (zone * 40 + i) * 2));
        }
        return l;
    }

    private static List<Object> borderPoints(int zone) {
        List<Object> l = new ArrayList<>();
        for (Object o : borderPointsRaw(zone)) {
            int p = (Integer) o;
            if (p < 0) {
                break;
            }
            l.add(p);
        }
        return l;
    }

    /**
     * Les 10 mots BRUTS de la liste (terminateurs négatifs compris). ATTENTION : une liste peut
     * être PLUS LONGUE que les 10 mots de son emplacement — le jeu continue de lire dans la zone
     * suivante (vérifié : la zone 47 du niveau A a 14 points), et l'index de luminosité suit
     * (zone*40 + i, i pouvant dépasser 39). C'est ce débordement qu'il faut reproduire.
     */
    private static List<Object> borderPointsRaw(int zone) {
        int base = Mem.l(ab3d2.bss.LevelBss.Lvl_ZoneBorderPointsPtr_l);
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            l.add(Mem.w(base + zone * 20 + i * 2));
        }
        return l;
    }

    private static List<Object> zones(int nz, int zptrs, int edgeBase) {
        List<Object> l = new ArrayList<>();
        for (int z = 0; z < nz; z++) {
            int zn = Mem.l(zptrs + z * 4);
            if (zn == 0) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", z);
            e.put("floorH", Mem.l(zn + Defs.ZoneT_Floor_l));
            e.put("roofH", Mem.l(zn + Defs.ZoneT_Roof_l));
            e.put("upperFloorH", Mem.l(zn + Defs.ZoneT_UpperFloor_l));
            e.put("upperRoofH", Mem.l(zn + Defs.ZoneT_UpperRoof_l));
            e.put("water", Mem.l(zn + Defs.ZoneT_Water_l));               // niveau d'eau (Y)
            // Luminosités : mot SIGNÉ (négatif = valeur directe ; positif avec octet fort non nul
            // = index dans Anim_BrightTable, lumières animées). cf. Hires.computeZoneBrightness.
            e.put("brightness", Mem.w(zn + Defs.ZoneT_Brightness_w));
            e.put("upperBrightness", Mem.w(zn + Defs.ZoneT_UpperBrightness_w));
            e.put("drawBackdrop", Mem.ub(zn + Defs.ZoneT_DrawBackdrop_b) != 0);
            e.put("echo", Mem.ub(zn + Defs.ZoneT_Echo_b));
            // ZoneT_BackSFXMask_w : 2 mots (etage bas / haut), un bit par bruit d'ambiance
            // possible dans la zone (BACKSFX en tire un au hasard).
            e.put("backSfxMask", Mem.w(zn + Defs.ZoneT_BackSFXMask_w));
            e.put("upperBackSfxMask", Mem.w(zn + Defs.ZoneT_BackSFXMask_w + 2));
            e.put("telZone", Mem.w(zn + Defs.ZoneT_TelZone_w));           // téléporteur (<0 = aucun)
            e.put("telX", Mem.w(zn + Defs.ZoneT_TelX_w));
            e.put("telZ", Mem.w(zn + Defs.ZoneT_TelZ_w));
            e.put("floorNoise", Mem.w(zn + Defs.ZoneT_FloorNoise_w));     // son de pas
            e.put("upperFloorNoise", Mem.w(zn + Defs.ZoneT_UpperFloorNoise_w));
            e.put("points", Mem.uw(zn + Defs.ZoneT_Points_w));
            // ZoneT_ControlPoint_w = 2 OCTETS : point de contrôle de l'étage bas, puis du haut.
            e.put("controlPoint", Mem.ub(zn + Defs.ZoneT_ControlPoint_w));
            e.put("upperControlPoint", Mem.ub(zn + Defs.ZoneT_ControlPoint_w + 1));
            e.put("pvs", pvs(zn));                                       // zones potentiellement visibles
            e.put("pointBrights", pointBrights(z));
            e.put("pointBrightsRaw", pointBrightsRaw(z));
            e.put("borderPoints", borderPoints(z));
            e.put("borderPointsRaw", borderPointsRaw(z));
            // Liste d'arêtes de la zone (ZoneT_EdgeListOffset). Format : indices (mots) ; -1 sépare les
            // groupes (checkwalls = 1er groupe ; checkotherwalls = tout jusqu'à -2) ; -2 = fin.
            // `edges` = 1er groupe avec géométrie (contour, sert au rendu sol/plafond).
            // `edgeList` = liste brute complète (indices + marqueurs -1/-2) pour la collision MoveObject.
            List<Object> edges = new ArrayList<>();
            List<Integer> edgeList = new ArrayList<>();
            int elist = zn + (short) Mem.uw(zn + Defs.ZoneT_EdgeListOffset_w);
            boolean firstGroup = true;
            for (int i = 0; i < 512; i++) {
                int ei = Mem.w(elist + i * 2);
                edgeList.add(ei);
                if (ei == -2) {
                    break;
                }
                if (ei < 0) {                            // -1 : fin du 1er groupe
                    firstGroup = false;
                    continue;
                }
                if (firstGroup) {
                    int ed = edgeBase + (ei << 4);
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("edge", ei);
                    edge.put("x", Mem.w(ed + Defs.EdgeT_XPos_w));
                    edge.put("z", Mem.w(ed + Defs.EdgeT_ZPos_w));
                    edge.put("dx", Mem.w(ed + Defs.EdgeT_XLen_w));
                    edge.put("dz", Mem.w(ed + Defs.EdgeT_ZLen_w));
                    edge.put("joinZone", Mem.w(ed + Defs.EdgeT_JoinZone_w));
                    edges.add(edge);
                }
            }
            e.put("edges", edges);
            e.put("edgeList", edgeList);
            l.add(e);
        }
        return l;
    }

    /**
     * Portes / ascenseurs (même format « liftable », lu par DoorRoutine/LiftRoutine). Record :
     * header 36 o {bottom@0, top@2, openSpeed@4, closeSpeed@6, openDur@8, 4 SFX@10-16, x@18, z@20,
     * position@22, speed@24, gfxPtr@26(l), zone@30, @32, openBits@34/closeBits@35} + liste de murs
     * (10 o/mur : {edgeIdx(w), 2 pointeurs}, fin = edgeIdx < 0). Fin de liste globale : bottom == 999.
     */
    private static List<Object> liftables(int a0) {
        List<Object> l = new ArrayList<>();
        if (a0 == 0) {
            return l;
        }
        for (int guard = 0; guard < 500; guard++) {
            int bottom = Mem.w(a0);
            if (bottom == 999) {
                break;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", l.size());
            e.put("bottom", bottom);
            e.put("top", Mem.w(a0 + 2));
            e.put("openingSpeed", Mem.w(a0 + 4));
            e.put("closingSpeed", Mem.w(a0 + 6));
            e.put("openDuration", Mem.w(a0 + 8));
            // Bruitages de la porte/ascenseur (le jeu leur retire 1 a l'usage) : Newanims:762-769.
            e.put("openingSFX", Mem.w(a0 + 10));
            e.put("closingSFX", Mem.w(a0 + 12));
            e.put("openedSFX", Mem.w(a0 + 14));
            e.put("closedSFX", Mem.w(a0 + 16));
            e.put("x", Mem.w(a0 + 18));
            e.put("z", Mem.w(a0 + 20));
            e.put("position", Mem.w(a0 + 22));               // position courante (runtime, init)
            e.put("speed", Mem.w(a0 + 24));                  // vitesse courante (runtime, init)
            e.put("zone", Mem.w(a0 + 30));
            e.put("openBits", Mem.ub(a0 + 34));
            e.put("closeBits", Mem.ub(a0 + 35));
            List<Integer> walls = new ArrayList<>();
            List<Object> patches = new ArrayList<>();
            int p = a0 + 36;
            for (int g2 = 0; g2 < 200; g2++) {
                int edge = Mem.w(p);
                if (edge < 0) {
                    break;
                }
                walls.add(edge);
                // Les deux pointeurs : le record de mur a deformer (offset dans le flux graph,
                // cf. gfxOfs) et la base du decalage V, a laquelle le jeu retranche
                // position>>2 chaque frame (adda.w d0,a2 ; move.w a2,12(a1)).
                Map<String, Object> pw = new LinkedHashMap<>();
                pw.put("edge", edge);
                pw.put("gfxOfs", Mem.l(p + 2));
                pw.put("yBase", Mem.l(p + 6));
                patches.add(pw);
                p += 10;                                     // mur = edgeIdx(w) + 2 pointeurs(l)
            }
            e.put("walls", walls);
            e.put("wallPatches", patches);
            a0 = p + 2;                                      // saute le terminateur (-1, 2 o)
            l.add(e);
        }
        return l;
    }

    /**
     * Liste des zones POTENTIELLEMENT VISIBLES d'une zone (ZoneT_PotVisibleZoneList_vw, tuples de
     * 8 octets terminés par une zone négative) : {@code zone} + {@code clip} = index (en MOTS) du
     * groupe de points de découpe dans {@code clips}, ou -1. C'est la base de la ligne de vue
     * (Objectmove.CanItBeSeen) — donc de la détection du joueur par les aliens.
     */
    private static List<Object> pvs(int zn) {
        List<Object> l = new ArrayList<>();
        int a3 = zn + Defs.ZoneT_PotVisibleZoneList_vw;
        for (int i = 0; i < 512 && Mem.w(a3) >= 0; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("zone", Mem.w(a3));
            e.put("clip", Mem.w(a3 + Defs.PVST_ClipID_w));
            l.add(e);
            a3 += Defs.PVST_SizeOf_l;
        }
        return l;
    }

    /**
     * Points de découpe des portails (Lvl_ClipsPtr_l) : suite de MOTS, un groupe par « clip »
     * = points gauches, -1, points droits, -2. On exporte jusqu'au dernier groupe référencé.
     */
    private static List<Object> clips(int nz, int zptrs) {
        int maxClip = -1;
        for (int z = 0; z < nz; z++) {
            int a3 = Mem.l(zptrs + z * 4) + Defs.ZoneT_PotVisibleZoneList_vw;
            for (int i = 0; i < 512 && Mem.w(a3) >= 0; i++) {
                maxClip = Math.max(maxClip, Mem.w(a3 + Defs.PVST_ClipID_w));
                a3 += Defs.PVST_SizeOf_l;
            }
        }
        List<Object> l = new ArrayList<>();
        if (maxClip < 0) {
            return l;
        }
        int base = Mem.l(ab3d2.bss.LevelBss.Lvl_ClipsPtr_l);
        int i = 0;
        int end = -1;
        for (; i < 1 << 20; i++) {                            // jusqu'au -2 qui clôt le dernier groupe
            int w = Mem.w(base + i * 2);
            l.add(w);
            if (w == -2 && i >= maxClip) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            l.clear();
        }
        return l;
    }

    /**
     * Les 10 MESSAGES du niveau (160 caractères chacun, en tête du .bin) : ce sont eux que le jeu
     * affiche au ramassage d'un objet ou à la mort d'un alien qui porte un {@code DisplayText}.
     */
    private static List<Object> messages() {
        List<Object> l = new ArrayList<>();
        int base = Mem.l(ab3d2.bss.LevelBss.Lvl_DataPtr_l);
        for (int i = 0; i < Defs.LVLT_MESSAGE_COUNT; i++) {
            l.add(PortReader.fixedStr(base + i * Defs.LVLT_MESSAGE_LENGTH, Defs.LVLT_MESSAGE_LENGTH));
        }
        return l;
    }

    /** Points de contrôle du niveau (8 octets : x, z, y, mot 3) — le maillage de navigation. */
    private static List<Object> controlPoints() {
        List<Object> l = new ArrayList<>();
        int base = Mem.l(ab3d2.bss.LevelBss.Lvl_ControlPointCoordsPtr_l);
        int n = Mem.uw(ab3d2.bss.LevelBss.Lvl_NumControlPoints_w);
        for (int i = 0; i < n; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("x", Mem.w(base + i * 8));
            e.put("z", Mem.w(base + i * 8 + 2));
            e.put("y", Mem.w(base + i * 8 + 4));
            e.put("w3", Mem.w(base + i * 8 + 6));
            l.add(e);
        }
        return l;
    }

    /**
     * Table de routage entre points de contrôle : {@code links[from * 100 + to]} = point SUIVANT
     * sur le chemin ($7F = pas de chemin ; bit 7 = « seulement en vue », cf. ONLYSEE).
     * Le PAS EST DE 100 quel que soit le nombre de points (GetNextCPt fait {@code muls #100}).
     */
    private static List<Object> links(int base) {
        List<Object> l = new ArrayList<>();
        int n = Mem.uw(ab3d2.bss.LevelBss.Lvl_NumControlPoints_w);
        for (int i = 0; i < n * 100; i++) {
            l.add(Mem.ub(base + i));
        }
        return l;
    }

    /** Instances d'objets : position (via point), zone, type, def (+ nom depuis le GLF). */
    private static List<Object> objects() {
        List<Object> l = new ArrayList<>();
        int glf = PortReader.glf();
        int objData = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
        int objPts = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
        int a0 = objData - Defs.ObjT_SizeOf_l;
        for (int k = 0; k < 2000; k++) {
            a0 += Defs.ObjT_SizeOf_l;
            int pidx = Mem.w(a0);
            if (pidx < 0) {
                break;
            }
            int typeID = Mem.ub(a0 + Defs.ObjT_TypeID_b);
            int defIdx = Mem.ub(a0 + Defs.EntT_Type_b);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("x", Mem.w(objPts + (pidx & 0xFFFF) * 8));
            o.put("z", Mem.w(objPts + (pidx & 0xFFFF) * 8 + 4));
            o.put("zone", Mem.w(a0 + Defs.ObjT_ZoneID_w));
            o.put("typeId", typeID);
            o.put("def", defIdx);
            // Mot @2 de l'enregistrement : luminosité propre à l'objet, ajoutée à la distance
            // pour l'éclairage des sprites (draw_BrightToAdd_w, Objdrawhires.java:549-553).
            o.put("bright", Mem.w(a0 + 2));
            // EntT_DoorsAndLiftsHeld_l : bits des portes/ascenseurs que cet objet déverrouille
            // (les CLÉS). Un objet qui en tient est toujours ramassable (Plr1_CollectItem).
            o.put("doorsHeld", Mem.l(a0 + Defs.EntT_DoorsAndLiftsHeld_l));
            o.put("upperZone", Mem.ub(a0 + Defs.ShotT_InUpperZone_b) != 0);
            // EntT_DisplayText_w : index du message de niveau a afficher (ramassage, mort) ; < 0 = aucun
            o.put("displayText", Mem.w(a0 + Defs.EntT_DisplayText_w));
            if (typeID == 0) {                               // ALIEN : état initial de l'entité
                o.put("hitPoints", Mem.ub(a0 + Defs.EntT_HitPoints_b));
                o.put("team", Mem.b(a0 + Defs.EntT_TeamNumber_b));
                o.put("mode", Mem.b(a0 + Defs.EntT_CurrentMode_b));
                o.put("controlPoint", Mem.w(a0 + Defs.EntT_CurrentControlPoint_w));
                o.put("targetControlPoint", Mem.w(a0 + Defs.EntT_TargetControlPoint_w));
                o.put("height", Mem.w(a0 + 4));              // 4(a0) : hauteur de dessin/collision
            }
            resolveGfx(o, glf, typeID, defIdx, a0);          // sprite (feuille+frame) OU modèle vectoriel
            String name = "";
            if (typeID == 1) {
                name = PortReader.fixedStr(glf + Defs.GLFT_ObjectNames_l + defIdx * 20, 20);
            } else if (typeID == 0) {
                name = PortReader.fixedStr(glf + Defs.GLFT_AlienNames_l + defIdx * 20, 20);
            }
            o.put("name", name);
            // Rendu additif (glow) : c'est le TYPE GRAPHIQUE de la définition qui le dit
            // (ODefT_GFXType_w == 2 = « glare »), pas le nom. Pour les aliens (typeId 0) on garde
            // le repli par le nom, leur table n'ayant pas ce champ.
            boolean additive = typeID == 1
                    ? Mem.w(glf + Defs.GLFT_ObjectDefs + defIdx * Defs.ODefT_SizeOf_l
                            + Defs.ODefT_GFXType_w) == 2
                    : name.toLowerCase().contains("glare");
            o.put("additive", additive);
            l.add(o);
        }
        return l;
    }
}
