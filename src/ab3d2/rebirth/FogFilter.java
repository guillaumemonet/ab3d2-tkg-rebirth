package ab3d2.rebirth;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.post.Filter;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;

/**
 * BROUILLARD DE DISTANCE — le lointain s'enfonce au lieu de s'arreter net.
 *
 * <p>Le jeu d'origine assombrissait DEJA avec la distance : son rasterizer ajoute
 * {@code profondeur >> 7} a la luminosite de chaque bande de mur (cf. {@code GouraudWallPaint}
 * cote portage). Le remake, lui, eclairait pareil de pres et de loin, et un couloir s'arretait
 * sur un mur aussi net que celui qu'on touche.
 *
 * <p>Pourquoi pas celui de jME : son shader ({@code Common/MatDefs/Post/Fog.frag}) ne sait pas
 * s'arreter au plan lointain. Le ciel y est a la profondeur maximale, donc noye a coup sur —
 * mesure faite, il disparaissait entierement. Celui-ci passe la main des que la profondeur
 * touche le fond du tampon, et travaille en DISTANCE REELLE plutot que sur la valeur du tampon,
 * qui est serree pres de la camera.
 */
public final class FogFilter extends Filter {

    private ColorRGBA couleur = new ColorRGBA(0.045f, 0.047f, 0.060f, 1f);
    private float debut = 26f;
    private float fin = 95f;
    private float near = 0.1f;
    private float far = 1000f;

    public FogFilter() {
        super("Fog");
    }

    /** La couleur vers laquelle le lointain se fond. */
    public void setColour(ColorRGBA c) {
        couleur = c;
        if (material != null) {
            material.setColor("FogColor", couleur);
        }
    }

    /**
     * Ou le brouillard commence et ou il est complet, en unites du monde (1 unite = 64 unites
     * de jeu). Une piece fait une trentaine d'unites de large.
     */
    public void setRange(float debut, float fin) {
        this.debut = debut;
        this.fin = Math.max(debut + 1f, fin);
        if (material != null) {
            material.setFloat("FogStart", this.debut);
            material.setFloat("FogEnd", this.fin);
        }
    }

    /** Les plans de la camera : sans eux la profondeur du tampon ne veut rien dire. */
    public void setFrustum(float near, float far) {
        this.near = near;
        this.far = far;
        if (material != null) {
            material.setFloat("Near", near);
            material.setFloat("Far", far);
        }
    }

    @Override
    protected boolean isRequiresDepthTexture() {
        return true;
    }

    @Override
    protected void initFilter(AssetManager manager, RenderManager renderManager, ViewPort vp,
                              int w, int h) {
        material = new Material(manager, "Shaders/Fog.j3md");
        material.setColor("FogColor", couleur);
        material.setFloat("FogStart", debut);
        material.setFloat("FogEnd", fin);
        material.setFloat("Near", near);
        material.setFloat("Far", far);
    }

    @Override
    protected Material getMaterial() {
        return material;
    }
}
