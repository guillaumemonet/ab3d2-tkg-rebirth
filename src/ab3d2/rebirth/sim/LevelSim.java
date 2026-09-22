package ab3d2.rebirth.sim;

import ab3d2.rebirth.LevelData;

import java.util.List;

/**
 * Etat RUNTIME d'un niveau pour la simulation (collision, portes).
 *
 * <p>Tableaux plats indexes comme dans le port : les aretes par index global
 * (Lvl_ZoneEdgePtr), les zones par id (Lvl_ZonePtrsPtr). {@link #edgeFlags} est le
 * EdgeT_Flags_w runtime : {@link Move} y pose le bit du joueur au contact, {@link Doors}
 * le lit puis le desarme (handshake d'origine).
 */
public final class LevelSim {

    /** Aretes globales (EdgeT). */
    public final int[] edgeX, edgeZ, edgeDx, edgeDz, edgeJoin, edgeW5, edgeB12, edgeB13;
    /** EdgeT_Flags_w runtime, partage entre Move et Doors. */
    public final int[] edgeFlags;
    /** Zones indexees par id (trous possibles = null). */
    public final Zone[] zones;

    public static final class Zone {
        public int floorH, roofH, upperFloorH, upperRoofH;
        public int water;                     // ZoneT_Water_l
        /** ZoneT_FloorNoise_w / UpperFloorNoise_w : type de sol (bruit de pas + degats). */
        public int floorNoise, upperFloorNoise;
        /** ZoneT_BackSFXMask_w : bruits d'ambiance possibles dans la zone (bas / haut). */
        public int backSfxMask, upperBackSfxMask;
        public int telZone, telX, telZ;       // ZoneT_TelZone_w / TelX / TelZ
        public int[] edgeList;                // liste brute : index d'aretes, -1 fin de groupe, -2 fin
    }

    private LevelSim(int n, Zone[] zones) {
        edgeX = new int[n];
        edgeZ = new int[n];
        edgeDx = new int[n];
        edgeDz = new int[n];
        edgeJoin = new int[n];
        edgeW5 = new int[n];
        edgeB12 = new int[n];
        edgeB13 = new int[n];
        edgeFlags = new int[n];
        this.zones = zones;
    }

    public static LevelSim of(LevelData lvl) {
        int maxId = 0;
        for (LevelData.Zone z : lvl.zones) {
            maxId = Math.max(maxId, z.id);
        }
        Zone[] zones = new Zone[maxId + 1];
        for (LevelData.Zone src : lvl.zones) {
            Zone z = new Zone();
            z.floorH = src.floorH;
            z.roofH = src.roofH;
            z.upperFloorH = src.upperFloorH;
            z.upperRoofH = src.upperRoofH;
            z.water = src.water;
            z.floorNoise = src.floorNoise;
            z.upperFloorNoise = src.upperFloorNoise;
            z.backSfxMask = src.backSfxMask;
            z.upperBackSfxMask = src.upperBackSfxMask;
            z.telZone = src.telZone;
            z.telX = src.telX;
            z.telZ = src.telZ;
            List<Integer> raw = src.edgeList == null ? List.of() : src.edgeList;
            z.edgeList = new int[raw.size()];
            for (int i = 0; i < z.edgeList.length; i++) {
                z.edgeList[i] = raw.get(i);
            }
            zones[src.id] = z;
        }

        List<LevelData.Edge> edges = lvl.edges == null ? List.of() : lvl.edges;
        LevelSim sim = new LevelSim(edges.size(), zones);
        for (int i = 0; i < edges.size(); i++) {
            LevelData.Edge e = edges.get(i);
            sim.edgeX[i] = e.x;
            sim.edgeZ[i] = e.z;
            sim.edgeDx[i] = e.dx;
            sim.edgeDz[i] = e.dz;
            sim.edgeJoin[i] = e.join;
            sim.edgeW5[i] = e.w5;
            sim.edgeB12[i] = e.b12;
            sim.edgeB13[i] = e.b13;
        }
        return sim;
    }
}
