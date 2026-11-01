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
 * Oracle des OUVERTURES VISIBLES et de la tranche d'ecran qu'elles occupent.
 *
 * <p>C'est le seul endroit du mecanisme qui depende de l'angle du regard : le PVS par arete est
 * fige, mais le choix des ouvertures qu'on retient change a chaque image. On pose donc le
 * joueur, on l'oriente, et l'on note ce que {@code Zone_CheckVisibleEdges} retient, ce que la
 * reunion des listes donne, et la tranche que {@code Zone_SetupEdgeClipping} en tire.
 *
 * <p>Chaque pose est relevee avec les portes TOUTES FERMEES puis TOUTES OUVERTES : c'est la
 * seule facon de voir si les conditions de porte sont lues dans le bon sens, puisqu'un etat
 * intermediaire ne distinguerait pas une condition ignoree d'une condition satisfaite.
 */
public final class VisibleEdgesExport {

    /** Combien de secteurs on eprouve par niveau. */
    private static final int PAR_NIVEAU = 10;

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les orientations relevees : huit directions, plus deux qui ne tombent pas rond. */
    private static final int[] ANGLES = {0, 1024, 2048, 3072, 4096, 5120, 6144, 7168, 777, 3333};

    /** Portes toutes fermees, puis toutes ouvertes. */
    private static final int[] ETATS_PORTES = {0x0000, 0xFFFF};

    private VisibleEdgesExport() {
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
            // Comme pour le PVS par arete : la carte des portes est effacee en entier, faute de
            // quoi des bits du niveau precedent y survivent.
            for (int i = 0; i < ab3d2.Defs.LVL_MAX_ZONE_COUNT / 8; i++) {
                Mem.wb(ab3d2.bss.ZoneBss.Zone_DoorMap_vb + i, 0);
            }
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
                    for (int portes : ETATS_PORTES) {
                        cas.add(unCas(String.valueOf(c), z, zonePtr, x, zz, angle, portes));
                    }
                }
                pris++;
            }
            System.out.printf("  niveau %s : %d secteurs × %d angles × %d etats de portes%n",
                    c, pris, ANGLES.length, ETATS_PORTES.length);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("visedges.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[visedges] " + cas.size() + " poses → " + f);
    }

    private static Map<String, Object> unCas(String letter, int zone, int zonePtr, int x, int z,
            int angle, int portes) {
        // La vue, pour la projection.
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);
        // Le point de vue du tri des ouvertures, qui est lu AILLEURS : dans le joueur 1.
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_XOff_l, x << 16);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_ZOff_l, z << 16);

        int sin = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + (angle & (TOUR - 2)));
        int cosAt = (angle + ab3d2.data.TablesData.COSINE_OFS) & (TOUR - 2);
        int cos = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + cosAt);
        Mem.ww(ab3d2.HireswallData.Vis_SinVal_w, sin);
        Mem.ww(ab3d2.HireswallData.Vis_CosVal_w, cos);
        Mem.wl(ab3d2.HiresData.xwobble, 0);

        Mem.wb(ab3d2.bss.VidBss.Vid_FullScreen_b, 0xFF);
        Mem.wb(ab3d2.bss.VidBss.Vid_DoubleWidth_b, 0);
        Mem.ww(ab3d2.HiresData.Vid_RightX_w, ab3d2.Hires.FS_WIDTH);
        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, ab3d2.Hires.FS_WIDTH / 2);

        int sauve = Mem.b(ab3d2.HiresData.draw_RenderMap_b);
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, 0xFF);
        ab3d2.modules.Transform.RotateLevelPts();
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, sauve);

        Mem.wl(ab3d2.bss.LevelBss.Lvl_ListOfGraphRoomsPtr_l,
                zonePtr + ab3d2.Defs.ZoneT_PotVisibleZoneList_vw);
        Mem.ww(ab3d2.bss.ZoneBss.Zone_CurrentDoorState_w, portes);

        ab3d2.c.ZoneEdgePvs.Zone_CheckVisibleEdges();

        int hdr = Mem.l(Mem.l(ab3d2.bss.LevelBss.Lvl_ZEdgePVSHeaderPtrsPtr_l) + 4 * zone);
        int listSize = Mem.w(hdr + ab3d2.Defs.ZEdgePVSHeaderT_ListSize_w);
        StringBuilder masque = new StringBuilder(listSize * 2);
        for (int i = 0; i < listSize; i++) {
            masque.append(String.format("%02x", Mem.ub(ab3d2.bss.ZoneBss.Zone_PVSMask_vb + i)));
        }

        int visJoins = Mem.w(ab3d2.bss.ZoneBss.Zone_VisJoins_w);
        List<Object> sommets = new ArrayList<>(visJoins * 2);
        for (int i = 0; i < visJoins * 2; i++) {
            sommets.add(Mem.w(ab3d2.bss.ZoneBss.Zone_EdgePointIndexes_vw + i * 2));
        }

        // La tranche d'ecran, pour un secteur QUELCONQUE puis pour celui ou l'on se tient : le
        // jeu rend toute la largeur au second, puisqu'on n'y regarde a travers rien.
        Mem.ww(ab3d2.bss.DrawBss.Draw_CurrentZone_w, -1);
        ab3d2.c.ZoneEdgePvs.Zone_SetupEdgeClipping();
        int autreL = Mem.w(ab3d2.bss.DrawBss.Draw_ZoneClipL_w);
        int autreR = Mem.w(ab3d2.bss.DrawBss.Draw_ZoneClipR_w);

        Mem.ww(ab3d2.bss.DrawBss.Draw_CurrentZone_w, zone);
        ab3d2.c.ZoneEdgePvs.Zone_SetupEdgeClipping();
        int siensL = Mem.w(ab3d2.bss.DrawBss.Draw_ZoneClipL_w);
        int siensR = Mem.w(ab3d2.bss.DrawBss.Draw_ZoneClipR_w);

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("zone", zone);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("angle", angle);
        cas.put("doors", portes);
        cas.put("visJoinMask", Mem.uw(ab3d2.bss.ZoneBss.Zone_VisJoinMask_w));
        cas.put("visJoins", visJoins);
        cas.put("totJoins", Mem.w(ab3d2.bss.ZoneBss.Zone_TotJoins_w));
        cas.put("points", sommets);
        cas.put("mask", masque.toString());
        cas.put("spanLeft", autreL);
        cas.put("spanRight", autreR);
        cas.put("ownLeft", siensL);
        cas.put("ownRight", siensR);
        return cas;
    }
}
