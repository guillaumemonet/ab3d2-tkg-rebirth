package ab3d2.rebirth.extract;

import ab3d2.rebirth.Assets;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Sort ce que contient l'INTRO du jeu.
 *
 * <p>La séquence de démarrage des disquettes de boot tient en deux lignes :
 * <pre>
 *   intro.exe
 *   tkg1:tkg
 * </pre>
 * L'intro est donc un programme à part, lancé avant le jeu. C'est un exécutable Amiga
 * <b>crunché</b> (StoneCracker 4.04), ce qui explique qu'on n'y voie rien en l'ouvrant :
 * {@link StoneCracker} le déplie, puis déroule le flux de hunks qu'il contient.
 *
 * <p>Ce qu'on y trouve est une démo auto-suffisante — aucun nom de fichier dans ses chaînes,
 * tout est embarqué :
 * <ul>
 *   <li>hunk 0, ~58 Ko en mémoire rapide : le code, ses tables, et les images ;</li>
 *   <li>hunk 1, 1,4 Mo : un tampon de travail, vide dans le fichier ;</li>
 *   <li>hunk 2, ~10 Ko en mémoire CHIP : ce que le matériel lit directement ;</li>
 *   <li>hunk 3, 313 Ko en CHIP : les écrans, vides dans le fichier.</li>
 * </ul>
 *
 * <p>Les images sont du <b>chunky 4 bits</b> — un octet par pixel, valeurs 0 à 15 — rangé
 * <b>colonne par colonne</b> et de bas en haut. On les repère sans rien deviner : ce sont les
 * plages où aucun octet ne dépasse 15, d'une longueur multiple de 40 (la hauteur). La plus
 * grande fait 320 × 40 : le titre « Alien Breed 3D II », anticrénelé sur 16 niveaux.
 *
 * <p>La palette, elle, n'est pas dans ces données : l'intro la construit à l'exécution. Les PNG
 * sortent donc en niveaux de gris, qui rendent fidèlement l'anticrénelage d'origine.
 *
 * <pre>
 * gradle -p rebirth intro
 * </pre>
 */
public final class IntroExport {

    /** Hauteur des bandes chunky de l'intro, en pixels (= octets par colonne). */
    private static final int BAND_H = 40;
    /** En deçà, une plage d'octets ≤ 15 est un hasard, pas une image. */
    private static final int MIN_RUN = 2000;

    private IntroExport() {
    }

    public static void main(String[] args) throws IOException {
        byte[] exe;
        try {
            exe = ab3d2.Assets.bytes("intro.exe");
        } catch (IOException absent) {
            System.err.println("[intro] intro.exe introuvable : il est sur les disquettes de BOOT "
                    + "(1 et 3). Lancer d'abord l'extraction des assets.");
            System.exit(1);
            return;
        }
        System.out.printf("[intro] intro.exe : %d octets packes%n", exe.length);

        StoneCracker.Image img = StoneCracker.unpackExe(exe);
        Path out = Assets.root().resolve("intro");
        Files.createDirectories(out);

        for (int i = 0; i < img.hunks().size(); i++) {
            byte[] h = img.hunks().get(i);
            int used = used(h);
            System.out.printf("[intro] hunk %d : alloue %7d o  %-4s  donnees %7d o%n",
                    i, img.sizes().get(i), img.chip().get(i) ? "CHIP" : "FAST", used);
            if (used > 0) {
                byte[] trimmed = new byte[used];
                System.arraycopy(h, 0, trimmed, 0, used);
                Files.write(out.resolve("hunk" + i + ".bin"), trimmed);
            }
        }

        int n = 0;
        for (byte[] h : img.hunks()) {
            if (used(h) == 0) {
                continue;                       // hunk de travail : alloue, jamais charge
            }
            for (int[] run : chunkyRuns(h)) {
                int cols = (run[1] - run[0]) / BAND_H;
                BufferedImage png = band(h, run[0], cols);
                String name = String.format("banniere_%dx%d.png", cols, BAND_H);
                ImageIO.write(png, "png", out.resolve(name).toFile());
                System.out.printf("[intro]   image %3d x %d  (offset 0x%X)  -> %s%n",
                        cols, BAND_H, run[0], name);
                n++;
            }
        }
        System.out.printf("[intro] %d image(s) dans %s%n", n, out);
    }

    /** Longueur utile : le hunk est alloué bien plus grand que ce que le fichier remplit. */
    private static int used(byte[] h) {
        int e = h.length;
        while (e > 0 && h[e - 1] == 0) {
            e--;
        }
        return e;
    }

    /**
     * Plages où aucun octet ne dépasse 15 et dont la longueur tient un nombre entier de
     * colonnes : les images chunky. Tout le reste du hunk est du code ou des tables.
     */
    private static List<int[]> chunkyRuns(byte[] h) {
        List<int[]> runs = new ArrayList<>();
        int s = -1;
        for (int i = 0; i <= h.length; i++) {
            boolean low = i < h.length && (h[i] & 0xFF) <= 15;
            if (low) {
                if (s < 0) {
                    s = i;
                }
            } else {
                if (s >= 0 && i - s >= MIN_RUN) {
                    int cols = (i - s) / BAND_H;
                    if (cols > 0 && !blank(h, s, cols * BAND_H)) {
                        runs.add(new int[] {s, s + cols * BAND_H});
                    }
                }
                s = -1;
            }
        }
        return runs;
    }

    /** Une plage de zeros n'est pas une image : c'est de la memoire mise a blanc. */
    private static boolean blank(byte[] h, int off, int len) {
        for (int i = off; i < off + len; i++) {
            if (h[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** Une bande chunky : colonne par colonne, de bas en haut, en niveaux de gris. */
    private static BufferedImage band(byte[] h, int off, int cols) {
        BufferedImage img = new BufferedImage(cols, BAND_H, BufferedImage.TYPE_INT_RGB);
        for (int c = 0; c < cols; c++) {
            for (int y = 0; y < BAND_H; y++) {
                int v = h[off + c * BAND_H + y] & 15;
                int g = v * 255 / 15;
                img.setRGB(c, BAND_H - 1 - y, (g << 16) | (g << 8) | g);
            }
        }
        return img;
    }
}
