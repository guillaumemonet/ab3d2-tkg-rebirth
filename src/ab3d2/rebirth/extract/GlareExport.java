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
 * Oracle du MIROITEMENT : la pose ET les pixels, car c'est UNE routine.
 *
 * <p>Le sprite ordinaire a demandé deux relevés — l'un pour savoir où il se pose, l'autre pour
 * savoir ce qu'il écrit — parce que sa pose se branche vers quatre boucles différentes. Le
 * miroitement n'en a qu'une, et la séparer n'apprendrait rien : on relève donc les deux d'un
 * seul tenant.
 *
 * <p>Comme pour le sprite, le tampon est CONNU avant le passage : un miroitement saute la
 * teinte zéro, et surtout il LIT l'octet déjà posé pour décider ce qu'il écrit. Le motif n'est
 * pas une précaution, c'est la moitié de l'épreuve.
 *
 * <h2>Ce qu'il faut semer</h2>
 *
 * <p>Un miroitement se reconnaît au SIGNE du mot double à l'offset huit — la feuille suivie
 * des drapeaux et de l'image. On sème donc des rangs de feuille NIÉS, de un à treize : les
 * quatorze feuilles nommées, moins la zéroième, dont l'opposé ne serait pas négatif et ne
 * passerait pas par cette routine.
 *
 * <p>Le mot de luminosité propre reçoit une valeur BRUYANTE. La routine doit le sauter sans le
 * lire ; s'il servait, on le verrait.
 *
 * <p>Un objet sur onze reçoit un octet de drapeaux NON NUL. C'est la seule façon de séparer la
 * lecture en mot de l'image — ce que fait le miroitement — d'une lecture en octet. Les
 * demi-tailles de ceux-là sont prises sur l'image RÉELLEMENT désignée, deux cent cinquante-six
 * rangs plus loin, pour que l'échelle reste utilisable.
 */
public final class GlareExport {

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les orientations relevées. */
    private static final int[] ANGLES = {0, 2048, 4777};

    /** Les hauteurs d'œil relevées. */
    private static final int[] HAUTEURS = {0, 60000};

    /** Les fenêtres horizontales relevées. */
    private static final int[][] FENETRES = {{0, 320}, {60, 250}};

    /** Au-delà, le miroitement est trop gros pour qu'on garde ses pixels. */
    private static final int MAX_PIXELS = 6000;

    /** La hauteur du tampon, celle que la vérification remplit aussi. */
    private static final int HAUTEUR = 256;

    /** Combien de feuilles portent un nom dans la base de liaison. */
    private static final int FEUILLES_NOMMEES = 14;

    /** Combien d'images par feuille. */
    private static final int IMAGES = 32;

    private GlareExport() {
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

        List<Object> cas = new ArrayList<>();
        Map<String, Object> champsParNiveau = new LinkedHashMap<>();
        Base64.Encoder encodeur = Base64.getEncoder();
        int[] hasard = {0};
        long pixels = 0;
        int poses = 0;
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
            int objets = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
            int n = 0;
            while (Mem.w(objets + n * ab3d2.Defs.ObjT_SizeOf_l) >= 0) {
                n++;
            }
            seme(objets, n);
            champsParNiveau.put(String.valueOf(c), champs(objets, n));

            int ici = 0;
            for (int[] vue : BitmapExport.pointsDeVue()) {
                for (int angle : ANGLES) {
                    tourne(vue[0], vue[1], angle);
                    for (int y : HAUTEURS) {
                        for (int[] fen : FENETRES) {
                            Map<String, Object> un = unCas(String.valueOf(c), objets, n, angle,
                                    y, fen[0], fen[1], vue[0], vue[1], encodeur, hasard);
                            cas.add(un);
                            for (Object o : (List<?>) un.get("placed")) {
                                if (o != null) {
                                    ici++;
                                }
                            }
                        }
                    }
                }
            }
            poses += ici;
            System.out.printf("  niveau %s : %d entités, %d miroitements posés%n", c, n, ici);
        }
        for (Object o : cas) {
            for (Object p : (List<?>) ((Map<?, ?>) o).get("pixels")) {
                if (p != null) {
                    pixels += ((String) p).length() * 3L / 4;
                }
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("objects", champsParNiveau);
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("glare.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[glare] %d vues, %d poses, %d pixels, %d laissés au hasard → %s%n",
                cas.size(), poses, pixels, hasard[0], f);
    }

    /** Sème des champs de dessin de MIROITEMENT. */
    private static void seme(int objets, int n) {
        int glf = Mem.l(ab3d2.HiresData.GLF_DatabasePtr_l);
        for (int e = 0; e < n; e++) {
            int o = objets + e * ab3d2.Defs.ObjT_SizeOf_l;
            // Une luminosité propre bruyante : la routine doit la SAUTER.
            Mem.ww(o + 2, 9999);
            Mem.ww(o + 4, ((e * 37) % 400 - 200) * 16);         // hauteur
            int feuille = 1 + (e * 5) % (FEUILLES_NOMMEES - 1); // jamais zéro : -0 n'est pas < 0
            int drapeaux = e % 11 == 4 ? 1 : 0;
            // Les drapeaux montent de huit bits dans l'index d'image : la feuille réellement
            // lue est huit rangs plus loin, et c'est d'elle qu'il faut prendre les tailles.
            int feuilleLue = feuille + drapeaux * 8;
            int image = (e * 11) % IMAGES;
            List<Integer> pleines = new ArrayList<>();
            List<Integer> rectangles = new ArrayList<>();
            for (int k = 0; k < IMAGES; k++) {
                if (demiLargeur(glf, feuilleLue, k) == 0) {
                    continue;
                }
                pleines.add(k);
                if (demiLargeur(glf, feuilleLue, k) != demiHauteur(glf, feuilleLue, k)) {
                    rectangles.add(k);
                }
            }
            List<Integer> choix = e % 3 == 0 && !rectangles.isEmpty() ? rectangles : pleines;
            if (!choix.isEmpty()) {
                image = choix.get(image % choix.size());
            }
            Mem.ww(o + 8, -feuille);                            // la feuille, NIÉE
            Mem.wb(o + 10, drapeaux);
            Mem.wb(o + 11, image);
            int dl = demiLargeur(glf, feuilleLue, image);
            int dh = demiHauteur(glf, feuilleLue, image);
            // Deux objets sur trente-et-un gardent une demi-taille NULLE : c'est la sortie que
            // la routine prend quand elle s'apprête à diviser par elle.
            Mem.wb(o + 6, e % 31 == 7 ? 0 : borne(dl + (e * 7) % 21 - 10));
            Mem.wb(o + 7, e % 29 == 11 ? 0 : borne(dh + (e * 13) % 21 - 10));
            Mem.ww(o + ab3d2.Defs.ShotT_AuxOffsetX_w, (e * 23) % 100 - 50);
            Mem.ww(o + ab3d2.Defs.ShotT_AuxOffsetY_w, (e * 29) % 100 - 50);
        }
    }

    /** Une demi-taille utilisable : jamais nulle, jamais la marque du modèle vectoriel. */
    private static int borne(int v) {
        return Math.max(4, Math.min(240, v));
    }

    private static int demiHauteur(int glf, int feuille, int image) {
        return Mem.uw(glf + ab3d2.Defs.GLFT_FrameData_l + feuille * 256 + image * 8 + 6);
    }

    private static int demiLargeur(int glf, int feuille, int image) {
        return Mem.uw(glf + ab3d2.Defs.GLFT_FrameData_l + feuille * 256 + image * 8 + 4);
    }

    /** Les champs semés d'un niveau, relevés UNE fois. */
    private static List<Object> champs(int objets, int n) {
        List<Object> objs = new ArrayList<>();
        for (int e = 0; e < n; e++) {
            int o = objets + e * ab3d2.Defs.ObjT_SizeOf_l;
            List<Object> un = new ArrayList<>();
            un.add(Mem.uw(o));                                  // rang du point
            un.add(Mem.w(o + 2));                               // luminosité, qui ne sert pas
            un.add(Mem.w(o + 4));                               // hauteur
            un.add(Mem.ub(o + 6));                              // demi-largeur
            un.add(Mem.ub(o + 7));                              // demi-hauteur
            un.add(Mem.uw(o + 8));                              // feuille, niée
            un.add(Mem.ub(o + 10));                             // drapeaux
            un.add(Mem.ub(o + 11));                             // image
            un.add(Mem.ub(o + ab3d2.Defs.ObjT_TypeID_b));       // type
            un.add(Mem.w(o + ab3d2.Defs.ShotT_AuxOffsetX_w));   // écart auxiliaire
            un.add(Mem.w(o + ab3d2.Defs.ShotT_AuxOffsetY_w));
            objs.add(un);
        }
        return objs;
    }

    /** Pose l'œil et fait tourner les points des choses. */
    private static void tourne(int x, int z, int angle) {
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);
        int sin = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + (angle & (TOUR - 2)));
        int cosAt = (angle + ab3d2.data.TablesData.COSINE_OFS) & (TOUR - 2);
        int cos = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + cosAt);
        Mem.ww(ab3d2.HireswallData.Vis_SinVal_w, sin);
        Mem.ww(ab3d2.HireswallData.Vis_CosVal_w, cos);
        Mem.wl(ab3d2.HiresData.xwobble, 0);
        Mem.wb(ab3d2.bss.VidBss.Vid_FullScreen_b, 0xFF);
        Mem.wb(ab3d2.bss.VidBss.Vid_DoubleWidth_b, 0);
        ab3d2.modules.Transform.RotateObjectPts();
    }

    /** Un cas : on pose la vue, puis on dessine chaque miroitement SEUL sur un motif connu. */
    private static Map<String, Object> unCas(String letter, int objets, int n, int angle,
            int y, int gauche, int droite, int x, int z, Base64.Encoder encodeur,
            int[] hasard) {
        int largeur = ab3d2.Hires.FS_WIDTH;
        int hauteur = ab3d2.Hires.FS_HEIGHT;
        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, largeur / 2);
        Mem.ww(ab3d2.HireswallData.Vid_CentreY_w, hauteur / 2);
        Mem.wl(ab3d2.HiresData.Plr_YOff_l, y);
        Mem.ww(ab3d2.HireswallData.draw_TopClip_w, 0);
        Mem.ww(ab3d2.HireswallData.draw_BottomClip_w, hauteur);
        Mem.ww(ab3d2.HireswallData.Draw_LeftClip_w, gauche);
        Mem.ww(ab3d2.HireswallData.Draw_RightClip_w, droite);
        Mem.wl(ab3d2.ObjdrawhiresData.draw_TopY_3D_l, -100 * 1024);
        Mem.wl(ab3d2.ObjdrawhiresData.draw_BottomY_3D_l, 100 * 1024);

        int buffer = Mem.l(ab3d2.bss.VidBss.Vid_FastBufferPtr_l);
        List<Object> poses = new ArrayList<>();
        List<Object> pixels = new ArrayList<>();
        for (int e = 0; e < n; e++) {
            // Premier passage : on relève la pose sans rien dessiner.
            ab3d2.tools.GlarePlace.reset();
            ab3d2.tools.GlarePlace.stop = true;
            ab3d2.tools.GlarePlace.capturing = true;
            ab3d2.Objdrawhires.draw_Object(e);
            ab3d2.tools.GlarePlace.capturing = false;
            if (ab3d2.tools.GlarePlace.placed.isEmpty()) {
                poses.add(null);
                pixels.add(null);
                continue;
            }
            int[] pose = ab3d2.tools.GlarePlace.placed.get(0);
            List<Object> p = new ArrayList<>();
            for (int v : pose) {
                p.add(v);
            }
            poses.add(p);

            int haut = pose[0];
            int colonne = pose[1];
            int colonnes = pose[2];
            int lignes = pose[3];
            if (haut < 0 || colonne < 0 || haut + lignes > HAUTEUR
                    || colonne + colonnes > largeur || colonnes * lignes > MAX_PIXELS) {
                pixels.add(null);
                continue;
            }

            // Le motif, puis le dessin, puis le relevé.
            motif(buffer, haut, colonne, colonnes, lignes);
            ab3d2.tools.GlarePlace.reset();
            ab3d2.tools.GlarePlace.stop = false;
            ab3d2.tools.GlarePlace.capturing = true;
            try {
                ab3d2.Objdrawhires.draw_Object(e);
            } catch (RuntimeException t) {
                // La bande désignée tombe hors de la feuille : l'original y lirait de la
                // mémoire qui ne lui appartient pas. On laisse le cas de côté.
                ab3d2.tools.GlarePlace.capturing = false;
                hasard[0]++;
                pixels.add(null);
                continue;
            }
            ab3d2.tools.GlarePlace.capturing = false;

            byte[] out = new byte[colonnes * lignes];
            for (int l = 0; l < lignes; l++) {
                for (int k = 0; k < colonnes; k++) {
                    out[l * colonnes + k] = (byte) Mem.ub(buffer
                            + (haut + l) * ab3d2.Hires.SCREEN_WIDTH + colonne + k);
                }
            }
            pixels.add(encodeur.encodeToString(out));
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("angle", angle);
        cas.put("eyeY", y);
        cas.put("centreX", largeur / 2);
        cas.put("centreY", hauteur / 2);
        cas.put("leftClip", gauche);
        cas.put("rightClip", droite);
        cas.put("placed", poses);
        cas.put("pixels", pixels);
        return cas;
    }

    /** Le motif de départ, fonction des coordonnées ABSOLUES de l'écran. */
    private static void motif(int buffer, int haut, int colonne, int colonnes, int lignes) {
        for (int l = 0; l < lignes; l++) {
            int y = haut + l;
            for (int k = 0; k < colonnes; k++) {
                int px = colonne + k;
                Mem.wb(buffer + y * ab3d2.Hires.SCREEN_WIDTH + px, (px * 7 + y * 13) & 0xFF);
            }
        }
    }
}
