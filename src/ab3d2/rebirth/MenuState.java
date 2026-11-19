package ab3d2.rebirth;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;

/**
 * LE MENU, comme un etat de jME et non comme un drapeau.
 *
 * <p>Il tenait dans un {@code boolean inMenu} et une poignee de {@code if} disperses : un pour
 * router les touches, un pour cacher le HUD, un pour figer la simulation, un autre pour masquer
 * l'arme. Chaque ajout risquait d'en oublier un.
 *
 * <p>Un {@link BaseAppState} fait tenir tout cela en DEUX methodes. {@code onEnable} ouvre --
 * la simulation s'arrete, le HUD s'efface, la souris redevient visible --, {@code onDisable}
 * rend la main. Et {@code isEnabled()} repond a la question que les autres se posaient, au lieu
 * d'un drapeau que chacun devait penser a mettre a jour.
 *
 * <p>C'est aussi jME qui l'appelle : plus besoin d'une branche dans {@code updateFrame}, son
 * {@code update} ne tourne que lorsqu'il est actif.
 */
public final class MenuState extends BaseAppState {

    private final Main jeu;
    /** Images ecoulees depuis l'ouverture : le fondu d'entree s'y accroche. */
    private int frames;

    public MenuState(Main jeu) {
        this.jeu = jeu;
        setEnabled(false);                             // on l'attache eteint, on l'allume au besoin
    }

    @Override
    protected void initialize(Application app) {
    }

    @Override
    protected void cleanup(Application app) {
    }

    /**
     * Ouvre le menu. C'est ce que fait ECHAP en cours de partie ({@code Game_Begin} rend la
     * main au menu), et c'est aussi par la qu'on demarre quand aucun niveau n'est impose.
     */
    @Override
    protected void onEnable() {
        if (jeu.menu == null) {
            setEnabled(false);
            return;
        }
        frames = 0;
        jeu.relacherTouches();
        jeu.menu.setVisible(true);
        if (jeu.objects != null) {
            jeu.menu.setGameState(Main.levelIndex(jeu.levelLetterCur), jeu.objects.inventory);
        }
        jeu.menu.openMain();
        jeu.montrerInterface(false);
        if (jeu.automap != null && jeu.automap.visible()) {
            jeu.automap.toggle();
        }
        getApplication().getInputManager().setCursorVisible(true);
    }

    /** Referme le menu et rend la main a la partie. */
    @Override
    protected void onDisable() {
        jeu.applyLiveOptions();
        if (jeu.menu != null) {
            jeu.menu.setVisible(false);
        }
        jeu.montrerInterface(true);
        jeu.reprendreCurseur();
    }

    @Override
    public void update(float tpf) {
        if (jeu.menu == null) {
            return;
        }
        frames++;
        jeu.menu.setFade(Math.min(1f, frames / 16f));  // mnu_fadein : 16 pas de 16
        jeu.menu.frame();
        // -Pautoplay=N : valide PLAY GAME a la frame N, pour exercer la transition
        // menu -> texte d'intro -> jeu sans avoir a appuyer sur une touche.
        if (jeu.autoPlay > 0 && frames == jeu.autoPlay) {
            jeu.menu.select();
        }
        // Dans les pages de reglages, ce qui peut s'appliquer tout de suite s'applique tout de
        // suite : on entend le volume et on voit la luminosite en reglant.
        switch (jeu.menu.page()) {
            case VIDEO, SOUND, CONTROLS2 -> jeu.applyLiveOptions();
            // De retour au menu principal, on prend en compte ce qui demande de recreer le
            // contexte (taille de fenetre, plein ecran, synchro verticale).
            case MAIN -> jeu.applyDisplayOptions();
            default -> { }
        }
        switch (jeu.menu.outcome()) {
            case QUIT -> getApplication().stop();
            case PLAY -> jouer();
            default -> { }
        }
    }

    /** PLAYTHEGAME : on charge ce qu'il faut, on sort du menu, et le texte narratif prend la suite. */
    private void jouer() {
        String lettre = String.valueOf((char) ('A' + jeu.menu.level()));
        ab3d2.full.sim.Inventory emporte = jeu.menu.pendingInventory();
        jeu.menu.clearOutcome();
        if (!lettre.equals(jeu.levelLetterCur) || emporte != null) {
            jeu.loadLevel(lettre, emporte);
        }
        setEnabled(false);
        StoryState texte = getStateManager().getState(StoryState.class);
        if (texte != null) {
            texte.montrer(false);
        }
    }
}
