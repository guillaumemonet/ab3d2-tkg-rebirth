package ab3d2.rebirth.sim;

import ab3d2.rebirth.Assets;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Table sinus/cosinus d'origine (SinCosTable_vw = "bigsine", extraite en
 * {@code assets/tables/sincos.bin}).
 *
 * <p>8192 mots big-endian = deux cycles de 4096 entrees ; l'angle du jeu est un OFFSET EN
 * OCTETS ({@code move.w (a1,d0.w)}) : un tour = 8192, masque AMOD_A = 8190, le cosinus est
 * l'angle + COSINE_OFS (2048). On lit la vraie table plutot que Math.sin : le deplacement du
 * joueur depend de ses arrondis.
 */
public final class SinCos {

    /** COSINE_OFS (data/tables_data.s) = SINE_SIZE/2 en offset d'octets. */
    public static final int COSINE_OFS = 2048;
    /** SINTAB_MASK_ADR. */
    public static final int MASK = 8190;

    private final short[] table;

    private SinCos(short[] table) {
        this.table = table;
    }

    public static SinCos load() {
        byte[] raw;
        try {
            raw = Files.readAllBytes(Assets.path("tables/sincos.bin"));
        } catch (IOException e) {
            throw new UncheckedIOException("tables/sincos.bin manquant (gradle -p rebirth extract -Pwhat=tables)", e);
        }
        short[] t = new short[raw.length / 2];
        for (int i = 0; i < t.length; i++) {
            t[i] = (short) (((raw[i * 2] & 0xFF) << 8) | (raw[i * 2 + 1] & 0xFF));
        }
        return new SinCos(t);
    }

    /** Valeur brute a un offset d'angle (en octets), sans masque : usage interne au port. */
    public int value(int angleBytes) {
        return table[(angleBytes & MASK) >> 1];
    }

    public int sin(int angleBytes) {
        return table[(angleBytes & MASK) >> 1];
    }

    public int cos(int angleBytes) {
        return table[((angleBytes + COSINE_OFS) & MASK) >> 1];
    }
}
