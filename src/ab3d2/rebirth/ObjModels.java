package ab3d2.rebirth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chargement des modeles vectoriels (rebirth/assets/models/&lt;NOM&gt;/frame_NNN.obj), extraits
 * par rebirth.VectObjExport depuis draw_PolygonModel.
 *
 * <p>Parser OBJ minimal (v / vt / f) : les fichiers sont produits par notre extraction, pas
 * besoin d'un loader generique. Les sommets sont deja a l'echelle locale (raw/128) avec Y et Z
 * inverses a l'export ; les UV pointent l'atlas texturemaps.
 *
 * <p>Le sidecar {@code frame_NNN.lvl} donne, par FACE et dans l'ordre des lignes {@code f} :
 * le niveau de luminosite de base et l'octet d'angle (secteur horizontal en quartet faible,
 * position verticale en quartet fort). {@link LightRings} s'en sert pour eclairer chaque face.
 */
public final class ObjModels {

    private static final Map<String, Model> CACHE = new HashMap<>();

    /** Un modele : triangles (position + UV) et donnees d'eclairage par face. */
    public static final class Model {
        /** Par triangle : 3 sommets de 5 flottants (x, y, z, u, v). */
        public final float[][] tris;
        /** Index de face de chaque triangle. */
        public final int[] triFace;
        /** Niveau de luminosite de base par face (0 = le plus clair). */
        public final int[] faceLevel;
        /** Octet d'angle par face : quartet faible = secteur, quartet fort = position verticale. */
        public final int[] faceAng;

        Model(float[][] tris, int[] triFace, int[] faceLevel, int[] faceAng) {
            this.tris = tris;
            this.triFace = triFace;
            this.faceLevel = faceLevel;
            this.faceAng = faceAng;
        }
    }

    private ObjModels() {
    }

    /** Renvoie le modele du fichier OBJ (cache), ou null s'il est absent/vide. */
    public static Model load(String relative) {
        if (CACHE.containsKey(relative)) {
            return CACHE.get(relative);
        }
        Model m = parse(Assets.text(relative), Assets.text(relative.replace(".obj", ".lvl")));
        CACHE.put(relative, m);
        return m;
    }

    private static Model parse(String text, String levels) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<float[]> vs = new ArrayList<>();
        List<float[]> vts = new ArrayList<>();
        List<float[]> tris = new ArrayList<>();
        List<Integer> triFace = new ArrayList<>();
        int face = 0;

        for (String line : text.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 0 || p[0].isEmpty()) {
                continue;
            }
            switch (p[0]) {
                case "v" -> vs.add(new float[] {
                        Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]) });
                case "vt" -> vts.add(new float[] {
                        Float.parseFloat(p[1]), Float.parseFloat(p[2]) });
                case "f" -> {
                    // Eventail : (1, k, k+1). Chaque token = "ptIdx/vtIdx" (1-based).
                    for (int k = 2; k < p.length - 1; k++) {
                        float[] t = new float[15];
                        corner(t, 0, vs, vts, p[1]);
                        corner(t, 5, vs, vts, p[k]);
                        corner(t, 10, vs, vts, p[k + 1]);
                        tris.add(t);
                        triFace.add(face);
                    }
                    face++;
                }
                default -> { /* vn, mtllib, usemtl, o, g : ignores */ }
            }
        }
        if (tris.isEmpty()) {
            return null;
        }

        int[] level = new int[face];
        int[] ang = new int[face];
        if (levels != null) {
            String[] rows = levels.split("\n");
            for (int i = 0; i < face && i < rows.length; i++) {
                String[] c = rows[i].trim().split("\\s+");
                if (c.length >= 1 && !c[0].isEmpty()) {
                    level[i] = Integer.parseInt(c[0]);
                }
                if (c.length >= 2) {
                    ang[i] = Integer.parseInt(c[1]);
                }
            }
        }
        int[] tf = new int[triFace.size()];
        for (int i = 0; i < tf.length; i++) {
            tf[i] = triFace.get(i);
        }
        return new Model(tris.toArray(new float[0][]), tf, level, ang);
    }

    private static void corner(float[] out, int at, List<float[]> vs, List<float[]> vts, String token) {
        String[] parts = token.split("/");
        int pi = Integer.parseInt(parts[0]) - 1;
        float u = 0f;
        float v = 0f;
        if (parts.length > 1 && !parts[1].isEmpty()) {
            int ti = Integer.parseInt(parts[1]) - 1;
            if (ti >= 0 && ti < vts.size()) {
                u = vts.get(ti)[0];
                // OBJ : v croissant vers le HAUT ; nos textures sont chargees v=0 en haut.
                v = 1f - vts.get(ti)[1];
            }
        }
        if (pi >= 0 && pi < vs.size()) {
            float[] q = vs.get(pi);
            out[at] = q[0];
            out[at + 1] = q[1];
            out[at + 2] = q[2];
        }
        out[at + 3] = u;
        out[at + 4] = v;
    }
}
