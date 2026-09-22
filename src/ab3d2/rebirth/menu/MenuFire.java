package ab3d2.rebirth.menu;

/**
 * Le FEU du menu et les plans de bits qui le portent — port litteral de {@code mnu_initrnd},
 * {@code getrnd} (menu/menunb.s) et {@code mnu_dofire} (c/menu.c).
 *
 * <p>L'ecran du menu est une image a 8 plans de bits de 320x256 :
 * <ul>
 *   <li>plans 0-1 : le FOND, qui defile ({@link #screen}, chaque plan stocke DEUX fois de suite
 *       pour que le defilement reboucle sans copie) ;</li>
 *   <li>plans 2-4 : le FEU ({@link #more}, plans 0..2) ;</li>
 *   <li>plans 5-7 : la POLICE ({@link #more}, plans 3..5).</li>
 * </ul>
 *
 * <p>Le feu n'est pas un automate sur pixels : c'est trois blits du blitter Amiga par frame,
 * {@code D = A_decale | (B & C)} — A = un plan de POLICE (d'ou le texte qui brule), B une source
 * de bruit precalculee par un LFSR, C le plan de feu une ligne plus bas. Les trois pointeurs de
 * source tournent a chaque frame et le decalage change sur un cycle de quatre, ce qui fait
 * monter et vaciller les flammes. On reproduit le blit tel quel, mot a mot.
 *
 * <p>Comme dans le portage, {@code mnu_plot} (les cinquante etincelles de Lissajous) n'est PAS
 * joue : l'emulation logicielle du blit ne « refroidit » pas comme le materiel, et les points
 * semes s'accumulent jusqu'a saturer l'ecran.
 */
public final class MenuFire {

    public static final int SCREEN_W = 320;
    public static final int SCREEN_H = 256;
    /** Largeur d'une ligne, en octets. */
    public static final int ROWSIZE = SCREEN_W / 8;         // 40
    /** Taille d'un plan de bits. */
    public static final int PLANESIZE = ROWSIZE * SCREEN_H; // 10240

    /** {@code mnu_speed} : de combien de lignes le feu monte par passe. */
    private static final int SPEED = 1;
    /** {@code mnu_size} : hauteur traitee par le blit. */
    private static final int SIZE = 256;
    /** Decalage de la source de bruit dans {@link #more}. */
    private static final int NOISE = 6 * PLANESIZE;
    /** Taille de la source de bruit ({@code 40*256 + 8192} octets). */
    private static final int NOISE_SIZE = ROWSIZE * 256 + 8192;

    /** Plans 0-1 du fond, chacun stocke deux fois (defilement circulaire). */
    public final byte[] screen = new byte[4 * PLANESIZE];
    /** Plans du feu (0..2), de la police (3..5), puis la source de bruit. */
    public final byte[] more = new byte[NOISE + NOISE_SIZE];

    /** {@code mnu_sourceptrs} : les trois plans de police, dans l'ordre courant. */
    private final int[] sourcePtrs = {
        3 * PLANESIZE + ROWSIZE,
        4 * PLANESIZE + ROWSIZE,
        5 * PLANESIZE + ROWSIZE,
    };
    private int rnd;
    private int rndPtr;
    private int count;
    private int subtract;
    /** {@code main_counter} : le feu n'avance qu'une frame sur deux. */
    private long counter;
    /** {@code mnu_screenpos} : la ligne du fond affichee en haut. */
    private int screenPos;

    /**
     * Monte la source de bruit. Le jeu construit d'abord une table de PARITE de 256 octets
     * (parite des bits 0, 2, 3 et 5), puis deroule un LFSR de graine {@code 'TBL!'}.
     */
    public MenuFire(byte[] backgroundPlanes) {
        // Fond : chaque plan est ecrit deux fois de suite, pour que le defilement d'une ligne
        // par frame reboucle sans jamais recopier (mnu_init).
        for (int i = 0; i < PLANESIZE; i++) {
            byte p0 = backgroundPlanes[i];
            byte p1 = backgroundPlanes[PLANESIZE + i];
            screen[i] = p0;
            screen[PLANESIZE + i] = p0;
            screen[2 * PLANESIZE + i] = p1;
            screen[3 * PLANESIZE + i] = p1;
        }
        initRnd();
    }

    /** {@code mnu_initrnd}. */
    private void initRnd() {
        byte[] parity = new byte[256];
        for (int d0 = 255; d0 >= 0; --d0) {
            int d1 = d0 & 1;
            d1 ^= (d0 >> 2) & 1;
            d1 ^= (d0 >> 3) & 1;
            d1 ^= (d0 >> 5) & 1;
            parity[d0] = (byte) d1;
        }
        int d3 = 0x54424C21;                                // 'TBL!'
        int at = NOISE;
        for (int n = NOISE_SIZE; n > 0; --n) {
            int d1;
            int d2 = d3;
            d1 = d2 & 0xFF;
            d2 &= 0xFFFFFFFE;
            d1 = parity[d1] & 0xFF;
            d2 = (d2 & ~0xFF) | ((d2 | d1) & 0xFF);
            d2 = rorw1(d2);
            d2 = (d2 >>> 16) | (d2 << 16);                  // swap
            d1 = d2 & 0xFF;
            d2 &= 0xFFFFFFFE;
            d1 = parity[d1] & 0xFF;
            d2 = (d2 & ~0xFF) | ((d2 | d1) & 0xFF);
            d2 = rorw1(d2);
            d3 = d2;
            d1 = d2 & 0xFFFF;
            d1 >>>= 8;
            d2 = (d2 & ~0xFFFF) | ((d2 | d1) & 0xFFFF);
            d1 = d2;
            d1 = (d1 >>> 16) | (d1 << 16);                  // swap
            d2 = (d2 & ~0xFFFF) | ((d2 | d1) & 0xFFFF);
            more[at++] = (byte) d2;
        }
    }

    /** {@code ror.w #1} : rotation d'un cran du seul mot bas. */
    private static int rorw1(int v) {
        int lw = v & 0xFFFF;
        lw = ((lw >>> 1) | (lw << 15)) & 0xFFFF;
        return (v & ~0xFFFF) | lw;
    }

    /** {@code getrnd} : avance la fenetre lue dans la source de bruit. */
    private void getRnd() {
        rndPtr = NOISE + (rnd & 8190);
        rnd = (rnd + 5) & 0xFFFF;
    }

    /**
     * Une frame de menu ({@code mnu_vblint}) : le fond defile d'une ligne, puis le feu avance
     * une frame sur deux.
     */
    public void frame() {
        counter++;
        screenPos = (screenPos + 1) & 0xFFFF;
        doFire();
    }

    /** La ligne du fond affichee en haut, 0..255. */
    public int scrollLine() {
        return screenPos & 255;
    }

    public long counter() {
        return counter;
    }

    /** {@code mnu_dofire}. */
    private void doFire() {
        if ((counter & 1) != 0) {
            return;
        }
        // Le jeu echantillonne le compteur de faisceau video ; sans faisceau, le portage prend
        // un substitut qui varie par frame. Cela ne deplace que la fenetre de bruit lue.
        rnd = (rnd + 0xA5 + (int) (counter & 0xFF)) & 0xFFFF;

        int s0 = sourcePtrs[0];                             // rotation des trois sources
        sourcePtrs[0] = sourcePtrs[1];
        sourcePtrs[1] = sourcePtrs[2];
        sourcePtrs[2] = s0;

        int cnt = count & 3;
        count = (count + 1) & 0xFFFF;
        int ash;
        subtract = 0;
        switch (cnt) {
            case 0 -> ash = 1;
            case 1 -> { ash = 15; subtract = -2; }
            default -> ash = 0;
        }
        for (int i = 0; i < 3; i++) {
            getRnd();
            fireBlit(sourcePtrs[i] - subtract, rndPtr,
                     i * PLANESIZE + SPEED * ROWSIZE, i * PLANESIZE, ash);
        }
    }

    /**
     * Le blit du feu : 255 lignes de 20 mots, {@code D = A_decale | (B & C)}. Le canal A passe
     * par le barrel shifter du blitter, qui reporte le mot precedent dans les bits sortants.
     */
    private void fireBlit(int aBase, int bBase, int cBase, int dBase, int ash) {
        int words = (SIZE - SPEED) * (ROWSIZE / 2);         // 255 * 20
        int prev = 0;
        for (int k = 0; k < words; k++) {
            int curA = word(aBase + k * 2);
            int aSh = (((prev << 16) | curA) >>> ash) & 0xFFFF;
            prev = curA;
            int bw = word(bBase + k * 2);
            int cw = word(cBase + k * 2);
            int d = aSh | (bw & cw);
            more[dBase + k * 2] = (byte) (d >> 8);
            more[dBase + k * 2 + 1] = (byte) d;
        }
    }

    private int word(int at) {
        return ((more[at] & 0xFF) << 8) | (more[at + 1] & 0xFF);
    }

    // ---- plans de police (3..5) : c'est ce que le feu devore ----

    /** {@code mnu_cls} : efface les trois plans de police. */
    public void clearText() {
        java.util.Arrays.fill(more, 3 * PLANESIZE, 6 * PLANESIZE, (byte) 0);
    }

    /**
     * Pose un glyphe de 16x16 dans les plans de police, a {@code xByte} octets et {@code y}
     * pixels ({@code mnu_printxy}). {@code glyph} est l'index dans l'atlas de police
     * (caractere - 32) ; l'atlas fait 20 glyphes par rangee.
     *
     * @param fontPlanes les trois plans de {@code font16x16.raw2}, 40 octets par ligne
     */
    public void putGlyph(byte[] fontPlanes, int glyph, int xByte, int y) {
        if (glyph < 0 || glyph >= 220) {
            return;
        }
        final int fontPlane = ROWSIZE * 176;
        int off = (glyph / 20) * (ROWSIZE * 16) + (glyph % 20) * 2;
        int dst = y * ROWSIZE + xByte;
        for (int row = 0; row < 16; row++) {
            if (dst < 0 || dst + 1 >= PLANESIZE) {
                break;
            }
            for (int p = 0; p < 3; p++) {
                int src = p * fontPlane + off;
                more[(3 + p) * PLANESIZE + dst] = fontPlanes[src];
                more[(3 + p) * PLANESIZE + dst + 1] = fontPlanes[src + 1];
            }
            off += ROWSIZE;
            dst += ROWSIZE;
        }
    }

    /**
     * Copie l'image des credits (3 plans 320x192) dans les plans de police, a la ligne 32
     * ({@code mnu_copycredz}) : elle brule comme le texte.
     */
    public void putCredits(byte[] creditPlanes) {
        final int cp = ROWSIZE * 192;
        int dst = 32 * ROWSIZE;
        for (int i = 0; i < cp; i++) {
            for (int p = 0; p < 3; p++) {
                more[(3 + p) * PLANESIZE + dst + i] = creditPlanes[p * cp + i];
            }
        }
    }

    /**
     * Compose les 8 plans en index de palette, une frame d'ecran de 320x256.
     * Reprend {@code Vid_PresentMenu} : le fond est lu avec son decalage de defilement.
     */
    public void composite(byte[] out) {
        int scroll = scrollLine() * ROWSIZE;
        int p0 = scroll;
        int p1 = scroll + PLANESIZE * 2;
        int pi = 0;
        for (int y = 0; y < SCREEN_H; y++) {
            int ro = y * ROWSIZE;
            for (int xb = 0; xb < ROWSIZE; xb++) {
                int b0 = screen[p0 + ro + xb] & 0xFF;
                int b1 = screen[p1 + ro + xb] & 0xFF;
                int b2 = more[ro + xb] & 0xFF;
                int b3 = more[PLANESIZE + ro + xb] & 0xFF;
                int b4 = more[2 * PLANESIZE + ro + xb] & 0xFF;
                int b5 = more[3 * PLANESIZE + ro + xb] & 0xFF;
                int b6 = more[4 * PLANESIZE + ro + xb] & 0xFF;
                int b7 = more[5 * PLANESIZE + ro + xb] & 0xFF;
                for (int bit = 7; bit >= 0; bit--) {
                    out[pi++] = (byte) (((b0 >> bit) & 1)
                            | (((b1 >> bit) & 1) << 1)
                            | (((b2 >> bit) & 1) << 2)
                            | (((b3 >> bit) & 1) << 3)
                            | (((b4 >> bit) & 1) << 4)
                            | (((b5 >> bit) & 1) << 5)
                            | (((b6 >> bit) & 1) << 6)
                            | (((b7 >> bit) & 1) << 7));
                }
            }
        }
    }
}
