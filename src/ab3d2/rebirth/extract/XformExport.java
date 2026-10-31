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
 * Oracle de la TRANSFORMATION DE VUE : ce que le portage calcule, pour quelques poses connues.
 *
 * <p>Les autres extracteurs relèvent des données ; celui-ci relève un CALCUL. On place le joueur
 * à un endroit et sous un angle choisis, on fait tourner {@code RotateLevelPts}, et on note pour
 * chaque point du niveau ses coordonnées tournées et son abscisse à l'écran.
 *
 * <p>C'est ce qu'il faut pour vérifier un rastériseur écrit ailleurs : la projection est le
 * premier maillon, et une erreur d'un bit s'y propage à tout le reste. Les poses sont prises
 * régulièrement sur le tour complet, pour que les quatre quadrants et les cas limites (point
 * derrière la caméra, division par un z' nul) soient tous rencontrés.
 */
public final class XformExport {

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les angles relevés : huit directions, plus deux valeurs qui ne tombent pas rond. */
    private static final int[] ANGLES = {
        0, 1024, 2048, 3072, 4096, 5120, 6144, 7168, 333, 4777,
    };

    private XformExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        // Le meme amorcage que l'extracteur des niveaux : sans les assets ni Sys_Init, le
        // prologue du niveau n'aboutit pas et les tables restent vides.
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
            int lvl = c - 'a';
            try {
                LevelExport.loadLevelForTools(lvl);
            } catch (Throwable t) {
                System.out.println("  niveau " + c + " : ÉCHEC " + t);
                continue;
            }
            // Le départ du joueur : une pose qui existe vraiment dans le niveau.
            int x = Mem.w(ab3d2.bss.PlayerBss.Plr1_XOff_l);
            int z = Mem.w(ab3d2.bss.PlayerBss.Plr1_ZOff_l);
            for (int angle : ANGLES) {
                for (int plein = 0; plein < 2; plein++) {
                    cas.add(unCas(String.valueOf(c), x, z, angle, plein != 0));
                }
            }
            System.out.printf("  niveau %s : %d poses%n", c, ANGLES.length * 2);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("xform.json");
        String json = new GsonBuilder().disableHtmlEscaping().create().toJson(root);
        Files.writeString(f, json);
        System.out.println("[xform] " + cas.size() + " poses → " + f);
    }

    /** Une pose : on la pose, on tourne, on note. */
    private static Map<String, Object> unCas(String letter, int x, int z, int angle,
            boolean pleinEcran) {
        // La vue suit le joueur : on écrit la pose là où le rendu la lit.
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);

        int sin = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + (angle & (TOUR - 2)));
        int cosAt = (angle + ab3d2.data.TablesData.COSINE_OFS) & (TOUR - 2);
        int cos = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + cosAt);
        Mem.ww(ab3d2.HireswallData.Vis_SinVal_w, sin);
        Mem.ww(ab3d2.HireswallData.Vis_CosVal_w, cos);
        Mem.wl(ab3d2.HiresData.xwobble, 0);

        // Plein écran ou vue encadrée : la largeur et le rapport d'aspect en dépendent.
        Mem.wb(ab3d2.bss.VidBss.Vid_FullScreen_b, pleinEcran ? 0xFF : 0);
        Mem.wb(ab3d2.bss.VidBss.Vid_DoubleWidth_b, 0);
        int largeur = pleinEcran ? ab3d2.Hires.FS_WIDTH : ab3d2.Hires.SMALL_WIDTH;
        Mem.ww(ab3d2.HiresData.Vid_RightX_w, largeur);
        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, largeur / 2);

        // draw_RenderMap_b non nul : on fait tourner TOUS les points, pas le sous-ensemble
        // visible — même arithmétique, mais un relevé complet.
        int sauve = Mem.b(ab3d2.HiresData.draw_RenderMap_b);
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, 0xFF);
        ab3d2.modules.Transform.RotateLevelPts();
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, sauve);

        int n = Mem.w(ab3d2.bss.LevelBss.Lvl_NumPoints_w) + 1;
        List<Object> rx = new ArrayList<>(n);
        List<Object> rz = new ArrayList<>(n);
        List<Object> sx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rx.add(Mem.l(ab3d2.bss.TablesBss.Rotated_vl + i * 8));
            rz.add(Mem.l(ab3d2.bss.TablesBss.Rotated_vl + i * 8 + 4));
            sx.add(Mem.w(ab3d2.bss.TablesBss.OnScreen_vl + i * 2));
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("angle", angle);
        cas.put("fullScreen", pleinEcran);
        cas.put("sin", sin);
        cas.put("cos", cos);
        cas.put("points", n);
        cas.put("rotX", rx);
        cas.put("rotZ", rz);
        cas.put("screenX", sx);
        return cas;
    }
}
