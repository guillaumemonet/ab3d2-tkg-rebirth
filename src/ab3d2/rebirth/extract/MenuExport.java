package ab3d2.rebirth.extract;

import ab3d2.Mem;
import ab3d2.data.MenunbData;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Extraction des images du MENU (menu/*.raw, menu/*.pal).
 *
 * <p>L'ecran du menu est une image a 8 plans de bits dont l'index se lit par tranches
 * (cf. {@code Vid_PresentMenu} et {@code mnu_createpalette}) :
 * <ul>
 *   <li>bits 0-1 : le FOND ({@code back2.raw}, 2 plans 320x256), qui defile ;</li>
 *   <li>bits 2-4 : le FEU, calcule chaque frame ;</li>
 *   <li>bits 5-7 : la POLICE ({@code font16x16.raw2}, 3 plans 320x176).</li>
 * </ul>
 * La couleur finale n'est pas une simple palette : la ou le feu est allume il EFFACE le fond,
 * mais melange {@code firepal[feu]} avec {@code firepal[fond]} ; ailleurs c'est {@code backpal
 * [fond]} ; et la police, si elle est posee, l'emporte sur tout ({@code fontpal[police]}).
 * On deroule ce calcul ici, une fois, en une palette de 256 entrees.
 *
 * <p>Sorties dans {@code assets/menu/} :
 * <ul>
 *   <li>{@code palette.png} (256x1) : la palette composite, prete a l'emploi ;</li>
 *   <li>{@code palettes.json} : les trois palettes sources (fond, feu, police) ;</li>
 *   <li>{@code background.png} (320x256) : l'INDEX du fond, 0..3, dans le canal rouge ;</li>
 *   <li>{@code font.png} (320x176) et {@code credits.png} (320x192) : de meme, index 0..7.</li>
 * </ul>
 * Les images d'index ne sont pas faites pour etre regardees ; {@code *_rgba.png} donne de chacune
 * la version coloriee (index 0 transparent). Celle de la police, {@code font_rgba.png}, n'est pas
 * qu'un apercu : c'est l'atlas dont le remake se sert pour redessiner le texte net.
 */
public final class MenuExport {

    private static final int SCREEN_W = 320;
    private static final int SCREEN_H = 256;
    /** Largeur d'une ligne en octets (320 px / 8). */
    private static final int ROWSIZE = SCREEN_W / 8;

    private static final int FONT_W = 320;
    private static final int FONT_H = 176;
    private static final int CREDITS_W = 320;
    private static final int CREDITS_H = 192;

    private MenuExport() {
    }

    public static void extract(Path outRoot) throws IOException {
        // Le simple acces a un champ declenche le bloc statique de MenunbData, qui charge les
        // incbin du menu (police, fond, cadre des credits, palettes).
        int backPalAddr = MenunbData.mnu_backpal;
        Path dir = outRoot.resolve("menu");
        Files.createDirectories(dir);

        int[] backPal = readPal(backPalAddr, 4);
        int[] firePal = readPal(MenunbData.mnu_firepal, 8);
        int[] fontPal = readPal(MenunbData.mnu_fontpal, 8);

        writePalette(dir, backPal, firePal, fontPal);
        writeJson(dir, backPal, firePal, fontPal);

        int[] back = planarToIndex(MenunbData.mnu_background, SCREEN_W, SCREEN_H, 2);
        writeIndex(dir, "background", back, SCREEN_W, SCREEN_H, backPal);

        int[] font = planarToIndex(MenunbData.mnu_font, FONT_W, FONT_H, 3);
        writeIndex(dir, "font", font, FONT_W, FONT_H, fontPal);

        int[] cred = planarToIndex(MenunbData.mnu_frame, CREDITS_W, CREDITS_H, 3);
        writeIndex(dir, "credits", cred, CREDITS_W, CREDITS_H, fontPal);
    }

    /** Lit n couleurs 0x00RRGGBB consecutives. */
    private static int[] readPal(int addr, int n) {
        int[] p = new int[n];
        for (int i = 0; i < n; i++) {
            p[i] = Mem.l(addr + i * 4) & 0xFFFFFF;
        }
        return p;
    }

    /**
     * Plans de bits -> index, un octet par pixel. Le plan {@code p} occupe
     * {@code p * (largeur/8) * hauteur} octets a partir de {@code base}, et le bit de poids
     * FORT du premier octet est le pixel le plus a gauche.
     */
    private static int[] planarToIndex(int base, int w, int h, int planes) {
        int rowBytes = w / 8;
        int planeSize = rowBytes * h;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = y * rowBytes + (x >> 3);
                int bit = 1 << (7 - (x & 7));
                int idx = 0;
                for (int p = 0; p < planes; p++) {
                    if ((Mem.ub(base + p * planeSize + at) & bit) != 0) {
                        idx |= 1 << p;
                    }
                }
                out[y * w + x] = idx;
            }
        }
        return out;
    }

    /**
     * {@code mnu_createpalette} (c/menu.c) deroulee : la palette composite des 256 index
     * possibles de l'ecran du menu.
     */
    private static int[] compositePalette(int[] backPal, int[] firePal, int[] fontPal) {
        int[] pal = new int[256];
        for (int c = 0; c < 256; c++) {
            if ((c & 0xe0) != 0) {                         // la POLICE l'emporte sur tout
                pal[c] = fontPal[c >> 5];
            } else if ((c & 0x1c) != 0) {                  // le FEU efface le fond, mais s'y melange
                int c1 = firePal[(c & 0x1c) >> 2];
                int c2 = firePal[c & 3];
                int r = Math.min(255, (c1 >> 16) + (c2 >> 16));
                int g = Math.min(255, ((c1 >> 8) & 0xFF) * 3 / 4 + ((c2 >> 8) & 0xFF));
                int b = Math.min(255, (c1 & 0xFF) + (c2 & 0xFF));
                pal[c] = (r << 16) | (g << 8) | b;
            } else {
                pal[c] = backPal[c & 3];
            }
        }
        return pal;
    }

    private static void writePalette(Path dir, int[] backPal, int[] firePal, int[] fontPal)
            throws IOException {
        int[] pal = compositePalette(backPal, firePal, fontPal);
        BufferedImage img = new BufferedImage(256, 1, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 256; i++) {
            img.setRGB(i, 0, pal[i]);
        }
        ImageIO.write(img, "png", dir.resolve("palette.png").toFile());
        System.out.println("[menu] palette.png (256 couleurs composites)");
    }

    private static void writeJson(Path dir, int[] backPal, int[] firePal, int[] fontPal)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("back", toList(backPal));
        m.put("fire", toList(firePal));
        m.put("font", toList(fontPal));
        String out = new com.google.gson.GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping().create().toJson(m);
        Files.writeString(dir.resolve("palettes.json"), out);
        System.out.println("[menu] palettes.json (fond 4, feu 8, police 8)");
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> l = new ArrayList<>(a.length);
        for (int v : a) {
            l.add(v);
        }
        return l;
    }

    /** Ecrit l'index brut (canal rouge) et, a cote, la version coloriee. */
    private static void writeIndex(Path dir, String name, int[] idx, int w, int h, int[] pal)
            throws IOException {
        BufferedImage raw = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage prev = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = idx[y * w + x];
                raw.setRGB(x, y, v << 16);
                prev.setRGB(x, y, v == 0 ? 0 : (0xFF000000 | pal[v]));
            }
        }
        ImageIO.write(raw, "png", dir.resolve(name + ".png").toFile());
        ImageIO.write(prev, "png", dir.resolve(name + "_rgba.png").toFile());
        System.out.printf("[menu] %s.png (%dx%d, index 0..%d)%n", name, w, h, pal.length - 1);
    }
}
