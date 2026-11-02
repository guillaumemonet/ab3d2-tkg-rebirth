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
 * Oracle de la POSE DU SPRITE : où l'image se pose, à quelle échelle, par quel bout.
 *
 * <p>Tout ce que la routine calcule tient en une dizaine de valeurs, et c'est au moment où elle
 * se branche vers l'une de ses quatre boucles de pixels qu'on les prend. La sonde l'arrête là :
 * aucun pixel n'est écrit, et le relevé ne dépend d'aucune des machineries qui suivent.
 *
 * <p>Un sprite que la routine écarte — derrière l'œil, hors de la fenêtre, de hauteur nulle —
 * ne passe jamais par ce point : son ABSENCE est le relevé, et la vérification l'attend.
 *
 * <p>Les champs de dessin — la feuille, l'image, les drapeaux, les demi-tailles — ne vivent
 * qu'en cours de partie : au chargement d'un niveau ils sont TOUS À ZÉRO, et la routine écarte
 * alors chaque sprite avant même de commencer. On les SÈME donc, d'après le rang de l'objet, de
 * façon à parcourir tout ce que la pose sait faire : les deux sens de l'image, les quatre
 * palettes éclairées, le mélange additif, les trente feuilles, les trente-deux images, et des
 * demi-tailles assez variées pour que l'échelle change à chaque objet.
 *
 * <p>Ce qui est semé est EXPORTÉ : la vérification rejoue les mêmes champs plutôt que de les
 * recalculer. C'est la pose qu'elle éprouve, pas la simulation.
 */
public final class BitmapExport {

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les orientations relevées. */
    private static final int[] ANGLES = {0, 2048, 4096, 6144, 4777};

    /** Combien de points de vue par niveau, pris sur les secteurs. */
    private static final int VUES = 8;

    /** Les hauteurs d'œil relevées, en unités du monde. */
    private static final int[] HAUTEURS = {0, 60000};

    /** Les fenêtres horizontales relevées. */
    private static final int[][] FENETRES = {{0, 320}, {60, 250}};

    private BitmapExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        // La table des pas de texture : _startup la remplit, et _startup ne tourne pas ici.
        ab3d2.Hires.Setup_ConstantTable();
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_Energy_w, 191);
        Mem.ww(ab3d2.bss.PlayerBss.Plr2_Energy_w, 191);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_Keys_b, 0);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_Mouse_b, 0xFF);
        Mem.wb(ab3d2.bss.PlayerBss.Plr2_Keys_b, 0);
        Mem.wb(ab3d2.bss.PlayerBss.Plr2_Mouse_b, 0xFF);
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, 0);

        List<Object> cas = new ArrayList<>();
        Map<String, Object> champsParNiveau = new LinkedHashMap<>();
        for (char c = 'a'; c <= 'p'; c++) {
            try {
                LevelExport.loadLevelForTools(c - 'a');
            } catch (Throwable t) {
                System.out.println("  niveau " + c + " : ÉCHEC " + t);
                continue;
            }
            int objets = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
            int n = 0;
            while (Mem.w(objets + n * ab3d2.Defs.ObjT_SizeOf_l) >= 0) {
                n++;
            }
            seme(objets, n);
            champsParNiveau.put(String.valueOf(c), champs(objets, n));
            int poses = 0;
            int dessines = 0;
            for (int[] vue : pointsDeVue()) {
                for (int angle : ANGLES) {
                    for (int plein = 1; plein >= 0; plein--) {
                        tourne(vue[0], vue[1], angle, plein != 0);
                        for (int y : HAUTEURS) {
                            for (int[] fen : FENETRES) {
                                Map<String, Object> un = unCas(String.valueOf(c), objets, n,
                                        angle, plein != 0, y, fen[0], fen[1], vue[0], vue[1]);
                                cas.add(un);
                                for (Object p : (List<?>) un.get("placed")) {
                                    if (p != null) {
                                        dessines++;
                                    }
                                }
                                poses++;
                            }
                        }
                    }
                }
            }
            System.out.printf("  niveau %s : %d entités, %d poses, %d sprites posés%n",
                    c, n, poses, dessines);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("objects", champsParNiveau);
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("bitmap.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.println("[bitmap] " + cas.size() + " poses → " + f);
    }

    /**
     * Les points de vue : des points de contrôle pris régulièrement sur les secteurs.
     *
     * <p>Les prendre au départ du joueur ne montrerait presque rien — tout le niveau est loin,
     * et la routine écarte alors chaque sprite ou sort de ses tables. Il faut se poser AU
     * MILIEU des choses pour que la pose ait lieu.
     */
    static List<int[]> pointsDeVue() {
        int nz = Mem.uw(ab3d2.bss.LevelBss.Lvl_NumZones_w);
        int zptrs = Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l);
        int coords = Mem.l(ab3d2.bss.LevelBss.Lvl_ControlPointCoordsPtr_l);
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < VUES; i++) {
            int z = (int) ((long) i * nz / VUES);
            int ptr = Mem.l(zptrs + z * 4);
            if (ptr == 0) {
                continue;
            }
            int cp = Mem.ub(ptr + ab3d2.Defs.ZoneT_ControlPoint_w);
            out.add(new int[] {Mem.w(coords + cp * 8), Mem.w(coords + cp * 8 + 2)});
        }
        // Et quelques vues prises TOUT PRES d'une chose : sans elles, aucun sprite n'approche
        // assez pour que la luminosite de distance descende sous la luminosite propre, et la
        // borne basse du calcul ne sert jamais.
        int objets = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
        int pts = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
        int pris = 0;
        for (int e = 0; pris < VUES / 2; e++) {
            int pi = Mem.w(objets + e * ab3d2.Defs.ObjT_SizeOf_l);
            if (pi < 0) {
                break;
            }
            if (Mem.w(objets + e * ab3d2.Defs.ObjT_SizeOf_l + ab3d2.Defs.ObjT_ZoneID_w) < 0
                    || e % 17 != 3) {
                continue;
            }
            // Deux ecarts : un pas de cote, et presque collee. La seconde seule amene la
            // profondeur sous soixante-quatre, ou la luminosite de distance ne couvre plus la
            // luminosite propre et ou la borne basse du calcul sert enfin.
            out.add(new int[] {Mem.w(pts + pi * 8) + 150, Mem.w(pts + pi * 8 + 4) + 150});
            out.add(new int[] {Mem.w(pts + pi * 8) + 34, Mem.w(pts + pi * 8 + 4) + 21});
            pris++;
        }
        return out;
    }

    /**
     * Les champs de dessin d'un niveau, relevés UNE fois.
     *
     * <p>La semence ne change pas d'une vue à l'autre : les répéter par vue ferait un relevé de
     * deux cents mégaoctets pour la même information.
     */
    static List<Object> champs(int objets, int n) {
        List<Object> objs = new ArrayList<>();
        for (int e = 0; e < n; e++) {
            int o = objets + e * ab3d2.Defs.ObjT_SizeOf_l;
            List<Object> un = new ArrayList<>();
            un.add(Mem.uw(o));                                  // rang du point
            un.add(Mem.w(o + 2));                               // luminosite propre
            un.add(Mem.w(o + 4));                               // hauteur
            un.add(Mem.ub(o + 6));                              // demi-largeur
            un.add(Mem.ub(o + 7));                              // demi-hauteur
            un.add(Mem.uw(o + 8));                              // feuille
            un.add(Mem.ub(o + 10));                             // drapeaux
            un.add(Mem.ub(o + 11));                             // image
            un.add(Mem.ub(o + ab3d2.Defs.ObjT_TypeID_b));       // type
            un.add(Mem.w(o + ab3d2.Defs.ShotT_AuxOffsetX_w));   // ecart auxiliaire
            un.add(Mem.w(o + ab3d2.Defs.ShotT_AuxOffsetY_w));
            objs.add(un);
        }
        return objs;
    }

    /** Les jeux de drapeaux semés : droit, retourné, les quatre éclairés, l'additif. */
    private static final int[] DRAPEAUX = {0, 0x80, 2, 3, 4, 5, 6, 0x86};

    /**
     * Sème les champs de dessin.
     *
     * <p>Deux objets sur trente-et-un gardent une demi-largeur NULLE : c'est la sortie que la
     * routine prend quand elle s'apprête à diviser par elle, et elle ne se rencontrerait pas
     * autrement.
     */
    static void seme(int objets, int n) {
        for (int e = 0; e < n; e++) {
            int o = objets + e * ab3d2.Defs.ObjT_SizeOf_l;
            int largeur = 0;                                    // posé plus bas
            Mem.ww(o + 2, (e * 17) % 60 - 10);                  // luminosité propre
            Mem.ww(o + 4, ((e * 37) % 400 - 200) * 16);         // hauteur
            // Les demi-tailles sont posées APRÈS l'image, et D'APRÈS elle : dans le jeu
            // la taille du monde suit celle du dessin, et les découpler ferait lire la
            // texture bien au-delà de ce que la feuille contient.
            // Seules QUATORZE feuilles sur trente portent un nom : les seize autres n'ont
            // aucun fichier, et le jeu y dessinerait depuis un pointeur nul.
            int feuille = (e * 5) % 14;
            Mem.ww(o + 8, feuille);
            Mem.wb(o + 10, DRAPEAUX[(e * 3) % DRAPEAUX.length]);
            // Les cases INUTILISEES de la table des images ont une demi-largeur nulle, ce qui
            // fait deborder le rapport d'echelle et rend la pose arbitraire. On en garde une
            // sur vingt-trois — assez pour eprouver ce debordement, pas assez pour que le
            // corpus ne parle que de lui.
            int image = (e * 11) % 32;
            if (e % 23 != 5) {
                // On choisit parmi les images PLEINES, et non la premiere pleine qui suit :
                // deux images sur trois sont vides, et suivre le premier creux ramenerait
                // toujours aux memes — or quatre-vingt-cinq images ne sont pas carrees, et
                // ce sont elles qui separent le rapport horizontal du vertical.
                int glf = Mem.l(ab3d2.HiresData.GLF_DatabasePtr_l);
                List<Integer> pleines = new ArrayList<>();
                List<Integer> rectangles = new ArrayList<>();
                for (int k = 0; k < 32; k++) {
                    if (demiLargeur(glf, feuille, k) == 0) {
                        continue;
                    }
                    pleines.add(k);
                    if (demiLargeur(glf, feuille, k) != demiHauteur(glf, feuille, k)) {
                        rectangles.add(k);
                    }
                }
                // Une fois sur trois on prend une image NON CARREE si la feuille en a : ce
                // sont les seules ou le rapport horizontal se separe du vertical, et sans
                // elles on ne saurait pas que la routine prend bien l'un pour l'un et l'autre
                // pour l'autre.
                List<Integer> choix = e % 3 == 0 && !rectangles.isEmpty() ? rectangles : pleines;
                if (!choix.isEmpty()) {
                    image = choix.get(image % choix.size());
                }
            }
            Mem.wb(o + 11, image);

            int glf2 = Mem.l(ab3d2.HiresData.GLF_DatabasePtr_l);
            int dl = demiLargeur(glf2, feuille, image);
            int dh = demiHauteur(glf2, feuille, image);
            largeur = e % 31 == 7 ? 0 : borne(dl + (e * 7) % 21 - 10);
            Mem.wb(o + 6, largeur);                             // demi-largeur (255 = vecteur)
            Mem.wb(o + 7, e % 29 == 11 ? 0 : borne(dh + (e * 13) % 21 - 10)); // demi-hauteur
            Mem.ww(o + ab3d2.Defs.ShotT_AuxOffsetX_w, (e * 23) % 100 - 50);
            Mem.ww(o + ab3d2.Defs.ShotT_AuxOffsetY_w, (e * 29) % 100 - 50);
        }
    }

    /** Une demi-taille utilisable : jamais nulle, jamais la marque du modèle vectoriel. */
    private static int borne(int v) {
        return Math.max(4, Math.min(240, v));
    }

    /** La demi-hauteur d'une image, dans la table de la base de liaison. */
    private static int demiHauteur(int glf, int feuille, int image) {
        return Mem.uw(glf + ab3d2.Defs.GLFT_FrameData_l + feuille * 256 + image * 8 + 6);
    }

    /** La demi-largeur d'une image, dans la table de la base de liaison. */
    private static int demiLargeur(int glf, int feuille, int image) {
        return Mem.uw(glf + ab3d2.Defs.GLFT_FrameData_l + feuille * 256 + image * 8 + 4);
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

    /** Un cas : on pose la vue, puis on fait dessiner CHAQUE objet du niveau. */
    private static Map<String, Object> unCas(String letter, int objets, int n, int angle,
            boolean pleinEcran, int y, int gauche, int droite, int x, int z) {
        int largeur = pleinEcran ? ab3d2.Hires.FS_WIDTH : ab3d2.Hires.SMALL_WIDTH;
        int hauteur = pleinEcran ? ab3d2.Hires.FS_HEIGHT : ab3d2.Hires.SMALL_HEIGHT;
        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, largeur / 2);
        Mem.ww(ab3d2.HireswallData.Vid_CentreY_w, hauteur / 2);
        Mem.wl(ab3d2.HiresData.Plr_YOff_l, y);
        Mem.ww(ab3d2.HireswallData.draw_TopClip_w, 0);
        Mem.ww(ab3d2.HireswallData.draw_BottomClip_w, hauteur);
        Mem.ww(ab3d2.HireswallData.Draw_LeftClip_w, gauche);
        Mem.ww(ab3d2.HireswallData.Draw_RightClip_w, droite);
        Mem.wl(ab3d2.ObjdrawhiresData.draw_TopY_3D_l, -100 * 1024);
        Mem.wl(ab3d2.ObjdrawhiresData.draw_BottomY_3D_l, 100 * 1024);

        List<Object> poses = new ArrayList<>();
        for (int e = 0; e < n; e++) {
            int o = objets + e * ab3d2.Defs.ObjT_SizeOf_l;
            if (Mem.ub(o + 6) == 0xFF) {
                poses.add(null);                                    // modele vectoriel
                continue;
            }
            ab3d2.tools.BitmapPlace.reset();
            ab3d2.tools.BitmapPlace.stop = true;
            ab3d2.tools.BitmapPlace.capturing = true;
            ab3d2.Objdrawhires.draw_Object(e);
            ab3d2.tools.BitmapPlace.capturing = false;
            if (ab3d2.tools.BitmapPlace.placed.isEmpty()) {
                poses.add(null);
            } else {
                List<Object> p = new ArrayList<>();
                for (int v : ab3d2.tools.BitmapPlace.placed.get(0)) {
                    p.add(v);
                }
                poses.add(p);
            }
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("angle", angle);
        cas.put("full", pleinEcran);
        cas.put("eyeY", y);
        cas.put("centreX", largeur / 2);
        cas.put("centreY", hauteur / 2);
        cas.put("topClip", 0);
        cas.put("bottomClip", hauteur);
        cas.put("leftClip", gauche);
        cas.put("rightClip", droite);
        cas.put("roomTop", -100 * 1024);
        cas.put("roomBottom", 100 * 1024);
        cas.put("placed", poses);
        return cas;
    }
}
