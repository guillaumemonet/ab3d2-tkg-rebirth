package ab3d2.rebirth;

import java.awt.image.BufferedImage;

/**
 * QUATRE CARTES EN UNE IMAGE : la planche que produit un generateur de matiere.
 *
 * <p>Le remake DEDUIT normalement ses cartes de la couleur : le relief vient de la clarte, la
 * brillance de la desaturation, la lueur du couple clair-et-colore. C'est honnete faute de
 * mieux, mais cela ne fait que relire ce qu'un peintre de 1996 a suggere.
 *
 * <p>Quand une texture vient avec ses PROPRES cartes, elles valent mieux : elles disent ce que
 * la surface EST, au lieu de le deviner. Le format est une planche de deux par deux, lue dans
 * l'ordre de lecture :
 *
 * <pre>
 *   couleur (albedo)        normales (+ hauteur dans l'alpha)
 *   lueur (emissivite)      brillance (speculaire)
 * </pre>
 *
 * <p>ELLE SE RECONNAIT TOUTE SEULE, sans convention de nom a retenir : une carte de normales
 * tangentes est bleue -- ses composantes tournent autour de (128, 128, 255) --, et aucune
 * texture de decor ne l'est. Il suffit donc de regarder le quartier en haut a droite.
 */
public final class CartesPackees {

    /** La couleur, en haut a gauche. */
    public final BufferedImage couleur;
    /** Les normales, en haut a droite ; la hauteur est dans leur alpha, s'il y en a un. */
    public final BufferedImage normales;
    /** La lueur, en bas a gauche. */
    public final BufferedImage lueur;
    /** La brillance, en bas a droite. */
    public final BufferedImage brillance;
    /** Vrai si les normales portent une hauteur : un JPEG, lui, n'a pas d'alpha. */
    public final boolean avecHauteur;

    private CartesPackees(BufferedImage c, BufferedImage n, BufferedImage l, BufferedImage b,
            boolean hauteur) {
        couleur = c;
        normales = n;
        lueur = l;
        brillance = b;
        avecHauteur = hauteur;
    }

    /**
     * Decoupe une planche, ou rend {@code null} si l'image n'en est pas une.
     *
     * <p>On ne decoupe que si le quartier en haut a droite EST une carte de normales : une
     * image deux fois trop grande n'est pas forcement une planche, et se tromper donnerait un
     * mur couvert d'un quart de sa propre texture.
     */
    public static CartesPackees decouper(BufferedImage src) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        if (w < 64 || h < 64 || (w & 1) != 0 || (h & 1) != 0) {
            return null;
        }
        int dw = w / 2;
        int dh = h / 2;
        BufferedImage hd = src.getSubimage(dw, 0, dw, dh);   // simple lecture : une vue suffit
        if (!ressembleADesNormales(hd)) {
            return null;
        }
        boolean alpha = src.getColorModel().hasAlpha();
        // CHAQUE QUARTIER DANS SA PROPRE IMAGE, et non une vue sur la planche.
        //
        // getSubimage rend une VUE : elle partage la trame du parent, dont la largeur de
        // ligne reste celle de la planche entiere. Le chargeur de textures de jME, lui,
        // dimensionne son tampon sur la largeur ANNONCEE et lit au-dela : BufferOverflow
        // au premier quartier. Une copie coute quatre images de plus au chargement et
        // s'oublie aussitot.
        return new CartesPackees(copie(src, 0, 0, dw, dh, alpha), copie(src, dw, 0, dw, dh, alpha),
                copie(src, 0, dh, dw, dh, alpha), copie(src, dw, dh, dw, dh, alpha), alpha);
    }

    /** Un quartier, recopie dans une image autonome. */
    private static BufferedImage copie(BufferedImage src, int x, int y, int w, int h,
            boolean alpha) {
        BufferedImage out = new BufferedImage(w, h,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, w, h, x, y, x + w, y + h, null);
        g.dispose();
        return out;
    }

    /**
     * Ce quartier a-t-il l'air d'une carte de normales ?
     *
     * <p>Une normale tangente pointe vers l'exterieur de la surface : sa composante Z domine,
     * et elle est codee dans le BLEU. La moyenne tourne donc autour de (128, 128, 230) et le
     * bleu l'emporte nettement sur les deux autres, ce qu'aucune texture de pierre ou de metal
     * ne fait.
     */
    private static boolean ressembleADesNormales(BufferedImage q) {
        long r = 0;
        long v = 0;
        long b = 0;
        int n = 0;
        // Un echantillon suffit : on cherche une tendance, pas une mesure.
        int pas = Math.max(1, Math.min(q.getWidth(), q.getHeight()) / 64);
        for (int y = 0; y < q.getHeight(); y += pas) {
            for (int x = 0; x < q.getWidth(); x += pas) {
                int p = q.getRGB(x, y);
                r += (p >> 16) & 0xFF;
                v += (p >> 8) & 0xFF;
                b += p & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return false;
        }
        float mr = r / (float) n;
        float mv = v / (float) n;
        float mb = b / (float) n;
        return mb > 150 && mb > mr + 30 && mb > mv + 30
                && Math.abs(mr - 128) < 60 && Math.abs(mv - 128) < 60;
    }
}
