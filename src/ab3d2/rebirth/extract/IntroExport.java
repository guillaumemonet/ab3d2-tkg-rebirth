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
        for (Asset a : ASSETS) {
            BufferedImage png = grey(code, a);
            String file = String.format("%s_%dx%d.png", a.name(), a.w(), a.h());
            ImageIO.write(png, "png", out.resolve(file).toFile());
            System.out.printf("[intro]   %-7s %3d x %2d  (offset 0x%X)  -> %s%n",
                    a.name(), a.w(), a.h(), a.off(), file);
        }
        System.out.printf("[intro] %d image(s) dans %s%n", ASSETS.length, out);
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
