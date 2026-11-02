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
 * Oracle des PIXELS DU SPRITE : la boucle simple, celle qui pose l'image telle quelle.
 *
 * <p>Comme l'eau, le sprite ne couvre pas tout ce qu'il touche : la teinte zéro est
 * transparente et laisse voir ce qui est dessous. Le relevé n'a donc de sens que si le tampon
 * est CONNU avant le passage. On y pose un motif, et la vérification pose le même.
 *
 * <p>Chaque sprite est relevé SEUL : on remet le motif sur son rectangle, on dessine, on prend
 * le rectangle. Deux sprites qui se recouvrent ne se gêneraient pas pour la justesse, mais une
 * erreur dans l'un deviendrait illisible dans l'autre.
 *
 * <p>Les drapeaux semés sont ramenés à DEUX valeurs — l'image droite et l'image retournée.
 * Les palettes éclairées et le mélange additif passent par d'autres boucles, qui ne sont pas
 * encore portées.
 */
public final class BitmapPaintExport {

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les orientations relevées. */
    private static final int[] ANGLES = {0, 2048, 4777};

    /** Combien de points de vue par niveau. */
    private static final int VUES = 4;

    /** Les fenêtres horizontales relevées. */
    private static final int[][] FENETRES = {{0, 320}, {60, 250}};

    /** Au-delà, le sprite est trop gros pour qu'on garde ses pixels. */
    private static final int MAX_PIXELS = 6000;

    /** La hauteur du tampon, celle que la vérification remplit aussi. */
    private static final int HAUTEUR = 256;

    private BitmapPaintExport() {
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
        long pixels = 0;
        int sprites = 0;
        int[] hasard = {0};
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
            BitmapExport.seme(objets, n);
            for (int e = 0; e < n; e++) {
                // Seuls l'endroit et l'envers : les autres boucles ne sont pas portées.
                Mem.wb(objets + e * ab3d2.Defs.ObjT_SizeOf_l + 10, e % 2 == 0 ? 0 : 0x80);
            }
            champsParNiveau.put(String.valueOf(c), BitmapExport.champs(objets, n));

            int ici = 0;
            for (int[] vue : BitmapExport.pointsDeVue()) {
                for (int angle : ANGLES) {
                    tourne(vue[0], vue[1], angle);
                    for (int[] fen : FENETRES) {
                        Map<String, Object> un = unCas(String.valueOf(c), objets, n, angle,
                                fen[0], fen[1], vue[0], vue[1], encodeur, hasard);
                        cas.add(un);
                        for (Object o : (List<?>) un.get("pixels")) {
                            if (o != null) {
                                ici++;
                            }
                        }
                    }
                }
            }
            sprites += ici;
            System.out.printf("  niveau %s : %d entités, %d sprites relevés%n", c, n, ici);
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
        Path f = outRoot.resolve("bitmappaint.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[bitmappaint] %d poses, %d sprites, %d pixels, %d laissés au"
                + " hasard → %s%n", cas.size(), sprites, pixels, hasard[0], f);
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

    /** Un cas : on pose la vue, puis on dessine chaque sprite SEUL sur un motif connu. */
    private static Map<String, Object> unCas(String letter, int objets, int n, int angle,
            int gauche, int droite, int x, int z, Base64.Encoder encodeur, int[] hasard) {
        int largeur = ab3d2.Hires.FS_WIDTH;
        int hauteur = ab3d2.Hires.FS_HEIGHT;
        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, largeur / 2);
        Mem.ww(ab3d2.HireswallData.Vid_CentreY_w, hauteur / 2);
        Mem.wl(ab3d2.HiresData.Plr_YOff_l, 0);
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
            int o = objets + e * ab3d2.Defs.ObjT_SizeOf_l;
            if (Mem.ub(o + 6) == 0xFF) {
                poses.add(null);
                pixels.add(null);
                continue;
            }
            // Premier passage : on relève la pose sans rien dessiner.
            ab3d2.tools.BitmapPlace.reset();
            ab3d2.tools.BitmapPlace.stop = true;
            ab3d2.tools.BitmapPlace.capturing = true;
            ab3d2.Objdrawhires.draw_Object(e);
            ab3d2.tools.BitmapPlace.capturing = false;
            if (ab3d2.tools.BitmapPlace.placed.isEmpty()) {
                poses.add(null);
                pixels.add(null);
                continue;
            }
            int[] pose = ab3d2.tools.BitmapPlace.placed.get(0);
            int haut = pose[0];
            int colonne = pose[1];
            int colonnes = pose[2];
            int lignes = pose[3];
            if (haut < 0 || colonne < 0 || haut + lignes > HAUTEUR
                    || colonne + colonnes > largeur || colonnes * lignes > MAX_PIXELS) {
                poses.add(null);
                pixels.add(null);
                continue;
            }

            // Le motif, puis le dessin, puis le relevé.
            motif(buffer, haut, colonne, colonnes, lignes);
            ab3d2.tools.BitmapPlace.reset();
            ab3d2.tools.BitmapPlace.stop = false;
            ab3d2.tools.BitmapPlace.capturing = true;
            try {
                ab3d2.Objdrawhires.draw_Object(e);
            } catch (RuntimeException t) {
                // L'échelle a débordé sa table et la bande désignée tombe hors de la feuille :
                // l'original y lirait de la mémoire qui ne lui appartient pas. On laisse le
                // cas de côté plutôt que de relever du hasard.
                ab3d2.tools.BitmapPlace.capturing = false;
                hasard[0]++;
                poses.add(null);
                pixels.add(null);
                continue;
            }
            ab3d2.tools.BitmapPlace.capturing = false;

            byte[] out = new byte[colonnes * lignes];
            for (int l = 0; l < lignes; l++) {
                for (int k = 0; k < colonnes; k++) {
                    out[l * colonnes + k] = (byte) Mem.ub(buffer
                            + (haut + l) * ab3d2.Hires.SCREEN_WIDTH + colonne + k);
                }
            }
            List<Object> p = new ArrayList<>();
            for (int v : pose) {
                p.add(v);
            }
            poses.add(p);
            pixels.add(encodeur.encodeToString(out));
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", letter);
        cas.put("x", x);
        cas.put("z", z);
        cas.put("angle", angle);
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
