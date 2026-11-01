package ab3d2.rebirth.extract;

import ab3d2.Mem;
import ab3d2.modules.draw.DrawSetClip;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle de la FENETRE DE DESSIN des secteurs.
 *
 * <p>Une fois l'ordre connu, chaque secteur visible n'a droit qu'a une tranche de l'ecran, que
 * les ouvertures traversees resserrent. On pose le joueur, on fait tourner le niveau, puis on
 * deroule pour chaque entree de la liste de visibilite le meme va-et-vient que la boucle de
 * rendu : {@code Draw_SetLeftClip} tant que les sommets de gauche se suivent, puis
 * {@code Draw_SetRightClip} pour ceux de droite.
 *
 * <p>La fenetre de depart est ici la vue ENTIERE. La boucle d'origine part d'une fenetre deja
 * resserree par les aretes dans le champ de vision ({@code Zone_SetupEdgeClipping}) ; en la
 * neutralisant on isole le seul maillon qu'on veut eprouver, et le relevé reste une entree
 * comme une autre pour qui voudra enchainer les deux.
 *
 * <p>Le joueur est pose au point de contrôle de chaque secteur, comme pour l'ordre de dessin :
 * il y est a l'interieur par construction.
 */
public final class ClipExport {

    /** Combien de secteurs on eprouve par niveau. */
    private static final int PAR_NIVEAU = 12;

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les orientations relevees : les quatre quadrants, plus deux qui ne tombent pas rond. */
    private static final int[] ANGLES = {0, 1024, 2048, 3072, 4096, 5555};

    private ClipExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_Energy_w, 191);
        Mem.ww(ab3d2.bss.PlayerBss.Plr2_Energy_w, 191);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_Keys_b, 0);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_Mouse_b, 0xFF);
        Mem.wb(ab3d2.bss.PlayerBss.Plr2_Keys_b, 0);
        Mem.wb(ab3d2.bss.PlayerBss.Plr2_Mouse_b, 0xFF);
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
                int cp = Mem.ub(zonePtr + ab3d2.Defs.ZoneT_ControlPoint_w);
                int x = Mem.w(coords + cp * 8);
                int zz = Mem.w(coords + cp * 8 + 2);
                for (int angle : ANGLES) {
                    for (int plein = 0; plein < 2; plein++) {
                        cas.add(unCas(String.valueOf(c), z, zonePtr, x, zz, angle, plein != 0));
                    }
                }
                pris++;
            }
            System.out.printf("  niveau %s : %d secteurs × %d angles × 2 vues (sur %d secteurs)%n",
                    c, pris, ANGLES.length, nz);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("clips.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[clips] " + cas.size() + " poses → " + f);
    }

    /** Une pose : on la pose, on tourne, on resserre chaque entree de la liste de visibilite. */
    private static Map<String, Object> unCas(String letter, int zone, int zonePtr, int x, int z,
            int angle, boolean pleinEcran) {
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);

        int sin = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + (angle & (TOUR - 2)));
        int cosAt = (angle + ab3d2.data.TablesData.COSINE_OFS) & (TOUR - 2);
        int cos = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + cosAt);
        Mem.ww(ab3d2.HireswallData.Vis_SinVal_w, sin);
        Mem.ww(ab3d2.HireswallData.Vis_CosVal_w, cos);
        Mem.wl(ab3d2.HiresData.xwobble, 0);

        // La vue encadree par le tableau de bord n'est pas qu'une fenetre plus petite : elle
        // change la correction d'aspect, donc les profondeurs, donc les sommets retenus.
        Mem.wb(ab3d2.bss.VidBss.Vid_FullScreen_b, pleinEcran ? 0xFF : 0);
        Mem.wb(ab3d2.bss.VidBss.Vid_DoubleWidth_b, 0);
        int largeur = pleinEcran ? ab3d2.Hires.FS_WIDTH : ab3d2.Hires.SMALL_WIDTH;
        Mem.ww(ab3d2.HiresData.Vid_RightX_w, largeur);
        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, largeur / 2);

        // Tous les points, et non le seul sous-ensemble visible : le relevé doit etre complet.
        int sauve = Mem.b(ab3d2.HiresData.draw_RenderMap_b);
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, 0xFF);
        ab3d2.modules.Transform.RotateLevelPts();
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, sauve);

        List<Object> entrees = new ArrayList<>();
        int pvs = zonePtr + ab3d2.Defs.ZoneT_PotVisibleZoneList_vw;
        while (Mem.w(pvs + ab3d2.Defs.PVST_Zone_w) >= 0) {
            entrees.add(uneEntree(pvs));
            pvs += ab3d2.Defs.PVST_SizeOf_l;
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("zone", zone);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("angle", angle);
        cas.put("fullScreen", pleinEcran);
        cas.put("entries", entrees);
        return cas;
    }

    /** Une entree de la liste de visibilite : le va-et-vient de draw_zone_graph.s, tel quel. */
    private static Map<String, Object> uneEntree(int pvs) {
        Mem.ww(ab3d2.HireswallData.Draw_LeftClip_w, 0);
        Mem.ww(ab3d2.HireswallData.Draw_RightClip_w, Mem.uw(ab3d2.HiresData.Vid_RightX_w));

        int clipID = Mem.w(pvs + ab3d2.Defs.PVST_ClipID_w);
        if (clipID >= 0) {
            int a0 = Mem.l(ab3d2.bss.LevelBss.Lvl_ClipsPtr_l) + clipID * 2;
            if (Mem.w(a0) >= 0) {
                a0 = DrawSetClip.Draw_SetLeftClip(a0);
                while (Mem.w(a0) >= 0) {
                    a0 = DrawSetClip.Draw_SetLeftClip(a0);
                }
            }
            a0 += 2;
            if (Mem.w(a0) >= 0) {
                a0 = DrawSetClip.Draw_SetRightClip(a0);
                while (Mem.w(a0) >= 0) {
                    a0 = DrawSetClip.Draw_SetRightClip(a0);
                }
            }
        }

        Map<String, Object> e = new LinkedHashMap<>();
        e.put("zone", Mem.w(pvs + ab3d2.Defs.PVST_Zone_w));
        e.put("clipID", clipID);
        e.put("left", Mem.w(ab3d2.HireswallData.Draw_LeftClip_w));
        e.put("right", Mem.w(ab3d2.HireswallData.Draw_RightClip_w));
        return e;
    }
}
