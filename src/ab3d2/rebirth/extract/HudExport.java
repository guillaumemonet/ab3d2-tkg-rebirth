package ab3d2.rebirth.extract;

import ab3d2.Mem;
import ab3d2.data.DrawData;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Extraction du PANNEAU DE STATUT (la bordure du jeu et ses chiffres).
 *
 * <p>{@code newborderpacked} est l'image de bordure PLEIN ECRAN : 8 plans de bits de 320×256
 * (8 × 10240 = 81920 octets, deja depackee — l'{@code unLHA} d'origine degenere en copie). On la
 * repasse en chunky (draw_PlanarToChunky, DrawC.java:460) puis dans la palette.
 *
 * <p>{@code bordercharsraw} (2320 octets) contient les chiffres, eux aussi en plans de bits mais
 * ENTRELACES par dix (10 octets par plan et par ligne — les dix chiffres cote a cote) :
 * <ul>
 *   <li>@0     : chiffres d'objet 8×5, arme NON possedee ;</li>
 *   <li>@400   : arme possedee ;</li>
 *   <li>@800   : arme SELECTIONNEE ;</li>
 *   <li>@1200  : compteur 8×7, valeur BASSE (alerte) ;</li>
 *   <li>@1760  : compteur 8×7, valeur normale.</li>
 * </ul>
 *
 * <p>Sortie : {@code assets/hud/border.png} et {@code assets/hud/digits.png} (les cinq jeux
 * empiles, dix chiffres par rangee).
 */
public final class HudExport {

    private static final int W = 320;
    private static final int H = 256;
    /** Taille d'un plan de bits de l'ecran (320*256/8). */
    private static final int PLANESIZE = W * H / 8;
    /** Le bandeau de statut occupe ces lignes de la bordure (mesure : plein sur toute la largeur). */
    public static final int BAR_TOP = 232;
    public static final int BAR_BOTTOM = 247;

    /** Les cinq jeux de chiffres : {offset dans bordercharsraw, largeur, hauteur, nom}. */
    private static final int[][] DIGIT_SETS = {
        { 0, 8, 5 },                                   // arme non possedee
        { 400, 8, 5 },                                 // arme possedee
        { 800, 8, 5 },                                 // arme selectionnee
        { 1200, 8, 7 },                                // compteur, valeur basse
        { 1760, 8, 7 },                                // compteur, valeur normale
    };

    private HudExport() {
    }

    public static void extract(Path outRoot, int[] pal) throws IOException {
        Path dir = outRoot.resolve("hud");
        Files.createDirectories(dir);
        writeBorder(dir, pal);
        writeDigits(dir, pal);
    }

    /** La bordure plein ecran, plans de bits -> chunky -> palette. */
    private static void writeBorder(Path dir, int[] pal) throws IOException {
        int base = DrawData.draw_BorderPacked_vb;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        int opaque = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int idx = 0;
                int byteAt = y * (W / 8) + (x >> 3);
                int bit = 1 << (7 - (x & 7));
                for (int p = 0; p < 8; p++) {
                    if ((Mem.ub(base + p * PLANESIZE + byteAt) & bit) != 0) {
                        idx |= 1 << p;
                    }
                }
                // L'index 0 est la zone que la VUE 3D recouvre : elle doit rester transparente.
                img.setRGB(x, y, idx == 0 ? 0 : pal[idx]);
                if (idx != 0) {
                    opaque++;
                }
            }
        }
        ImageIO.write(img, "png", dir.resolve("border.png").toFile());
        System.out.printf("[hud] border.png (%dx%d, %d pixels opaques sur %d)%n",
                W, H, opaque, W * H);

        // Le BANDEAU seul : c'est la seule partie de la bordure qui ait un sens en plein ecran
        // (le cadre encadrait une vue 3D de 288x232, pas un ecran moderne). Il est plein sur
        // toute la largeur de y=BAR_TOP a y=BAR_BOTTOM.
        BufferedImage bar = img.getSubimage(0, BAR_TOP, W, BAR_BOTTOM - BAR_TOP + 1);
        ImageIO.write(bar, "png", dir.resolve("bar.png").toFile());
        System.out.printf("[hud] bar.png (%dx%d, lignes %d..%d de la bordure)%n",
                W, BAR_BOTTOM - BAR_TOP + 1, BAR_TOP, BAR_BOTTOM);
    }

    /**
     * Les chiffres. Attention au format : les dix chiffres d'un jeu sont ENTRELACES — pour la
     * ligne y du chiffre d, le plan p est a {@code base + d + p*10 + y*largeur*10}.
     */
    private static void writeDigits(Path dir, int[] pal) throws IOException {
        int base = DrawData.draw_BorderChars_vb;
        int totalH = 0;
        for (int[] set : DIGIT_SETS) {
            totalH = Math.max(totalH, set[2]);
        }
        int rows = DIGIT_SETS.length;
        BufferedImage img = new BufferedImage(8 * 10, totalH * rows, BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < rows; r++) {
            int[] set = DIGIT_SETS[r];
            int off = set[0];
            int w = set[1];
            int h = set[2];
            for (int d = 0; d < 10; d++) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int idx = 0;
                        int at = base + off + d + y * w * 10 + (x >> 3);
                        int bit = 1 << (7 - (x & 7));
                        for (int p = 0; p < 8; p++) {
                            if ((Mem.ub(at + p * 10) & bit) != 0) {
                                idx |= 1 << p;
                            }
                        }
                        img.setRGB(d * 8 + x, r * totalH + y, idx == 0 ? 0 : pal[idx]);
                    }
                }
            }
        }
        ImageIO.write(img, "png", dir.resolve("digits.png").toFile());
        System.out.printf("[hud] digits.png (%dx%d, %d jeux de 10 chiffres, rangee de %d px)%n",
                img.getWidth(), img.getHeight(), rows, totalH);
    }
}
