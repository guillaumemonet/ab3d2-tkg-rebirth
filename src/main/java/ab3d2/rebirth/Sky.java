package ab3d2.rebirth;

import com.jme3.asset.AssetManager;
import com.jme3.math.FastMath;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.util.SkyFactory;

import java.awt.image.BufferedImage;

/**
 * LE CIEL DES ZONES OUVERTES, porte de Newanims.Draw_SkyBackdrop (Newanims.java:2133).
 *
 * <h2>Le trou noir au zenith</h2>
 *
 * <p>C'etait un CYLINDRE, et un cylindre n'a pas de couvercle. Tant qu'on regardait devant
 * soi, personne ne s'en apercevait ; des qu'on levait la tete dans une zone a ciel ouvert --
 * le niveau L en a quarante-neuf sur cinquante-quatre -- un disque parfaitement noir occupait
 * le haut de l'ecran. Ce n'etait pas une texture manquante : c'etait le vide par-dessus bord.
 *
 * <p>Au passage, la bande d'image s'arretait a 36 degres au-dessus de l'horizon et le reste
 * etait sa derniere ligne de texels etiree a l'infini ({@code EdgeClamp}) : des rayons
 * verticaux partant de l'horizon, bien visibles sur la capture.
 *
 * <h2>Ce qu'on fait a la place</h2>
 *
 * <p>Une SPHERE, celle de {@link SkyFactory}. jME la rend dans le seau {@code Sky}, avec un
 * nuanceur qui n'utilise que la ROTATION de la camera : le ciel est a l'infini par
 * construction, et il n'y a plus a le recentrer a chaque image. Il n'a ni bord ni couvercle,
 * donc plus de trou, ou qu'on regarde.
 *
 * <p>Elle veut une image EQUIRECTANGULAIRE -- 360 degres en largeur, 180 en hauteur --, et le
 * jeu n'en a pas : sa panoramique fait 648 par 240 et ne couvre qu'un demi-tour et 72,6
 * degres de hauteur. On la fabrique donc, et c'est tout le travail de cette classe.
 *
 * <h2>Le mapping d'origine, garde tel quel</h2>
 *
 * <p>Horizontalement, {@code ((angPos & 4095) * 648) >> 12} : la panoramique couvre un DEMI
 * tour (le tour complet vaut 8192 unites d'angle), donc elle se repete deux fois par
 * revolution. Verticalement, les 240 lignes sont copiees UNE LIGNE ECRAN PAR TEXEL avec
 * l'horizon a {@code Vid_CentreY} : la projection est PLANAIRE, pas angulaire. La ligne de
 * texel d'une elevation donnee est donc en {@code tan(elevation)}, et non en
 * {@code elevation} -- c'est ce que reproduit {@link #ligneSource}, pour que le ciel se pose
 * exactement la ou il se posait.
 *
 * <p>Au-dela de la bande, l'image d'origine ne dit RIEN. On prolonge chaque colonne vers son
 * zenith et son nadir par un degrade qui part de sa propre derniere ligne : le ciel garde donc
 * son relief panoramique au ras de l'horizon et se ferme sur une couleur unie en haut, tiree
 * de l'image elle-meme. C'est une invention, assumee -- mais une invention continue, sans
 * couture visible, la ou l'etirement d'une seule ligne de texels se voyait en rayons.
 */
public final class Sky {

    /** Demi-champ vertical couvert par la panoramique, en radians (cf. explication ci-dessus). */
    private static final float HALF_V_FOV = 36.3f * FastMath.DEG_TO_RAD;

    /** Combien la couleur du zenith est assombrie par rapport au haut de la panoramique. */
    private static final float ZENITH = 0.72f;
    /** Idem pour le nadir, qu'un sol cache presque toujours. */
    private static final float NADIR = 0.45f;

    /** Largeur minimale et maximale de l'image equirectangulaire fabriquee. */
    private static final int LARGEUR_MIN = 1024;
    private static final int LARGEUR_MAX = 4096;

    /**
     * La texture ne depend pas du niveau : on la fabrique UNE fois.
     *
     * <p>{@code create} est appele a chaque chargement, et la fabrication coute deux millions
     * de pixels de trigonometrie.
     */
    private static Texture2D cache;
    private static boolean tentee;

    private Sky() {
    }

    /**
     * LA COULEUR DU CIEL, moyenne de sa bande haute, ramenee a pleine valeur.
     *
     * <p>Elle sert a teinter le SOLEIL des zones a ciel ouvert : une lumiere qui vient du ciel
     * doit avoir la couleur du ciel qu'on voit, sans quoi on eclaire un paysage violet avec du
     * blanc. On ne garde que la TEINTE, pas la clarte : l'intensite vient de la luminosite que
     * les auteurs ont donnee aux secteurs.
     *
     * <p>{@code null} si la panoramique n'est pas extraite.
     */
    public static float[] couleurDuCiel() {
        BufferedImage src = Assets.image("textures/sky/backdrop.png");
        if (src == null) {
            return null;
        }
        int lignes = Math.max(1, src.getHeight() / 6);  // le haut : du ciel, pas du paysage
        long r = 0;
        long v = 0;
        long b = 0;
        int n = 0;
        for (int y = 0; y < lignes; y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                r += (p >> 16) & 0xFF;
                v += (p >> 8) & 0xFF;
                b += p & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        float fr = r / (float) n;
        float fv = v / (float) n;
        float fb = b / (float) n;
        float max = Math.max(1f, Math.max(fr, Math.max(fv, fb)));
        return new float[] {fr / max, fv / max, fb / max};
    }

    /** Le ciel, ou {@code null} si la panoramique n'est pas extraite. */
    public static Spatial create(AssetManager assetManager) {
        Texture2D tex = panoramique();
        if (tex == null) {
            return null;
        }
        Spatial ciel = SkyFactory.createSky(assetManager, tex,
                SkyFactory.EnvMapType.EquirectMap);
        // APRES, ET PAS AVANT : SkyFactory impose ses propres filtres et son propre mode de
        // bouclage a la texture qu'on lui donne. Pose avant l'appel, tout ceci serait
        // silencieusement remplace -- et le bouclage, surtout : la sphere fait le tour
        // complet, donc l'image doit boucler en U. En butee de bord, la derniere colonne se
        // retrouve etiree sur la couture, juste derriere le joueur.
        //
        // BILINEAIRE, contrairement au reste du jeu. Ailleurs le filtre « au plus proche »
        // garde le grain d'epoque ; ici l'image couvre tout le tour de l'horizon et se voit
        // donc agrandie quatre fois a l'ecran : au plus proche, le moindre degrade du ciel
        // devient un escalier de bandes.
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        // SANS MIPMAPS. Au pole, u varie d'un demi-tour d'un pixel a l'autre : le GPU y
        // choisit le niveau le plus grossier, c'est-a-dire la couleur moyenne de toute
        // l'image -- le sol rouge compris. Cela se voyait en un trait orange en travers du
        // zenith.
        tex.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        tex.setWrap(Texture.WrapAxis.S, Texture.WrapMode.Repeat);
        tex.setWrap(Texture.WrapAxis.T, Texture.WrapMode.EdgeClamp);
        ciel.setName("Sky");
        return ciel;
    }

    /** La texture equirectangulaire, fabriquee au premier appel. */
    private static synchronized Texture2D panoramique() {
        if (tentee) {
            return cache;
        }
        tentee = true;
        BufferedImage src = Assets.image("textures/sky/backdrop.png");
        if (src == null) {
            return null;
        }
        BufferedImage eq = equirectangulaire(src);
        // flipY = VRAI, a l'inverse de tout le reste du jeu (cf. Assets.toTexture).
        //
        // La sphere de SkyFactory est construite avec ses normales TOURNEES VERS L'INTERIEUR,
        // et c'est la normale qui sert de direction au nuanceur : le zenith arrive donc au
        // nuanceur avec y = -1, soit v = 1 -- la DERNIERE ligne de la texture. Televersee a
        // l'endroit, l'image se retrouvait la tete en bas : on avait le sol rouge du paysage
        // etale en rayons au-dessus de soi, a la place du ciel.
        cache = new Texture2D(new com.jme3.texture.plugins.AWTLoader().load(eq, true));
        System.out.printf("[Level] ciel : %dx%d -> sphere equirectangulaire %dx%d%n",
                src.getWidth(), src.getHeight(), eq.getWidth(), eq.getHeight());
        return cache;
    }

    /**
     * La panoramique du jeu, depliee en image equirectangulaire.
     *
     * <p>Convention de jME ({@code Optics_LonLatCoords}) : {@code u = (atan2(z, x) + PI) / 2PI}
     * et {@code v = acos(y) / PI}. Donc la ligne 0 est le ZENITH et la colonne 0 regarde vers
     * -X. Le cylindre, lui, posait la colonne 0 de la panoramique vers -Z, l'avant de jME :
     * d'ou le decalage d'un quart de tour ci-dessous, sans lequel tout le paysage pivoterait
     * de 90 degres.
     */
    private static BufferedImage equirectangulaire(BufferedImage src) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int w = Math.min(LARGEUR_MAX, Math.max(LARGEUR_MIN, sw * 2));
        int h = w / 2;

        int[] pix = src.getRGB(0, 0, sw, sh, null, 0, sw);
        float tanBord = FastMath.tan(HALF_V_FOV);
        // Bornes de la bande d'image, en coordonnee v de l'equirectangulaire.
        float vHaut = (FastMath.HALF_PI - HALF_V_FOV) / FastMath.PI;
        float vBas = (FastMath.HALF_PI + HALF_V_FOV) / FastMath.PI;

        // UNE SEULE COULEUR AU ZENITH, pas une par colonne.
        //
        // Chaque colonne tirait d'abord vers SON propre zenith, assombri depuis sa propre
        // ligne de ciel. Mais toutes les colonnes se rejoignent au pole : leurs couleurs
        // restaient distinctes jusqu'au bout et se voyaient en rayons partant du zenith,
        // exactement ce qu'on cherchait a supprimer. Elles convergent donc vers la MEME
        // couleur, la moyenne de la ligne de ciel, assombrie.
        int zenith = teinte(moyenneLigne(pix, sw, 0), ZENITH);
        int nadir = teinte(moyenneLigne(pix, sw, (sh - 1) * sw), NADIR);

        int[] out = new int[w * h];
        for (int x = 0; x < w; x++) {
            // Colonne source : un quart de tour de decalage, puis deux repetitions par tour.
            float ue = (x + 0.5f) / w;
            float us = ue * 2f - 0.5f;
            us -= FastMath.floor(us);
            int sx = Math.min(sw - 1, (int) (us * sw));

            int hautRgb = pix[sx];                     // le ciel de cette colonne
            int basRgb = pix[(sh - 1) * sw + sx];      // le sol de cette colonne

            for (int y = 0; y < h; y++) {
                float ve = (y + 0.5f) / h;
                int rgb;
                if (ve < vHaut) {
                    rgb = melange(zenith, hautRgb, douceur(ve / vHaut), x, y);
                } else if (ve > vBas) {
                    rgb = melange(nadir, basRgb, douceur((1f - ve) / (1f - vBas)), x, y);
                } else {
                    int sy = ligneSource(ve, tanBord, sh);
                    rgb = pix[sy * sw + sx];
                }
                out[y * w + x] = rgb;
            }
        }
        BufferedImage eq = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        eq.setRGB(0, 0, w, h, out, 0, w);
        return eq;
    }

    /**
     * La ligne de la panoramique qui tombe a cette elevation.
     *
     * <p>C'est ici que se joue la fidelite : le jeu copiait une ligne de texel par ligne
     * d'ecran autour de l'horizon, ce qui est une projection PLANAIRE. La ligne est donc en
     * {@code tan(elevation)} et non en {@code elevation}, et la panoramique se retrouve
     * exactement a la meme hauteur qu'avant.
     */
    private static int ligneSource(float ve, float tanBord, int sh) {
        float elevation = FastMath.HALF_PI - ve * FastMath.PI;
        float v = 0.5f - FastMath.tan(elevation) / (2f * tanBord);
        return Math.max(0, Math.min(sh - 1, (int) (v * sh)));
    }

    /** Courbe en S : le raccord avec la bande d'image se fait sans pli visible. */
    private static float douceur(float t) {
        t = FastMath.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** La couleur moyenne d'une ligne de l'image. */
    private static int moyenneLigne(int[] pix, int sw, int debut) {
        long r = 0;
        long v = 0;
        long b = 0;
        for (int x = 0; x < sw; x++) {
            int p = pix[debut + x];
            r += (p >> 16) & 0xFF;
            v += (p >> 8) & 0xFF;
            b += p & 0xFF;
        }
        return (int) ((r / sw) << 16 | (v / sw) << 8 | (b / sw));
    }

    /** La meme couleur, assombrie. */
    private static int teinte(int rgb, float facteur) {
        int r = Math.round(((rgb >> 16) & 0xFF) * facteur);
        int v = Math.round(((rgb >> 8) & 0xFF) * facteur);
        int b = Math.round((rgb & 0xFF) * facteur);
        return (r << 16) | (v << 8) | b;
    }

    /**
     * Interpolation de deux couleurs, avec un BRUIT d'un demi-niveau.
     *
     * <p>Un degrade de ciel sur huit bits par voie se voit en bandes : trente niveaux etales
     * sur trois cents pixels donnent dix pixels par marche, et l'oeil les attrape. Un demi
     * niveau de bruit casse la marche sans se voir.
     *
     * <p>Le bruit est TIRE DE LA POSITION, pas d'un generateur : deux fabrications de la meme
     * image doivent donner le meme resultat, sans quoi deux captures du meme instant
     * differeraient et l'outil de comparaison ne vaudrait plus rien.
     */
    private static int melange(int a, int b, float t, int x, int y) {
        float bruit = ((((x * 73856093) ^ (y * 19349663)) >>> 8 & 0xFF) / 255f - 0.5f);
        int r = niveau(((a >> 16) & 0xFF), ((b >> 16) & 0xFF), t, bruit);
        int v = niveau(((a >> 8) & 0xFF), ((b >> 8) & 0xFF), t, bruit);
        int c = niveau((a & 0xFF), (b & 0xFF), t, bruit);
        return (r << 16) | (v << 8) | c;
    }

    private static int niveau(int a, int b, float t, float bruit) {
        return Math.max(0, Math.min(255, Math.round(a + (b - a) * t + bruit)));
    }
}
