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
 * Oracle du PLACAGE DES MURS : d'une colonne d'ecran aux pixels.
 *
 * <p>{@link WallExport} releve ou vont les colonnes ; celui-ci releve ce qu'on y voit. Pour
 * chaque colonne peinte, les onze valeurs qui ont decide du resultat — la ligne et l'abscisse,
 * la hauteur, les deux rampes d'ombrage, le format de texel, le bloc de texture, le point de
 * depart, le pas et le masque de hauteur — PUIS les octets reellement ecrits dans le tampon.
 *
 * <p>Les octets comptent autant que les parametres : ils sont les seuls a eprouver la lecture
 * des texels, c'est-a-dire les trois valeurs de cinq bits serrees dans chaque mot et le damier
 * qui alterne les deux rampes d'un pixel au suivant.
 *
 * <p>Le corpus est plus petit que celui de la projection, parce qu'une colonne y pese ses
 * pixels et non six nombres. Quatre secteurs par niveau et quatre orientations suffisent a
 * couvrir les treize textures du jeu et les trois formats de texel.
 *
 * <p>Comme pour la projection, l'eclairage est MIS DE COTE : sans lui les quatre coins d'un
 * mur valent un, le rendu simple est choisi, et c'est celui-la qu'on porte. Le rendu gouraud
 * viendra avec les lumieres dynamiques.
 */
public final class WallPaintExport {

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

    private WallPaintExport() {
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
            System.out.printf("  niveau %s : %d secteurs × %d angles, %d pans%n",
                    c, pris, ANGLES.length, murs);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("wallpaint.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[wallpaint] %d poses, %d colonnes → %s%n", cas.size(), colonnes, f);
    }

    /**
     * La table des pas de texture, que {@code _startup} remplit au lancement du jeu.
     *
     * <p>Les outils n'appellent pas {@code _startup} : sans ce remplissage la table reste a
     * zero, le pas vertical vaut zero, et chaque colonne de mur repete le meme texel du haut
     * en bas. Le releve serait parfaitement reproductible et parfaitement faux.
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
     * Seme des luminosites de points variees, pour que l'ombrage ait quelque chose a dire.
     *
     * <p>L'eclairage lui-meme n'est pas l'objet de ce releve : ce qui compte ici est que les
     * colonnes couvrent toute l'etendue de la table d'ombrage, des rampes les plus sombres aux
     * plus claires, et les rangs IMPAIRS ou deux rampes voisines alternent en damier. Laissees a
     * zero, les luminosites donneraient a toute colonne la meme rampe et le damier ne se verrait
     * jamais. On ne reproduit donc pas ces valeurs dans la reecriture : on les RELEVE, mur par
     * mur, telles que le portage les a calculees, et on les lui redonne.
     *
     * <p>La table porte ses luminosites BIAISEES DE TROIS CENTS, comme le calcul d'eclairage les
     * y depose, parce que la subdivision des murs fait transiter la luminosite dans le mot
     * faible d'un mot double dont le mot fort porte l'abscisse de texture : le biais la tient
     * positive pendant les moyennes successives, et le rendu le retranche au dernier moment.
     * Semer des valeurs petites donnerait des luminosites tres negatives, donc la rampe la plus
     * sombre partout.
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
        // Le rendu gouraud des murs attend les lumieres dynamiques : on impose le rendu simple,
        // comme le jeu lui-meme le fait quand les quatre coins s'accordent.
        Mem.wb(ab3d2.bss.DrawBss.Draw_ForceSimpleWalls_b, 0xFF);
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
                    ab3d2.tools.WallPixels.reset();
                    ab3d2.tools.WallPixels.capturing = true;
                    int fin;
                    try {
                        fin = ab3d2.Hireswall.Draw_Wall(at);
                    } finally {
                        ab3d2.tools.WallPixels.capturing = false;
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

    /** Un pan et ses colonnes peintes, ou {@code null} s'il n'en a peint aucune. */
    private static Map<String, Object> unMur(int at, int tag) {
        int gauche = Mem.w(ab3d2.HireswallData.draw_LeftWallBright_w);
        int droite = Mem.w(ab3d2.HireswallData.draw_RightWallBright_w);
        List<int[]> releve = ab3d2.tools.WallPixels.strips;
        if (releve.isEmpty()) {
            return null;
        }
        List<Object> bandes = new ArrayList<>(releve.size());
        Base64.Encoder encodeur = Base64.getEncoder().withoutPadding();
        for (int i = 0; i < releve.size(); i++) {
            List<Object> une = new ArrayList<>(12);
            for (int v : releve.get(i)) {
                une.add(v);
            }
            une.add(encodeur.encodeToString(ab3d2.tools.WallPixels.pixels.get(i)));
            bandes.add(une);
        }
        Map<String, Object> mur = new LinkedHashMap<>();
        mur.put("offset", at - 2 - Mem.l(ab3d2.bss.LevelBss.Lvl_GraphicsPtr_l));
        mur.put("tag", tag);
        mur.put("leftBright", gauche);
        mur.put("rightBright", droite);
        mur.put("strips", bandes);
        return mur;
    }
}
