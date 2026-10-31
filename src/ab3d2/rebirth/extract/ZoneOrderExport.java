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
 * Oracle de l'ORDRE DE DESSIN des secteurs.
 *
 * <p>Le jeu peint du plus loin au plus pres, sans tampon de profondeur : l'ordre fait l'image.
 * On place donc le joueur dans une suite de secteurs, on fait tourner {@code Zone_OrderZones} du
 * portage, et l'on note la table qu'il produit.
 *
 * <p>Le joueur est pose au POINT DE CONTROLE de chaque secteur — celui dont l'IA se sert pour
 * naviguer — parce qu'il est a l'interieur par construction, ce qu'un sommet du contour ne
 * garantit pas.
 *
 * <p>Le tri garde en memoire la derniere position traitee et ne recommence que si le joueur a
 * bouge d'assez. On remet cette memoire a zero avant chaque pose, faute de quoi la plupart des
 * relevés ne seraient que la copie du precedent.
 */
public final class ZoneOrderExport {

    /** Combien de secteurs on eprouve par niveau. */
    private static final int PAR_NIVEAU = 12;

    private ZoneOrderExport() {
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
                Map<String, Object> un = unCas(String.valueOf(c), z, zonePtr, x, zz);
                if (un != null) {
                    cas.add(un);
                    pris++;
                }
            }
            System.out.printf("  niveau %s : %d secteurs eprouves (sur %d)%n", c, pris, nz);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("zoneorder.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[zoneorder] " + cas.size() + " poses → " + f);
    }

    /** Une pose : on la pose, on trie, on note la table. */
    private static Map<String, Object> unCas(String letter, int zone, int zonePtr, int x, int z) {
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        // La liste des secteurs visibles depuis celui-ci : c'est elle que le tri ordonne.
        Mem.wl(ab3d2.bss.LevelBss.Lvl_ListOfGraphRoomsPtr_l,
                zonePtr + ab3d2.Defs.ZoneT_PotVisibleZoneList_vw);
        // La memoire du dernier tri, sans quoi la pose serait ignoree.
        Mem.wl(ab3d2.Orderzones.zone_LastPosition_vw, -1);

        try {
            ab3d2.Orderzones.Zone_OrderZones();
        } catch (Throwable t) {
            return null;
        }

        int base = ab3d2.bss.ZoneBss.Zone_FinalOrderTable_vw;
        int fin = Mem.l(ab3d2.bss.ZoneBss.Zone_EndOfListPtr_l);
        int n = Math.max(0, (fin - base) / 2);
        List<Object> ordre = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ordre.add(Mem.w(base + i * 2));
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("zone", zone);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("order", ordre);
        return cas;
    }
}
