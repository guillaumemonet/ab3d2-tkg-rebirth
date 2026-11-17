package ab3d2.rebirth;

import com.jme3.texture.Texture2D;

import java.awt.image.BufferedImage;

/**
 * LE RELIEF D'UNE TEXTURE, TIRE D'ELLE-MEME.
 *
 * <p>Les textures de 1996 sont peintes a la main, et le relief y est DESSINE : une pierre
 * saillante est peinte plus claire sur le dessus et plus sombre en dessous, une fissure est un
 * trait noir. Ce relief-la est fige — il suppose une lumiere venue d'en haut, et il ne bouge
 * pas quand on eclaire le mur d'ailleurs.
 *
 * <p>On le rend au moteur. La clarte de chaque texel sert de HAUTEUR : clair = en relief,
 * sombre = en creux. La pente locale donne la normale, et le mur repond alors aux vraies
 * lumieres — une lampe posee a gauche creuse les joints a droite, un tir qui passe fait courir
 * la lumiere sur la pierre.
 *
 * <p>C'est une lecture naive, et c'est exactement ce qu'il faut ici : ces textures ont ete
 * peintes avec cette convention. Elle echoue la ou la couleur ne dit rien du relief — un mur
 * peint en damier clair et sombre devient un gaufrier —, d'ou {@link #FORCE}, assez faible pour
 * que l'erreur reste discrete et le gain visible.
 */
public final class Relief {

    private Relief() {
    }

    /**
     * De combien la pente compte. Au-dela, les textures tres contrastees (grilles, rayures de
     * danger) se mettent a onduler comme de la tole.
     */
    private static final float FORCE = 1.6f;

    /**
     * Le flou applique a la hauteur avant d'en prendre la pente.
     *
     * <p>Un texel isole tres clair n'est pas une bosse, c'est un grain. Sans ce lissage, chaque
     * pixel du bruit de la texture devient une facette et le mur grouille des qu'on bouge.
     */
    private static final int LISSAGE = 1;

    /** La carte de normales d'une image, ou {@code null} si l'image manque. */
    public static Texture2D normalMap(BufferedImage src) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        float[] haut = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                // Clarte perceptuelle : le vert pese plus que le bleu dans ce que l'oeil lit
                // comme un relief.
                haut[y * w + x] = (0.299f * ((p >> 16) & 0xFF)
                        + 0.587f * ((p >> 8) & 0xFF)
                        + 0.114f * (p & 0xFF)) / 255f;
            }
        }
        haut = lisser(haut, w, h);

        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Sobel sur la hauteur. Les textures s'enroulent (un mur les repete), donc les
                // bords se referment sur eux-memes : pas de couture visible a la jointure.
                float gx = pente(haut, w, h, x + 1, y - 1) + 2 * pente(haut, w, h, x + 1, y)
                        + pente(haut, w, h, x + 1, y + 1)
                        - pente(haut, w, h, x - 1, y - 1) - 2 * pente(haut, w, h, x - 1, y)
                        - pente(haut, w, h, x - 1, y + 1);
                float gy = pente(haut, w, h, x - 1, y + 1) + 2 * pente(haut, w, h, x, y + 1)
                        + pente(haut, w, h, x + 1, y + 1)
                        - pente(haut, w, h, x - 1, y - 1) - 2 * pente(haut, w, h, x, y - 1)
                        - pente(haut, w, h, x + 1, y - 1);
                float nx = -gx * FORCE;
                float ny = -gy * FORCE;
                float nz = 1f;
                float n = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                int r = Math.round((nx / n * 0.5f + 0.5f) * 255f);
                int v = Math.round((ny / n * 0.5f + 0.5f) * 255f);
                int b = Math.round((nz / n * 0.5f + 0.5f) * 255f);
                out.setRGB(x, y, (r << 16) | (v << 8) | b);
            }
        }
        return Assets.toTexture(out);
    }

    /** La hauteur en (x, y), la texture s'enroulant sur ses bords. */
    private static float pente(float[] haut, int w, int h, int x, int y) {
        int cx = ((x % w) + w) % w;
        int cy = ((y % h) + h) % h;
        return haut[cy * w + cx];
    }

    /** Moyenne sur un petit carre : le grain d'une texture n'est pas du relief. */
    private static float[] lisser(float[] haut, int w, int h) {
        if (LISSAGE <= 0) {
            return haut;
        }
        float[] out = new float[haut.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int dy = -LISSAGE; dy <= LISSAGE; dy++) {
                    for (int dx = -LISSAGE; dx <= LISSAGE; dx++) {
                        s += pente(haut, w, h, x + dx, y + dy);
                        n++;
                    }
                }
                out[y * w + x] = s / n;
            }
        }
        return out;
    }
}
