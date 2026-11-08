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
 * Oracle de l'image ECLAIREE : la palette qu'elle se fabrique, et les pixels qu'elle pose.
 *
 * <p>C'est la quatrième et dernière des boucles de sprite, et la seule qui ne lise aucune
 * palette toute faite : elle en CONSTRUIT une, de deux cent trente-deux octets, pour chaque
 * image et à chaque fois qu'on la dessine. Seize des vingt monstres du jeu se dessinent ainsi.
 *
 * <p>On relève donc DEUX choses par sprite : le tampon de palette une fois rempli, et le
 * rectangle de pixels. La première dit où le calcul d'éclairage se trompe, la seconde où la
 * boucle de pixels se trompe — sans quoi une erreur de l'une serait illisible dans l'autre.
 *
 * <p>Le tampon fait trois cent quatre-vingt-douze octets alors que la boucle n'en remplit que
 * deux cent trente-deux, et l'octet du sprite va jusqu'à deux cent cinquante-cinq : la traînée
 * laissée par le sprite PRECEDENT fait partie de ce qu'il faut reproduire. On relève donc le
 * tampon entier, et on ne l'efface jamais — ni ici, ni dans le portage.
 *
 * <p>Les drapeaux semés sont les quatre valeurs éclairées, à l'endroit et à l'envers. Les trois
 * autres boucles ont leur propre oracle.
 */
public final class BitmapLitExport {

    /** Un tour complet de la table de sinus. */
    private static final int TOUR = 8192;

    /** Les orientations relevées. L'angle du REGARD entre dans le calcul de la tache. */
    private static final int[] ANGLES = {0, 1301, 2048, 4096, 4777, 6144};

    /** Les fenêtres horizontales relevées. */
    private static final int[][] FENETRES = {{0, 320}, {60, 250}};

    /** Au-delà, le sprite est trop gros pour qu'on garde ses pixels. */
    private static final int MAX_PIXELS = 6000;

    /** La hauteur du tampon, celle que la vérification remplit aussi. */
    private static final int HAUTEUR = 256;

    private BitmapLitExport() {
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
                int o = objets + e * ab3d2.Defs.ObjT_SizeOf_l;
                // Les QUATRE palettes éclairées, à tour de rôle, et une image sur deux
                // retournée — la tache de lumière se retourne avec elle, par une seconde
                // table d'indices.
                Mem.wb(o + 10, (2 + e % 4) | (e % 2 == 0 ? 0 : 0x80));
            }
            champsParNiveau.put(String.valueOf(c), BitmapExport.champs(objets, n));
            eclaire();

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
        Path f = outRoot.resolve("bitmaplit.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[bitmaplit] %d poses, %d sprites éclairés, %d pixels, %d laissés au"
                + " hasard → %s%n", cas.size(), sprites, pixels, hasard[0], f);
    }

    /**
     * Sème les luminosités de points, celles d'où sortent les anneaux d'éclairage.
     *
     * <p>Sans cela le relevé ne vaudrait rien : l'extraction ne joue aucune image, les
     * luminosités de points restent toutes nulles, et les quatre anneaux sortent entièrement
     * à la marque du RIEN. Tout le calcul de direction se réduit alors à une constante, et un
     * corpus où l'entrée ne varie pas ne prouve rien de ce qui en dépend.
     *
     * <p>Les valeurs couvrent les deux côtés du seuil de trois cents — en deçà la contribution
     * est nulle — et une sur sept est NEGATIVE, pour éprouver le repliement que la routine
     * applique alors.
     */
    private static void eclaire() {
        for (int i = 0; i < 256 * 40; i++) {
            int v = (i * 97) % 420 + 250;
            if (i % 7 == 3) {
                v = -v;
            }
            Mem.ww(ab3d2.bss.TablesBss.CurrentPointBrights_vl + i * 2, v);
        }
    }

    /** Pose l'œil et fait tourner les points des choses. */
    private static void tourne(int x, int z, int angle) {
        Mem.wl(ab3d2.HiresData.Plr_XOff_l, x << 16);
        Mem.wl(ab3d2.HiresData.Plr_ZOff_l, z << 16);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, angle);
        // L'angle du REGARD, que le calcul de la tache de lumiere lit directement.
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_TmpAngPos_w, angle);
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
        List<Object> palettes = new ArrayList<>();
        List<Object> entrees = new ArrayList<>();
        List<Object> anneaux = new ArrayList<>();
        List<Object> bases = new ArrayList<>();
        List<Object> clartes = new ArrayList<>();
        for (int e = 0; e < n; e++) {
            int o = objets + e * ab3d2.Defs.ObjT_SizeOf_l;
            if (Mem.ub(o + 6) == 0xFF) {
                rien(poses, pixels, palettes, entrees, anneaux, bases, clartes);
                continue;
            }
            // Premier passage : on relève la pose sans rien dessiner.
            ab3d2.tools.BitmapPlace.reset();
            ab3d2.tools.BitmapPlace.stop = true;
            ab3d2.tools.BitmapPlace.capturing = true;
            ab3d2.Objdrawhires.draw_Object(e);
            ab3d2.tools.BitmapPlace.capturing = false;
            if (ab3d2.tools.BitmapPlace.placed.isEmpty()) {
                rien(poses, pixels, palettes, entrees, anneaux, bases, clartes);
                continue;
            }
            int[] pose = ab3d2.tools.BitmapPlace.placed.get(0);
            int haut = pose[0];
            int colonne = pose[1];
            int colonnes = pose[2];
            int lignes = pose[3];
            if (haut < 0 || colonne < 0 || haut + lignes > HAUTEUR
                    || colonne + colonnes > largeur || colonnes * lignes > MAX_PIXELS) {
                rien(poses, pixels, palettes, entrees, anneaux, bases, clartes);
                continue;
            }

            // Le secteur courant : dans le jeu c'est celui que le parcours traverse, et la
            // chose y est dessinée. Ici rien ne le pose, et c'est de lui que dépendent les
            // points de bord dont sortent les anneaux.
            int sz = Mem.w(o + ab3d2.Defs.ObjT_ZoneID_w);
            Mem.ww(ab3d2.bss.DrawBss.Draw_CurrentZone_w, Math.max(sz, 0));
            Mem.wb(ab3d2.bss.DrawBss.draw_InUpperZone_b, e % 3 == 0 ? 0xFF : 0);

            // Le motif, puis le dessin, puis le relevé.
            motif(buffer, haut, colonne, colonnes, lignes);
            ab3d2.tools.BitmapPlace.reset();
            ab3d2.tools.BitmapPlace.stop = false;
            ab3d2.tools.BitmapPlace.capturing = true;
            ab3d2.tools.LitPalette.reset();
            ab3d2.tools.LitPalette.capturing = true;
            // La TRAINEE : le tampon fait trois cent quatre-vingt-douze octets, la boucle n'en
            // remplit que deux cent trente-deux, et l'octet du sprite va jusqu'à deux cent
            // cinquante-cinq. Ce qui se trouve au-delà est, dans le jeu, ce que le sprite
            // PRECEDENT y a laissé — rien ne le définit. On le met à zéro avant chaque sprite,
            // et le portage ne l'écrit jamais : la comparaison porte alors sur ce que la
            // routine ECRIT, non sur ce qu'elle hérite.
            for (int k = 0; k < 4 * 2 * 49; k++) {
                Mem.wb(ab3d2.bss.DrawBss.draw_Pals_vl + k, 0);
            }
            try {
                ab3d2.Objdrawhires.draw_Object(e);
            } catch (RuntimeException t) {
                // L'échelle a débordé sa table et la bande désignée tombe hors de la feuille :
                // l'original y lirait de la mémoire qui ne lui appartient pas. On laisse le
                // cas de côté plutôt que de relever du hasard.
                ab3d2.tools.BitmapPlace.capturing = false;
                ab3d2.tools.LitPalette.capturing = false;
                hasard[0]++;
                rien(poses, pixels, palettes, entrees, anneaux, bases, clartes);
                continue;
            }
            ab3d2.tools.BitmapPlace.capturing = false;
            ab3d2.tools.LitPalette.capturing = false;
            if (ab3d2.tools.LitPalette.builds.isEmpty()) {
                // La routine n'est pas allée jusqu'à la palette : ce n'est pas un éclairé.
                rien(poses, pixels, palettes, entrees, anneaux, bases, clartes);
                continue;
            }

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
            List<Object> dit = new ArrayList<>();
            for (int v : ab3d2.tools.LitPalette.builds.get(0)) {
                dit.add(v);
            }
            poses.add(p);
            entrees.add(dit);
            palettes.add(encodeur.encodeToString(ab3d2.tools.LitPalette.pals.get(0)));
            anneaux.add(encodeur.encodeToString(ab3d2.tools.LitPalette.rings.get(0)));
            bases.add(ab3d2.tools.LitPalette.bases.get(0));
            clartes.add(encodeur.encodeToString(ab3d2.tools.LitPalette.ramps.get(0)));
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
        cas.put("inputs", entrees);
        cas.put("rings", anneaux);
        cas.put("bases", bases);
        cas.put("ramps", clartes);
        cas.put("pals", palettes);
        cas.put("pixels", pixels);
        return cas;
    }

    /** Un sprite laissé de côté : sept trous alignés. */
    private static void rien(List<Object> poses, List<Object> pixels, List<Object> palettes,
            List<Object> entrees, List<Object> anneaux, List<Object> bases,
            List<Object> clartes) {
        poses.add(null);
        entrees.add(null);
        anneaux.add(null);
        bases.add(null);
        clartes.add(null);
        palettes.add(null);
        pixels.add(null);
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
