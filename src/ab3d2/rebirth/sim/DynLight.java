package ab3d2.rebirth.sim;

/**
 * Point d'accroche des LUMIERES DYNAMIQUES ({@code anim_BrightenPoints} et
 * {@code Anim_BrightenPointsAngle}, newanims.s:101 et 287).
 *
 * <p>La simulation appelle ces methodes la ou le jeu appelle les routines : un projectile
 * lumineux eclaire a chaque frame de vol et pendant son explosion, un monstre a torche eclaire
 * a chaque passage de son IA. Le rendu (qui detient {@code CurrentPointBrights}) fournit
 * l'implementation ; {@code null} = niveau sans lumieres dynamiques.
 *
 * @see ab3d2.rebirth.DynLights
 */
public interface DynLight {

    /**
     * anim_BrightenPoints : eclaire les points des zones potentiellement visibles depuis
     * {@code zone}, en fonction de leur distance de Manhattan a ({@code x},{@code z}) et de
     * l'ecart de hauteur a {@code brightY}.
     *
     * @param bright valeur d'origine, DEJA negative (le jeu fait {@code neg.w d0})
     * @param brightY Anim_BrightY_l : hauteur de la source, en unites longues
     */
    void brighten(int bright, int x, int z, int zone, int brightY);

    /** Anim_BrightenPointsAngle : idem, mais pondere par un CONE oriente selon {@code angle}. */
    void brightenAngle(int bright, int x, int z, int zone, int angle, int brightY);
}
