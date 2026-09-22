package ab3d2.rebirth;

import ab3d2.rebirth.sim.M68k;
import ab3d2.rebirth.sim.SinCos;

import java.util.List;
import java.util.Map;

/**
 * Éclairage DIRECTIONNEL des objets : port de draw_CalcBrightRings / draw_CalcBrightsInZone /
 * draw_TweenBrights (Objdrawhires.java:257-470).
 *
 * <p>Pour un objet donné, le jeu construit un anneau de 16 secteurs d'angle autour de lui :
 * chaque point de bordure de sa zone (et des zones voisines, atteintes par les arêtes-portails)
 * y dépose sa luminosité dans le secteur qui pointe vers lui, et chaque mur plein y dépose 48
 * (= sombre). Les trous sont ensuite interpolés, puis les quatre anneaux (bas/haut × zone
 * courante/voisines) sont combinés en deux : {@link #bottom(int)} et {@link #top(int)}.
 *
 * <p>Le résultat s'AJOUTE au niveau de base de chaque face du modèle (assombrissement).
 * Ici les objets sont statiques : l'anneau est calculé une fois au chargement. Dans le jeu il
 * est recalculé à chaque frame et pour chaque objet.
 */
public final class LightRings {

    /** 4 anneaux de 16 secteurs : +0 bas (zone), +16 haut (zone), +32 bas (voisins/murs), +48 haut. */
    private final byte[] ring = new byte[64];

    private final Map<Integer, LevelData.Zone> zones;
    private final List<LevelData.Point> points;
    private final List<LevelData.Edge> edges;
    private final SinCos sinCos;
    private final LightAnim lights;
    private final int objX;
    private final int objZ;

    /** OneOverN_vw (hires.s) : 16384/n. */
    private static final int[] ONE_OVER_N = new int[512];

    static {
        for (int n = 1; n < ONE_OVER_N.length; n++) {
            ONE_OVER_N[n] = 16384 / n;
        }
    }

    private LightRings(Map<Integer, LevelData.Zone> zones, List<LevelData.Point> points,
                       List<LevelData.Edge> edges, SinCos sinCos, LightAnim lights,
                       int objX, int objZ) {
        this.zones = zones;
        this.points = points;
        this.edges = edges;
        this.sinCos = sinCos;
        this.lights = lights;
        this.objX = objX;
        this.objZ = objZ;
    }

    /**
     * Variante des SPRITES éclairés : {@code drawBitmapLighted} n'appelle que
     * {@code draw_ResetAngleBrights} — donc uniquement les points de bordure de la zone COURANTE,
     * sans les murs, sans les zones voisines, et SANS interpolation. Les secteurs sans point
     * restent à 0x80 (vides) et sont ignorés par le calcul de direction moyenne.
     */
    public static LightRings zoneOnly(Map<Integer, LevelData.Zone> zones, List<LevelData.Point> points,
                                      List<LevelData.Edge> edges, SinCos sinCos, LightAnim lights,
                                      int objX, int objZ, int zone) {
        LightRings r = new LightRings(zones, points, edges, sinCos, lights, objX, objZ);
        java.util.Arrays.fill(r.ring, (byte) 0x80);
        r.calcBrightsInZone(zone, 0);
        return r;
    }

    /** draw_CalcBrightRings : construit les anneaux pour un objet posé en (objX, objZ) dans `zone`. */
    public static LightRings compute(Map<Integer, LevelData.Zone> zones, List<LevelData.Point> points,
                                     List<LevelData.Edge> edges, SinCos sinCos, LightAnim lights,
                                     int objX, int objZ, int zone) {
        LightRings r = new LightRings(zones, points, edges, sinCos, lights, objX, objZ);
        r.build(zone);
        return r;
    }

    /** Luminosité de l'anneau BAS pour un secteur 0..15 (à ajouter au niveau de base). */
    public int bottom(int sector) {
        return ring[sector & 15] & 0xFF;
    }

    /** Luminosité de l'anneau HAUT pour un secteur 0..15. */
    public int top(int sector) {
        return ring[16 + (sector & 15)] & 0xFF;
    }

    private void build(int zone) {
        // draw_ResetAngleBrights : 64 octets à 0x80 (= vide), puis les points de la zone courante.
        java.util.Arrays.fill(ring, (byte) 0x80);
        calcBrightsInZone(zone, 0);

        // Arêtes de la zone : portail -> points de la zone voisine ; mur plein -> 48 (sombre).
        LevelData.Zone z = zones.get(zone);
        if (z != null && z.edgeList != null) {
            for (int ei : z.edgeList) {
                if (ei < 0) {
                    break;                             // .no_more_walls (1er groupe seulement)
                }
                if (ei >= edges.size()) {
                    continue;
                }
                LevelData.Edge e = edges.get(ei);
                if (e.join >= 0) {
                    calcBrightsInZone(e.join, 32);
                    continue;
                }
                // .solid_wall : direction perpendiculaire au mur
                int nx = (short) (objX - e.dz);
                int nz = (short) (objZ + e.dx);
                int sector = sectorTowards(nx, nz);
                if (sector >= 0) {
                    ring[32 + sector] = 48;
                    ring[48 + sector] = 48;
                }
            }
        }

        tween(0);
        tween(16);
        tween(32);
        tween(48);

        // Combinaison finale (Objdrawhires.java:463-489) : les anneaux 32/48 (voisins et murs)
        // limitent les anneaux 0/16, et le résultat est un ASSOMBRISSEMENT (valeur >= 0).
        for (int i = 0; i < 16; i++) {
            int d3 = ring[32 + i];
            int d4 = ring[48 + i];
            d3 = (short) (48 - d3);
            d4 = (short) (48 - d4);
            d4 = d4 >> 1;
            d3 = d3 >> 1;
            int d5 = ring[16 + i];
            d4 = (byte) (d4 - d5);
            if (d4 > 0) {
                d4 = 0;
            }
            d5 = ring[i];
            d3 = (byte) (d3 - d5);
            if (d3 > 0) {
                d3 = 0;
            }
            ring[16 + i] = (byte) -d4;
            ring[i] = (byte) -d3;
        }
    }

    /**
     * draw_CalcBrightsInZone : dépose dans l'anneau la luminosité des points de bordure de `zone`,
     * chacun dans le secteur qui pointe vers lui depuis l'objet.
     */
    private void calcBrightsInZone(int zone, int ringBase) {
        // ATTENTION : la liste de points de bordure d'une zone n'est PAS bornée à ses 10 mots.
        // Le jeu lit jusqu'au premier mot négatif, en DÉBORDANT sur la zone suivante (vérifié :
        // zone 47 du niveau A = 14 points, dont 4 pris dans la zone 48), et le pointeur de
        // luminosité avance en parallèle (4 mots par point), donc lui aussi déborde. On reproduit
        // ce parcours « à plat » : sans ça l'anneau diverge du portage.
        for (int i = 0; ; i++) {
            int listZone = zone + i / 10;
            LevelData.Zone lz = zones.get(listZone);
            if (lz == null || lz.borderPointsRaw == null || lz.borderPointsRaw.size() <= i % 10) {
                return;
            }
            int p = lz.borderPointsRaw.get(i % 10);
            if (p < 0) {
                return;                                // .done_point_bright
            }
            if (p >= points.size()) {
                continue;
            }
            int sector = sectorTowards(points.get(p).x, points.get(p).z);
            if (sector < 0) {
                continue;
            }
            int flat = zone * 40 + i * 4;
            ring[ringBase + sector] = pointValueFlat(flat);          // bas
            ring[ringBase + sector + 16] = pointValueFlat(flat + 1); // haut
        }
    }

    /** Luminosité d'un point par index À PLAT (zone*40 + i), qui peut franchir les zones. */
    private byte pointValueFlat(int flat) {
        return pointValue(flat / 40, flat % 40);
    }

    /** Remap d'une luminosité de point pour l'anneau (Objdrawhires.java:1299-1310). */
    private byte pointValue(int zoneId, int idx) {
        int v = lights != null ? lights.pointBright(zoneId, idx) : 0;
        if (v < 0) {                                   // bge .okpos : le repli ne vise que les NEGATIFS
            v = (short) (v + 332);
            v = (short) (v >> 2);
            v = (short) -v;
            v = (short) (v + 332);
        }
        v = (short) (v - 300);
        if (v < 0) {
            v = 0;
        }
        int d2 = (byte) v >> 1;                        // v *= 1.5 (en octets)
        return (byte) (v + d2);
    }

    /** Secteur 0..15 vers (nx, nz) depuis l'objet, ou -1 si le point est confondu avec l'objet. */
    private int sectorTowards(int nx, int nz) {
        int ang = headTowardsAng(nx, nz);
        if (ang < 0) {
            return -1;
        }
        int d1 = (-ang) & 8191;                        // neg.w + AMOD_I (SINE_SIZE*2-1)
        d1 = d1 >> 8;                                  // asr.w #8
        return (d1 >> 1) & 15;                         // asr.w #1
    }

    /**
     * Angle objet -&gt; (nx, nz), port de HeadTowardsAng (Objectmove.java) : racine de Newton puis
     * recherche dichotomique dans la table sinus. Renvoie l'angle en unités de table (0..8190),
     * ou -1 si la distance est nulle.
     */
    private int headTowardsAng(int nx, int nz) {
        int xdiff = (short) (nx - objX);
        int zdiff = (short) (nz - objZ);
        int d2 = M68k.muls(xdiff, xdiff) + M68k.muls(zdiff, zdiff);
        if (d2 == 0) {
            return -1;
        }
        int d0 = sqrtNewton(d2);
        if ((short) d0 == 0) {
            return -1;
        }
        d0 = M68k.setw(d0, d0 + 1);
        int sinRet = M68k.divs((xdiff << 16) >> 1, d0);
        int cosRet = M68k.divs((zdiff << 16) >> 1, d0);

        int d0s = M68k.setw(0, sinRet);
        int d1 = M68k.setw(0, cosRet);
        int d2a = 0;
        int d6 = 2048;                                 // COSINE_OFS (en entrées de table)
        for (int iter = 0; iter < 4; iter++) {         // moveq #3,d5 ; dbra
            int d3 = sinCos.sin((d2a & 0xFFFF) * 2);
            int d4 = sinCos.cos((d2a & 0xFFFF) * 2);
            d4 = M68k.muls(d4, d0s);
            d3 = M68k.muls(d3, d1);
            d4 -= d3;
            if (d4 >= 0) {
                d2a = M68k.setw(d2a, d2a + d6);
                d2a = M68k.setw(d2a, d2a + d6);
            }
            d2a = M68k.setw(d2a, d2a - d6);
            d2a = M68k.setw(d2a, d2a & 4095);
            d6 = M68k.setw(d6, ((short) d6) >> 1);
        }
        return M68k.setw(d2a, d2a + d2a) & 0xFFFF;
    }

    /** Racine carrée de Newton (3 itérations) telle que le port l'implémente. */
    private static int sqrtNewton(int d2) {
        int d0 = 31;
        while ((d2 & (1 << (d0 & 31))) == 0) {
            d0 = M68k.setw(d0, d0 - 1);
            if ((short) d0 == -1) {
                break;
            }
        }
        d0 = M68k.setw(d0, ((short) d0) >> 1);
        d0 = 1 << (d0 & 31);
        for (int i = 0; i < 3; i++) {
            int d1 = M68k.setw(0, d0);
            d1 = M68k.muls(d1, d1);
            d1 -= d2;
            d1 >>= 1;
            d1 = M68k.divs(d1, d0);
            d0 = M68k.setw(d0, d0 - d1);
            if ((short) d0 <= 0) {
                d0 = M68k.setw(d0, 1);
            }
        }
        return d0;
    }

    /** draw_TweenBrights : interpole les secteurs vides (0x80) d'un anneau de 16 octets. */
    private void tween(int base) {
        int d0 = 0;
        while (ring[base + d0] == (byte) 0x80) {
            d0++;
            if (d0 >= 16) {
                return;                                // anneau vide : rien à interpoler
            }
        }
        int start = d0;
        int d1 = d0;
        while (true) {
            do {
                d0 = (d0 + 1) & 15;
            } while (ring[base + d0] == (byte) 0x80);
            int d2 = ring[base + d1] & 0xFF;
            int d3 = ring[base + d0] & 0xFF;
            d3 = M68k.setw(d3, d3 - d2);
            int d4 = d0 - d1;
            if (d4 <= 0) {
                d4 += 16;
            }
            int acc = d2 << 16;                        // swap : partie entière dans le mot fort
            int step = d3 << 16;
            if (step != 0) {
                int gap = d4;
                int inv = (short) ONE_OVER_N[gap];
                step = (step >> 7) * inv;
                step = step >> 7;
                d4 = gap;
            }
            d4 = d4 - 1;
            while (true) {
                ring[base + d1] = (byte) (acc >> 16);
                acc += step;
                d1 = (d1 + 1) & 15;
                d4--;
                if (d4 == -1) {
                    break;
                }
            }
            if (d0 == start) {
                break;
            }
            d1 = d0;
        }
    }
}
