package ab3d2.rebirth.extract;

import ab3d2.Mem;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle du TRI DES CHOSES : lesquelles dessiner dans ce secteur, et dans quel ordre.
 *
 * <p>Le rendu n'a pas de tampon de profondeur : les choses se peignent de la plus loin à la
 * plus proche. Ce qu'on relève ici est la table d'insertion telle qu'elle est au moment où le
 * dessin va la parcourir — quatre-vingts cases, vides comprises. La sonde ARRÊTE
 * {@code Draw_Objects} à cet endroit : rien n'est dessiné, et le relevé ne dépend d'aucune des
 * machineries qui viennent après.
 *
 * <p>Le filtre ne lit PAS le secteur que la simulation tient à jour, mais une copie que le jeu
 * ne rafraîchit qu'une image sur cinq. On la pose ici comme {@code DOALLANIMS} la pose — pour
 * les seules entités vivantes, les autres gardant ce qu'elles avaient — et on l'EXPORTE, de
 * sorte que la vérification reçoive exactement les mêmes entrées.
 *
 * <p>Un cas par niveau force toutes les entités dans un même secteur. Ce n'est pas une pose du
 * jeu : c'est le seul moyen d'atteindre le débordement de la table de quatre-vingts, que les
 * niveaux publiés n'atteignent jamais, et de voir tomber la chose la plus proche.
 */
public final class SpriteOrderExport {

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les orientations relevées. */
    private static final int[] ANGLES = {0, 2048, 4777};

    /** Combien de secteurs peuplés relever par niveau. */
    private static final int SECTEURS = 6;

    /** Les deux étages, tels octets. */
    private static final int[] ETAGES = {0, 0xFF};

    private SpriteOrderExport() {
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

        // Le mot que Draw_Objects lit en entrée : il ne décide que des bornes verticales, dont
        // le tri n'a cure, mais la routine le lit et il faut qu'il soit quelque part.
        int programme = Mem.alloc(2);
        Mem.ww(programme, 2);

        List<Object> cas = new ArrayList<>();
        for (char c = 'a'; c <= 'p'; c++) {
            try {
                LevelExport.loadLevelForTools(c - 'a');
            } catch (Throwable t) {
                System.out.println("  niveau " + c + " : ÉCHEC " + t);
                continue;
            }
            int objets = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
            int n = compteEntites(objets);

            // La copie de secteur, posée comme DOALLANIMS la pose.
            for (int e = 0; e < n; e++) {
                int zone = Mem.w(objets + e * ab3d2.Defs.ObjT_SizeOf_l + ab3d2.Defs.ObjT_ZoneID_w);
                if (zone >= 0) {
                    Mem.ww(objets + e * ab3d2.Defs.ObjT_SizeOf_l + ab3d2.Defs.EntT_ZoneID_w, zone);
                }
            }
            List<Integer> secteurs = secteursPeuples(objets, n);
            int x0 = Mem.w(ab3d2.bss.PlayerBss.Plr1_XOff_l);
            int z0 = Mem.w(ab3d2.bss.PlayerBss.Plr1_ZOff_l);

            int poses = 0;
            for (int angle : ANGLES) {
                for (int plein = 1; plein >= 0; plein--) {
                    tourne(x0, z0, angle, plein != 0);
                    for (int zone : secteurs) {
                        for (int etage : ETAGES) {
                            cas.add(unCas(String.valueOf(c), objets, n, programme, zone, etage,
                                    0, ab3d2.Hires.FS_WIDTH, angle, plein != 0, x0, z0));
                            poses++;
                        }
                    }
                    // La fenêtre large d'un seul pixel : la routine sort sans rien ranger.
                    cas.add(unCas(String.valueOf(c), objets, n, programme, secteurs.get(0), 0,
                            100, 101, angle, plein != 0, x0, z0));
                    poses++;
                }
            }

            // Le cas qui déborde : tout le monde dans le même secteur. On ne touche PAS à
            // l'octet d'étage — c'est le seul endroit du corpus où l'entité du niveau n qui
            // porte la valeur 1, ni zéro ni deux cent cinquante-cinq, est mise à l'épreuve.
            int entasse = 1;
            for (int e = 0; e < n; e++) {
                Mem.ww(objets + e * ab3d2.Defs.ObjT_SizeOf_l + ab3d2.Defs.EntT_ZoneID_w, entasse);
            }
            tourne(x0, z0, 0, true);
            for (int etage : ETAGES) {
                cas.add(unCas(String.valueOf(c), objets, n, programme, entasse, etage, 0,
                        ab3d2.Hires.FS_WIDTH, 0, true, x0, z0));
                poses++;
            }

            System.out.printf("  niveau %s : %d entités, %d secteurs peuplés, %d poses%n",
                    c, n, secteurs.size(), poses);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("spriteorder.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[spriteorder] " + cas.size() + " poses → " + f);
    }

    /** Combien d'entités la table en porte, terminateur non compris. */
    private static int compteEntites(int objets) {
        int n = 0;
        while (Mem.w(objets + n * ab3d2.Defs.ObjT_SizeOf_l) >= 0) {
            n++;
        }
        return n;
    }

    /** Les secteurs les plus peuplés, plus un qui ne l'est pas du tout. */
    private static List<Integer> secteursPeuples(int objets, int n) {
        Map<Integer, Integer> combien = new HashMap<>();
        for (int e = 0; e < n; e++) {
            int z = Mem.w(objets + e * ab3d2.Defs.ObjT_SizeOf_l + ab3d2.Defs.EntT_ZoneID_w);
            combien.merge(z, 1, Integer::sum);
        }
        List<Integer> out = new ArrayList<>(combien.keySet());
        out.sort((p, q) -> combien.get(q) - combien.get(p));
        while (out.size() > SECTEURS) {
            out.remove(out.size() - 1);
        }
        int vide = 0;
        while (combien.containsKey(vide)) {
            vide++;
        }
        out.add(vide);
        return out;
    }

    /** Pose l'œil et fait tourner les points des choses. */
    private static void tourne(int x, int z, int angle, boolean pleinEcran) {
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);
        int sin = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + (angle & (TOUR - 2)));
        int cosAt = (angle + ab3d2.data.TablesData.COSINE_OFS) & (TOUR - 2);
        int cos = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + cosAt);
        Mem.ww(ab3d2.HireswallData.Vis_SinVal_w, sin);
        Mem.ww(ab3d2.HireswallData.Vis_CosVal_w, cos);
        Mem.wl(ab3d2.HiresData.xwobble, 0);
        Mem.wb(ab3d2.bss.VidBss.Vid_FullScreen_b, pleinEcran ? 0xFF : 0);
        Mem.wb(ab3d2.bss.VidBss.Vid_DoubleWidth_b, 0);
        ab3d2.modules.Transform.RotateObjectPts();
    }

    /** Un cas : on pose le secteur, l'étage et la fenêtre, on trie, on relève la table. */
    private static Map<String, Object> unCas(String letter, int objets, int n, int programme,
            int zone, int etage, int gauche, int droite, int angle, boolean pleinEcran,
            int x, int z) {
        Mem.ww(ab3d2.bss.DrawBss.Draw_CurrentZone_w, zone);
        Mem.wb(ab3d2.bss.DrawBss.Draw_DoUpper_b, etage);
        Mem.ww(ab3d2.HireswallData.Draw_LeftClip_w, gauche);
        Mem.ww(ab3d2.HireswallData.Draw_RightClip_w, droite);

        ab3d2.tools.ObjectOrder.reset();
        ab3d2.tools.ObjectOrder.capturing = true;
        ab3d2.Objdrawhires.Draw_Objects(programme);
        ab3d2.tools.ObjectOrder.capturing = false;

        List<Object> profondeurs = new ArrayList<>();
        List<Object> rangs = new ArrayList<>();
        for (int[] s : ab3d2.tools.ObjectOrder.slots) {
            profondeurs.add(s[0]);
            rangs.add(s[1]);
        }

        List<Object> drawZones = new ArrayList<>();
        List<Object> etages = new ArrayList<>();
        for (int e = 0; e < n; e++) {
            drawZones.add(Mem.w(objets + e * ab3d2.Defs.ObjT_SizeOf_l + ab3d2.Defs.EntT_ZoneID_w));
            etages.add(Mem.ub(objets + e * ab3d2.Defs.ObjT_SizeOf_l
                    + ab3d2.Defs.ShotT_InUpperZone_b));
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("angle", angle);
        cas.put("full", pleinEcran);
        cas.put("zone", zone);
        cas.put("upper", etage);
        cas.put("leftClip", gauche);
        cas.put("rightClip", droite);
        cas.put("drawZones", drawZones);
        cas.put("uppers", etages);
        cas.put("depths", profondeurs);
        cas.put("ranks", rangs);
        return cas;
    }
}
