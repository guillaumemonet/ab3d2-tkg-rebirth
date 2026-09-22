package ab3d2.rebirth;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

import java.util.ArrayList;
import java.util.List;

/** Petit accumulateur de triangles (position + UV) -> Mesh jME. */
public final class MeshBuilder {

    private final List<Float> pos = new ArrayList<>();
    private final List<Float> uv = new ArrayList<>();
    private final List<Float> bright = new ArrayList<>();
    private boolean hasBright;
    // Source de la luminosite par sommet : {zone, index de point, decalage} ; zone < 0 = fixe.
    private final List<int[]> litSource = new ArrayList<>();
    private boolean hasLitSource;

    public void vertex(float x, float y, float z, float u, float v) {
        vertex(x, y, z, u, v, 0f);
    }

    /** `b` = luminosite du sommet (TexCoord2.x), utilisee par le materiau WallShade. */
    /**
     * Sommet dont la luminosite vient d'un point de niveau : elle pourra etre RECALCULEE quand
     * les lumieres animees changent (cf. LevelBuilder.refreshLighting).
     */
    public void vertexLit(float x, float y, float z, float u, float v, float b,
                          int zone, int idx, int off) {
        vertex(x, y, z, u, v, b);
        litSource.set(litSource.size() - 1, new int[] { zone, idx, off });
        hasLitSource = true;
    }

    /** Sources de luminosite par sommet (meme ordre que les sommets), ou null si aucune. */
    public int[][] litSources() {
        return hasLitSource ? litSource.toArray(new int[0][]) : null;
    }

    public void vertex(float x, float y, float z, float u, float v, float b) {
        pos.add(x);
        pos.add(y);
        pos.add(z);
        uv.add(u);
        uv.add(v);
        bright.add(b);
        bright.add(0f);
        litSource.add(NO_SOURCE);
        hasBright |= b != 0f;
    }

    /** Sommets {x, y, z, u, v} ou {x, y, z, u, v, luminosite}. */
    public void triangle(float[] a, float[] b, float[] c) {
        emit(a);
        emit(b);
        emit(c);
    }

    private void emit(float[] v) {
        vertex(v[0], v[1], v[2], v[3], v[4], v.length > 5 ? v[5] : 0f);
    }

    public boolean isEmpty() {
        return pos.isEmpty();
    }

    /** Vide l'accumulateur pour rebatir un maillage (HUD : on le refait quand un compteur change). */
    public void reset() {
        pos.clear();
        uv.clear();
        bright.clear();
        litSource.clear();
        hasBright = false;
        hasLitSource = false;
    }

    public int triangleCount() {
        return pos.size() / 9;
    }

    private static final int[] NO_SOURCE = { -1, -1, 0 };

    /**
     * Normales PLATES : les triangles ne sont pas indexes (chaque sommet est emis une fois par
     * triangle), donc la normale de la face va directement a ses trois sommets. C'est ce qu'il
     * faut pour un decor a facettes — aucun lissage a inventer.
     */
    private static float[] flatNormals(float[] p) {
        float[] n = new float[p.length];
        for (int i = 0; i + 8 < p.length; i += 9) {
            float ax = p[i + 3] - p[i];
            float ay = p[i + 4] - p[i + 1];
            float az = p[i + 5] - p[i + 2];
            float bx = p[i + 6] - p[i];
            float by = p[i + 7] - p[i + 1];
            float bz = p[i + 8] - p[i + 2];
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-6f) {
                nx /= len;
                ny /= len;
                nz /= len;
            } else {
                ny = 1f;                               // triangle degenere
            }
            for (int k = 0; k < 3; k++) {
                n[i + k * 3] = nx;
                n[i + k * 3 + 1] = ny;
                n[i + k * 3 + 2] = nz;
            }
        }
        return n;
    }

    /**
     * La luminosite d'origine, cuite en COULEUR par sommet — c'est le light map des auteurs du
     * jeu, reutilise tel quel par le rendu moteur.
     *
     * <p>Conversion : l'attribut porte {@code 2*(coin - 300)} (cf. LevelBuilder.wallBright) et le
     * rasterizer d'origine en tire un bloc de palette {@code ceil(clamp(b,0,64)/2)} sur 32, ou 0
     * est le plus CLAIR et 31 le plus sombre. Le facteur lumineux vaut donc
     * {@code 1 - bloc/31}.
     */
    private float[] bakedColours() {
        int n = pos.size() / 3;
        float[] c = new float[n * 4];
        for (int i = 0; i < n; i++) {
            float b = bright.get(i * 2);
            b = ((b + 128f) % 256f + 256f) % 256f - 128f;   // octet BAS signe, comme le shader
            float block = Math.min((float) Math.ceil(Math.max(0f, Math.min(64f, b)) * 0.5f), 31f);
            float f = 1f - block / 31f;
            c[i * 4] = f;
            c[i * 4 + 1] = f;
            c[i * 4 + 2] = f;
            c[i * 4 + 3] = 1f;
        }
        return c;
    }

    public Mesh build() {
        int n = pos.size() / 3;
        float[] p = new float[pos.size()];
        for (int i = 0; i < p.length; i++) {
            p[i] = pos.get(i);
        }
        float[] t = new float[uv.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = uv.get(i);
        }
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(p));
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(t));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
        mesh.setBuffer(VertexBuffer.Type.Normal, 3, BufferUtils.createFloatBuffer(flatNormals(p)));
        mesh.setBuffer(VertexBuffer.Type.Color, 4, BufferUtils.createFloatBuffer(bakedColours()));
        if (hasBright) {
            float[] b = new float[bright.size()];
            for (int i = 0; i < b.length; i++) {
                b[i] = bright.get(i);
            }
            mesh.setBuffer(VertexBuffer.Type.TexCoord2, 2, BufferUtils.createFloatBuffer(b));
        }
        mesh.updateBound();
        return mesh;
    }
}
