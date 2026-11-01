package ab3d2.rebirth.extract;

import ab3d2.Mem;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle du PLACAGE DES SOLS ET DES PLAFONDS : les tranches et leurs pixels.
 *
 * <p>{@link FlatExport} releve ou vont les tranches ; celui-ci releve ce qu'on y voit. Pour
 * chaque tranche peinte, les sept valeurs qui ont decide du resultat — la ligne, l'abscisse de
 * depart, la largeur, la rangee d'ombrage, la tuile, la position dans la texture et son pas —
 * PUIS les octets reellement ecrits dans le tampon.
 *
 * <p>Les surfaces d'EAU sont laissees de cote : elles ont leur propre boucle texel, qui fait
 * onduler la ligne avant de la lire et attend l'animation. Le rendu <i>gouraud</i> l'est aussi,
 * comme dans {@link FlatExport} et pour la meme raison.
 *
 * <p>Le corpus est plus petit que celui de la projection, parce qu'une tranche y pese ses
 * pixels et non sept nombres — et une tranche de sol est large, la ou une colonne de mur est
 * etroite.
 */
public final class FlatPaintExport {

    /** Combien de secteurs on éprouve par niveau. */
    private static final int PAR_NIVEAU = 3;

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les orientations relevées. */
    private static final int[] ANGLES = {0, 2730, 5461};

    /** Où placer l'œil entre le plafond et le plancher, en quarts. */
    private static final int[] HAUTEURS = {1, 2, 3};

    /** Les codes du programme d'habillage. */
    private static final int PAINT_WALL = 0;
    private static final int PAINT_FLOOR = 1;
    private static final int PAINT_CEILING = 2;
    private static final int PAINT_OBJECTS = 4;
    private static final int PAINT_WATER = 7;
    private static final int PAINT_SKY = 12;

    /** Taille d'un enregistrement de mur, mot de tête non compris. */
    private static final int WALL_SIZE = 28;

    private FlatPaintExport() {
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
            if (Mem.l(ab3d2.bss.VidBss.Vid_FastBufferPtr_l) == 0) {
                throw new IllegalStateException("pas de tampon d'écran : rien à relever");
            }
            int nz = Mem.uw(ab3d2.bss.LevelBss.Lvl_NumZones_w);
            int zptrs = Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l);
            int coords = Mem.l(ab3d2.bss.LevelBss.Lvl_ControlPointCoordsPtr_l);
            int pris = 0;
            int surfaces = 0;
            int pas = Math.max(1, nz / PAR_NIVEAU);
            for (int z = 0; z < nz && pris < PAR_NIVEAU; z += pas) {
                int zonePtr = Mem.l(zptrs + z * 4);
                if (zonePtr == 0) {
                    continue;
                }
                int cp = Mem.ub(zonePtr + ab3d2.Defs.ZoneT_ControlPoint_w);
                int x = Mem.w(coords + cp * 8);
                int zz = Mem.w(coords + cp * 8 + 2);
                int roof = Mem.l(zonePtr + ab3d2.Defs.ZoneT_Roof_l);
                int floor = Mem.l(zonePtr + ab3d2.Defs.ZoneT_Floor_l);
                for (int quart : HAUTEURS) {
                    int y = roof + (floor - roof) * quart / 4;
                    for (int angle : ANGLES) {
                        Map<String, Object> un = unCas(String.valueOf(c), z, zonePtr,
                                x, zz, y, angle);
                        if (un != null) {
                            cas.add(un);
                            surfaces += ((List<?>) un.get("flats")).size();
                        }
                    }
                }
                pris++;
            }
            System.out.printf("  niveau %s : %d secteurs × %d hauteurs × %d angles, %d surfaces%n",
                    c, pris, HAUTEURS.length, ANGLES.length, surfaces);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("flatpaint.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[flatpaint] " + cas.size() + " poses → " + f);
    }

    private static Map<String, Object> unCas(String letter, int zone, int zonePtr,
            int x, int z, int y, int angle) {
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.wl(ab3d2.HiresData.Plr_YOff_l, y);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);

        int sin = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + (angle & (TOUR - 2)));
        int cosAt = (angle + ab3d2.data.TablesData.COSINE_OFS) & (TOUR - 2);
        int cos = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + cosAt);
        Mem.ww(ab3d2.HireswallData.Vis_SinVal_w, sin);
        Mem.ww(ab3d2.HireswallData.Vis_CosVal_w, cos);
        Mem.wl(ab3d2.HiresData.xwobble, 0);
        Mem.ww(ab3d2.HiresData.xwobxoff, 0);
        Mem.ww(ab3d2.HiresData.xwobzoff, 0);

        // La hauteur de l'œil telle que les surfaces la voient : au quart de ce que porte
        // Plr_YOff_l, parce que leur propre hauteur se compte ainsi.
        Mem.ww(ab3d2.HiresData.flooryoff, y >> 6);

        Mem.wb(ab3d2.bss.VidBss.Vid_FullScreen_b, 0xFF);
        Mem.wb(ab3d2.bss.VidBss.Vid_DoubleWidth_b, 0);
        Mem.wb(ab3d2.bss.VidBss.Vid_DoubleHeight_b, 0);
        Mem.ww(ab3d2.HiresData.Vid_RightX_w, ab3d2.Hires.FS_WIDTH);
        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, ab3d2.Hires.FS_WIDTH / 2);
        int centreY = ab3d2.Hires.FS_HEIGHT / 2;
        Mem.ww(ab3d2.HireswallData.Vid_CentreY_w, centreY);
        Mem.ww(ab3d2.HireswallData.Vid_BottomY_w, ab3d2.Hires.FS_HEIGHT);
        Mem.wl(ab3d2.HireswallData.BIGMIDDLEY, centreY * ab3d2.Hires.SCREEN_WIDTH);
        Mem.ww(ab3d2.HireswallData.draw_TopClip_w, 0);
        Mem.ww(ab3d2.HireswallData.draw_BottomClip_w, ab3d2.Hires.FS_HEIGHT);

        int sauve = Mem.b(ab3d2.HiresData.draw_RenderMap_b);
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, 0xFF);
        ab3d2.modules.Transform.RotateLevelPts();
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, sauve);

        // La fenêtre de dessin : toute la vue. Le resserrement a ses propres relevés.
        int gauche = 0;
        int droite = ab3d2.Hires.FS_WIDTH;
        Mem.ww(ab3d2.HireswallData.Draw_LeftClip_w, gauche);
        Mem.ww(ab3d2.HireswallData.Draw_RightClip_w, droite);
        Mem.wl(ab3d2.HireswallData.Draw_LeftClip_l, gauche);
        Mem.wl(ab3d2.HireswallData.Draw_RightClip_l, droite);

        // Le secteur : ses bornes verticales, et le fait que le joueur s'y tienne — c'est cela
        // seul qui décide de la teinte d'eau plein écran.
        Mem.wl(ab3d2.bss.DrawBss.Draw_TopOfRoom_l, Mem.l(zonePtr + ab3d2.Defs.ZoneT_Roof_l));
        Mem.wl(ab3d2.bss.DrawBss.Draw_BottomOfRoom_l, Mem.l(zonePtr + ab3d2.Defs.ZoneT_Floor_l));
        Mem.wl(ab3d2.HiresData.ZonePtr_l, zonePtr);
        Mem.wb(ab3d2.HiresData.DOANYWATER, 0xFF);
        Mem.wb(ab3d2.HiresData.fillscrnwater, 0);

        Mem.wb(ab3d2.HireswallData.Draw_GoodRender_b, 0xFF);
        Mem.wb(ab3d2.bss.DrawBss.Draw_ForceSimpleWalls_b, 0);
        Mem.ww(ab3d2.bss.DrawBss.Draw_CurrentZone_w, zone);
        Mem.wb(ab3d2.bss.DrawBss.Draw_DoUpper_b, 0);
        Mem.ww(ab3d2.HiresData.Zone_Bright_w, 0);
        Mem.wb(ab3d2.HiresData.draw_GouraudFlatsSelected_b, 0);

        // La carte 2D, où le rendu inscrit ce qu'il dessine.
        Mem.wl(ab3d2.HiresData.Lvl_BigMapPtr_l, ab3d2.bss.TablesBss.Lvl_BigMap_vl + zone * 40);
        Mem.wl(ab3d2.HiresData.Lvl_CompactMapPtr_l,
                ab3d2.bss.TablesBss.Lvl_CompactMap_vl + zone * 4);

        int adds = Mem.l(ab3d2.bss.LevelBss.Lvl_ZoneGraphAddsPtr_l);
        int base = Mem.l(ab3d2.bss.LevelBss.Lvl_GraphicsPtr_l);
        int at = Mem.l(adds + zone * 8) + base;
        at += 2;                                       // le premier mot est l'identité du secteur

        List<Object> surfaces = new ArrayList<>();
        while (true) {
            int tag = Mem.uw(at);
            at += 2;
            int op = tag & 0xFF;
            if ((byte) op < 0) {
                break;
            }
            switch (op) {
                case PAINT_WALL -> at += WALL_SIZE;
                case PAINT_FLOOR, PAINT_CEILING, PAINT_WATER -> {
                    Mem.ww(ab3d2.HiresData.draw_WallID_w, tag);
                    Mem.ww(ab3d2.HiresData.SMALLIT, op == PAINT_WATER ? 2 : 1);
                    Mem.wb(ab3d2.HiresData.draw_UseWater_b, op == PAINT_WATER ? 0xFF : 0);
                    Mem.wb(ab3d2.HiresData.draw_UseGouraudFlats_b, 0);
                    ab3d2.tools.FloorPixels.reset();
                    ab3d2.tools.FloorPixels.capturing = true;
                    int fin;
                    try {
                        fin = ab3d2.Hires.Draw_Flats(at, op == PAINT_WATER ? 3 : op);
                    } finally {
                        ab3d2.tools.FloorPixels.capturing = false;
                    }
                    Map<String, Object> une = uneSurface(at - 2 - base, tag);
                    if (une != null) {
                        surfaces.add(une);
                    }
                    at = fin;
                }
                case PAINT_OBJECTS -> at += 2;
                case PAINT_SKY -> { }
                default -> { }
            }
        }
        if (surfaces.isEmpty()) {
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
        cas.put("roof", Mem.l(zonePtr + ab3d2.Defs.ZoneT_Roof_l));
        cas.put("floor", Mem.l(zonePtr + ab3d2.Defs.ZoneT_Floor_l));
        cas.put("tint", Mem.ub(ab3d2.HiresData.fillscrnwater));
        cas.put("flats", surfaces);
        return cas;
    }

    /** Une surface et ses tranches peintes, ou {@code null} si elle n'en a peint aucune. */
    private static Map<String, Object> uneSurface(int offset, int tag) {
        List<int[]> releve = ab3d2.tools.FloorPixels.spans;
        if (releve.isEmpty()) {
            return null;
        }
        Base64.Encoder encodeur = Base64.getEncoder().withoutPadding();
        List<Object> tranches = new ArrayList<>(releve.size());
        for (int i = 0; i < releve.size(); i++) {
            List<Object> une = new ArrayList<>(8);
            for (int v : releve.get(i)) {
                une.add(v);
            }
            une.add(encodeur.encodeToString(ab3d2.tools.FloorPixels.pixels.get(i)));
            tranches.add(une);
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("offset", offset);
        flat.put("tag", tag);
        flat.put("spans", tranches);
        return flat;
    }
}
