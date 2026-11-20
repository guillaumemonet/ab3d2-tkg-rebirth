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
 * Oracle du PVS PAR ARETE : qui se voit, et par quelle ouverture.
 *
 * <p>Le fichier de niveau dit quels secteurs sont potentiellement visibles depuis un secteur
 * donne, mais pas par ou. Le chargeur l'etablit lui-meme, une fois pour toutes : pour chaque
 * arete qui mene ailleurs, il descend recursivement le decor en ne franchissant que les aretes
 * auxquelles l'ouverture de depart fait face, et note ce qu'il atteint.
 *
 * <p>C'est une donnee derivee, immuable, et volumineuse : un octet par secteur potentiellement
 * visible et par ouverture, plus un mot de condition quand des portes sont en jeu. On la releve
 * en entier — seize niveaux, tous les secteurs — parce qu'un seul octet faux change ce que le
 * rendu croit voir.
 */
public final class EdgePvsExport {

    private EdgePvsExport() {
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

        List<Object> niveaux = new ArrayList<>();
        for (char c = 'a'; c <= 'p'; c++) {
            // Le chargeur d'origine ne remet a zero que les OCTETS PLEINS de la carte des
            // portes : `for (i = 0; i < Lvl_NumZones_w / 8; ++i)`. Les bits du dernier octet
            // survivent au changement de niveau, et un secteur passe alors pour une porte
            // parce qu'il en etait une dans le niveau precedent. On efface la carte en entier
            // avant chaque chargement, pour que le releve dise ce que le NIVEAU implique et
            // non dans quel ordre on l'a lu.
            for (int i = 0; i < ab3d2.Defs.LVL_MAX_ZONE_COUNT / 8; i++) {
                Mem.wb(ab3d2.bss.ZoneBss.Zone_DoorMap_vb + i, 0);
            }
            try {
                LevelExport.loadLevelForTools(c - 'a');
            } catch (Throwable t) {
                System.out.println("  niveau " + c + " : ÉCHEC " + t);
                continue;
            }
            niveaux.add(unNiveau(String.valueOf(c)));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("levels", niveaux);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("edgepvs.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[edgepvs] " + niveaux.size() + " niveaux → " + f);
    }

    private static Map<String, Object> unNiveau(String letter) {
        int nz = Mem.uw(ab3d2.bss.LevelBss.Lvl_NumZones_w);

        // La liste compacte des portes, dans l'ordre ou le chargeur les a retenues : c'est le
        // rang dans cette liste qui donne le bit de condition.
        List<Object> portes = new ArrayList<>();
        for (int i = 0; i < ab3d2.Defs.LVL_MAX_DOOR_ZONES; i++) {
            int z = Mem.w(ab3d2.bss.ZoneBss.Zone_DoorList_vw + 2 * i);
            if (z < 0) {
                break;
            }
            portes.add(z);
        }

        // La carte des portes, telle qu'elle est REELLEMENT en memoire : le chargeur ne remet a
        // zero que les octets pleins, et le dernier peut garder des bits du niveau precedent.
        List<Object> carte = new ArrayList<>();
        for (int i = 0; i < (nz + 7) / 8; i++) {
            carte.add(Mem.ub(ab3d2.bss.ZoneBss.Zone_DoorMap_vb + i));
        }

        List<Object> zones = new ArrayList<>(nz);
        int troporte = 0;
        for (int z = 0; z < nz; z++) {
            int zonePtr = Mem.l(Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l) + 4 * z);
            int hdr = Mem.l(Mem.l(ab3d2.bss.LevelBss.Lvl_ZEdgePVSHeaderPtrsPtr_l) + 4 * z);
            int listSize = Mem.w(hdr + ab3d2.Defs.ZEdgePVSHeaderT_ListSize_w);
            int edgeCount = Mem.w(hdr + ab3d2.Defs.ZEdgePVSHeaderT_EdgeCount_w);
            if (edgeCount > 10) {
                troporte++;
            }

            List<Object> aretes = new ArrayList<>(edgeCount);
            List<Object> debuts = new ArrayList<>(edgeCount);
            List<Object> fins = new ArrayList<>(edgeCount);
            for (int i = 0; i < edgeCount; i++) {
                int zei = hdr + ab3d2.Defs.ZEdgePVSHeaderT_EdgeInfoList_v
                        + i * ab3d2.Defs.ZEdgeInfoT_SizeOf_l;
                aretes.add(Mem.w(zei + ab3d2.Defs.ZEdgeInfoT_EdgeID_w));
                debuts.add(Mem.w(zei + ab3d2.Defs.ZEdgeInfoT_StartPointID_w));
                fins.add(Mem.w(zei + ab3d2.Defs.ZEdgeInfoT_EndPointID_w));
            }

            // L'ordre des secteurs dans la liste : c'est lui qui indexe les masques.
            List<Object> pvs = new ArrayList<>(listSize);
            int rec = zonePtr + ab3d2.Defs.ZoneT_PotVisibleZoneList_vw;
            for (int i = 0; i < listSize; i++) {
                pvs.add(Mem.w(rec + i * ab3d2.Defs.ZPVSRecordT_SizeOf_l
                        + ab3d2.Defs.ZPVSRecordT_ZoneID_w));
            }

            int masks = hdr + Mem.w(hdr + ab3d2.Defs.ZEdgePVSHeaderT_ZoneMaskOffset_w);
            StringBuilder vis = new StringBuilder(edgeCount * listSize * 2);
            for (int i = 0; i < edgeCount * listSize; i++) {
                vis.append(String.format("%02x", Mem.ub(masks + i)));
            }

            int doorOff = Mem.w(hdr + ab3d2.Defs.ZEdgePVSHeaderT_DoorMaskOffset_w);
            String cond = null;
            if (doorOff != 0) {
                StringBuilder d = new StringBuilder(edgeCount * listSize * 4);
                for (int i = 0; i < edgeCount * listSize; i++) {
                    d.append(String.format("%04x", Mem.uw(hdr + doorOff + i * 2)));
                }
                cond = d.toString();
            }

            Map<String, Object> une = new LinkedHashMap<>();
            une.put("listSize", listSize);
            une.put("edges", aretes);
            une.put("startPoints", debuts);
            une.put("endPoints", fins);
            une.put("pvs", pvs);
            une.put("vis", vis.toString());
            une.put("cond", cond);
            zones.add(une);
        }

        System.out.printf("  niveau %s : %d secteurs, %d porte(s)%s%n", letter, nz, portes.size(),
                troporte == 0 ? "" : ", " + troporte + " secteur(s) a plus de dix ouvertures");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", letter);
        out.put("doors", portes);
        out.put("doorMap", carte);
        out.put("zones", zones);
        return out;
    }
}
