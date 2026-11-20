package ab3d2.rebirth;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.math.FastMath;

import java.util.HashMap;
import java.util.Map;

/**
 * LES COMMANDES : ce que le joueur demande, et rien d'autre.
 *
 * <p>C'etait disperse dans {@code Main} en quatre endroits qui ne se parlaient pas : les
 * liaisons de touches, deux ecouteurs, l'invariant du curseur tenu a chaque image, et surtout
 * la VISEE -- l'angle accumule a la souris, la pente du regard, le front montant de la touche
 * accroupi -- lue et ecrite au beau milieu du pas de simulation. Un etat de jME en fait une
 * seule chose, que jME appelle lui-meme.
 *
 * <p>Il est TOUJOURS actif, menu compris : c'est lui qui tient la saisie du curseur, et c'est
 * justement dans le menu qu'il faut la relacher.
 *
 * <p>La simulation, elle, ne lit plus rien d'ici : elle recoit un {@code World.Input} rempli
 * d'un bloc par {@link #remplir}. Tout ce qui tient de la commande -- les touches de visee au
 * clavier, le demi-tour, le pas chasse force, la course permanente, les modes de validation --
 * se decide donc ici, et le pas de simulation ne connait plus que le resultat.
 */
public final class InputState extends BaseAppState {

    /**
     * COMBIEN D'IMAGES LA SOURIS RESTE MUETTE apres une prise du curseur.
     *
     * <p>Quand la fenetre saisit le curseur, le systeme signale d'un coup tout l'ecart entre
     * la ou il etait et le centre. Ce n'est pas un mouvement du joueur : c'est un artefact de
     * la saisie, et il faisait partir la vue de travers au lancement, au point que deux
     * captures du meme instant ne montraient pas la meme chose.
     *
     * <p>DEUX IMAGES, ET RIEN D'AUTRE. jME capture tres bien sans aide : tout ce qu'on ajoute
     * par-dessus se paie en souris qui ne repond pas. Deux tentatives, toutes deux retirees :
     *
     * <ul>
     *   <li>un SEUIL sur l'ecart, pour jeter les sauts de saisie. Un coup d'oeil rapide
     *       depasse n'importe quel seuil : la visee etait bridee, impossible de se retourner
     *       d'un geste.</li>
     *   <li>le meme seuil REARME a chaque image de plus d'un quart de seconde, pour les
     *       retours d'alt-tab. Mais un chargement de niveau, une pause du ramasse-miettes ou
     *       les premieres images de la fenetre durent aussi longtemps : la souris se faisait
     *       museler en boucle, et c'est ce qu'on sentait manette en main.</li>
     * </ul>
     *
     * <p>Le decompte se fait en IMAGES, pas en pas de simulation : quand plusieurs pas
     * tombent dans la meme image il s'epuisait d'un coup, et quand elle ne tournait pas il ne
     * s'epuisait jamais.
     */
    private static final int MOUSE_SETTLE = 2;

    /** Vitesse de rotation au clavier, en unites d'angle par frame de simulation. */
    private static final float TURN_SPEED = 48f;
    /** Vitesse de visee verticale au clavier, en radians par frame. */
    private static final float LOOK_SPEED = 0.04f;

    private final Main jeu;

    /** Etat tenu de chaque commande : vrai tant que la touche est enfoncee. */
    private final Map<String, Boolean> keys = new HashMap<>();
    /** L'ecouteur de la PARTIE : les ecrans le retirent le temps de s'afficher. */
    private ActionListener ecouteurJeu;
    /** Les commandes qu'il ecoute, pour pouvoir le reposer a l'identique. */
    private String[] nomsTouches;

    /** Accumulateur souris horizontal, en unites d'angle (8192 = tour complet). */
    private float lookX;
    /** Pente du regard, en radians. La visee se transmet par elle, pas par des lignes. */
    private float pitch = Float.parseFloat(System.getProperty("rebirth.pitch", "0"));
    /** Images restantes avant que la souris ne compte. */
    private int mouseSettle = MOUSE_SETTLE;
    /**
     * Sensibilite souris : unites d'angle par unite analogique jME. Vient de CONTROL OPTIONS ;
     * -Psens la force pour les tests.
     */
    private float sensitivity = Float.parseFloat(System.getProperty("rebirth.sens", "-1"));

    /** Front montant de LOOK BEHIND. */
    private boolean lookBehindTap;
    /** Front montant de la touche accroupi (le jeu efface la touche apres l'avoir lue). */
    private boolean prevDuck;

    /** Mode demo (validation auto) : avance tout droit, action maintenue. */
    private final boolean demo = System.getProperty("rebirth.demo") != null;
    /** Validation visuelle : garde la gachette enfoncee (-Pfire). */
    private final boolean autoFire = System.getProperty("rebirth.fire") != null;
    /** Test : rester a portee d'un interrupteur SANS l'actionner. */
    private final boolean noAction = System.getProperty("rebirth.noAction") != null;
    /** -PcursorLog : imprime le mode GLFW du curseur a chaque image. */
    private final boolean cursorLog = System.getProperty("rebirth.cursorLog") != null;

    public InputState(Main jeu) {
        this.jeu = jeu;
    }

    // ------------------------------------------------------------ liaisons

    @Override
    protected void initialize(Application app) {
        jeu.getFlyByCamera().setEnabled(false);
        com.jme3.input.InputManager inputManager = app.getInputManager();
        inputManager.setCursorVisible(false);
        mouseSettle = MOUSE_SETTLE;                    // la saisie va sauter : on l'ignore

        ab3d2.rebirth.menu.Options options = jeu.options;

        // Les liaisons viennent de CONTROL OPTIONS ; les fleches restent cablees en plus, car
        // elles servent aussi a naviguer dans le menu (le jeu s'en sert de la meme facon :
        // mnu_waitmenu lit forward_key et backward_key).
        mapKey("forward", options.key("forward"), KeyInput.KEY_UP);
        mapKey("backward", options.key("backward"), KeyInput.KEY_DOWN);
        mapKey("left", options.key("left"));
        mapKey("right", options.key("right"));
        mapKey("run", options.key("run"), KeyInput.KEY_RSHIFT);
        mapKey("jump", options.key("jump"));
        mapKey("duck", options.key("duck"));
        mapKey("action", options.key("action"));
        mapKey("nextWeapon", options.key("nextWeapon"));
        mapKey("menuOk", KeyInput.KEY_RETURN, KeyInput.KEY_SPACE);
        mapKey("menuLeft", KeyInput.KEY_LEFT);
        mapKey("menuRight", KeyInput.KEY_RIGHT);
        mapKey("quit", KeyInput.KEY_ESCAPE);
        // Carte : les memes touches que le jeu (TAB, F1/F2 pour le zoom, pave numerique pour
        // la faire defiler, 5 pour recentrer).
        mapKey("map", KeyInput.KEY_TAB);
        mapKey("mapZoomIn", KeyInput.KEY_F1);
        mapKey("mapZoomOut", KeyInput.KEY_F2);
        mapKey("mapUp", KeyInput.KEY_NUMPAD8);
        mapKey("mapDown", KeyInput.KEY_NUMPAD2);
        mapKey("mapLeft", KeyInput.KEY_NUMPAD4);
        mapKey("mapRight", KeyInput.KEY_NUMPAD6);
        mapKey("mapCentre", KeyInput.KEY_NUMPAD5);
        mapKey("mapFade", KeyInput.KEY_NUMPADENTER);
        inputManager.addMapping("fire", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        if (options.key("fire") > 0) {
            inputManager.addMapping("fire", new KeyTrigger(options.key("fire")));
        }
        keys.put("fire", false);
        // Commandes de visee au clavier : le remake joue a la souris, mais CONTROL OPTIONS les
        // propose, donc elles agissent vraiment.
        mapKey("turnLeft", options.key("turnLeft"));
        mapKey("turnRight", options.key("turnRight"));
        mapKey("strafeMode", options.key("strafeMode"));
        mapKey("lookUp", options.key("lookUp"));
        mapKey("lookDown", options.key("lookDown"));
        mapKey("centreView", options.key("centreView"));
        mapKey("lookBehind", options.key("lookBehind"));
        for (int i = 0; i <= 9; i++) {                 // touches 1..9,0 : selection d'arme
            mapKey("gun" + i, KeyInput.KEY_1 + i);
        }

        // L'ECOUTEUR DE LA PARTIE, ET RIEN D'AUTRE.
        //
        // Il commencait par deux branches -- sommes-nous dans un ecran de texte ? dans le
        // menu ? -- avant d'en venir au jeu. Chaque ecran a maintenant SON ecouteur, pose
        // quand il s'ouvre et retire quand il se ferme, et celui-ci est retire pendant ce
        // temps. Aucune touche ne peut donc fuir vers la simulation : ce n'est plus une
        // precaution a prendre, c'est une impossibilite.
        ecouteurJeu = (name, pressed, tpf) -> {
            if ("quit".equals(name) && pressed) {      // ECHAP : on quitte le NIVEAU vers le menu
                if (jeu.menu != null) {
                    jeu.etatMenu.setEnabled(true);
                } else {
                    jeu.stop();
                }
                return;
            }
            keys.put(name, pressed);
            if (pressed && name.startsWith("gun")) {
                jeu.selectGun(name.charAt(3) - '0');   // gun0 = touche 1 ... gun9 = touche 0
            }
            if (pressed && "nextWeapon".equals(name)) {
                jeu.nextWeapon();
            }
            if (pressed && "lookBehind".equals(name)) {
                lookBehindTap = true;
            }
            if (pressed && jeu.automap != null) {
                switch (name) {
                    case "map" -> jeu.automap.toggle();
                    case "mapZoomIn" -> jeu.automap.zoom(-1);
                    case "mapZoomOut" -> jeu.automap.zoom(1);
                    case "mapCentre" -> jeu.automap.centre();
                    case "mapFade" -> jeu.automap.toggleTransparent();
                    default -> { }
                }
            }
        };
        nomsTouches = new String[] { "forward", "backward", "left", "right", "run", "jump",
                "duck", "action", "quit", "fire", "nextWeapon", "gun0", "gun1", "gun2", "gun3",
                "gun4", "gun5", "gun6", "gun7", "gun8", "gun9",
                "map", "mapZoomIn", "mapZoomOut", "mapUp", "mapDown", "mapLeft", "mapRight",
                "mapCentre", "mapFade", "menuOk", "menuLeft", "menuRight",
                "turnLeft", "turnRight", "strafeMode", "lookUp", "lookDown", "centreView",
                "lookBehind" };
        inputManager.addListener(ecouteurJeu, nomsTouches);

        inputManager.addMapping("lookLeft", new MouseAxisTrigger(MouseInput.AXIS_X, true));
        inputManager.addMapping("lookRight", new MouseAxisTrigger(MouseInput.AXIS_X, false));
        inputManager.addMapping("lookUp", new MouseAxisTrigger(MouseInput.AXIS_Y, false));
        inputManager.addMapping("lookDown", new MouseAxisTrigger(MouseInput.AXIS_Y, true));
        AnalogListener onMouse = (name, value, tpf) -> {
            if (mouseSettle > 0) {
                return;                                // la prise du curseur, pas un geste
            }
            switch (name) {
                case "lookLeft" -> lookX -= value * sensitivity;
                case "lookRight" -> lookX += value * sensitivity;
                case "lookUp" -> pitch = FastMath.clamp(
                        pitch + value * 3f * invertMouse(), -1.4f, 1.4f);
                case "lookDown" -> pitch = FastMath.clamp(
                        pitch - value * 3f * invertMouse(), -1.4f, 1.4f);
                default -> { }
            }
        };
        inputManager.addListener(onMouse, "lookLeft", "lookRight", "lookUp", "lookDown");

        // CONTROL OPTIONS : pendant un reliage il faut la touche BRUTE, pas une commande.
        inputManager.addRawInputListener(new com.jme3.input.RawInputListener() {
            @Override
            public void onKeyEvent(com.jme3.input.event.KeyInputEvent e) {
                if (e.isPressed() && jeu.menu != null && jeu.menu.rebinding()) {
                    jeu.menu.rawKey(e.getKeyCode());
                    e.setConsumed();
                }
            }

            @Override public void beginInput() { }
            @Override public void endInput() { }
            @Override public void onJoyAxisEvent(com.jme3.input.event.JoyAxisEvent e) { }
            @Override public void onJoyButtonEvent(com.jme3.input.event.JoyButtonEvent e) { }
            @Override public void onMouseMotionEvent(com.jme3.input.event.MouseMotionEvent e) { }
            @Override public void onMouseButtonEvent(com.jme3.input.event.MouseButtonEvent e) { }
            @Override public void onTouchEvent(com.jme3.input.event.TouchEvent e) { }
        });
    }

    private void mapKey(String name, int... codes) {
        java.util.List<KeyTrigger> t = new java.util.ArrayList<>(codes.length);
        for (int code : codes) {
            if (code > 0) {                            // 0 = action non liee
                t.add(new KeyTrigger(code));
            }
        }
        if (!t.isEmpty()) {
            getApplication().getInputManager().addMapping(name, t.toArray(new KeyTrigger[0]));
        }
        keys.put(name, false);
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

    // --------------------------------------------------------------- image

    @Override
    public void update(float tpf) {
        if (mouseSettle > 0) {
            mouseSettle--;                             // en IMAGES, pas en pas de simulation
            lookX = 0;                                 // et ce qui serait deja tombe dedans
        }
        tenirLeCurseur();
        if (cursorLog && getApplication().getContext()
                instanceof com.jme3.system.lwjgl.LwjglWindow f && f.getWindowHandle() != 0L) {
            System.out.println("[curseur] mode GLFW = " + org.lwjgl.glfw.GLFW.glfwGetInputMode(
                    f.getWindowHandle(), org.lwjgl.glfw.GLFW.GLFW_CURSOR)
                    + "  (212995 = pris, 212993 = libre)");
        }
    }

    /**
     * LE CURSEUR RESTE PRIS, ET ON LE VERIFIE A CHAQUE IMAGE.
     *
     * <p>Le demander une fois ne suffit pas, et c'est la cause de la souris qui se
     * &laquo; balade &raquo; : {@code GlfwMouseInput.setCursorVisible} commence par
     * {@code if (!context.isRenderable()) return;}. L'appel pose a l'initialisation tombe
     * donc dans le vide quand la fenetre n'est pas encore affichable -- sans erreur, sans
     * trace. Le curseur reste alors en mode NORMAL : il sort de la fenetre, et la vue ne
     * suit plus au bord de l'ecran.
     *
     * <p>On ne le demande donc plus : on le VERIFIE. Une interrogation GLFW par image, et on
     * repose le mode s'il n'est pas celui qu'on veut. L'etat du curseur cesse d'etre le
     * resultat d'un appel passe pour devenir un invariant tenu.
     */
    private void tenirLeCurseur() {
        if (!(getApplication().getContext()
                instanceof com.jme3.system.lwjgl.LwjglWindow fenetre)) {
            return;                                    // pas de contexte GLFW sous la main
        }
        long win = fenetre.getWindowHandle();
        if (win == 0L) {
            return;                                    // la fenetre n'existe pas encore
        }
        // Le menu et les ecrans de texte rendent le curseur ; la partie le prend.
        int voulu = jeu.inMenu()
                ? org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL
                : org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED;
        if (org.lwjgl.glfw.GLFW.glfwGetInputMode(win,
                org.lwjgl.glfw.GLFW.GLFW_CURSOR) != voulu) {
            org.lwjgl.glfw.GLFW.glfwSetInputMode(win,
                    org.lwjgl.glfw.GLFW.GLFW_CURSOR, voulu);
            if (voulu == org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED) {
                mouseSettle = MOUSE_SETTLE;            // la prise va sauter : on l'ignore
            }
        }
    }

    // -------------------------------------------------------- ce qu'on lit

    /** Vrai tant que la commande est tenue. */
    boolean key(String name) {
        return Boolean.TRUE.equals(keys.get(name));
    }

    /** La pente du regard, pour orienter la camera. */
    float visee() {
        return pitch;
    }

    /** Mode demo : la validation automatique avance toute seule. */
    boolean enDemo() {
        return demo;
    }

    /**
     * CE QUE LE JOUEUR DEMANDE pendant cette image de simulation.
     *
     * <p>La simulation le prend d'un bloc : c'est la seule chose que le remake lui donne, et
     * c'est par la que passent ses divergences a lui -- la souris, la visee par pente.
     */
    void remplir(ab3d2.full.sim.World.Input vouloir) {
        // plr_MouseControl : la souris pilote l'angle (unites entieres, pair via AMOD_A)
        // TURN LEFT / TURN RIGHT : la meme entree que la souris, au clavier.
        if (key("turnLeft")) {
            lookX -= TURN_SPEED;
        }
        if (key("turnRight")) {
            lookX += TURN_SPEED;
        }
        int delta = (int) lookX;
        if (delta != 0) {
            lookX -= delta;
            vouloir.turn += delta & ~1;                // AMOD_A : les angles sont PAIRS
        }
        if (lookBehindTap) {                           // LOOK BEHIND : demi-tour
            lookBehindTap = false;
            vouloir.turn += 4096;
        }
        if (key("lookUp")) {
            pitch = FastMath.clamp(pitch + LOOK_SPEED, -1.4f, 1.4f);
        }
        if (key("lookDown")) {
            pitch = FastMath.clamp(pitch - LOOK_SPEED, -1.4f, 1.4f);
        }
        if (key("centreView")) {
            pitch = 0f;
        }
        vouloir.forward = key("forward") || demo;
        vouloir.backward = key("backward");
        // FORCE S/S : la touche de pas chasse transforme la rotation clavier en deplacement
        // lateral, comme le "force sidestep" du jeu.
        boolean strafe = key("strafeMode");
        vouloir.stepLeft = key("left") || (strafe && key("turnLeft"));
        vouloir.stepRight = key("right") || (strafe && key("turnRight"));
        vouloir.run = key("run") || jeu.options.on(ab3d2.rebirth.menu.Options.ALWAYS_RUN);
        vouloir.jump = key("jump");
        boolean duck = key("duck");
        vouloir.duck = duck && !prevDuck;              // clr.b (a5,d7.w) : un seul front
        prevDuck = duck;

        // -PnoAction : rester a portee d'un interrupteur SANS l'actionner.
        vouloir.action = (key("action") || demo) && !noAction;
        vouloir.fire = key("fire") || autoFire;
        // La visee se transmet par la PENTE du rayon, pas par les lignes d'ecran du jeu :
        // c'est elle qui fait suivre le rayon aux tirs, quelle que soit l'ouverture de la vue.
        vouloir.aim = Math.round(FastMath.tan(pitch) * 100f);
    }

    // ------------------------------------------------------- ce qu'on pose

    /** CONTROL OPTIONS a bouge : la sensibilite suit, sauf si -Psens l'a forcee. */
    void appliquerReglages() {
        if (sensitivity < 0f) {
            sensitivity = jeu.options.sensitivity();
        }
    }

    /** INVERT MOUSE : -1 inverse l'axe vertical de la visee. */
    private float invertMouse() {
        return jeu.options.invertMouse ? -1f : 1f;
    }

    /** Reprend le curseur et laisse passer la secousse de saisie. */
    void reprendreCurseur() {
        getApplication().getInputManager().setCursorVisible(false);
        mouseSettle = MOUSE_SETTLE;
    }

    /** Oublie les touches tenues : on change d'ecran, rien ne doit rester enfonce. */
    void relacherTouches() {
        keys.replaceAll((k, v) -> false);
    }

    /**
     * UN ECRAN PREND LA MAIN SUR LES TOUCHES.
     *
     * <p>L'ecouteur de la partie est RETIRE, celui de l'ecran est pose a sa place. Tant qu'il
     * est la, la simulation ne peut rien recevoir -- ce n'est plus une precaution a prendre
     * dans chaque branche, c'est une impossibilite.
     */
    void passerLaMain(ActionListener ecran) {
        if (nomsTouches == null) {
            return;                                    // les liaisons ne sont pas encore posees
        }
        getApplication().getInputManager().removeListener(ecouteurJeu);
        getApplication().getInputManager().addListener(ecran, nomsTouches);
        relacherTouches();
    }

    /** L'ecran se ferme : la partie reprend ses touches. */
    void reprendreLaMain(ActionListener ecran) {
        if (nomsTouches == null) {
            return;
        }
        getApplication().getInputManager().removeListener(ecran);
        getApplication().getInputManager().addListener(ecouteurJeu, nomsTouches);
        relacherTouches();
    }
}
