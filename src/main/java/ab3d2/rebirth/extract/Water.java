package ab3d2.rebirth.extract;

import ab3d2.Mem;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Extraction de ce qu'il faut pour l'EAU.
 *
 * <p>Dans le jeu, la surface d'eau est un « flat » de type 7 du flux graph (donc une géométrie,
 * déjà exportée avec les sols) que {@code draw_WaterSurface} (Hires.java:417) dessine en
 * ÉCHANTILLONNANT L'ÉCRAN déjà rendu, décalé verticalement, puis en mappant le pixel obtenu à
 * travers un bloc de la palette de shade ({@code Draw_TexturePalettePtr + 256*16 + distance}).
 * Le décalage horizontal vient d'un fichier de vagues ({@code waterfile}) dont 8 phases sont
 * utilisées en boucle, plus un défilement continu ({@code wateroff}).
 *
 * <p>On exporte donc les deux tables d'origine :
 * <ul>
 *   <li>{@code water_shade.lut.png} : 11 rangées (blocs 16 à 26) × 256 couleurs = la teinte de
 *       l'eau selon la distance ;</li>
 *   <li>{@code water_waves.png} : 8 rangées (les 8 phases) × 256 déplacements, encodés en
 *       niveau de gris signé (128 = zéro).</li>
 * </ul>
 */
public final class Water {

    private Water() {
    }

    /** Premier bloc de palette utilisé par la surface d'eau (a1 = palette + 256*16). */
    private static final int TINT_BLOCK = 16;
    /**
     * Blocs parcourus : la DISTANCE en ajoute 0 à 10 ({@code d0 = (dist & $3f00)*2}, borné à
     * 5*512, ajouté en octets donc 10 blocs) et la VAGUE 0 à 18 ({@code (wave*2) & $ff00}).
     */
    private static final int TINT_ROWS = 29;

    static void extract(Path outRoot, int[] pal) throws Exception {
        PortReader.bootWithAssets();
        Path dir = outRoot.resolve("textures");
        Files.createDirectories(dir);

        int shade = Mem.l(ab3d2.bss.DrawBss.Draw_TexturePalettePtr_l);
        if (shade != 0) {
            BufferedImage lut = new BufferedImage(256, TINT_ROWS, BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < TINT_ROWS; row++) {
                for (int texel = 0; texel < 256; texel++) {
                    lut.setRGB(texel, row,
                            pal[Mem.ub(shade + 256 * (TINT_BLOCK + row) + texel)]);
                }
            }
            ImageIO.write(lut, "png", dir.resolve("water_shade.lut.png").toFile());
            System.out.println("[eau] water_shade.lut.png (256x" + TINT_ROWS + ")");
        }

        if (shade != 0) {
            // Sous l'eau, le jeu repasse TOUT l'ecran par le bloc 40 (fillscrnwater,
            // Hires.java:3414) : on exporte ce bloc pour en tirer la teinte.
            BufferedImage under = new BufferedImage(256, 1, BufferedImage.TYPE_INT_RGB);
            for (int texel = 0; texel < 256; texel++) {
                under.setRGB(texel, 0, pal[Mem.ub(shade + 256 * 40 + texel)]);
            }
            ImageIO.write(under, "png", dir.resolve("water_under.lut.png").toFile());
            System.out.println("[eau] water_under.lut.png (256x1, bloc 40)");
        }

        // Vagues : 8 phases, 256 entrées chacune, lues comme des MOTS au pas de 4 octets
        // (move.w (a0,d6.w*4),d0 avec d6 = 0..255).
        int wf = ab3d2.data.DrawData.draw_WaterFrames_vb;
        int[] starts = { 0, 2, 256, 258, 512, 514, 768, 770 };
        BufferedImage waves = new BufferedImage(256, starts.length, BufferedImage.TYPE_INT_RGB);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int phase = 0; phase < starts.length; phase++) {
            for (int i = 0; i < 256; i++) {
                int v = Mem.w(wf + starts[phase] + i * 4);
                min = Math.min(min, v);
                max = Math.max(max, v);
                // La valeur sert de DECALAGE DE BLOC de palette : (wave*2) & $ff00 -> bloc.
                int g = Math.max(0, Math.min(255, (v * 2) >> 8));
                waves.setRGB(i, phase, (g << 16) | (g << 8) | g);
            }
        }
        ImageIO.write(waves, "png", dir.resolve("water_waves.png").toFile());
        System.out.println("[eau] water_waves.png (256x" + starts.length + "), vagues "
                + min + ".." + max + " -> blocs " + (min * 2 >> 8) + ".." + (max * 2 >> 8));

        writeNormalMap(dir, wf, starts);
    }

    /**
     * NORMAL MAP de l'eau, pour le rendu MOTEUR — construite à partir de la table de vagues DU
     * JEU, pour que les rides gardent leur forme d'origine.
     *
     * <p>La table est une hauteur à UNE dimension (un déplacement par colonne). On en fait un
     * champ de hauteur 2D en la lisant en diagonale, {@code h(x,y) = vague[(x + 2y) & 255]},
     * puis on dérive la normale par différences finies. Le résultat est une texture répétable de
     * 128×128, au format tangent classique ({@code RGB = (nx, ny, nz) * 0.5 + 0.5}).
     */
    private static void writeNormalMap(Path dir, int wf, int[] starts) throws Exception {
        final int n = 128;
        float[][] h = new float[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                // Deux phases superposées : une seule donne des bandes trop régulières.
                int a = Mem.w(wf + starts[0] + (((x + 2 * y) & 255) * 4));
                int b = Mem.w(wf + starts[4] + (((2 * x - y) & 255) * 4));
                h[y][x] = (a + b) / 512f;
            }
        }
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float dx = h[y][(x + 1) % n] - h[y][(x + n - 1) % n];
                float dy = h[(y + 1) % n][x] - h[(y + n - 1) % n][x];
                float nx = -dx;
                float ny = -dy;
                float nz = 1.4f;                       // aplatit : une eau trop bosselee scintille
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                int r = clamp((int) ((nx / len) * 127.5f + 127.5f));
                int g = clamp((int) ((ny / len) * 127.5f + 127.5f));
                int bb = clamp((int) ((nz / len) * 127.5f + 127.5f));
                img.setRGB(x, y, (r << 16) | (g << 8) | bb);
            }
        }
        ImageIO.write(img, "png", dir.resolve("water_normal.png").toFile());
        System.out.println("[eau] water_normal.png (" + n + "x" + n + ", derivee de la table de vagues)");
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
