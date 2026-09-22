package ab3d2.rebirth;

import com.jme3.math.ColorRGBA;

import ab3d2.rebirth.sim.DynLight;

/**
 * Les lumieres dynamiques du jeu, rendues par le MOTEUR : un projectile lumineux, une explosion
 * ou la torche d'un garde deviennent de vraies {@link com.jme3.light.PointLight}.
 *
 * <p>Remplace {@link DynLights}, qui reproduisait {@code anim_BrightenPoints} en ECRIVANT dans le
 * light map a chaque frame — ce qui obligeait a reconstruire tous les tampons de sommets eclaires
 * derriere. Ici la simulation appelle les memes points d'accroche, aux memes endroits, mais la
 * lumiere est posee dans la scene et le GPU s'en occupe.
 *
 * <p>La simulation passe une luminosite NEGATIVE (le jeu fait {@code neg.w d0}) : on la remet a
 * l'endroit. Les hauteurs sont en unites longues du jeu, d'ou le {@code / 8192}.
 */
public final class DynLights3D implements DynLight {

    /** Teinte d'un projectile ou d'une explosion : blanc legerement chaud. */
    private static final ColorRGBA SHOT = new ColorRGBA(1f, 0.88f, 0.7f, 1f);
    /** Teinte d'une torche de garde. */
    private static final ColorRGBA TORCH = new ColorRGBA(1f, 0.93f, 0.78f, 1f);

    private final Lights3D lights;

    public DynLights3D(Lights3D lights) {
        this.lights = lights;
    }

    @Override
    public void brighten(int bright, int x, int z, int zone, int brightY) {
        lights.flash(x / 64f, -brightY / 8192f, -z / 64f, -bright, SHOT);
    }

    @Override
    public void brightenAngle(int bright, int x, int z, int zone, int angle, int brightY) {
        // Le cone d'origine (ai_DoTorch) n'a pas d'equivalent direct : une source ponctuelle
        // rend mieux une lampe portee, et le joueur ne voit de toute facon que ce qu'elle eclaire.
        lights.flash(x / 64f, -brightY / 8192f, -z / 64f, -bright, TORCH);
    }
}
