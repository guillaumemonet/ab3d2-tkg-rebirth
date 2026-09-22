package ab3d2.rebirth.sim;

/**
 * Semantique 68000 utilisee par les portages de la simulation (mots signes, muls, divs).
 *
 * <p>Le remake garde ces operations exactes : les algorithmes d'origine dependent des
 * troncatures 16 bits et du comportement de divs (regle du projet : pas d'approximation).
 */
public final class M68k {

    private M68k() {
    }

    /** Mot bas signe (ext.w). */
    public static int s16(int v) {
        return (short) v;
    }

    /** move.w vN,dN : remplace le mot bas de reg, garde le mot haut. */
    public static int setw(int reg, int v) {
        return (reg & 0xFFFF0000) | (v & 0xFFFF);
    }

    /** muls.w : produit signe 16x16 -> 32. */
    public static int muls(int a, int b) {
        return (short) a * (short) b;
    }

    /**
     * divs.w : division signee 32/16 tronquee vers zero.
     * Division par zero (trap 68k) et debordement (|q| &gt; 32767) laissent dN inchange.
     */
    /**
     * Le RESTE d'une division signee, celui que le 68k laisse dans le mot FORT de Dn apres
     * {@code divs.w} (l'original le recupere par un {@code swap}). {@link #divs} ne rend que le
     * quotient : tout code qui a besoin du reste doit passer par ici.
     *
     * <p>Le reste prend le signe du DIVIDENDE, comme sur 68k et comme {@code %} en Java.
     */
    public static int divsRem(int dn, int src) {
        int dv = (short) src;
        if (dv == 0) {
            return 0;
        }
        return dn % dv;
    }

    /**
     * ATTENTION : ne rend que le QUOTIENT. Le {@code divs.w} du 68k rend en plus le reste dans
     * le mot fort ; pour celui-la, voir {@link #divsRem}.
     */
    public static int divs(int dn, int src) {
        int dv = (short) src;
        if (dv == 0) {
            return dn;
        }
        int q = Math.abs(dn) / Math.abs(dv);
        if ((dn < 0) != (dv < 0)) {
            q = -q;
        }
        if (q < -32768 || q > 32767) {
            return dn;
        }
        return q;
    }

    /** asr.w #n (decalage arithmetique sur le mot bas). */
    public static int asrw(int reg, int n) {
        return setw(reg, ((short) reg) >> n);
    }

    /** neg.w */
    public static int negw(int reg) {
        return setw(reg, -(short) reg);
    }
}
