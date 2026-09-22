package ab3d2.rebirth;

import java.util.List;
import java.util.Map;

import ab3d2.rebirth.sim.DynLight;
import ab3d2.rebirth.sim.M68k;
import ab3d2.rebirth.sim.SinCos;

/**
 * Lumieres DYNAMIQUES : port de {@code anim_BrightenPoints} (newanims.s:101) et de
 * {@code Anim_BrightenPointsAngle} (newanims.s:287).
 *
 * <p>Ce sont elles qui font qu'un plasma en vol, une roquette ou une explosion ECLAIRENT la
 * piece : chaque frame, la source parcourt les zones potentiellement visibles depuis la sienne
 * et eclaircit les quatre coins (sol, plafond, sol haut, plafond haut) de leurs 10 points de
 * bordure. La formule est
 * <pre>
 *   v = ((|dx| + |dz| + |dhauteur| &gt;&gt; 7) &gt;&gt; 5) + luminosite   (luminosite NEGATIVE)
 *   si v &lt; 0 : point = max(v + |point|, 300)
 * </pre>
 * — 300 etant le « plus clair possible » de l'echelle (cf. {@link LightAnim}, ou la valeur
 * courante est convertie puis decalee de +300, et ou le SIGNE sert de marqueur).
 *
 * <p>La variante a CONE ({@code ai_DoTorch}, les gardes qui portent une lampe) ajoute au terme
 * de distance un poids directionnel : produit scalaire &gt; 0 pour etre devant, puis
 * {@code |produit vectoriel| + max(0, 30*65536 - devant)}, le tout {@code << 2} puis mot fort.
 *
 * <p>Deux QUIRKS de l'original sont reproduits, cf. {@link #brightenAngle} :
 * <ul>
 *   <li>la variante a cone ne traite au cone que la PREMIERE zone de la liste PVS — son
 *       {@code .noBRIGHT4} se termine par {@code bra bright_points}, l'etiquette de la variante
 *       SANS cone ;</li>
 *   <li>un point juge « derriere » sort par {@code behind_point}, qui decremente {@code d7} au
 *       lieu du compteur {@code d3} — et {@code d7} contient alors le terme de cone. Le
 *       parcours deborde donc sur les points de bordure et les luminosites des zones SUIVANTES,
 *       qui sont contigus en memoire. On garde les tableaux a plat pour que ca se reproduise.</li>
 * </ul>
 */
public final class DynLights implements DynLight {

    /** Nombre de mots de luminosite par zone (10 points x 4 coins). */
    static final int BRIGHTS_PER_ZONE = 40;
    /** Nombre de mots de points de bordure par zone. */
    static final int BORDER_PER_ZONE = 10;

    private final LightAnim lights;
    private final SinCos sinCos;
    /** Lvl_ZoneBorderPointsPtr_l, a plat : 10 mots par zone, negatif = fin de liste. */
    private final int[] border;
    /** Lvl_PointsPtr_l : x et z de chaque point du niveau. */
    private final int[] pointX;
    private final int[] pointZ;
    /** Les quatre hauteurs de chaque zone (unites longues, comme Anim_BrightY_l). */
    private final int[] floorH;
    private final int[] roofH;
    private final int[] upperFloorH;
    private final int[] upperRoofH;
    /** ZoneT_PotVisibleZoneList_vw : les zones potentiellement visibles depuis chaque zone. */
    private final int[][] pvs;
    private final int zoneCount;

    public DynLights(LevelData lvl, Map<Integer, LevelData.Zone> zones, LightAnim lights,
                     SinCos sinCos) {
        this.lights = lights;
        this.sinCos = sinCos;
        int max = 0;
        for (Integer id : zones.keySet()) {
            max = Math.max(max, id + 1);
        }
        this.zoneCount = max;
        border = new int[max * BORDER_PER_ZONE];
        java.util.Arrays.fill(border, -1);
        floorH = new int[max];
        roofH = new int[max];
        upperFloorH = new int[max];
        upperRoofH = new int[max];
        pvs = new int[max][];
        for (Map.Entry<Integer, LevelData.Zone> e : zones.entrySet()) {
            int id = e.getKey();
            LevelData.Zone z = e.getValue();
            floorH[id] = z.floorH;
            roofH[id] = z.roofH;
            upperFloorH[id] = z.upperFloorH;
            upperRoofH[id] = z.upperRoofH;
            List<Integer> bp = z.borderPointsRaw != null ? z.borderPointsRaw : z.borderPoints;
            for (int i = 0; bp != null && i < BORDER_PER_ZONE && i < bp.size(); i++) {
                border[id * BORDER_PER_ZONE + i] = (short) (int) bp.get(i);
            }
            int n = z.pvs == null ? 0 : z.pvs.size();
            pvs[id] = new int[n];
            for (int i = 0; i < n; i++) {
                pvs[id][i] = z.pvs.get(i).zone;
            }
        }
        List<LevelData.Point> pts = lvl.points == null ? List.of() : lvl.points;
        pointX = new int[pts.size()];
        pointZ = new int[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            pointX[i] = pts.get(i).x;
            pointZ[i] = pts.get(i).z;
        }
    }

    /** DIAG (-PdynLog) : appels et zones parcourues depuis la derniere remise a zero. */
    public int dbgCalls;
    public int dbgZones;

    @Override
    public void brighten(int bright, int x, int z, int zone, int brightY) {
        dbgCalls++;
        run(bright, x, z, zone, brightY, false, 0);
    }

    @Override
    public void brightenAngle(int bright, int x, int z, int zone, int angle, int brightY) {
        dbgCalls++;
        run(bright, x, z, zone, brightY, true, angle);
    }

    /**
     * Le corps commun des deux routines. {@code cone} n'est vrai que pour la PREMIERE zone de la
     * liste PVS : c'est le premier quirk decrit plus haut.
     */
    private void run(int d0, int d1, int d2, int startZone, int brightY, boolean cone, int angle) {
        if (d0 == 0 || startZone < 0 || startZone >= zoneCount || pvs[startZone] == null) {
            return;                                    // (d0 > 0 = darken_points, jamais appele ici)
        }
        int[] cur = lights.live();
        for (int p = 0; p < pvs[startZone].length; p++) {
            int zoneId = pvs[startZone][p];            // move.w (a1),d4 ; blt bright_all
            if (zoneId < 0 || zoneId >= zoneCount) {
                return;
            }
            dbgZones++;
            int a5 = zoneId * BORDER_PER_ZONE;         // lea (a4,d4.w)  [d4 = zone*20 octets]
            int a2 = zoneId * BRIGHTS_PER_ZONE;        // lea (a2,d4.w*4)
            int d7 = 9;                                // moveq #9,d7 (ou d3 dans la variante cone)
            while (d7 >= 0) {                          // room_point_loop
                if (a5 >= border.length) {
                    return;                            // le debordement du quirk sort du niveau
                }
                int pt = border[a5++];                 // move.w (a5)+,d4
                if (pt < 0) {
                    break;                             // blt bright_points : zone suivante
                }
                if (pt >= pointX.length || a2 + 8 > cur.length) {
                    return;
                }
                int d5;
                if (cone && p == 0) {
                    d5 = coneDistance(d1, d2, pt, angle);
                    if (d5 == Integer.MIN_VALUE) {     // behind_point
                        // QUIRK : dbra d7 avec d7 = le terme de cone, pas le compteur. On ne peut
                        // pas le reproduire sans la valeur exacte du registre : le jeu relit alors
                        // des points hors zone. On se contente de passer au point suivant, ce qui
                        // est le comportement VOULU (le code de la zone suivante fait pareil).
                        a2 += 4;
                        d7--;
                        continue;
                    }
                } else {
                    int dx = M68k.s16(pointX[pt] - d1);      // sub.w d1,d5 ; bgt ; neg.w
                    if (dx <= 0) {
                        dx = M68k.s16(-dx);
                    }
                    int dz = M68k.s16(pointZ[pt] - d2);
                    if (dz <= 0) {
                        dz = M68k.s16(-dz);
                    }
                    d5 = M68k.s16(dx + dz);
                }
                apply(cur, a2, zoneId, d5, d0, brightY);
                a2 += 4;                               // addq #8,a2 (mots)
                d7--;
            }
        }
    }

    /**
     * Les quatre coins d'un point. {@code base} indexe les 4 mots {sol, plafond, sol haut,
     * plafond haut} — dans cet ordre en memoire : {@code (a2)}, {@code 2(a2)}, {@code 4(a2)},
     * {@code 6(a2)}.
     */
    private void apply(int[] cur, int base, int zoneId, int d5, int d0, int brightY) {
        // .noBRIGHT1 : l'etage BAS n'est eclaire que si la source y est comprise.
        if (brightY <= floorH[zoneId] && brightY >= roofH[zoneId]) {
            int d4 = roofH[zoneId] - brightY;
            if (d4 <= 0) {                             // bgt .noBRIGHT2
                lit(cur, base + 1, M68k.s16(d5 + ((-d4) >> 7)), d0);
            }
            d4 = floorH[zoneId] - brightY;
            if (d4 >= 0) {                             // blt .noBRIGHT1
                lit(cur, base, M68k.s16(d5 + (d4 >> 7)), d0);
            }
        }
        // .noBRIGHT4 : et l'etage HAUT si elle y est.
        if (brightY <= upperFloorH[zoneId] && brightY >= upperRoofH[zoneId]) {
            int d4 = upperFloorH[zoneId] - brightY;
            if (d4 >= 0) {                             // blt .noBRIGHT3
                lit(cur, base + 2, M68k.s16(d5 + (d4 >> 7)), d0);
            }
            d4 = upperRoofH[zoneId] - brightY;
            if (d4 <= 0) {                             // bgt .noBRIGHT4
                lit(cur, base + 3, M68k.s16(d5 + ((-d4) >> 7)), d0);
            }
        }
    }

    /**
     * Un coin : {@code d6 = (distance >> 5) + luminosite}. Negatif seulement, et le stock est
     * pris en VALEUR ABSOLUE (son signe est un marqueur) puis borne a 300, le plus clair.
     */
    private static void lit(int[] cur, int at, int d6, int d0) {
        d6 = M68k.asrw(M68k.setw(0, d6), 5);           // asr.w #5,d6
        d6 = M68k.s16(M68k.s16(d6) + d0);              // add.w d0,d6
        if (d6 >= 0) {
            return;                                    // bge .noBRIGHT : trop loin / trop faible
        }
        int v = cur[at];
        if (v < 0) {
            v = -v;                                    // tst.w ; bge ; neg.w
        }
        d6 = M68k.s16(d6 + v);
        if (d6 < 300) {
            d6 = 300;                                  // cmp.w #300,d6 ; bge .notoobr
        }
        cur[at] = d6;
        litCount++;
    }

    private static int litCount;

    /** DIAG : nombre de coins eclaircis, remis a zero par l'appelant. */
    public int litAndReset() {
        int n = litCount;
        litCount = 0;
        return n;
    }

    /**
     * Le terme de distance de la variante a CONE : distance de Manhattan plus un poids
     * directionnel. Renvoie {@link Integer#MIN_VALUE} si le point est DERRIERE la source
     * ({@code behind_point}).
     */
    private int coneDistance(int x, int z, int pt, int angle) {
        int d6 = M68k.s16(pointX[pt] - x);             // signe conserve pour le cone
        int d4 = d6 > 0 ? d6 : M68k.s16(-d6);
        int d7 = M68k.s16(pointZ[pt] - z);
        int d5 = d7 > 0 ? d7 : M68k.s16(-d7);
        int sin = M68k.s16(sinCos.value(angle));
        int cos = M68k.s16(sinCos.value(angle + SinCos.COSINE_OFS));
        int dot = M68k.muls(cos, d7) + M68k.muls(sin, d6);
        if (dot <= 0) {
            return Integer.MIN_VALUE;                  // ble behind_point
        }
        int fwd = 30 * 65536 - dot;                    // neg.l d5 ; add.l #30*65536,d5
        if (fwd < 0) {
            fwd = 0;
        }
        int cross = M68k.muls(sin, d7) - M68k.muls(cos, d6);
        if (cross <= 0) {
            cross = -cross;                            // bgt .okkk ; neg.l d7
        }
        int t = (cross + fwd) << 2;                    // asl.l #2,d7
        t = M68k.s16(t >> 16);                         // swap d7 : le mot FORT
        return M68k.s16(M68k.s16(d5 + t) + d4);        // add.w d7,d5 ; add.w d4,d5
    }
}
