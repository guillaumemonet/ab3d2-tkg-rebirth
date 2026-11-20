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
 * Oracle de la ROTATION DES CHOSES : où se trouve, vu de l'œil, chaque entité du niveau.
 *
 * <p>C'est le pendant de {@link XformExport} pour ce qui bouge — et ce n'est pas le même calcul.
 * Le décor garde son abscisse au huitième de bit près ; les choses n'en gardent que les seize
 * bits de poids fort avant de la remultiplier par cent vingt-huit. Le nombre est le même, mais
 * arrondi, et seul un relevé côte à côte le montre.
 *
 * <p>La boucle a deux particularités qui ne se devinent pas : elle SAUTE les entités
 * auxiliaires sans avancer dans la table des points — ce qui aligne les deux tables — et elle
 * met à ZÉRO le point de toute entité dont le secteur est négatif, c'est-à-dire retirée du
 * niveau. Il y en a soixante-trois par niveau, exactement, dans les seize.
 *
 * <p>Les poses font varier l'orientation, la position de l'œil et le balancement du pas, et
 * chacune est relevée dans les deux tailles de vue : la correction d'aspect de la profondeur
 * n'existe qu'en plein écran.
 */
public final class ObjXformExport {

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les angles relevés : huit directions, plus deux valeurs qui ne tombent pas rond. */
    private static final int[] ANGLES = {
        0, 1024, 2048, 3072, 4096, 5120, 6144, 7168, 333, 4777,
    };

    /** Les écarts appliqués à la pose de l'œil, pour varier les distances. */
    private static final int[][] ECARTS = {{0, 0}, {900, -400}, {-1700, 2300}};

    /** Les balancements du pas relevés. */
    private static final int[] WOBBLE = {0, 183};

    private ObjXformExport() {
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
            int x0 = Mem.w(ab3d2.bss.PlayerBss.Plr1_XOff_l);
            int z0 = Mem.w(ab3d2.bss.PlayerBss.Plr1_ZOff_l);
            int n = Mem.w(ab3d2.bss.LevelBss.Lvl_NumObjectPoints_w);
            int poses = 0;
            for (int[] e : ECARTS) {
                for (int angle : ANGLES) {
                    for (int wobble : WOBBLE) {
                        for (int plein = 1; plein >= 0; plein--) {
                            cas.add(unCas(String.valueOf(c), x0 + e[0], z0 + e[1], angle,
                                    wobble, plein != 0, n));
                            poses++;
                        }
                    }
                }
            }
            System.out.printf("  niveau %s : %d points d'objets, %d poses%n", c, n + 1, poses);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("objxform.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[objxform] " + cas.size() + " poses → " + f);
    }

    /** Une pose : on la pose, on tourne, on note. */
    private static Map<String, Object> unCas(String letter, int x, int z, int angle, int wobble,
            boolean pleinEcran, int n) {
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);

        int sin = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + (angle & (TOUR - 2)));
        int cosAt = (angle + ab3d2.data.TablesData.COSINE_OFS) & (TOUR - 2);
        int cos = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + cosAt);
        Mem.ww(ab3d2.HireswallData.Vis_SinVal_w, sin);
        Mem.ww(ab3d2.HireswallData.Vis_CosVal_w, cos);
        Mem.wl(ab3d2.HiresData.xwobble, wobble);

        Mem.wb(ab3d2.bss.VidBss.Vid_FullScreen_b, pleinEcran ? 0xFF : 0);
        Mem.wb(ab3d2.bss.VidBss.Vid_DoubleWidth_b, 0);

        ab3d2.modules.Transform.RotateObjectPts();

        List<Object> rx = new ArrayList<>();
        List<Object> rz = new ArrayList<>();
        List<Object> wx = new ArrayList<>();
        for (int i = 0; i <= n; i++) {
            rx.add(Mem.w(ab3d2.bss.TablesBss.ObjRotated_vl + i * 8));
            rz.add(Mem.w(ab3d2.bss.TablesBss.ObjRotated_vl + i * 8 + 2));
            wx.add(Mem.l(ab3d2.bss.TablesBss.ObjRotated_vl + i * 8 + 4));
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("angle", angle);
        cas.put("wobble", wobble);
        cas.put("full", pleinEcran);
        cas.put("points", n + 1);
        cas.put("rotX", rx);
        cas.put("rotZ", rz);
        cas.put("worldX", wx);
        return cas;
    }
}
