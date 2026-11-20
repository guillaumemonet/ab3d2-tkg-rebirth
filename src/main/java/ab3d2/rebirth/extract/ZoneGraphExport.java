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
 * Oracle de la MISE EN SCÈNE : ce que la boucle maîtresse décide de dessiner, dans l'ordre.
 *
 * <p>C'est le seul relevé qui fasse tourner la vraie boucle du jeu de bout en bout. Les
 * oracles précédents contournaient tous ce trou : ils posaient eux-mêmes un secteur, des
 * bornes et une luminosité, puis appelaient une routine de dessin. On éprouvait les pixels en
 * tenant pour acquis ce qui les commande. Ici c'est l'inverse : on ne relève AUCUN pixel, mais
 * la suite complète des décisions.
 *
 * <p>Chaque commande donne quatorze valeurs : le secteur, son étage, sa luminosité, la fenêtre
 * horizontale après resserrement, le haut et le bas de la pièce, les quatre bornes du plan
 * d'eau, le fait d'être dans le secteur du joueur, le code de la commande, et l'offset de son
 * record dans le fichier d'habillage — qui dit DE QUEL mur ou de QUELLE surface il s'agit.
 *
 * <p>Une pose = un secteur de départ, un point de contrôle, un angle. Le relevé suit alors
 * tout ce que le joueur verrait depuis là.
 */
public final class ZoneGraphExport {

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les orientations relevées : trois quarts de tour et un angle qui ne tombe pas juste. */
    private static final int[] ANGLES = {0, 2048, 4096, 6144, 3331};

    /** Combien de secteurs de départ par niveau. */
    private static final int SECTEURS = 6;

    /** Les hauteurs d'œil relevées : sous le plafond du bas, puis très haut. */
    private static final int[] HAUTEURS = {0, -400 * 1024};

    /**
     * Les poses NOMMÉES : des points de vue relevés ailleurs, qu'on veut éprouver tels quels.
     *
     * <p>Les six premières sont les images maigres que {@code gameTest} signale dans le niveau
     * n, et la septième — {@code n/904} — est la seule image NOIRE de tout le harnais : zéro
     * pixel posé, alors que douze secteurs ont été parcourus. Les poses de ce fichier sont
     * tirées de points de contrôle et ne tombent jamais là ; celles-ci viennent d'une partie.
     *
     * <p>Chaque ligne : niveau, secteur, x, z, angle, hauteur d'œil.
     */
    private static final int[][] POSES = {
        {13, 10, -999, 1364, 5000, -79794},            // n/535
        {13, 10, -1120, 1345, 4930, -79390},           // n/572
        {13, 11, -1132, 1300, 5130, -77344},           // n/579
        {13, 11, -1132, 1294, 5170, -76966},           // n/580
        {13, 11, -1132, 1288, 5210, -76576},           // n/581
        {13, 11, -1132, 1282, 5250, -76178},           // n/582
        {13, 11, -1122, 1284, 232, -75978},            // n/904 — l'image NOIRE
        {13, 11, -1121, 1294, 232, -76178},            // n/905
        {13, 11, -1119, 1304, 232, -76772},            // n/906
        {13, 11, -1118, 1315, 232, -77344},            // n/907
        {13, 11, -1117, 1325, 232, -77882},            // n/908
        {13, 11, -1115, 1336, 232, -78376},            // n/909
    };

    private ZoneGraphExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        ab3d2.Hires.Setup_ConstantTable();
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_Energy_w, 191);
        Mem.ww(ab3d2.bss.PlayerBss.Plr2_Energy_w, 191);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_Keys_b, 0);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_Mouse_b, 0xFF);
        Mem.wb(ab3d2.bss.PlayerBss.Plr2_Keys_b, 0);
        Mem.wb(ab3d2.bss.PlayerBss.Plr2_Mouse_b, 0xFF);
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, 0);
        // La borne qui ferme la table d'ordre par le BAS : la boucle maîtresse la parcourt à
        // rebours et s'arrête sur le premier mot négatif. Sans elle, elle recule dans la
        // mémoire voisine — pleine de zéros — et redessine le secteur zéro sans fin. Le jeu la
        // pose une fois au démarrage (`not.w` d'un mot nul).
        Mem.ww(ab3d2.bss.ZoneBss.Zone_OrderTable_Barrier_w, 0xFFFF);

        List<Object> cas = new ArrayList<>();
        long commandes = 0;
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
            int ici = 0;
            for (int i = 0; i < SECTEURS; i++) {
                int z = (int) ((long) i * nz / SECTEURS);
                int zonePtr = Mem.l(zptrs + z * 4);
                if (zonePtr == 0) {
                    continue;
                }
                int cp = Mem.ub(zonePtr + ab3d2.Defs.ZoneT_ControlPoint_w);
                int x = Mem.w(coords + cp * 8);
                int zz = Mem.w(coords + cp * 8 + 2);
                for (int angle : ANGLES) {
                    for (int y : HAUTEURS) {
                        Map<String, Object> un = unCas(String.valueOf(c), z, zonePtr, x, zz,
                                angle, y);
                        if (un == null) {
                            continue;
                        }
                        cas.add(un);
                        ici += ((List<?>) un.get("plan")).size();
                    }
                }
            }
            // Les poses nommées de ce niveau.
            for (int[] p : POSES) {
                if (p[0] != c - 'a') {
                    continue;
                }
                int zonePtr = Mem.l(zptrs + p[1] * 4);
                if (zonePtr == 0) {
                    continue;
                }
                Map<String, Object> un = unCas(String.valueOf(c), p[1], zonePtr, p[2], p[3],
                        p[4], p[5]);
                if (un == null) {
                    continue;
                }
                cas.add(un);
                int n = ((List<?>) un.get("plan")).size();
                ici += n;
                System.out.printf("    pose nommée %s secteur %d en %d,%d angle %d œil %d :"
                        + " %d commande(s)%n", c, p[1], p[2], p[3], p[4], p[5], n);
            }
            commandes += ici;
            System.out.printf("  niveau %s : %d secteurs, %d commandes relevées%n", c, nz, ici);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("zonegraph.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[zonegraph] %d poses, %d commandes → %s%n", cas.size(), commandes, f);
    }

    /** Une pose : on place l'œil, on trie, on tourne les points, et on laisse la boucle courir. */
    private static Map<String, Object> unCas(String letter, int zone, int zonePtr, int x, int z,
            int angle, int y) {
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.wl(ab3d2.HiresData.Plr_YOff_l, y);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);
        // Le point de vue du tri des ouvertures se lit AILLEURS : dans le joueur 1. Sans ces
        // deux-là, le PVS d'arêtes juge depuis la position de départ du niveau.
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
        Mem.wl(ab3d2.HiresData.ZonePtr_l, zonePtr);
        Mem.wl(ab3d2.bss.LevelBss.Lvl_ListOfGraphRoomsPtr_l,
                zonePtr + ab3d2.Defs.ZoneT_PotVisibleZoneList_vw);
        Mem.wl(ab3d2.Orderzones.zone_LastPosition_vw, -1);

        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, ab3d2.Hires.FS_WIDTH / 2);
        Mem.ww(ab3d2.HireswallData.Vid_CentreY_w, ab3d2.Hires.FS_HEIGHT / 2);
        Mem.ww(ab3d2.HiresData.Vid_RightX_w, ab3d2.Hires.FS_WIDTH);
        Mem.ww(ab3d2.HireswallData.draw_TopClip_w, 0);
        Mem.ww(ab3d2.HireswallData.draw_BottomClip_w, ab3d2.Hires.FS_HEIGHT);

        try {
            ab3d2.Orderzones.Zone_OrderZones();
            // Tous les points, et non le seul sous-ensemble que le jeu juge utile : sans cela
            // les autres gardent la valeur d'une pose précédente, et les sommets d'ouverture
            // qui en font partie donnent une fenêtre de départ arbitraire. Les oracles de la
            // découpe font déjà ainsi.
            int sauve = Mem.b(ab3d2.HiresData.draw_RenderMap_b);
            Mem.wb(ab3d2.HiresData.draw_RenderMap_b, 0xFF);
            ab3d2.modules.Transform.RotateLevelPts();
            Mem.wb(ab3d2.HiresData.draw_RenderMap_b, sauve);
            ab3d2.modules.Transform.RotateObjectPts();
        } catch (Throwable t) {
            return null;
        }

        ab3d2.tools.ZonePlan.reset();
        ab3d2.tools.ZonePlan.stop = true;
        ab3d2.tools.ZonePlan.capturing = true;
        try {
            ab3d2.modules.draw.DrawZoneGraph.Draw_Zone_Graph();
        } catch (Throwable t) {
            System.out.println("    pose écartée (" + letter + " secteur " + zone + " angle "
                    + angle + ") : " + t);
            return null;
        } finally {
            ab3d2.tools.ZonePlan.capturing = false;
        }

        List<Object> plan = new ArrayList<>(ab3d2.tools.ZonePlan.plan.size());
        for (int[] ligne : ab3d2.tools.ZonePlan.plan) {
            List<Object> une = new ArrayList<>(ligne.length);
            for (int v : ligne) {
                une.add(v);
            }
            plan.add(une);
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("zone", zone);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("y", y);
        cas.put("angle", angle);
        // Combien d'ouvertures le champ de vision retient : c'est d'elles que se tire la
        // fenêtre de départ des secteurs autres que la racine.
        cas.put("visJoins", Mem.w(ab3d2.bss.ZoneBss.Zone_VisJoins_w));
        cas.put("visJoinMask", Mem.uw(ab3d2.bss.ZoneBss.Zone_VisJoinMask_w));
        // L'ordre retenu, et pour chacun la marque que le PVS d'arêtes lui a laissée :
        // c'est elle qui décide si le secteur se dessine ou non.
        int base = ab3d2.bss.ZoneBss.Zone_FinalOrderTable_vw;
        int fin = Mem.l(ab3d2.bss.ZoneBss.Zone_EndOfListPtr_l);
        List<Object> ordre = new ArrayList<>();
        List<Object> marques = new ArrayList<>();
        int zptrs2 = Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l);
        for (int i = 0; i < Math.max(0, (fin - base) / 2); i++) {
            int id = Mem.w(base + i * 2);
            ordre.add(id);
            int zp = id >= 0 ? Mem.l(zptrs2 + id * 4) : 0;
            marques.add(zp != 0 ? Mem.w(zp + ab3d2.Defs.ZoneT_Unused_w) : -999);
        }
        cas.put("order", ordre);
        cas.put("marks", marques);
        cas.put("plan", plan);
        return cas;
    }
}
