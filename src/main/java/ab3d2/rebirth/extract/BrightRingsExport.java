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
 * Oracle des ANNEAUX DE LUMIERE : de quel cote vient la clarte, en seize directions.
 *
 * <p>Un objet en relief ne peut pas se contenter d'une luminosite : ses faces regardent
 * chacune ailleurs. Le jeu lui fabrique donc une boussole de seize rangs, refaite a chaque
 * objet, et c'est elle qu'on releve ici — les soixante-quatre octets, tels quels.
 *
 * <h2>Ce dont cela depend</h2>
 *
 * <p>De la table d'eclairage, qui change a chaque image : on la seme exactement comme le fait
 * l'oracle de l'eclairage — on laisse passer quelques images de clignotement, on recalcule
 * pour le secteur regarde, puis on pose quelques lumieres mobiles. La verification rejoue cette
 * meme semaille, ce qu'elle sait faire puisque l'eclairage est deja eprouve.
 *
 * <p>Et de la POSITION de l'objet, puisque tout l'exercice consiste a trouver sous quel angle
 * il voit chaque point du decor. On releve donc plusieurs positions par secteur : son point de
 * controle, trois ecarts proches, et un point lointain ou tous les angles se resserrent.
 *
 * <h2>Le dernier angle</h2>
 *
 * <p>{@code AngRet} est une variable globale, et un point de bord tombant EXACTEMENT sur
 * l'objet n'en calcule aucun : sa luminosite s'inscrit alors dans la direction du point
 * precedent. On la remet a zero avant chaque cas, pour que les deux cotes partent du meme
 * endroit ; sa persistance d'un point a l'autre, elle, reste eprouvee.
 */
public final class BrightRingsExport {

    /** Combien de secteurs on eprouve par niveau. */
    private static final int PAR_NIVEAU = 12;

    /** Combien d'images de clignotement on laisse passer. */
    private static final int IMAGES = 9;

    /** Les ecarts a la position du point de controle. */
    private static final int[][] ECARTS = {{0, 0}, {400, -300}, {-700, 500}, {4000, 4000},
        {-9000, 1200}, {60, 60}, {0, -25000}, {17000, -17000}};

    /** Dix points de bord par secteur, quatre luminosites chacun. */
    private static final int PAR_SECTEUR = 40;

    /** Les soixante-quatre octets de la boussole. */
    private static final int ANNEAUX = 64;

    private BrightRingsExport() {
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
                cas.add(unCas(String.valueOf(c), nz, zptrs, coords, z, zonePtr));
                pris++;
            }
            System.out.printf("  niveau %s : %d secteurs%n", c, pris);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("brightrings.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[brightrings] " + cas.size() + " secteurs → " + f);
    }

    private static Map<String, Object> unCas(String letter, int nz, int zptrs, int coords,
            int zone, int zonePtr) {
        // La meme semaille que l'oracle de l'eclairage, pour que la verification la rejoue.
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
        for (int n = 0; n < IMAGES; n++) {
            ab3d2.Newanims.brightanim();
        }
        ab3d2.Hires.computeZoneBrightness(zonePtr + ab3d2.Defs.ZoneT_PotVisibleZoneList_vw);

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

        // Les positions de l'objet : le point de controle du secteur, et quelques ecarts.
        int cp = Mem.ub(zonePtr + ab3d2.Defs.ZoneT_ControlPoint_w);
        int baseX = Mem.w(coords + cp * 8);
        int baseZ = Mem.w(coords + cp * 8 + 2);

        // Un point de bord du secteur, s'il en a un : s'y poser EXACTEMENT est le seul cas ou
        // aucun angle n'est calcule et ou le precedent ressert.
        int borne = Mem.l(ab3d2.bss.LevelBss.Lvl_ZoneBorderPointsPtr_l) + zone * 20;
        int pts = Mem.l(ab3d2.bss.LevelBss.Lvl_PointsPtr_l);
        List<int[]> ou = new ArrayList<>();
        for (int[] ecart : ECARTS) {
            ou.add(ecart);
        }
        for (int k = 0; k < 2; k++) {
            int num = Mem.w(borne + k * 2);
            if (num >= 0) {
                ou.add(new int[] {Mem.w(pts + num * 4) - baseX,
                    Mem.w(pts + num * 4 + 2) - baseZ});
            }
        }

        List<Object> poses = new ArrayList<>();
        for (int[] ecart : ou) {
            for (int haut = 0; haut <= 1; haut++) {
                int x = (short) (baseX + ecart[0]);
                int zz = (short) (baseZ + ecart[1]);
                Mem.ww(ab3d2.ObjectmoveData.AngRet, 0);
                Mem.ww(ab3d2.bss.DrawBss.Draw_CurrentZone_w, zone);
                Mem.wb(ab3d2.bss.DrawBss.draw_InUpperZone_b, haut != 0 ? 0xFF : 0);
                Mem.ww(ab3d2.bss.DrawBss.draw_Obj_XPos_w, x);
                Mem.ww(ab3d2.bss.DrawBss.draw_Obj_ZPos_w, zz);
                ab3d2.Objdrawhires.draw_CalcBrightRings();

                List<Object> anneaux = new ArrayList<>(ANNEAUX);
                for (int i = 0; i < ANNEAUX; i++) {
                    anneaux.add((int) (byte) Mem.ub(ab3d2.bss.DrawBss.draw_AngleBrights_vl + i));
                }
                Map<String, Object> une = new LinkedHashMap<>();
                une.put("x", x);
                une.put("z", zz);
                une.put("upper", haut != 0);
                une.put("rings", anneaux);
                poses.add(une);
            }
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("zone", zone);
        cas.put("frames", IMAGES);
        cas.put("lights", lumieres);
        cas.put("placed", poses);
        return cas;
    }
}
