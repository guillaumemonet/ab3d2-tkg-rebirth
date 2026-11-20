package ab3d2.rebirth;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;

/**
 * LA VUE DE L'ARME, qui se met a jour QUOI QU'IL ARRIVE.
 *
 * <p>L'arme tenue a sa propre scene, rendue apres celle du jeu avec le tampon de profondeur
 * remis a zero : c'est ce qui l'empeche de traverser les murs quand on s'y colle. Mais une
 * scene a part n'a personne pour la mettre a jour, et jME refuse de dessiner une scene laissee
 * sale.
 *
 * <p>Elle etait donc appelee depuis un {@code finally} autour de la boucle d'image -- un
 * rattrapage pose apres coup, parce que les sorties anticipees vers le menu et les ecrans de
 * texte l'avaient oubliee et que le jeu plantait des la premiere image passee dans le menu.
 *
 * <p>Un etat A PART n'a pas ce probleme : il est toujours actif, jME l'appelle a chaque image,
 * et aucune branche d'ailleurs ne peut le sauter. Le {@code finally} disparait avec le piege
 * qu'il colmatait.
 */
public final class WeaponViewState extends BaseAppState {

    private final Main jeu;

    public WeaponViewState(Main jeu) {
        this.jeu = jeu;
    }

    @Override
    protected void initialize(Application app) {
    }

    @Override
    protected void cleanup(Application app) {
    }

    @Override
    protected void onEnable() {
    }

    @Override
    protected void onDisable() {
    }

    @Override
    public void update(float tpf) {
        jeu.majVueArme(tpf);
    }
}
