package ab3d2.rebirth;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;

/**
 * LA PARTIE : la simulation tourne, le monde se met a jour, la camera suit.
 *
 * <p>Elle se reconnaissait a une NEGATION. La boucle d'image commencait par demander
 * « sommes-nous dans le menu ? dans un ecran de texte ? » et ne faisait le travail du jeu que
 * si la reponse etait non deux fois. Dire ce qu'une chose n'est pas est la facon la plus sure
 * d'en oublier une : il a suffi d'ajouter les ecrans de texte pour qu'il faille retrouver
 * toutes ces questions, une par une.
 *
 * <p>Ici la partie est un etat comme les autres. Le menu et le texte l'ETEIGNENT en s'ouvrant,
 * jME ne l'appelle plus, et personne n'a de question a poser.
 */
public final class GameState extends BaseAppState {

    private final Main jeu;

    public GameState(Main jeu) {
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
        jeu.imageDeJeu(tpf);
    }
}
