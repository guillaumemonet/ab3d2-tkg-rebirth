package ab3d2.rebirth;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * UN SPRITE MIS EN VOLUME, par extrusion de ses pixels.
 *
 * <p>Les munitions et les soins du jeu sont des PANNEAUX : une image plate qui pivote pour
 * rester face au joueur. Vu de cote, un tel panneau n'a pas d'epaisseur, il ne prend pas la
 * lumiere d'une piece et son ombre est celle d'une decoupe de papier.
 *
 * <p>On en fait donc un VOLUME, et sans rien dessiner a la main : chaque pixel opaque devient un
 * petit bloc, et les faces laterales ne sont emises qu'a la FRONTIERE -- la ou un pixel opaque
 * touche un pixel transparent ou le bord. La silhouette est donc exactement celle du sprite,
 * quelle que soit sa forme, et le nombre de triangles reste celui de son CONTOUR, pas de sa
 * surface.
 *
 * <p>Les faces avant et arriere gardent les coordonnees de texture de l'image : le volume porte
 * son propre graphique, aux pixels pres. Les faces laterales reprennent celles du pixel dont
 * elles partent, si bien que la tranche a la couleur de ce qu'elle prolonge.
 *
 * <p>Economie faite sur les faces avant/arriere : une rangee de pixels opaques voisins est
 * emise en UN SEUL quad (balayage par bandes horizontales), ce qui divise le compte par cinq a
 * dix sur les graphismes pleins.
 */
public final class SpriteExtrude {

    private SpriteExtrude() {
    }

    /** En-dessous de quoi un pixel est tenu pour transparent. */
    private static final int ALPHA = 128;

    /**
     * Le volume d'un sprite.
     *
     * @param img        l'image, lue par son canal alpha
     * @param ppu        combien de pixels de l'image font une unite jME
     * @param epaisseur  l'epaisseur du volume, en unites jME
     * @return le maillage, ou {@code null} si l'image est entierement transparente
     */
    public static Mesh build(BufferedImage img, float ppu, float epaisseur) {
        if (img == null) {
            return null;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[][] plein = new boolean[h][w];
        boolean rien = true;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                plein[y][x] = (img.getRGB(x, y) >>> 24) >= ALPHA;
                rien &= !plein[y][x];
            }
        }
        if (rien) {
            return null;
        }

        // Le volume est centre sur lui-meme, comme l'etait le panneau qu'il remplace.
        float ox = w / 2f;
        float oy = h / 2f;
        float e = epaisseur / 2f;
        List<Float> pos = new ArrayList<>();
        List<Float> uv = new ArrayList<>();
        List<Float> nor = new ArrayList<>();

        // --- AVANT ET ARRIERE, par bandes horizontales de pixels opaques ---
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                if (!plein[y][x]) {
                    x++;
                    continue;
                }
                int fin = x;
                while (fin + 1 < w && plein[y][fin + 1]) {
                    fin++;
                }
                float x0 = (x - ox) / ppu;
                float x1 = (fin + 1 - ox) / ppu;
                float y0 = (oy - y) / ppu;             // l'image descend, le monde monte
                float y1 = (oy - (y + 1)) / ppu;
                float u0 = x / (float) w;
                float u1 = (fin + 1) / (float) w;
                float v0 = y / (float) h;
                float v1 = (y + 1) / (float) h;
                // avant (vers +Z)
                quad(pos, uv, nor,
                        x0, y0, e, u0, v0, x1, y0, e, u1, v0,
                        x1, y1, e, u1, v1, x0, y1, e, u0, v1, 0f, 0f, 1f);
                // arriere (vers -Z) : meme bande, ordre inverse
                quad(pos, uv, nor,
                        x1, y0, -e, u1, v0, x0, y0, -e, u0, v0,
                        x0, y1, -e, u0, v1, x1, y1, -e, u1, v1, 0f, 0f, -1f);
                x = fin + 1;
            }
        }

        // --- LES TRANCHES, a la frontiere seulement, ET PAR BANDES ---
        //
        // Une tranche par pixel de contour coute cher pour rien : un bord droit de vingt
        // pixels, c'est UN rectangle. On balaye donc chaque bord en cherchant des suites
        // de pixels frontaliers, et l'on emet un quad par suite. Mesure : 17 456 triangles
        // a l'image contre 11 866 sans volumes, puis 13 400 une fois les bandes posees.
        for (int x = 0; x < w; x++) {              // bords GAUCHE et DROIT : suites verticales
            bandeVerticale(pos, uv, nor, plein, w, h, x, true, ox, oy, ppu, e);
            bandeVerticale(pos, uv, nor, plein, w, h, x, false, ox, oy, ppu, e);
        }
        for (int y = 0; y < h; y++) {              // bords HAUT et BAS : suites horizontales
            bandeHorizontale(pos, uv, nor, plein, w, h, y, true, ox, oy, ppu, e);
            bandeHorizontale(pos, uv, nor, plein, w, h, y, false, ox, oy, ppu, e);
        }

        int n = pos.size() / 3;
        float[] p = new float[pos.size()];
        for (int i = 0; i < p.length; i++) {
            p[i] = pos.get(i);
        }
        float[] t = new float[uv.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = uv.get(i);
        }
        float[] nm = new float[nor.size()];
        for (int i = 0; i < nm.length; i++) {
            nm[i] = nor.get(i);
        }
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        float[] blanc = new float[n * 4];
        java.util.Arrays.fill(blanc, 1f);
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(p));
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(t));
        mesh.setBuffer(VertexBuffer.Type.Normal, 3, BufferUtils.createFloatBuffer(nm));
        mesh.setBuffer(VertexBuffer.Type.Color, 4, BufferUtils.createFloatBuffer(blanc));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
        mesh.updateBound();
        return mesh;
    }

    /**
     * Les tranches d'un bord VERTICAL d'une colonne : gauche ({@code vers Gauche}) ou droite.
     *
     * <p>Un pixel est frontalier si son voisin de ce cote manque. Les frontaliers qui se
     * suivent dans la colonne forment une bande, et la bande fait un seul rectangle.
     */
    private static void bandeVerticale(List<Float> pos, List<Float> uv, List<Float> nor,
            boolean[][] plein, int w, int h, int x, boolean versGauche,
            float ox, float oy, float ppu, float e) {
        int y = 0;
        while (y < h) {
            if (!frontiereX(plein, w, x, y, versGauche)) {
                y++;
                continue;
            }
            int fin = y;
            while (fin + 1 < h && frontiereX(plein, w, x, fin + 1, versGauche)) {
                fin++;
            }
            float xf = (versGauche ? x : x + 1) - ox;
            float xw = xf / ppu;
            float y0 = (oy - y) / ppu;
            float y1 = (oy - (fin + 1)) / ppu;
            float u = (x + 0.5f) / w;
            float v = (y + (fin - y + 1) * 0.5f) / h;
            if (versGauche) {
                quad(pos, uv, nor, xw, y0, -e, u, v, xw, y0, e, u, v,
                        xw, y1, e, u, v, xw, y1, -e, u, v, -1f, 0f, 0f);
            } else {
                quad(pos, uv, nor, xw, y0, e, u, v, xw, y0, -e, u, v,
                        xw, y1, -e, u, v, xw, y1, e, u, v, 1f, 0f, 0f);
            }
            y = fin + 1;
        }
    }

    private static boolean frontiereX(boolean[][] plein, int w, int x, int y,
            boolean versGauche) {
        if (!plein[y][x]) {
            return false;
        }
        return versGauche ? (x == 0 || !plein[y][x - 1])
                : (x == w - 1 || !plein[y][x + 1]);
    }

    /** Les tranches d'un bord HORIZONTAL d'une ligne : haut ({@code versHaut}) ou bas. */
    private static void bandeHorizontale(List<Float> pos, List<Float> uv, List<Float> nor,
            boolean[][] plein, int w, int h, int y, boolean versHaut,
            float ox, float oy, float ppu, float e) {
        int x = 0;
        while (x < w) {
            if (!frontiereY(plein, h, x, y, versHaut)) {
                x++;
                continue;
            }
            int fin = x;
            while (fin + 1 < w && frontiereY(plein, h, fin + 1, y, versHaut)) {
                fin++;
            }
            float yw = (oy - (versHaut ? y : y + 1)) / ppu;
            float x0 = (x - ox) / ppu;
            float x1 = (fin + 1 - ox) / ppu;
            float u = (x + (fin - x + 1) * 0.5f) / w;
            float v = (y + 0.5f) / h;
            if (versHaut) {
                quad(pos, uv, nor, x0, yw, -e, u, v, x1, yw, -e, u, v,
                        x1, yw, e, u, v, x0, yw, e, u, v, 0f, 1f, 0f);
            } else {
                quad(pos, uv, nor, x0, yw, e, u, v, x1, yw, e, u, v,
                        x1, yw, -e, u, v, x0, yw, -e, u, v, 0f, -1f, 0f);
            }
            x = fin + 1;
        }
    }

    private static boolean frontiereY(boolean[][] plein, int h, int x, int y,
            boolean versHaut) {
        if (!plein[y][x]) {
            return false;
        }
        return versHaut ? (y == 0 || !plein[y - 1][x])
                : (y == h - 1 || !plein[y + 1][x]);
    }

    /** Deux triangles, quatre sommets donnes dans le sens trigonometrique vu de la normale. */
    private static void quad(List<Float> pos, List<Float> uv, List<Float> nor,
            float ax, float ay, float az, float au, float av,
            float bx, float by, float bz, float bu, float bv,
            float cx, float cy, float cz, float cu, float cv,
            float dx, float dy, float dz, float du, float dv,
            float nx, float ny, float nz) {
        sommet(pos, uv, nor, ax, ay, az, au, av, nx, ny, nz);
        sommet(pos, uv, nor, bx, by, bz, bu, bv, nx, ny, nz);
        sommet(pos, uv, nor, cx, cy, cz, cu, cv, nx, ny, nz);
        sommet(pos, uv, nor, ax, ay, az, au, av, nx, ny, nz);
        sommet(pos, uv, nor, cx, cy, cz, cu, cv, nx, ny, nz);
        sommet(pos, uv, nor, dx, dy, dz, du, dv, nx, ny, nz);
    }

    private static void sommet(List<Float> pos, List<Float> uv, List<Float> nor,
            float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        pos.add(x);
        pos.add(y);
        pos.add(z);
        uv.add(u);
        uv.add(v);
        nor.add(nx);
        nor.add(ny);
        nor.add(nz);
    }
}
