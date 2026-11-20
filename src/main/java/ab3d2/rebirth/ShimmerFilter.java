package ab3d2.rebirth;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.post.Filter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;

/**
 * DÉMATÉRIALISATION : port de {@code c2p_Convert1xTeleFx}
 * (modules/c2p/teleport_fx/routines.s) — l'écran se brouille de plus en plus quand le joueur
 * se téléporte ou atteint la zone de sortie.
 *
 * <p>Le jeu recopie chaque groupe de 8 pixels de l'image déjà rendue depuis un endroit décalé,
 * lu dans {@code shimmerfile} : 8 frames d'amplitude croissante. La table est extraite en carte
 * de déplacement ({@code assets/fx/shimmer.png}, cf. {@code rebirth.Shimmer}) et appliquée ici
 * en post-traitement, en unités d'écran d'origine — donc identique à toute résolution.
 *
 * <p>La cadence est celle du jeu ({@code ScreenC.applyTeleportShimmer}) : la zone de sortie
 * ajoute 2 par frame de simulation, la présentation retire 1 et dessine la valeur obtenue, et à
 * 9 le niveau s'achève. Un téléport pose directement 8.
 */
public final class ShimmerFilter extends Filter {

    /** Le nombre de frames de la table (shimmerfile = 8 x 1024 octets). */
    public static final int FRAMES = 8;

    private Texture2D displace;
    private int frame;

    public ShimmerFilter() {
        super("Shimmer");
        setEnabled(false);                             // rien tant qu'aucune frame n'est demandee
    }

    /**
     * Frame courante : NEGATIF = effet eteint, 0..7 = brouillage croissant. La frame 0 existe
     * bien dans la table (deplacements de 2 pixels) et le jeu la dessine une derniere fois quand
     * le compteur retombe a zero, d'ou la distinction avec « eteint ».
     */
    public void setFrame(int f) {
        if (f < 0) {
            setEnabled(false);
            return;
        }
        frame = Math.min(FRAMES - 1, f);
        setEnabled(true);
        if (material != null) {
            material.setFloat("Frame", frame);
        }
    }

    public int frame() {
        return frame;
    }

    @Override
    protected void initFilter(AssetManager manager, RenderManager renderManager, ViewPort vp,
                              int w, int h) {
        material = new Material(manager, "Shaders/Shimmer.j3md");
        if (displace == null) {
            displace = Assets.texture("fx/shimmer.png");
            if (displace != null) {
                // Carte de deplacement : aucune interpolation, ce sont des valeurs, pas des couleurs.
                displace.setMagFilter(Texture.MagFilter.Nearest);
                displace.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
            }
        }
        if (displace != null) {
            material.setTexture("Displace", displace);
        }
        material.setFloat("Frame", frame);
    }

    @Override
    protected Material getMaterial() {
        return material;
    }

    /** Vrai si la table de deplacement a bien ete trouvee. */
    public boolean ready() {
        return Assets.exists("fx/shimmer.png");
    }
}
