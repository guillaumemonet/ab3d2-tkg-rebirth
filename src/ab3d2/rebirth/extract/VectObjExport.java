package ab3d2.rebirth.extract;

import ab3d2.Defs;
import ab3d2.Mem;
import ab3d2.modules.FileIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extraction des modèles vectoriels (vectobj / draw_PolygonModel) vers OBJ (+ UV atlas) — un fichier par frame.
 *
 * <p>Format (conforme à {@code objdrawhires.s::draw_PolygonModel}) : +0 sortIt(w), +2 numPoints(w),
 * +4 numFrames(w), +6 table frames [ptsOfs(w),angOfs(w)] (offsets relatifs à fichier+2), puis liste des
 * parts {bodyOfs(w),refPt(w)} (fin=mot<0). Points @ ptsOfs : onOff(l) + angles((numPoints+1)/2*2) +
 * numPoints×(SWORD x,y,z). Polygone @ bodyOfs : numLines N(w ; <0=fin) + flags(w) + (N+1)×{ptIdx(w),u(b),v(b)}
 * + footer (texOffset@N*4+12). Coords /128, Y et Z inversés. Les UV mappent l'atlas texturemaps.
 */
public final class VectObjExport {

    private static final float SCALE = 128f;
    private static int atlasH = 512;                         // hauteur atlas (numBanks*4*64) ; init au boot

    private VectObjExport() {
    }

    static void extractAll(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        int glf = PortReader.glf();
        long tf = FileIo.IO_LoadFile(glf + Defs.GLFT_TextureFilename_l); // NEWTEXTUREMAPS → nb de banques
        atlasH = Math.max(1, FileIo.len(tf) / 65536) * 4 * 64;

        Path root = outRoot.resolve("models");
        int models = 0, files = 0;
        for (int i = 0; i < Defs.NUM_OBJECT_DEFS; i++) {
            int nameAddr = glf + Defs.GLFT_VectorNames_l + i * 64;
            String full = PortReader.fixedStr(nameAddr, 64);
            if (full.isEmpty()) {
                continue;
            }
            String name = PortReader.baseName(full);
            long fr = FileIo.IO_LoadFile(nameAddr);
            int n = exportModel(FileIo.addr(fr), FileIo.len(fr), root.resolve(name), name);
            if (n > 0) {
                System.out.printf("  vectobj %-16s %d frame(s)%n", name, n);
                models++;
                files += n;
            }
        }
        System.out.println("[vectobj] " + models + " modèles, " + files + " OBJ → " + root);
    }

    /** Exporte un modèle (un OBJ par frame + un .mtl local). Renvoie le nombre de frames écrites. */
    private static int exportModel(int base, int flen, Path dir, String name) throws Exception {
        if (flen < 6) {
            return 0;
        }
        int numPoints = Mem.uw(base + 2);
        int numFrames = Mem.uw(base + 4);
        if (numPoints <= 0 || numPoints > 4096 || numFrames <= 0 || numFrames > 64) {
            return 0;
        }
        int startOfs = base + 2;

        int[] ptsOfs = new int[numFrames];
        int[] angOfs = new int[numFrames];             // 2e mot du record de frame = table PolyAng
        for (int f = 0; f < numFrames; f++) {
            ptsOfs[f] = startOfs + Mem.uw(base + 6 + f * 4);
            angOfs[f] = startOfs + Mem.uw(base + 6 + f * 4 + 2);
        }
        List<Integer> parts = new ArrayList<>();
        int pos = base + 6 + numFrames * 4;
        while (pos + 4 <= base + flen) {
            int fw = Mem.w(pos);
            if (fw < 0) {
                break;
            }
            parts.add(startOfs + (fw & 0xFFFF));
            pos += 4;
        }
        if (parts.isEmpty()) {
            return 0;
        }

        Files.createDirectories(dir);
        // Matériau local (à côté des OBJ) → le viewer ouvre l'OBJ + son .mtl sans chemin relatif.
        Files.writeString(dir.resolve("vectobj.mtl"),
                "newmtl vectobj\nKd 1 1 1\nmap_Kd ../../textures/texturemaps_atlas.png\n");
        int written = 0;
        for (int f = 0; f < numFrames; f++) {
            long onOff = Mem.l(ptsOfs[f]) & 0xFFFFFFFFL;
            int[][] pts = readPoints(ptsOfs[f], numPoints, base + flen);
            if (pts == null) {
                continue;
            }
            StringBuilder v = new StringBuilder(), vt = new StringBuilder(), fb = new StringBuilder();
            for (int[] p : pts) {
                v.append("v ").append(p[0] / SCALE).append(' ')
                 .append(-p[1] / SCALE).append(' ').append(-p[2] / SCALE).append('\n');
            }
            int vtCount = 0, faceCount = 0;
            // Un « niveau de base » + un « octet d'angle » par face, dans l'ordre des lignes « f ».
            StringBuilder lv = new StringBuilder();
            for (int pi = 0; pi < parts.size(); pi++) {
                if ((onOff & (1L << pi)) == 0) {
                    continue;
                }
                for (Poly poly : readPolygons(parts.get(pi), base + flen, numPoints, angOfs[f])) {
                    fb.append('f');
                    for (int k = 0; k < poly.pt.length; k++) {
                        float[] uv = atlasUV(poly.texOffset, poly.u[k], poly.v[k]);
                        vt.append("vt ").append(uv[0]).append(' ').append(uv[1]).append('\n');
                        vtCount++;
                        fb.append(' ').append(poly.pt[k] + 1).append('/').append(vtCount);
                    }
                    fb.append('\n');
                    lv.append(poly.level).append(' ').append(poly.ang).append('\n');
                    faceCount++;
                }
            }
            if (faceCount == 0) {
                continue;
            }
            String obj = "# " + name + " frame " + f + " (vectobj)\n"
                    + "mtllib vectobj.mtl\no " + name + "_f" + f + "\nusemtl vectobj\n"
                    + v + vt + fb;
            Files.writeString(dir.resolve(String.format("frame_%03d.obj", f)), obj);
            // Sidecar : niveau de luminosité par face, dans l'ordre des lignes « f » de l'OBJ.
            Files.writeString(dir.resolve(String.format("frame_%03d.lvl", f)), lv.toString());
            written++;
        }
        return written;
    }

    /** texOffset (poly) + u/v (sommet) → coord UV dans l'atlas texture-maps (V flippé pour OBJ). */
    private static float[] atlasUV(int texOffset, int vtxU, int vtxV) {
        int bank = (texOffset & 0x8000) != 0 ? 1 : 0;
        int relOfs = texOffset & 0x7FFF;
        int slot = relOfs % 4;
        int rowStart = relOfs / 1024;
        int colStart = (relOfs % 1024) / 4;
        int pixelX = Math.min(255, colStart + (vtxU & 63));
        int pixelY = (bank * 4 + slot) * 64 + rowStart + (vtxV & 63);
        return new float[]{pixelX / 256f, 1f - pixelY / (float) atlasH};
    }

    /** numPoints × (SWORD x,y,z) après onOff(4) + angles((numPoints+1)/2*2). */
    private static int[][] readPoints(int ofs, int numPts, int end) {
        int angleBytes = ((numPts + 1) / 2) * 2;
        int start = ofs + 4 + angleBytes;
        if (start + numPts * 6 > end) {
            return null;
        }
        int[][] pts = new int[numPts][3];
        for (int i = 0; i < numPts; i++) {
            pts[i][0] = Mem.w(start + i * 6);
            pts[i][1] = Mem.w(start + i * 6 + 2);
            pts[i][2] = Mem.w(start + i * 6 + 4);
        }
        return pts;
    }

    private record Poly(int[] pt, int[] u, int[] v, int texOffset, int level, int ang) {
    }

    /**
     * Niveau de luminosité PROPRE à la face, depuis l'octet qui suit texOffset dans le footer
     * (Objdrawhires.java:1849-1857) : {@code niveau = 31 - (((b << 5) * 41) >> 12)}, borné [0,31].
     * Le jeu y ajoute encore la contribution DIRECTIONNELLE (anneau draw_AngleBrights), non portée.
     */
    private static int faceLevel(int b) {
        int t = ((b << 5) * 41) >> 12;
        return Math.max(0, Math.min(31, 31 - t));
    }

    /** Polygones d'une part : numVerts = numLines+1 (la boucle ferme le polygone) ; capture ptIdx + u/v + texOffset. */
    private static List<Poly> readPolygons(int bodyOfs, int end, int numPoints, int polyAngTable) {
        List<Poly> faces = new ArrayList<>();
        int pos = bodyOfs;
        for (int guard = 0; guard < 4096; guard++) {
            if (pos + 4 > end) {
                break;
            }
            int numLines = Mem.w(pos);
            if (numLines < 0 || numLines > 64) {
                break;
            }
            int polySize = 18 + numLines * 4;
            if (pos + polySize > end) {
                break;
            }
            int numVerts = numLines + 1;
            int texOffset = Mem.uw(pos + numLines * 4 + 12);
            int level = faceLevel(Mem.ub(pos + numLines * 4 + 14));
            // Octet suivant = INDEX dans la table d'angles de la frame (draw_PolyAngPtr) ; la valeur
            // lue porte le secteur horizontal (quartet faible) et la position verticale (quartet
            // fort) de la face, utilisés par l'anneau d'éclairage (Objdrawhires.java:1866-1888).
            int ang = polyAngTable == 0 ? 0 : Mem.ub(polyAngTable + Mem.ub(pos + numLines * 4 + 15));
            List<int[]> vs = new ArrayList<>(numVerts);       // {ptIdx, u, v}
            for (int i = 0; i < numVerts; i++) {
                int o = pos + 4 + i * 4;
                int ptIdx = Mem.uw(o);
                if (ptIdx < numPoints) {
                    vs.add(new int[]{ptIdx, Mem.ub(o + 2), Mem.ub(o + 3)});
                }
            }
            if (vs.size() >= 3) {
                int[] pt = new int[vs.size()], u = new int[vs.size()], vv = new int[vs.size()];
                for (int j = 0; j < vs.size(); j++) {
                    pt[j] = vs.get(j)[0];
                    u[j] = vs.get(j)[1];
                    vv[j] = vs.get(j)[2];
                }
                faces.add(new Poly(pt, u, vv, texOffset, level, ang));
            }
            pos += polySize;
        }
        return faces;
    }
}
