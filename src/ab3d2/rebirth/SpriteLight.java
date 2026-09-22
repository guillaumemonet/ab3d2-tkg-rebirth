package ab3d2.rebirth;

import ab3d2.rebirth.sim.M68k;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Éclairage des sprites « lightsourcés » (les monstres) : port de la partie éclairage de
 * {@code drawBitmapLighted} (Objdrawhires.java:1085-1240).
 *
 * <p>Le jeu déduit de l'anneau directionnel ({@link LightRings}) une DIRECTION de lumière moyenne
 * et une luminosité, puis remplit une grille 7×7 ({@code willy}) à partir de la table
 * {@code guff} (16 positions verticales × 7 rangées × 16 directions) et d'une vignette
 * ({@code willybright}). Chaque sprite range ses couleurs en 29 GROUPES de 8 teintes : le groupe
 * choisit sa case dans la grille ({@code draw_Brights}), la case donne un NIVEAU 0..31, et la
 * couleur finale est {@code palette[variante][niveau*8 + (index & 7)]}.
 */
public final class SpriteLight {

    /** draw_Brights_vw : case de la grille 7×7 pour chacun des 29 groupes de couleurs. */
    private static final int[] BRIGHTS = {
            3, 8, 9, 10, 11, 12, 15, 16, 17, 18, 19, 21, 22, 23, 24, 25, 26, 27,
            29, 30, 31, 32, 33, 36, 37, 38, 39, 40, 45 };
    /** draw_Brights2_vw : idem, sprite retourné horizontalement. */
    private static final int[] BRIGHTS2 = {
            3, 12, 11, 10, 9, 8, 19, 18, 17, 16, 15, 27, 26, 25, 24, 23, 22, 21,
            33, 32, 31, 30, 29, 40, 39, 38, 37, 36, 45 };
    /** draw_XZAngs_vw : vecteur (x, z) de chacun des 16 secteurs. */
    private static final int[] XZ_ANGS = {
            0, 23, 10, 20, 16, 16, 20, 10, 23, 0, 20, -10, 16, -16, 10, -20,
            0, -23, -10, -20, -16, -16, -20, -10, -23, 0, -20, 10, -16, 16, -10, 20 };
    /** willybright : vignette 7×7 ajoutée à la grille. */
    private static final int[] WILLYBRIGHT = {
            30, 30, 30, 30, 30, 30, 30,
            30, 20, 20, 20, 20, 20, 30,
            30, 20, 6, 3, 6, 20, 30,
            30, 20, 6, 0, 6, 20, 30,
            30, 20, 6, 6, 6, 20, 30,
            30, 20, 20, 20, 20, 20, 30,
            30, 30, 30, 30, 30, 30, 30 };
    /** draw_MapToAng_vw. */
    private static final int[] MAP_TO_ANG = { 3, 2, 0, 1, 4, 5, 7, 6, 12, 13, 15, 14, 11, 10, 8, 9 };

    /** SINE_SIZE (data/tables_data.s). */
    private static final int SINE_SIZE = 4096;

    private static byte[] guff;

    private SpriteLight() {
    }

    /** guff : 16 positions verticales × 7 rangées × 16 directions (1792 octets). */
    private static byte[] guff() {
        if (guff == null) {
            try {
                guff = Files.readAllBytes(Assets.path("tables/guff.bin"));
            } catch (IOException e) {
                throw new UncheckedIOException("tables/guff.bin manquant "
                        + "(gradle -p rebirth extract -Pwhat=tables)", e);
            }
        }
        return guff;
    }

    public static boolean available() {
        return Assets.exists("tables/guff.bin");
    }

    /**
     * Niveaux de luminosité des 29 groupes de couleurs d'un sprite éclairé.
     *
     * @param rings       anneau directionnel calculé à la position de l'objet
     * @param viewAngle   angle de vue du joueur (Plr1_TmpAngPos_w, unités de la table sinus)
     * @param brightToAdd luminosité propre de l'objet + distance&gt;&gt;6 (draw_BrightToAdd_w)
     * @param flip        sprite retourné horizontalement (draw_FlipIt_b)
     */
    public static float[] levels(LightRings rings, int viewAngle, int brightToAdd, boolean flip) {
        // --- average_angle : direction moyenne pondérée par la luminosité des secteurs ---
        int d0 = 0;                                    // plus fort de l'anneau BAS
        int d1 = 0;                                    // plus fort de l'anneau HAUT
        int sumTopX = 0, sumTopZ = 0, sumBotX = 0, sumBotZ = 0;
        for (int i = 0; i < 16; i++) {
            int top = rings.top(i);
            if (top != 0x80) {
                int v = M68k.setw(0, -top);
                v = M68k.setw(v, v + 48);
                if ((byte) v > (byte) d1) {
                    d1 = M68k.setw(d1, v) & 0xFF;
                }
                sumTopX += M68k.muls(XZ_ANGS[i * 2], v);
                sumTopZ += M68k.muls(XZ_ANGS[i * 2 + 1], v);
            }
            int bot = rings.bottom(i);
            if (bot != 0x80) {
                int v = M68k.setw(0, -bot);
                v = M68k.setw(v, v + 48);
                if ((byte) v >= (byte) d0) {
                    d0 = M68k.setw(d0, v) & 0xFF;
                }
                sumBotX += M68k.muls(XZ_ANGS[i * 2], v);
                sumBotZ += M68k.muls(XZ_ANGS[i * 2 + 1], v);
            }
        }
        int angle = findRoughAngle(sumBotX + sumTopX, sumBotZ + sumTopZ);

        // --- position verticale (d2) et luminosité (d3) de la lumière dominante ---
        int d2 = 7;
        int d3 = d1;
        if (d1 != d0) {
            if (!(d1 > d0)) {
                d3 = d0;
            }
            d2 = M68k.setw(d0, d0 + d1);
            int t = M68k.setw(d1, (d1 & 0xFFFF) << 4);
            t = M68k.setw(t, t - 1);
            t = M68k.divs(t, d2);
            d2 = M68k.setw(d2, t) & 0xFFFF;
        }
        d3 = M68k.setw(d3, -d3);
        d3 = M68k.setw(d3, d3 + 48);

        // --- grille 7×7 depuis guff, tournée selon la vue et la direction de lumière ---
        int dir = M68k.setw(0, -viewAngle);
        dir = M68k.setw(dir, dir + SINE_SIZE);
        dir = dir & (SINE_SIZE * 2 - 1);               // AMOD_I
        dir = ((short) dir) >> 8;
        dir = ((short) dir) >> 1;
        dir = (byte) (dir - 3);
        dir = (byte) (dir + angle);
        dir = dir & 15;

        byte[] g = guff();
        int base = (short) d2 * (7 * 16);
        int[] willy = new int[49];
        int k = 0;
        for (int across = 0; across < 7; across++) {
            int d5 = dir;
            for (int down = 0; down < 7; down++) {
                int at = base + across * 16 + d5;
                int v = at >= 0 && at < g.length ? g[at] : 0;
                willy[k++] = (byte) (v + (short) d3);  // move.b puis ext.w
                d5 = (d5 + 1) & 15;
            }
        }

        // --- ajoute la luminosité de l'objet + la vignette ---
        for (int i = 0; i < 49; i++) {
            int v = M68k.setw(brightToAdd, brightToAdd + WILLYBRIGHT[i]);
            if ((short) v <= 0) {
                v = 0;
            }
            willy[i] = (short) M68k.setw(willy[i], willy[i] + v);
        }

        // --- niveau de chacun des 29 groupes de couleurs ---
        int[] cells = flip ? BRIGHTS2 : BRIGHTS;
        float[] levels = new float[cells.length];
        for (int i = 0; i < cells.length; i++) {
            int v = willy[cells[i]];
            if (v < 0) {
                v = 0;
            }
            if (v >= 31) {
                v = 31;
            }
            levels[i] = v;
        }
        return levels;
    }

    /** draw_FindRoughAngle (Objdrawhires.java:234) : secteur 0..15 d'un vecteur (x, z). */
    private static int findRoughAngle(int d4, int d5) {
        d5 = -d5;
        int d7 = 0;
        if (d4 < 0) {
            d7 += 8;
            d4 = -d4;
        }
        if (d5 < 0) {
            d5 = -d5;
            d7 += 4;
        }
        if (d4 < d5) {
            d7 += 2;
            int t = d4;
            d4 = d5;
            d5 = t;
        }
        d4 = d4 >> 1;
        if (d4 < d5) {
            d7 += 1;
        }
        return MAP_TO_ANG[d7 & 15];
    }
}
