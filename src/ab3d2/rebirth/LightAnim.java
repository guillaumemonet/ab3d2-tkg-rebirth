package ab3d2.rebirth;

import ab3d2.rebirth.sim.M68k;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lumières ANIMÉES : port de {@code brightanim} (Newanims.java:497) et du calcul des luminosités
 * de points de {@code computeZoneBrightness} (Hires.java:2805-2840).
 *
 * <p>Les sept animations sont codées en dur dans le jeu (NewanimsData) : cinq « pulses » et deux
 * « flickers ». Chaque frame, chaque animation avance d'un cran dans sa séquence de mots et sa
 * valeur courante va dans {@code Anim_BrightTable}. Un point de niveau dont l'octet fort est non
 * nul (et l'octet bas ≥ 0) suit une de ces lumières :
 * <pre>
 *   lumiere = quartet faible de l'octet fort  (index dans Anim_BrightTable, 1..7)
 *   poids   = (quartet fort de l'octet fort) + 1
 *   v = base + ((Anim_BrightTable[lumiere-1] - base) * poids) &gt;&gt; 4
 * </pre>
 * puis la conversion commune {@code v = (v*397)>>8 ; si v<0 v -= 600 ; v += 300}.
 */
public final class LightAnim {

    /** Fin d'une séquence d'animation (BRIGHT_ANIM_END). */
    private static final int END = 999;

    /** Les sept séquences d'origine, dans l'ordre de anim_BrightessAnimPtrs_vl. */
    private static final int[][] SEQUENCES = {
            concat(seq(1, 20), seq(20, 1)),
            concat(seq(9, 20), seq(20, 1), seq(1, 8)),
            concat(seq(17, 20), seq(20, 1), seq(1, 16)),
            concat(seq(16, 1), seq(1, 20), seq(20, 17)),
            concat(seq(8, 1), seq(1, 20), seq(20, 9)),
            concat(rep(20, 20), new int[] { 1 }, rep(30, 20), new int[] { 1 }, rep(5, 20), new int[] { 1 }),
            { -10, -9, -6, -10, -6, -5, -5, -7, -5, -10, -9, -8, -7, -5, -5, -5, -5,
              -5, -5, -5, -5, -6, -7, -8, -9, -5, -10, -9, -10, -6, -5, -5, -5, -5, -5,
              -5, -5 },
    };

    /** Anim_BrightTable_vw : 20 entrées, seules les 7 premières sont animées. */
    private final int[] brightTable = new int[20];
    private final int[] cursor = new int[SEQUENCES.length];
    /** Anim_Timer_w : brightanim n'avance qu'une frame sur six (compte a rebours depuis 5). */
    private int timer;

    private final Map<Integer, LevelData.Zone> zones;
    private final Map<Integer, int[]> computed = new HashMap<>();
    private boolean dirty = true;

    /**
     * CurrentPointBrights_vl : les luminosites de la frame COURANTE, 40 mots par zone, a plat
     * comme dans le jeu — les lumieres DYNAMIQUES ({@link DynLights}) ecrivent dedans, et leur
     * debordement d'une zone sur la suivante fait partie du comportement d'origine.
     */
    private int[] current;
    /** L'etat de la frame precedente : sert a savoir s'il faut redessiner. */
    private int[] previous;
    /** Les valeurs STATIQUES (animation de luminosite comprise), sans les lumieres dynamiques. */
    private int[] base;
    private boolean baseDirty = true;

    public LightAnim(Map<Integer, LevelData.Zone> zones) {
        this.zones = zones;
        advance();                                     // pose les valeurs de la 1re frame
    }

    /**
     * Une frame de jeu : {@code Anim_Timer_w} décroît, et brightanim n'avance que lorsqu'il atteint
     * zéro, avec remise à 5 (Hires.java:4724 et Newanims.java:1331) — soit une frame sur SIX.
     */
    public boolean tick() {
        timer--;
        if (timer > 0) {
            return false;
        }
        timer = 5;
        return advance();
    }

    /**
     * brightanim : avance chaque séquence d'un cran. Renvoie true si une valeur a changé
     * (auquel cas les luminosités de points doivent être recalculées).
     */
    public boolean advance() {
        boolean changed = false;
        for (int i = 0; i < SEQUENCES.length; i++) {
            int v = SEQUENCES[i][cursor[i]];
            cursor[i] = (cursor[i] + 1) % SEQUENCES[i].length;   // BRIGHT_ANIM_END -> retour au début
            if (brightTable[i] != v) {
                brightTable[i] = v;
                changed = true;
            }
        }
        if (changed) {
            computed.clear();
            baseDirty = true;
            dirty = true;
        }
        return changed;
    }

    /** True si les luminosités ont changé depuis le dernier {@link #clearDirty()}. */
    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    /** Luminosité courante d'un point (index 0..39 dans la zone), équivalent CurrentPointBrights. */
    public int pointBright(int zoneId, int idx) {
        int[] cur = live();
        int at = zoneId * SLOTS + idx;
        return idx >= 0 && idx < SLOTS && at >= 0 && at < cur.length ? cur[at] : 0;
    }

    /** Mots de luminosité par zone (10 points de bordure x 4 coins). */
    public static final int SLOTS = 40;

    /**
     * CurrentPointBrights_vl, a plat : c'est la-dedans que {@link DynLights} ecrit. Alloue a la
     * demande pour que les outils qui n'animent rien continuent de marcher.
     */
    public int[] live() {
        if (current == null) {
            int max = 0;
            for (Integer id : zones.keySet()) {
                max = Math.max(max, id + 1);
            }
            base = new int[max * SLOTS];
            current = new int[max * SLOTS];
            previous = new int[max * SLOTS];
            rebuildBase();
            System.arraycopy(base, 0, current, 0, base.length);
        }
        return current;
    }

    /**
     * Debut d'une frame de simulation : les luminosites repartent des valeurs STATIQUES, comme
     * le jeu qui recalcule CurrentPointBrights (hires.s, doneallz) avant de lancer les objets.
     */
    public void beginFrame() {
        int[] cur = live();
        System.arraycopy(cur, 0, previous, 0, cur.length);
        if (baseDirty) {
            rebuildBase();
            baseDirty = false;
        }
        System.arraycopy(base, 0, cur, 0, base.length);
    }

    /** Fin de frame : si une lumiere dynamique a bouge, le rendu doit se rafraichir. */
    public void endFrame() {
        if (current != null && !java.util.Arrays.equals(current, previous)) {
            dirty = true;
        }
    }

    private void rebuildBase() {
        java.util.Arrays.fill(base, 0);
        for (Map.Entry<Integer, LevelData.Zone> e : zones.entrySet()) {
            int id = e.getKey();
            if (id < 0 || (id + 1) * SLOTS > base.length) {
                continue;
            }
            int[] vals = compute(id);
            System.arraycopy(vals, 0, base, id * SLOTS, SLOTS);
        }
    }

    private int[] compute(int zoneId) {
        LevelData.Zone z = zones.get(zoneId);
        int[] out = new int[40];
        if (z == null) {
            return out;
        }
        List<Integer> raw = z.pointBrightsRaw;
        for (int i = 0; i < out.length; i++) {
            if (raw != null && i < raw.size()) {
                out[i] = fromRaw(raw.get(i));
            } else if (z.pointBrights != null && i < z.pointBrights.size()) {
                out[i] = z.pointBrights.get(i);         // extraction ancienne : valeur statique
            }
        }
        return out;
    }

    /** Un mot brut de PointBrights -> luminosité courante (Hires.java:2810-2836). */
    private int fromRaw(int word) {
        int d2 = word;
        if ((byte) d2 >= 0) {                          // tst.b d2 ; blt .justbright
            int d3 = (d2 & 0xFFFF) >>> 8;              // octet fort
            if ((byte) d3 != 0) {
                int d4 = d3;
                d3 = d3 & 0xF;                         // index de lumière
                d4 = (d4 & 0xFFFF) >>> 4;
                d4 = d4 + 1;                           // poids
                d3 = d3 >= 1 && d3 - 1 < brightTable.length ? brightTable[d3 - 1] : 0;
                d2 = (byte) d2;                        // ext.w : base signée
                d3 = M68k.setw(d3, d3 - d2);
                d3 = M68k.muls(d3, d4);
                d3 = M68k.asrw(d3, 4);
                d2 = M68k.setw(d2, d2 + d3);
            }
        }
        // .justbright
        d2 = (byte) d2;                                // ext.w (octet bas signé)
        d2 = M68k.muls(d2, 397) >> 8;
        if (d2 < 0) {
            d2 = M68k.setw(d2, d2 - 600);
        }
        return (short) M68k.setw(d2, d2 + 300);
    }

    // ------------------------------------------------- séquences d'origine

    private static int[] seq(int from, int to) {
        int n = Math.abs(to - from) + 1;
        int step = to >= from ? 1 : -1;
        int[] r = new int[n];
        for (int i = 0, v = from; i < n; i++, v += step) {
            r[i] = v;
        }
        return r;
    }

    private static int[] rep(int n, int v) {
        int[] r = new int[n];
        java.util.Arrays.fill(r, v);
        return r;
    }

    private static int[] concat(int[]... parts) {
        int n = 0;
        for (int[] p : parts) {
            n += p.length;
        }
        int[] r = new int[n];
        int at = 0;
        for (int[] p : parts) {
            System.arraycopy(p, 0, r, at, p.length);
            at += p.length;
        }
        return r;
    }

    /** Valeur courante d'une lumière (diagnostic). */
    public int light(int i) {
        return i >= 0 && i < brightTable.length ? brightTable[i] : 0;
    }

    static int end() {
        return END;
    }
}
