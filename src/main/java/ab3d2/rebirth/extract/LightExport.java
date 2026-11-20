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
 * Oracle de l'ECLAIRAGE : la table que le rendu consulte en chaque point du decor.
 *
 * <p>Trois choses s'y ajoutent, et le releve les prend dans l'ordre. D'abord les sept sequences
 * de clignotement avancent d'un certain nombre d'images — on en releve plusieurs, pour les
 * prendre a des endroits differents de leur boucle. Puis la table est refaite pour les secteurs
 * potentiellement visibles depuis un secteur donne. Enfin quelques lumieres mobiles sont
 * posees, les unes tout autour d'elles, les autres en cone.
 *
 * <p>On releve toute la table : les luminosites de secteur et les quarante valeurs de chaque
 * secteur touche. Un secteur reste « non touche » tant que rien n'y a ecrit, et c'est une
 * information en soi — le recalcul ne porte que sur ce que l'on regarde.
 *
 * <p>Les lumieres sont posees aux points de controle des secteurs, a des hauteurs et avec des
 * portees variees, pour que le cone, le debordement derriere la torche et la butee de
 * luminosite soient tous atteints.
 */
public final class LightExport {

    /** Combien de secteurs on eprouve par niveau. */
    private static final int PAR_NIVEAU = 4;

    /**
     * Combien d'images on laisse passer avant de relever, pour chaque pose.
     *
     * <p>La derniere depasse la plus courte des sept sequences, qui compte trente-sept valeurs :
     * sans elle, aucune ne reboucle et la facon dont elles rebouclent ne serait pas eprouvee.
     */
    private static final int[] IMAGES = {1, 9, 37, 60};

    /** Dix points de bord par secteur, quatre luminosites chacun. */
    private static final int PAR_SECTEUR = 40;

    private LightExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, 0);

        List<Object> cas = new ArrayList<>();
        for (char c = 'a'; c <= 'p'; c++) {
            try {
                LevelExport.loadLevelForTools(c - 'a');
            } catch (Throwable t) {
                System.out.println("  niveau " + c + " : ÉCHEC " + t);
                continue;
            }
            int nz = Mem.uw(ab3d2.bss.LevelBss.Lvl_NumZones_w);
            int zptrs = Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l);
            int coords = Mem.l(ab3d2.bss.LevelBss.Lvl_ControlPointCoordsPtr_l);
            int pris = 0;
            int pas = Math.max(1, nz / PAR_NIVEAU);
            for (int z = 0; z < nz && pris < PAR_NIVEAU; z += pas) {
                int zonePtr = Mem.l(zptrs + z * 4);
                if (zonePtr == 0) {
                    continue;
                }
                for (int images : IMAGES) {
                    cas.add(unCas(String.valueOf(c), nz, zptrs, coords, z, zonePtr, images));
                }
                pris++;
            }
            System.out.printf("  niveau %s : %d secteurs × %d images%n", c, pris, IMAGES.length);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("lights.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[lights] " + cas.size() + " poses → " + f);
    }

    private static Map<String, Object> unCas(String letter, int nz, int zptrs, int coords,
            int zone, int zonePtr, int images) {
        // Table nette : ce qui n'est pas recalcule doit rester a zero, et se voir.
        for (int i = 0; i < 256 * PAR_SECTEUR; i++) {
            Mem.ww(ab3d2.bss.TablesBss.CurrentPointBrights_vl + i * 2, 0);
        }
        for (int i = 0; i < 300 * 2; i++) {
            Mem.ww(ab3d2.bss.ZoneBss.Zone_BrightTable_vl + i * 2, 0);
        }
        for (int i = 0; i < 20; i++) {
            Mem.ww(ab3d2.bss.AnimBss.Anim_BrightTable_vw + i * 2, 0);
        }
        // Les sept sequences repartent de leur debut : le curseur de chacune est le pointeur
        // que brightanim avance, et la table voisine en garde l'origine.
        for (int i = 0; i < 7; i++) {
            Mem.wl(ab3d2.NewanimsData.anim_BrightessAnimPtrs_vl + i * 4,
                    Mem.l(ab3d2.NewanimsData.anim_BrightnessAnimStartPtrs_vl + i * 4));
        }
        for (int n = 0; n < images; n++) {
            ab3d2.Newanims.brightanim();
        }

        List<Object> sequences = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            sequences.add(Mem.w(ab3d2.bss.AnimBss.Anim_BrightTable_vw + i * 2));
        }

        ab3d2.Hires.computeZoneBrightness(zonePtr + ab3d2.Defs.ZoneT_PotVisibleZoneList_vw);

        List<Object> avant = tableau(nz);

        // Les lumieres mobiles, posees aux points de controle des secteurs voisins.
        Mem.wb(ab3d2.NewanimsData.Anim_LightingEnabled_b, 0xFF);
        List<Object> lumieres = new ArrayList<>();
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
            int bright = -20 - posees * 30;
            boolean cone = (posees & 1) != 0;
            int angle = (posees * 2731) & 8190;

            Mem.wl(ab3d2.bss.AnimBss.Anim_BrightY_l, y);
            if (cone) {
                ab3d2.Newanims.Anim_BrightenPointsAngle(bright, x, zz, vis, angle);
            } else {
                ab3d2.Newanims.anim_BrightenPoints(bright, x, zz, vis);
            }
            Map<String, Object> une = new LinkedHashMap<>();
            une.put("bright", bright);
            une.put("x", x);
            une.put("z", zz);
            une.put("zone", vis);
            une.put("y", y);
            une.put("angle", cone ? angle : -1);
            lumieres.add(une);
            posees++;
        }

        List<Object> secteurs = tableau(nz);

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("zone", zone);
        cas.put("frames", images);
        cas.put("flicker", sequences);
        cas.put("lights", lumieres);
        cas.put("before", avant);
        cas.put("zones", secteurs);
        return cas;
    }

    /** La table, secteur par secteur : on ne garde que ceux ou quelque chose a ete ecrit. */
    private static List<Object> tableau(int nz) {
        List<Object> secteurs = new ArrayList<>();
        for (int z = 0; z < nz; z++) {
            int bas = Mem.w(ab3d2.bss.ZoneBss.Zone_BrightTable_vl + z * 4);
            int haut = Mem.w(ab3d2.bss.ZoneBss.Zone_BrightTable_vl + z * 4 + 2);
            List<Object> points = new ArrayList<>(PAR_SECTEUR);
            boolean vide = bas == 0 && haut == 0;
            for (int i = 0; i < PAR_SECTEUR; i++) {
                int v = Mem.w(ab3d2.bss.TablesBss.CurrentPointBrights_vl
                        + (z * PAR_SECTEUR + i) * 2);
                points.add(v);
                vide = vide && v == 0;
            }
            if (vide) {
                continue;
            }
            Map<String, Object> un = new LinkedHashMap<>();
            un.put("zone", z);
            un.put("lower", bas);
            un.put("upper", haut);
            un.put("points", points);
            secteurs.add(un);
        }
        return secteurs;
    }
}
