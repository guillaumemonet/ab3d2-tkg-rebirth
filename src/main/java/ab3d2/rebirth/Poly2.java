package ab3d2.rebirth;

/**
 * Triangulation d'un polygone simple (sans trou) par "ear clipping".
 *
 * <p>Remplace Geometry2D.triangulate_polygon (Godot) pour les sols/plafonds de secteur.
 * Le contour vient des ARETES de la zone (anneau ferme fiable). En cas d'echec (contour
 * degenere ou auto-intersectant), l'appelant retombe sur un eventail.
 */
public final class Poly2 {

    private Poly2() {
    }

    /**
     * @param ring contour {x0,y0, x1,y1, ...} (sens quelconque)
     * @return indices de triangles (multiples de 3), ou null si la triangulation echoue
     */
    public static int[] triangulate(float[] ring) {
        int n = ring.length / 2;
        if (n < 3) {
            return null;
        }
        int[] idx = new int[n];
        boolean ccw = signedArea(ring) > 0f;
        for (int i = 0; i < n; i++) {
            idx[i] = ccw ? i : n - 1 - i;    // on travaille toujours en sens direct
        }

        int[] out = new int[(n - 2) * 3];
        int outCount = 0;
        int remaining = n;
        int guard = 0;
        int i = 0;
        while (remaining > 3) {
            if (guard++ > 4 * n * n) {
                return null;                  // polygone non simple : on abandonne
            }
            int i0 = idx[(i + remaining - 1) % remaining];
            int i1 = idx[i % remaining];
            int i2 = idx[(i + 1) % remaining];
            if (isEar(ring, idx, remaining, i0, i1, i2)) {
                out[outCount++] = i0;
                out[outCount++] = i1;
                out[outCount++] = i2;
                // retire le sommet i1 de la liste
                int at = i % remaining;
                System.arraycopy(idx, at + 1, idx, at, remaining - at - 1);
                remaining--;
                if (i >= remaining) {
                    i = 0;
                }
            } else {
                i++;
            }
        }
        out[outCount++] = idx[0];
        out[outCount++] = idx[1];
        out[outCount++] = idx[2];

        int[] res = new int[outCount];
        System.arraycopy(out, 0, res, 0, outCount);
        return res;
    }

    /** Eventail depuis le sommet 0 (repli quand la triangulation echoue). */
    public static int[] fan(int n) {
        if (n < 3) {
            return new int[0];
        }
        int[] out = new int[(n - 2) * 3];
        int k = 0;
        for (int i = 1; i < n - 1; i++) {
            out[k++] = 0;
            out[k++] = i;
            out[k++] = i + 1;
        }
        return out;
    }

    private static boolean isEar(float[] ring, int[] idx, int count, int i0, int i1, int i2) {
        float ax = ring[i0 * 2], ay = ring[i0 * 2 + 1];
        float bx = ring[i1 * 2], by = ring[i1 * 2 + 1];
        float cx = ring[i2 * 2], cy = ring[i2 * 2 + 1];
        float cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (cross <= 0f) {
            return false;                     // sommet reflex (ou plat)
        }
        for (int k = 0; k < count; k++) {
            int p = idx[k];
            if (p == i0 || p == i1 || p == i2) {
                continue;
            }
            if (inTriangle(ring[p * 2], ring[p * 2 + 1], ax, ay, bx, by, cx, cy)) {
                return false;
            }
        }
        return true;
    }

    private static boolean inTriangle(float px, float py,
                                      float ax, float ay, float bx, float by, float cx, float cy) {
        float d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
        float d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
        float d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    private static float signedArea(float[] ring) {
        float a = 0f;
        int n = ring.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            a += ring[j * 2] * ring[i * 2 + 1] - ring[i * 2] * ring[j * 2 + 1];
        }
        return a * 0.5f;
    }
}
