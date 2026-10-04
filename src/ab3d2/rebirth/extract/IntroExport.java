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

    /**
     * Les trois images de l'intro, aux adresses où l'intro elle-même va les chercher.
     *
     * <p>Rien n'est deviné ici : le désassemblage du hunk 0 donne les trois instructions.
     * {@code 0x310C : lea $75f8,a0} charge le titre et l'estampe, colonne par colonne et au
     * pas de 0x201, dans deux tables de hunk 1 — c'est-à-dire qu'il le grave en relief dans la
     * carte de bosses que le moteur affiche ensuite. {@code 0x316A : lea $a7f8,a0} et
     * {@code 0x317E : lea $c5f8,a0} passent les deux logos à une routine de conversion
     * chunky → plans de bits qui écrit dans la mémoire CHIP par rangées de 20 octets, d'où
     * leurs 160 pixels de large.
     */
    private record Asset(int off, int w, int h, boolean colMajor, String name) { }

    private static final Asset[] ASSETS = {
        new Asset(0x75F8, 320, 40, true,  "titre"),       // « Alien Breed 3D II »
        new Asset(0xA7F8, 160, 48, false, "team17"),
        new Asset(0xC5F8, 160, 48, false, "ocean"),
    };

    /**
     * La palette source, en 0x6F78 : quatre bancs de 32 couleurs, quatre octets chacune
     * ({@code 00 RR GG BB}).
     *
     * <p>Le constructeur est en {@code $320E}. Il lit cette table, met chaque composante à
     * l'échelle d'un facteur de fondu, puis la coupe en deux — poids forts et poids faibles —
     * pour écrire DEUX listes Copper encadrées de {@code BPLCON3 = $0000} et {@code $0200}.
     * C'est le procédé AGA qui donne 8 bits par canal là où le Copper n'en adresse que 4. Les
     * bancs 1 et 3 sont une roue de teintes suivie d'une rampe de gris, à l'usage du moteur ;
     * les bancs 0 (rose) et 2 (bleu) habillent l'écran des logos, que l'intro fait passer de
     * l'un à l'autre — les deux logos sont écrits côte à côte dans le MÊME écran de 320
     * ({@code $316A} : hunk 3 +0 et +20 octets), ils partagent donc forcément leurs couleurs.
     */
    private static final int PALETTE = 0x6F78;
    private static final int BANKS = 4;
    private static final int COLORS = 32;
    /** Les deux bancs qui habillent les logos. */
    private static final int[] LOGO_BANKS = {0, 2};

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

        byte[] code = img.hunks().get(0);
        int n = 0;
        for (Asset a : ASSETS) {
            String file = String.format("%s_%dx%d.png", a.name(), a.w(), a.h());
            ImageIO.write(grey(code, a), "png", out.resolve(file).toFile());
            System.out.printf("[intro]   %-7s %3d x %2d  (offset 0x%X)  -> %s%n",
                    a.name(), a.w(), a.h(), a.off(), file);
            n++;
            if (a.colMajor()) {
                continue;                       // le titre n'a pas de palette : voir plus bas
            }
            for (int b : LOGO_BANKS) {
                String f = String.format("%s_banc%d.png", a.name(), b);
                ImageIO.write(color(code, a, b), "png", out.resolve(f).toFile());
                System.out.printf("[intro]   %-7s banc %d                        -> %s%n",
                        a.name(), b, f);
                n++;
            }
        }
        ImageIO.write(swatches(code), "png", out.resolve("palette.png").toFile());
        System.out.printf("[intro]   palette %d bancs de %d                     -> palette.png%n",
                BANKS, COLORS);
        System.out.printf("[intro] %d image(s) dans %s%n", n + 1, out);
    }

    /** Une couleur de la table source : quatre octets, le premier inutilise. */
    private static int rgb(byte[] h, int bank, int i) {
        int a = PALETTE + bank * COLORS * 4 + i * 4;
        return ((h[a + 1] & 0xFF) << 16) | ((h[a + 2] & 0xFF) << 8) | (h[a + 3] & 0xFF);
    }

    /** Une image avec ses vraies couleurs. */
    private static BufferedImage color(byte[] h, Asset a, int bank) {
        BufferedImage img = new BufferedImage(a.w(), a.h(), BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < a.w() * a.h(); i++) {
            int v = a.off() + i < h.length ? h[a.off() + i] & 0xFF : 0;
            img.setRGB(i % a.w(), i / a.w(), rgb(h, bank, Math.min(v, COLORS - 1)));
        }
        return img;
    }

    /** Les quatre bancs en nuancier, une ligne par banc. */
    private static BufferedImage swatches(byte[] h) {
        int cell = 12;
        BufferedImage img = new BufferedImage(COLORS * cell, BANKS * cell,
                BufferedImage.TYPE_INT_RGB);
        for (int b = 0; b < BANKS; b++) {
            for (int i = 0; i < COLORS; i++) {
                int c = rgb(h, b, i);
                for (int y = 0; y < cell; y++) {
                    for (int x = 0; x < cell; x++) {
                        img.setRGB(i * cell + x, b * cell + y, c);
                    }
                }
            }
        }
        return img;
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
     * Une image de l'intro, en niveaux de gris.
     *
     * <p>Un octet par pixel. Le titre est rangé <b>colonne par colonne et de bas en haut</b>,
     * les logos ligne par ligne. La palette, elle, n'est pas dans les données : l'intro la
     * construit à l'exécution. On normalise donc sur le maximum de l'image, ce qui restitue
     * l'anticrénelage d'origine mais pas ses couleurs.
     */
    private static BufferedImage grey(byte[] h, Asset a) {
        int max = 1;
        for (int i = 0; i < a.w() * a.h(); i++) {
            max = Math.max(max, h[a.off() + i] & 0xFF);
        }
        BufferedImage img = new BufferedImage(a.w(), a.h(), BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < a.w() * a.h(); i++) {
            int x = a.colMajor() ? i / a.h() : i % a.w();
            int y = a.colMajor() ? a.h() - 1 - i % a.h() : i / a.w();
            int g = (h[a.off() + i] & 0xFF) * 255 / max;
            img.setRGB(x, y, (g << 16) | (g << 8) | g);
        }
        return img;
    }
}
