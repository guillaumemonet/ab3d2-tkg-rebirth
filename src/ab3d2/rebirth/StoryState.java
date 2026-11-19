package ab3d2.rebirth;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;

/**
 * L'ECRAN DE TEXTE, comme un etat de jME.
 *
 * <p>Le texte narratif d'un niveau (TWEENTEXT) et celui de la fin partagent tout : la partie
 * gele derriere, le HUD s'efface, n'importe quelle validation passe a la suite. Seule change la
 * sortie — l'introduction rend la main au jeu, la fin rend la main au menu.
 *
 * <p>Il s'ouvre par {@link #montrer(boolean)} plutot que par {@code setEnabled(true)} : un
 * niveau peut n'avoir AUCUN texte, et il faut alors ne pas s'allumer du tout.
 */
public final class StoryState extends BaseAppState {

    private final Main jeu;
    /** Le texte affiche est-il celui de la FIN ? Il rend alors la main au menu. */
    private boolean fin;

    public StoryState(Main jeu) {
        this.jeu = jeu;
        setEnabled(false);
    }

    @Override
    protected void initialize(Application app) {
    }

    @Override
    protected void cleanup(Application app) {
    }

    /**
     * Affiche l'ecran de texte, s'il y en a un.
     *
     * @param fin le texte de FIN plutot que l'introduction du niveau
     * @return vrai si un texte s'est ouvert
     */
    public boolean montrer(boolean fin) {
        if (jeu.story == null) {
            return false;
        }
        boolean montre = fin ? jeu.story.showEnd()
                : jeu.story.showIntro(Main.levelIndex(jeu.levelLetterCur));
        if (!montre) {
            return false;                              // ce niveau n'a pas de narration
        }
        this.fin = fin;
        setEnabled(true);
        return true;
    }

    @Override
    protected void onEnable() {
        jeu.relacherTouches();
        jeu.montrerInterface(false);
        jeu.reprendreCurseur();
    }

    @Override
    protected void onDisable() {
        if (fin) {
            fin = false;
            MenuState menu = getStateManager().getState(MenuState.class);
            if (menu != null) {
                menu.setEnabled(true);
                return;                                // le menu se charge de l'interface
            }
        }
        jeu.montrerInterface(true);
    }

    @Override
    public void update(float tpf) {
        // introPresentLoop : n'importe quelle validation passe l'ecran.
        if (!jeu.story.frame(jeu.key("menuOk") || jeu.key("action") || jeu.key("fire"))) {
            setEnabled(false);
        }
    }

    /** Un ecran de texte ne survit pas a un changement de taille de fenetre. */
    public void fermerSansSuite() {
        fin = false;
        setEnabled(false);
    }
}
