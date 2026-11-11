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
 * Oracle du PLACAGE DES MURS DEGRADES : le second rasteriseur, celui ou la luminosite varie du
 * haut de la colonne a son bas.
 *
 * <p>{@link WallPaintExport} releve le rendu SIMPLE, qu'il obtient en l'imposant. Celui-ci
 * releve l'autre, et ne l'impose pas : il seme des luminosites assez contrastees pour que le
 * jeu le choisisse de lui-meme. Le choix tient a trois niveaux d'ecart entre le haut et le bas
 * d'un meme bout du pan ; en dessous, les deux se valent et le rendu simple l'emporte.
 *
 * <p>Pour chaque colonne peinte, les dix valeurs qui ont decide du resultat — la ligne et
 * l'abscisse, la hauteur, le depart et le pas du degrade, le format de texel, le bloc de
 * texture, la position de depart et son pas, le masque de hauteur — PUIS les octets ecrits.
 *
 * <p>Les QUATRE luminosites de coin sont relevees avec le pan, comme {@link WallPaintExport} en
 * releve deux : l'eclairage est un maillon a part, et ce qui se verifie ici est ce qu'on fait
 * d'une luminosite, pas d'ou elle vient.
 */
public final class GourWallPaintExport {

    /** Combien de secteurs on eprouve par niveau. */
    private static final int PAR_NIVEAU = 4;

    /** Les orientations relevees. */
    private static final int[] ANGLES = {0, 2048, 4096, 6144};

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les codes du programme d'habillage. */
    private static final int PAINT_WALL = 0;
    private static final int PAINT_FLOOR = 1;
    private static final int PAINT_CEILING = 2;
    private static final int PAINT_OBJECTS = 4;
    private static final int PAINT_WATER = 7;
    private static final int PAINT_SKY = 12;

    private GourWallPaintExport() {
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

        fillConstantTable();

        List<Object> cas = new ArrayList<>();
        long colonnes = 0;
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
            semerDesLumieres();
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
                    Map<String, Object> un = unCas(String.valueOf(c), z, x, zz, y, angle);
                    if (un != null) {
                        cas.add(un);
                        for (Object m : (List<?>) un.get("walls")) {
                            List<?> bandes = (List<?>) ((Map<?, ?>) m).get("strips");
                            murs++;
                            colonnes += bandes.size();
                        }
                    }
                }
                pris++;
            }
            System.out.printf("  niveau %s : %d secteurs × %d angles, %d pans dégradés%n",
                    c, pris, ANGLES.length, murs);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("gourwallpaint.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[gourwallpaint] %d poses, %d colonnes → %s%n", cas.size(), colonnes, f);
    }

    /**
     * La table des pas de texture, que {@code _startup} remplit au lancement du jeu.
     *
     * <p>Les outils n'appellent pas {@code _startup} : sans ce remplissage le pas vertical vaut
     * zero et chaque colonne repete le meme texel du haut en bas.
     */
    private static void fillConstantTable() {
        int a0 = ab3d2.bss.TablesBss.ConstantTable_vl;
        int d0 = 1;
        int d1 = 8191;
        do {
            int d2 = 16384 * 64;
            d2 = d2 / d0;
            int d3 = 64 * 64 * 65536;
            d3 = d3 / d2;
            Mem.wl(a0, d3);
            a0 += 4;
            d2 = d2 >> 1;
            d2 = d2 - 40 * 64;
            d2 = d2 * d3;
            d2 = d2 >> 6;
            Mem.wl(a0, d2);
            a0 += 4;
            d0 += 1;
        } while (--d1 != -1);
    }

    /**
     * Seme des luminosites de points variees, assez contrastees pour que le degrade s'impose.
     *
     * <p>La table porte quarante valeurs par secteur : dix points de bord, quatre emplacements
     * chacun. Le haut et le bas d'un meme bout de mur y lisent DEUX emplacements differents, et
     * c'est leur ecart qui decide du rastériseur. Le pas de treize choisi ici les ecarte de bien
     * plus que les trois niveaux qu'il faut, pour presque tous les pans.
     *
     * <p>Les valeurs sont BIAISEES DE TROIS CENTS, comme l'eclairage les y depose : voir
     * {@link WallPaintExport} pour la raison, qui tient a la subdivision.
     */
    private static void semerDesLumieres() {
        int at = ab3d2.bss.TablesBss.CurrentPointBrights_vl;
        for (int i = 0; i < 256 * 40; i++) {
            Mem.ww(at + i * 2, 300 + (i * 13) % 41);
        }
    }

    private static Map<String, Object> unCas(String letter, int zone, int x, int z, int y,
            int angle) {
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
        Mem.wb(ab3d2.bss.VidBss.Vid_DoubleHeight_b, 0);
        Mem.ww(ab3d2.HiresData.Vid_RightX_w, ab3d2.Hires.FS_WIDTH);
        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, ab3d2.Hires.FS_WIDTH / 2);
        int centreY = ab3d2.Hires.FS_HEIGHT / 2;
        Mem.ww(ab3d2.HireswallData.Vid_CentreY_w, centreY);
        Mem.ww(ab3d2.HireswallData.Vid_BottomY_w, ab3d2.Hires.FS_HEIGHT);

        // La fenetre de dessin : toute la vue, en hauteur comme en largeur.
        Mem.ww(ab3d2.HireswallData.Draw_LeftClip_w, 0);
        Mem.ww(ab3d2.HireswallData.Draw_RightClip_w, ab3d2.Hires.FS_WIDTH);
        Mem.wl(ab3d2.HireswallData.Draw_LeftClip_l, 0);
        Mem.wl(ab3d2.HireswallData.Draw_RightClip_l, ab3d2.Hires.FS_WIDTH);
        Mem.ww(ab3d2.HireswallData.draw_TopClip_w, 0);
        Mem.ww(ab3d2.HireswallData.draw_BottomClip_w, ab3d2.Hires.FS_HEIGHT);
        Mem.ww(ab3d2.HireswallData.TOPOFFSET, 0);

        int sauve = Mem.b(ab3d2.HiresData.draw_RenderMap_b);
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, 0xFF);
        ab3d2.modules.Transform.RotateLevelPts();
        Mem.wb(ab3d2.HiresData.draw_RenderMap_b, sauve);

        Mem.wb(ab3d2.HireswallData.Draw_GoodRender_b, 0xFF);
        // Rien n'est impose : le jeu choisit le degrade de lui-meme des que les quatre coins
        // s'ecartent, et c'est ce choix-la qu'on veut eprouver.
        Mem.wb(ab3d2.bss.DrawBss.Draw_ForceSimpleWalls_b, 0);
        Mem.ww(ab3d2.bss.DrawBss.Draw_CurrentZone_w, zone);
        Mem.wb(ab3d2.bss.DrawBss.Draw_DoUpper_b, 0);
        Mem.ww(ab3d2.HiresData.Zone_Bright_w, 0);
        // LE CALAGE VERTICAL DE LA TEXTURE, et pourquoi il ne doit SURTOUT pas rester nul.
        //
        // Ce releve l'a longtemps force a zero, « pour simplifier ». La conversion le
        // passait a zero elle aussi, et la verification tombait juste au pixel pres sur
        // deux cent mille pixels — alors que le jeu, lui, mettait les textures des murs
        // AILLEURS des que le joueur n'avait pas les yeux a hauteur zero.
        //
        // C'est le defaut classique d'un oracle : il avait fige la variable meme que le
        // portage oubliait de faire varier. Le bug se voyait en jouant, jamais en
        // verifiant — les murs semblaient suivre le personnage quand il montait.
        //
        // On le fait donc VARIER, et avec la formule du jeu :
        //   DrawDisplay : move.l Plr_YOff_l,d0 ; asr.l #8,d0 ; add.w #224,d0 ; and.w #255
        Mem.ww(ab3d2.HireswallData.draw_WallYOffset_w,
                ((Mem.l(ab3d2.HiresData.Plr_YOff_l) >> 8) + 224) & 255);

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
                    ab3d2.tools.GourWallPixels.reset();
                    ab3d2.tools.GourWallPixels.capturing = true;
                    int fin;
                    try {
                        fin = ab3d2.Hireswall.Draw_Wall(at);
                    } finally {
                        ab3d2.tools.GourWallPixels.capturing = false;
                    }
                    Map<String, Object> mur = unMur(at, tag);
                    if (mur != null) {
                        murs.add(mur);
                    }
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
        cas.put("walls", murs);
        return cas;
    }

    /** Un pan et ses colonnes peintes, ou {@code null} s'il n'est pas passe par le degrade. */
    private static Map<String, Object> unMur(int at, int tag) {
        List<int[]> releve = ab3d2.tools.GourWallPixels.strips;
        if (releve.isEmpty()) {
            return null;
        }
        List<Object> bandes = new ArrayList<>(releve.size());
        Base64.Encoder encodeur = Base64.getEncoder().withoutPadding();
        for (int i = 0; i < releve.size(); i++) {
            List<Object> une = new ArrayList<>(11);
            for (int v : releve.get(i)) {
                une.add(v);
            }
            une.add(encodeur.encodeToString(ab3d2.tools.GourWallPixels.pixels.get(i)));
            bandes.add(une);
        }
        Map<String, Object> mur = new LinkedHashMap<>();
        mur.put("offset", at - 2 - Mem.l(ab3d2.bss.LevelBss.Lvl_GraphicsPtr_l));
        mur.put("tag", tag);
        mur.put("leftBright", Mem.w(ab3d2.HireswallData.draw_LeftWallBright_w));
        mur.put("rightBright", Mem.w(ab3d2.HireswallData.draw_RightWallBright_w));
        mur.put("leftUpperBright", Mem.w(ab3d2.HireswallData.draw_LeftWallTopBright_w));
        mur.put("rightUpperBright", Mem.w(ab3d2.HireswallData.draw_RightWallTopBright_w));
        mur.put("strips", bandes);
        return mur;
    }
}
