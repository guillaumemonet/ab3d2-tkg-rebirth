package ab3d2.rebirth.extract;

import ab3d2.Mem;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle des POINTS D'UN MODELE : tournes, projetes, eclaires.
 *
 * <p>C'est la premiere moitie du dessin d'un objet en relief, et elle se tient d'un bloc : la
 * grille d'eclairage fabriquee depuis les anneaux, la rotation des points autour de l'objet,
 * leur projection a l'ecran, et la luminosite que chacun prend dans la grille. Rien de tout
 * cela ne se verifie separement — la grille n'existe que pour la luminosite, et la luminosite
 * n'a de sens qu'avec les points.
 *
 * <h2>Ce qu'on seme</h2>
 *
 * <p>Chaque entite du niveau est transformee en MODELE : l'octet de demi-largeur passe a
 * {@code $ff}, qui est la marque, et les deux mots suivants nomment le modele et la pose. On
 * fait le tour des vingt-deux modeles et de leurs poses, pour que les vingt fichiers soient
 * tous lus et que les comptes de points different.
 *
 * <p>L'etage est seme aussi : c'est lui qui decide dans quelle moitie de la table d'eclairage
 * les anneaux vont puiser.
 *
 * <p>L'eclairage est seme comme l'oracle des anneaux le fait, et pour la meme raison : sans
 * lui, la grille serait plate et la luminosite des points ne dirait rien.
 */
public final class ModelPointsExport {

    /** Combien d'images de clignotement on laisse passer. */
    private static final int IMAGES = 9;

    /** Dix points de bord par secteur, quatre luminosites chacun. */
    private static final int PAR_SECTEUR = 40;

    /**
     * Les orientations du regard relevees, toutes PAIRES.
     *
     * <p>Un angle est un deplacement en OCTETS dans la table de sinus, dont les entrees sont
     * des mots : le jeu n'en produit jamais d'impair, et {@code AMOD_A} le garantit. Un angle
     * impair ferait lire un mot a cheval sur deux entrees, ce qui n'arrive pas.
     */
    private static final int[] ANGLES = {0, 2048, 4776};

    /** Les hauteurs d'oeil relevees. */
    private static final int[] HAUTEURS = {0, 60000};

    /**
     * On ne garde qu'une entite sur treize, et qu'un point de vue sur deux.
     *
     * <p>Sept millions de points tiendraient dans trois cents megaoctets de releve, et
     * n'apprendraient rien de plus : chaque modele est relu a des dizaines de positions, et
     * ce qui fait varier le calcul — la pose, l'orientation, l'etage, la distance — varie deja
     * d'une entite a l'autre. Les vingt-deux modeles et toutes leurs poses restent atteints.
     */
    private static final int UNE_SUR = 17;
    private static final int VUE_SUR = 2;

    /** Combien de modeles la base nomme. */
    private static final int MODELES = 22;

    private ModelPointsExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        ab3d2.Hires.Setup_ConstantTable();
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, 0);

        List<Object> cas = new ArrayList<>();
        Map<String, Object> champsParNiveau = new LinkedHashMap<>();
        int captures = 0;
        long points = 0;
        for (char c = 'a'; c <= 'p'; c++) {
            try {
                LevelExport.loadLevelForTools(c - 'a');
            } catch (Throwable t) {
                System.out.println("  niveau " + c + " : ÉCHEC " + t);
                continue;
            }
            int objets = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
            int n = 0;
            while (Mem.w(objets + n * ab3d2.Defs.ObjT_SizeOf_l) >= 0) {
                n++;
            }
            int zone = eclaire(c);
            seme(objets, n);
            champsParNiveau.put(String.valueOf(c), champs(objets, n));

            int ici = 0;
            int rang = 0;
            for (int[] vue : BitmapExport.pointsDeVue()) {
                if (rang++ % VUE_SUR != 0) {
                    continue;
                }
                for (int angle : ANGLES) {
                    tourne(vue[0], vue[1], angle);
                    for (int y : HAUTEURS) {
                        Map<String, Object> un = unCas(String.valueOf(c), zone, objets, n,
                                angle, y, vue[0], vue[1]);
                        cas.add(un);
                        for (Object o : (List<?>) un.get("models")) {
                            Object pts = ((Map<?, ?>) o).get("points");
                            if (pts != null) {
                                ici++;
                                points += ((List<?>) pts).size();
                            }
                        }
                    }
                }
            }
            captures += ici;
            System.out.printf("  niveau %s : %d entités, secteur %d, %d modèles relevés%n",
                    c, n, zone, ici);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("objects", champsParNiveau);
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("modelpoints.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[modelpoints] %d vues, %d modèles, %d points → %s%n",
                cas.size(), captures, points, f);
    }

    /** Les lumieres mobiles posees, relevees pour que la verification les rejoue. */
    private static final List<Object> LUMIERES = new ArrayList<>();

    /** Seme l'eclairage comme l'oracle des anneaux, et rend le secteur regarde. */
    private static int eclaire(char lettre) {
        LUMIERES.clear();
        int nz = Mem.uw(ab3d2.bss.LevelBss.Lvl_NumZones_w);
        int zptrs = Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l);
        int coords = Mem.l(ab3d2.bss.LevelBss.Lvl_ControlPointCoordsPtr_l);
        int zone = 0;
        while (zone < nz && Mem.l(zptrs + zone * 4) == 0) {
            zone++;
        }
        int zonePtr = Mem.l(zptrs + zone * 4);

        for (int i = 0; i < 256 * PAR_SECTEUR; i++) {
            Mem.ww(ab3d2.bss.TablesBss.CurrentPointBrights_vl + i * 2, 0);
        }
        for (int i = 0; i < 300 * 2; i++) {
            Mem.ww(ab3d2.bss.ZoneBss.Zone_BrightTable_vl + i * 2, 0);
        }
        for (int i = 0; i < 20; i++) {
            Mem.ww(ab3d2.bss.AnimBss.Anim_BrightTable_vw + i * 2, 0);
        }
        for (int i = 0; i < 7; i++) {
            Mem.wl(ab3d2.NewanimsData.anim_BrightessAnimPtrs_vl + i * 4,
                    Mem.l(ab3d2.NewanimsData.anim_BrightnessAnimStartPtrs_vl + i * 4));
        }
        for (int k = 0; k < IMAGES; k++) {
            ab3d2.Newanims.brightanim();
        }
        ab3d2.Hires.computeZoneBrightness(zonePtr + ab3d2.Defs.ZoneT_PotVisibleZoneList_vw);

        Mem.wb(ab3d2.NewanimsData.Anim_LightingEnabled_b, 0xFF);
        int at = zonePtr + ab3d2.Defs.ZoneT_PotVisibleZoneList_vw;
        int posees = 0;
        while (posees < 4) {
            int vis = Mem.w(at);
            if (vis < 0) {
                break;
            }
            at += ab3d2.Defs.PVST_SizeOf_l;
            int other = Mem.l(zptrs + (vis & 0xFFFF) * 4);
            if (other == 0) {
                continue;
            }
            int cp = Mem.ub(other + ab3d2.Defs.ZoneT_ControlPoint_w);
            int x = Mem.w(coords + cp * 8);
            int zz = Mem.w(coords + cp * 8 + 2);
            int y = Mem.w(coords + cp * 8 + 4);
            Mem.wl(ab3d2.bss.AnimBss.Anim_BrightY_l, y);
            boolean cone = (posees & 1) != 0;
            int bright = -20 - posees * 30;
            int ang = (posees * 2731) & 8190;
            if (cone) {
                ab3d2.Newanims.Anim_BrightenPointsAngle(bright, x, zz, vis, ang);
            } else {
                ab3d2.Newanims.anim_BrightenPoints(bright, x, zz, vis);
            }
            Map<String, Object> une = new LinkedHashMap<>();
            une.put("bright", bright);
            une.put("x", x);
            une.put("z", zz);
            une.put("zone", vis);
            une.put("y", y);
            une.put("angle", cone ? ang : -1);
            LUMIERES.add(une);
            posees++;
        }
        Mem.ww(ab3d2.bss.DrawBss.Draw_CurrentZone_w, zone);
        return zone;
    }

    /** Fait de chaque entite un MODELE, avec sa pose et son etage. */
    private static void seme(int objets, int n) {
        for (int e = 0; e < n; e++) {
            int o = objets + e * ab3d2.Defs.ObjT_SizeOf_l;
            Mem.ww(o + 2, ((e * 37) % 60 - 30) * 8);            // luminosite propre
            Mem.ww(o + 4, ((e * 23) % 400 - 200) * 16);         // hauteur
            Mem.wb(o + 6, 0xFF);                                // la marque du modele
            // Le modele suit le rang ECHANTILLONNE, sinon le sous-echantillonnage n'en
            // atteindrait qu'une poignee : la guepe, dont quatre parties designent des points
            // au-dela de son propre compte, ne serait jamais relevee.
            int modele = (e / UNE_SUR) % MODELES;
            int ptr = Mem.l(ab3d2.bss.DrawBss.Draw_PolyObjects_vl + modele * 4);
            int poses = ptr == 0 ? 1 : Mem.uw(ptr + 4);
            Mem.ww(o + 8, modele);
            Mem.ww(o + 10, poses == 0 ? 0 : (e / UNE_SUR * 3) % poses);
            Mem.ww(o + ab3d2.Defs.EntT_CurrentAngle_w, (e * 613) & 8190);
            Mem.wb(o + ab3d2.Defs.ShotT_InUpperZone_b, e % 5 == 2 ? 0xFF : 0);
        }
    }

    /** Les champs semes d'un niveau, releves UNE fois. */
    private static List<Object> champs(int objets, int n) {
        int pts = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
        List<Object> objs = new ArrayList<>();
        for (int e = 0; e < n; e++) {
            int o = objets + e * ab3d2.Defs.ObjT_SizeOf_l;
            int pt = Mem.uw(o);
            List<Object> un = new ArrayList<>();
            un.add(pt);                                         // rang du point
            un.add(Mem.w(o + 4));                               // hauteur
            un.add(Mem.uw(o + 8));                              // modele
            un.add(Mem.uw(o + 10));                             // pose
            un.add(Mem.uw(o + ab3d2.Defs.EntT_CurrentAngle_w)); // orientation
            un.add(Mem.ub(o + ab3d2.Defs.ShotT_InUpperZone_b) != 0 ? 1 : 0);
            un.add(Mem.w(pts + pt * 8));                        // abscisse du point
            un.add(Mem.w(pts + pt * 8 + 4));                    // profondeur du point
            objs.add(un);
        }
        return objs;
    }

    /** Pose l'oeil et fait tourner les points des choses. */
    private static void tourne(int x, int z, int angle) {
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);
        int tour = 8192;
        int sin = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + (angle & (tour - 2)));
        int cosAt = (angle + ab3d2.data.TablesData.COSINE_OFS) & (tour - 2);
        int cos = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + cosAt);
        Mem.ww(ab3d2.HireswallData.Vis_SinVal_w, sin);
        Mem.ww(ab3d2.HireswallData.Vis_CosVal_w, cos);
        Mem.wl(ab3d2.HiresData.xwobble, 0);
        Mem.wb(ab3d2.bss.VidBss.Vid_FullScreen_b, 0xFF);
        Mem.wb(ab3d2.bss.VidBss.Vid_DoubleWidth_b, 0);
        ab3d2.modules.Transform.RotateObjectPts();
    }

    /** Un cas : la vue posee, puis chaque modele releve seul. */
    private static Map<String, Object> unCas(String lettre, int zone, int objets, int n,
            int angle, int y, int x, int z) {
        int largeur = ab3d2.Hires.FS_WIDTH;
        int hauteur = ab3d2.Hires.FS_HEIGHT;
        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, largeur / 2);
        Mem.ww(ab3d2.HireswallData.Vid_CentreY_w, hauteur / 2);
        Mem.wl(ab3d2.HiresData.Plr_YOff_l, y);
        Mem.ww(ab3d2.HireswallData.draw_TopClip_w, 0);
        Mem.ww(ab3d2.HireswallData.draw_BottomClip_w, hauteur);
        Mem.ww(ab3d2.HireswallData.Draw_LeftClip_w, 0);
        Mem.ww(ab3d2.HireswallData.Draw_RightClip_w, largeur);

        List<Object> modeles = new ArrayList<>();
        for (int e = 0; e < n; e += UNE_SUR) {
            ab3d2.tools.ModelPoints.reset();
            ab3d2.tools.ModelPoints.stop = true;
            ab3d2.tools.ModelPoints.capturing = true;
            try {
                ab3d2.Objdrawhires.draw_Object(e);
            } finally {
                ab3d2.tools.ModelPoints.capturing = false;
            }
            if (!ab3d2.tools.ModelPoints.captured) {
                Map<String, Object> rien = new LinkedHashMap<>();
                rien.put("entity", e);
                modeles.add(rien);
                continue;
            }
            List<Object> pts = new ArrayList<>();
            for (int[] p : ab3d2.tools.ModelPoints.perPoint) {
                List<Object> un = new ArrayList<>();
                for (int v : p) {
                    un.add(v);
                }
                pts.add(un);
            }
            List<Object> parties = new ArrayList<>();
            for (int[] q : ab3d2.tools.ModelPoints.parts) {
                List<Object> une = new ArrayList<>();
                une.add(q[0]);
                une.add(q[1]);
                parties.add(une);
            }
            List<Object> grille = new ArrayList<>(256);
            for (int v : ab3d2.tools.ModelPoints.grid) {
                grille.add(v);
            }
            Map<String, Object> un = new LinkedHashMap<>();
            un.put("entity", e);
            un.put("sortIt", ab3d2.tools.ModelPoints.sortIt);
            un.put("onOff", ab3d2.tools.ModelPoints.onOff);
            un.put("grid", grille);
            un.put("parts", parties);
            un.put("points", pts);
            modeles.add(un);
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", lettre);
        cas.put("zone", zone);
        cas.put("frames", IMAGES);
        cas.put("lights", new ArrayList<>(LUMIERES));
        cas.put("x", x);
        cas.put("z", z);
        cas.put("angle", angle);
        cas.put("eyeY", y);
        cas.put("centreX", largeur / 2);
        cas.put("centreY", hauteur / 2);
        cas.put("models", modeles);
        return cas;
    }
}
