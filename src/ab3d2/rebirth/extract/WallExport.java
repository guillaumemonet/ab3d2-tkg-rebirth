package ab3d2.rebirth.extract;

import ab3d2.Mem;
import ab3d2.modules.DevMacros;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle de la PROJECTION VERTICALE DES MURS.
 *
 * <p>Pour chaque pan de mur d'un secteur, les bandes d'ecran que le portage en tire : une par
 * colonne, avec son abscisse de texture, sa distance, son haut et son bas. C'est la geometrie
 * du mur, prise juste avant le placage des pixels.
 *
 * <p>L'eclairage est MIS DE COTE, par le drapeau que le portage prevoit pour cela. Il ne s'agit
 * pas d'ecarter une difficulte : la luminosite des quatre coins decide lequel des deux rendus de
 * mur s'execute, et elle est rangee a cote de l'abscisse de texture dans un meme mot double que
 * la subdivision moyenne d'un bloc — de sorte que les deux valeurs sortent liees. Sans lumieres,
 * les quatre coins valent un, le rendu simple est choisi, et ce qu'on releve ne depend plus que
 * de la geometrie. Les lumieres viendront avec le placage.
 *
 * <p>Deux reglages de finesse sont releves, parce qu'ils font varier le nombre de coupes et donc
 * tout le decoupage en bandes.
 */
public final class WallExport {

    /** Combien de secteurs on eprouve par niveau. */
    private static final int PAR_NIVEAU = 8;

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les orientations relevees. */
    private static final int[] ANGLES = {0, 1365, 2730, 4096, 5461, 6826, 911};

    /** Les codes du programme d'habillage. */
    private static final int PAINT_WALL = 0;
    private static final int PAINT_FLOOR = 1;
    private static final int PAINT_CEILING = 2;
    private static final int PAINT_OBJECTS = 4;
    private static final int PAINT_WATER = 7;
    private static final int PAINT_SKY = 12;

    /** Taille d'un enregistrement de mur, mot de tete non compris. */
    private static final int WALL_SIZE = 28;

    private WallExport() {
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

        // Sans lumieres : les quatre coins valent un et le rendu simple est choisi.
        DevMacros.DEV_ENABLE(ab3d2.modules.DevInst.Dev_DebugFlags_l, DevMacros.DEV_SKIP_LIGHTING);

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
            int murs = 0;
            int pas = Math.max(1, nz / PAR_NIVEAU);
            for (int z = 0; z < nz && pris < PAR_NIVEAU; z += pas) {
                int zonePtr = Mem.l(zptrs + z * 4);
                if (zonePtr == 0) {
                    continue;
                }
                int cp = Mem.ub(zonePtr + ab3d2.Defs.ZoneT_ControlPoint_w);
                int x = Mem.w(coords + cp * 8);
                int zz = Mem.w(coords + cp * 8 + 2);
                int y = Mem.w(coords + cp * 8 + 4);
                for (int angle : ANGLES) {
                    for (int fin = 0; fin < 2; fin++) {
                        Map<String, Object> un = unCas(String.valueOf(c), z, x, zz, y, angle,
                                fin != 0);
                        if (un != null) {
                            cas.add(un);
                            murs += ((List<?>) un.get("walls")).size();
                        }
                    }
                }
                pris++;
            }
            System.out.printf("  niveau %s : %d secteurs × %d angles × 2 finesses, %d murs%n",
                    c, pris, ANGLES.length, murs);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("walls.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[walls] " + cas.size() + " poses → " + f);
    }

    private static Map<String, Object> unCas(String letter, int zone, int x, int z, int y,
            int angle, boolean goodRender) {
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.wl(ab3d2.HiresData.Plr_YOff_l, y << 8);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);

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
        int centreY = ab3d2.Hires.FS_HEIGHT / 2;
        Mem.ww(ab3d2.HireswallData.Vid_CentreY_w, centreY);

        int sauve = Mem.b(ab3d2.HiresData.draw_RenderMap_b);
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, 0xFF);
        ab3d2.modules.Transform.RotateLevelPts();
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, sauve);

        // La fenetre de dessin : toute la vue. Le resserrement a ses propres releves.
        int gauche = 0;
        int droite = ab3d2.Hires.FS_WIDTH;
        Mem.ww(ab3d2.HireswallData.Draw_LeftClip_w, gauche);
        Mem.ww(ab3d2.HireswallData.Draw_RightClip_w, droite);
        Mem.wl(ab3d2.HireswallData.Draw_LeftClip_l, gauche);
        Mem.wl(ab3d2.HireswallData.Draw_RightClip_l, droite);

        Mem.wb(ab3d2.HireswallData.Draw_GoodRender_b, goodRender ? 0xFF : 0);
        Mem.wb(ab3d2.bss.DrawBss.Draw_ForceSimpleWalls_b, 0);
        Mem.ww(ab3d2.bss.DrawBss.Draw_CurrentZone_w, zone);
        Mem.wb(ab3d2.bss.DrawBss.Draw_DoUpper_b, 0);
        Mem.ww(ab3d2.HiresData.Zone_Bright_w, 0);
        Mem.ww(ab3d2.HireswallData.draw_WallYOffset_w, 0);

        // La carte 2D, ou Draw_Wall inscrit les pans qu'il dessine.
        Mem.wl(ab3d2.HiresData.Lvl_BigMapPtr_l, ab3d2.bss.TablesBss.Lvl_BigMap_vl + zone * 40);
        Mem.wl(ab3d2.HiresData.Lvl_CompactMapPtr_l,
                ab3d2.bss.TablesBss.Lvl_CompactMap_vl + zone * 4);

        int adds = Mem.l(ab3d2.bss.LevelBss.Lvl_ZoneGraphAddsPtr_l);
        int at = Mem.l(adds + zone * 8) + Mem.l(ab3d2.bss.LevelBss.Lvl_GraphicsPtr_l);
        at += 2;                                       // le premier mot est l'identite du secteur

        List<Object> murs = new ArrayList<>();
        while (true) {
            int tag = Mem.uw(at);
            at += 2;
            int op = tag & 0xFF;
            if ((byte) op < 0) {
                break;
            }
            switch (op) {
                case PAINT_WALL -> {
                    Mem.ww(ab3d2.HiresData.draw_WallID_w, tag);
                    ab3d2.tools.WallStrips.reset();
                    ab3d2.tools.WallStrips.capturing = true;
                    int fin;
                    try {
                        fin = ab3d2.Hireswall.Draw_Wall(at);
                    } finally {
                        ab3d2.tools.WallStrips.capturing = false;
                    }
                    murs.add(unMur(at, tag));
                    at = fin;
                }
                case PAINT_FLOOR, PAINT_CEILING, PAINT_WATER -> {
                    int sides = Mem.w(at + 2) + 1;
                    at += 4 + (sides + 1) * 2 + 6;
                }
                case PAINT_OBJECTS -> at += 2;
                case PAINT_SKY -> { }
                default -> { }
            }
        }
        if (murs.isEmpty()) {
            return null;
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("zone", zone);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("y", y);
        cas.put("angle", angle);
        cas.put("centreY", centreY);
        cas.put("goodRender", goodRender);
        cas.put("walls", murs);
        return cas;
    }

    /** Un pan et ses bandes. L'offset du record identifie le pan sans ambiguite. */
    private static Map<String, Object> unMur(int at, int tag) {
        List<Object> bandes = new ArrayList<>(ab3d2.tools.WallStrips.strips.size());
        for (int[] s : ab3d2.tools.WallStrips.strips) {
            List<Object> une = new ArrayList<>(6);
            for (int v : s) {
                une.add(v);
            }
            bandes.add(une);
        }
        Map<String, Object> mur = new LinkedHashMap<>();
        mur.put("offset", at - 2 - Mem.l(ab3d2.bss.LevelBss.Lvl_GraphicsPtr_l));
        mur.put("tag", tag);
        mur.put("left", Mem.w(at));
        mur.put("right", Mem.w(at + 2));
        mur.put("strips", bandes);
        return mur;
    }
}
