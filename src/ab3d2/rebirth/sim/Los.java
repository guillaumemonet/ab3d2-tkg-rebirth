package ab3d2.rebirth.sim;

import static ab3d2.rebirth.sim.M68k.divs;
import static ab3d2.rebirth.sim.M68k.muls;
import static ab3d2.rebirth.sim.M68k.s16;

import ab3d2.rebirth.LevelData;

import java.util.List;

/**
 * Ligne de vue : port de {@code CanItBeSeen} / {@code InList} / {@code GoThroughZones}
 * (Objectmove.java:973-1210). C'est ce qui décide si un alien voit le joueur.
 *
 * <p>Trois étages, comme l'original :
 * <ol>
 *   <li>même zone → vu (sauf si l'un est à l'étage haut et l'autre en bas) ;</li>
 *   <li>la zone cible doit être dans la liste des zones POTENTIELLEMENT VISIBLES de la zone
 *       de départ, et le segment doit passer entre les points de découpe du portail
 *       ({@code clips} : points gauches, -1, points droits, -2) ;</li>
 *   <li>marche de zone en zone le long du segment : à chaque arête traversée on calcule la
 *       HAUTEUR de croisement et on vérifie qu'elle passe entre sol et plafond des deux côtés
 *       (c'est ce test qui cache un alien derrière une marche ou sous un plafond bas).</li>
 * </ol>
 */
public final class Los {

    private final LevelSim lvl;
    private final int[] ptX, ptZ;
    private final int[] clips;
    private final int[][] pvsZone, pvsClip;

    // Entrées (Viewer* / Target* / ViewerTop / TargetTop de ObjectmoveData).
    public int viewerX, viewerZ, viewerY, viewerZone;
    public int targetX, targetZ, targetY, targetZone;
    public boolean viewerTop, targetTop;
    /** CanSee. */
    public boolean canSee;

    public Los(LevelSim lvl, LevelData data) {
        this.lvl = lvl;
        List<LevelData.Point> pts = data.points == null ? List.of() : data.points;
        ptX = new int[pts.size()];
        ptZ = new int[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            ptX[i] = pts.get(i).x;
            ptZ[i] = pts.get(i).z;
        }
        List<Integer> cl = data.clips == null ? List.of() : data.clips;
        clips = new int[cl.size()];
        for (int i = 0; i < clips.length; i++) {
            clips[i] = cl.get(i);
        }
        int maxId = lvl.zones.length;
        pvsZone = new int[maxId][];
        pvsClip = new int[maxId][];
        for (LevelData.Zone z : data.zones) {
            List<LevelData.Pvs> l = z.pvs == null ? List.of() : z.pvs;
            pvsZone[z.id] = new int[l.size()];
            pvsClip[z.id] = new int[l.size()];
            for (int i = 0; i < l.size(); i++) {
                pvsZone[z.id][i] = l.get(i).zone;
                pvsClip[z.id][i] = l.get(i).clip;
            }
        }
    }

    /** CanItBeSeen : résultat dans {@link #canSee}. */
    public void canItBeSeen() {
        canSee = false;
        if (viewerZone < 0 || targetZone < 0 || viewerZone >= lvl.zones.length
                || targetZone >= lvl.zones.length) {
            return;
        }
        if (viewerZone == targetZone) {                // cmp.l a0,a1 ; beq insameroom
            canSee = viewerTop == targetTop;           // eor.b d0,d1 ; bne outlist
            return;
        }
        // InList : la zone cible est-elle dans la liste des zones visibles depuis la nôtre ?
        int[] zs = pvsZone[viewerZone];
        if (zs == null) {
            return;
        }
        int found = -1;
        for (int i = 0; i < zs.length; i++) {
            if (zs[i] == targetZone) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            return;                                    // outlist
        }

        // isinlist : découpe du portail
        canSee = true;
        int d1 = s16(s16(targetX) - s16(viewerX));
        int d2 = s16(s16(targetZ) - s16(viewerZ));
        int clip = pvsClip[viewerZone][found];
        if (clip >= 0) {
            int a1 = clip;
            while (a1 < clips.length && clips[a1] >= 0) { // checklcliploop
                int p = clips[a1];
                int d3 = s16(ptX[p] - s16(viewerX));
                int d4 = s16(ptZ[p] - s16(viewerZ));
                d3 = muls(d3, d2);
                d4 = muls(d4, d1);
                if (d4 - d3 <= 0) {                    // ble outlist
                    canSee = false;
                    return;
                }
                a1++;
            }
            a1++;                                      // nomorelclips : addq #2,a1
            while (a1 < clips.length && clips[a1] >= 0) { // checkrcliploop
                int p = clips[a1];
                int d3 = s16(ptX[p] - s16(viewerX));
                int d4 = s16(ptZ[p] - s16(viewerZ));
                d3 = muls(d3, d2);
                d4 = muls(d4, d1);
                if (d4 - d3 >= 0) {                    // bge outlist
                    canSee = false;
                    return;
                }
                a1++;
            }
        }

        // nomorerclips : travail vertical, zone après zone
        int d0v = s16(s16(targetX) - s16(viewerX));
        int d1v = s16(s16(targetZ) - s16(viewerZ));
        int zone = viewerZone;
        boolean top = viewerTop;                       // d2
        int d7 = s16(s16(targetY) - s16(viewerY));

        for (int guard = 0; guard < 64; guard++) {     // GoThroughZones
            LevelSim.Zone z = lvl.zones[zone];
            if (z == null) {
                canSee = false;
                return;
            }
            int next = -1;
            int crossHeight = 0;
            for (int ei : z.edgeList) {                // FindWayOut
                if (ei < 0) {
                    canSee = false;                    // blt outlist
                    return;
                }
                int d3 = s16(lvl.edgeX[ei] - s16(viewerX));
                int d4 = s16(lvl.edgeZ[ei] - s16(viewerZ));
                int d5 = s16(d3);
                int d6 = s16(d4);
                d3 = muls(d3, d1v);
                d4 = muls(d4, d0v);
                if (d4 - d3 <= 0) {                    // ble FindWayOut
                    continue;
                }
                d5 = s16(d5 + lvl.edgeDx[ei]);
                d6 = s16(d6 + lvl.edgeDz[ei]);
                d6 = muls(d6, d0v);
                d5 = muls(d5, d1v);
                if (d6 - d5 >= 0) {                    // bge FindWayOut
                    continue;
                }
                if (lvl.edgeJoin[ei] < 0) {            // mur plein
                    canSee = false;
                    return;
                }
                // Hauteur à laquelle on franchit cette arête.
                int e3 = s16(s16(targetX) - lvl.edgeX[ei]);
                int e4 = s16(s16(targetZ) - lvl.edgeZ[ei]);
                e4 = muls(e4, lvl.edgeDx[ei]);
                e3 = muls(e3, lvl.edgeDz[ei]);
                e4 = e4 - e3;
                int e5 = s16(s16(viewerX) - lvl.edgeX[ei]);
                int e6 = s16(s16(viewerZ) - lvl.edgeZ[ei]);
                e6 = muls(e6, lvl.edgeDx[ei]);
                e5 = muls(e5, lvl.edgeDz[ei]);
                e5 = e5 - e6;
                int w5 = lvl.edgeW5[ei];
                e4 = divs(e4, w5);
                e5 = divs(e5, w5);
                e4 = s16(e4 + e5);
                if (s16(e4) != 0) {                    // beq sameheight
                    e5 = muls(s16(e5), d7);
                    e5 = divs(e5, e4);
                }
                int h = s16(s16(e5) + s16(viewerY));   // add.w Viewery,d5
                h = h << 7;                            // ext.l ; asl.l #7
                if (top) {
                    if (h < z.upperRoofH || h > z.upperFloorH) {
                        canSee = false;
                        return;
                    }
                } else {
                    if (h < z.roofH || h > z.floorH) {
                        canSee = false;
                        return;
                    }
                }
                next = lvl.edgeJoin[ei];
                crossHeight = h;
                break;                                 // madeit
            }
            if (next < 0 || next >= lvl.zones.length || lvl.zones[next] == null) {
                canSee = false;
                return;
            }
            // madeit : on entre dans la zone voisine, en bas ou en haut
            zone = next;
            LevelSim.Zone n = lvl.zones[zone];
            top = false;
            if (crossHeight > n.floorH) {
                canSee = false;
                return;
            }
            if (crossHeight <= n.roofH) {              // bgt.s GotIn
                top = true;
                if (crossHeight > n.upperFloorH || crossHeight < n.upperRoofH) {
                    canSee = false;
                    return;
                }
            }
            // GotIn
            if (zone == targetZone) {
                canSee = targetTop == top;             // eor.b d2,d3 ; bne outlist
                return;
            }
        }
        canSee = false;
    }
}
