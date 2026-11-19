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

    /**
     * La largeur pour laquelle {@link #LISSAGE} a ete regle.
     *
     * <p>Le lissage se compte en TEXELS, mais le relief se juge en metres de mur. Une
     * texture agrandie quatre fois a quatre fois plus de texels pour le meme mur : garder
     * le meme rayon reviendrait a lisser quatre fois moins, et chaque grain deviendrait une
     * bosse -- essaye, le mur tourne au papier froisse. Le rayon suit donc la resolution.
     */
    private static final int REFERENCE = 128;

    /** Combien la brillance monte : au-dela, le decor devient du plastique mouille. */
    private static final float SPECULAIRE = 1.25f;

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

        // RGBA : les trois premiers canaux portent la normale, le QUATRIEME la hauteur.
        // C'est ce que jME appelle PackedNormalParallax -- une seule texture pour les deux,
        // donc un seul echantillon de plus dans le shader au lieu de deux.
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
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
                int a = Math.round(Math.max(0f, Math.min(1f, haut[y * w + x])) * 255f);
                out.setRGB(x, y, (a << 24) | (r << 16) | (v << 8) | b);
            }
        }
        return lisseeEtRepetee(out);
    }

    /**
     * LA CARTE DE BRILLANCE, tiree elle aussi de la texture.
     *
     * <p>Ce qui brille, dans ce decor, c'est le metal et la pierre polie : du CLAIR et du
     * GRIS. Ce qui ne brille pas, c'est la roche sombre, la terre, le bois : du sombre, ou
     * du colore. On tire donc la brillance de la clarte et de la DESATURATION -- un texel
     * clair et gris repond a la lumiere, un texel brun l'absorbe.
     *
     * <p>C'est elle qui fait qu'une lampe se PROMENE sur un mur quand on marche le long :
     * la part diffuse, elle, ne depend pas de l'oeil et ne bouge jamais.
     */
    public static Texture2D specularMap(BufferedImage src) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                float r = ((p >> 16) & 0xFF) / 255f;
                float v = ((p >> 8) & 0xFF) / 255f;
                float bl = (p & 0xFF) / 255f;
                float max = Math.max(r, Math.max(v, bl));
                float min = Math.min(r, Math.min(v, bl));
                float sat = max <= 0.001f ? 0f : (max - min) / max;
                float clarte = 0.299f * r + 0.587f * v + 0.114f * bl;
                // Au carre : seuls les texels vraiment clairs brillent, et la brillance
                // ne s'etale pas sur toute la surface.
                float s = clarte * clarte * (1f - sat) * SPECULAIRE;
                int g = Math.round(Math.max(0f, Math.min(1f, s)) * 255f);
                out.setRGB(x, y, (g << 16) | (g << 8) | g);
            }
        }
        return lisseeEtRepetee(out);
    }

    /**
     * Une carte de RELIEF, pas une image : elle s'interpole.
     *
     * <p>{@code Assets.toTexture} pose le filtre << plus proche voisin >>, qui garde aux
     * textures de 1996 leur trait net. Pour une normale ou une hauteur c'est l'inverse
     * qu'il faut : sans interpolation, chaque texel devient une FACETTE et le mur se
     * couvre d'ecailles des qu'on s'en approche. La couleur reste nette, la lumiere
     * devient douce.
     */
    private static Texture2D lisseeEtRepetee(BufferedImage img) {
        Texture2D t = Assets.toTexture(img);
        t.setMagFilter(com.jme3.texture.Texture.MagFilter.Bilinear);
        t.setMinFilter(com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps);
        t.setWrap(com.jme3.texture.Texture.WrapMode.Repeat);
        return t;
    }

    /** La hauteur en (x, y), la texture s'enroulant sur ses bords. */
    private static float pente(float[] haut, int w, int h, int x, int y) {
        int cx = ((x % w) + w) % w;
        int cy = ((y % h) + h) % h;
        return haut[cy * w + cx];
    }

    /** Moyenne sur un petit carre : le grain d'une texture n'est pas du relief. */
    private static float[] lisser(float[] haut, int w, int h) {
        int rayon = Math.max(LISSAGE, Math.round(LISSAGE * Math.max(w, h)
                / (float) REFERENCE));
        if (rayon <= 0) {
            return haut;
        }
        float[] out = new float[haut.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0;
                int n = 0;
                for (int dy = -rayon; dy <= rayon; dy++) {
                    for (int dx = -rayon; dx <= rayon; dx++) {
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
