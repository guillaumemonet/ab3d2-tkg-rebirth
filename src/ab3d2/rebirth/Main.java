package ab3d2.rebirth;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.system.AppSettings;

import ab3d2.full.sim.Aliens;
import ab3d2.full.sim.Anims;
import ab3d2.full.sim.LevelSim;
import ab3d2.full.sim.Los;
import ab3d2.full.sim.Nav;
import ab3d2.full.sim.PlayerSim;
import ab3d2.full.sim.Shots;
import ab3d2.full.SinCos;

import java.util.HashMap;
import java.util.Map;

/**
 * Point d'entree du remake "rebirth" (jMonkeyEngine).
 *
 * <p>La SIMULATION (deplacement, collision, portes, ascenseurs) est portee du jeu d'origine et
 * tourne a pas FIXE de 50 Hz (cadence PAL : les constantes de vitesse/gravite sont par frame) ;
 * le rendu jME tourne librement par-dessus.
 *
 * <pre>
 *   gradle -p rebirth/game run                 # niveau A
 *   gradle -p rebirth/game run -Plevel=c       # autre niveau
 *   gradle -p rebirth/game run -Pshot=90       # capture puis quitte (validation visuelle)
 *   gradle -p rebirth/game run -Pfreecam       # camera libre (debug geometrie)
 * </pre>
 */
public class Main extends SimpleApplication {

    /** Cadence de la simulation d'origine (PAL). */
    private static final float SIM_STEP = 1f / 50f;

    private final String levelLetter;
    private final int shotAt;
    /** -Pshotn : combien d'images de suite la rafale prend. */
    private final int shotCount = Math.max(1, Integer.getInteger("rebirth.shotn", 1));
    private final String camAt;
    final boolean freeCam;

    private LevelData level;
    private LevelSim levelSim;
    PlayerSim player;
    private Anims anims;
    /** -Pforcedoor : la porte que l'on fait bouger de force, ou -1. */
    private int doorSweep = -1;
    ab3d2.full.sim.ObjectRuntime objects;
    Shots shots;
    private Aliens monsters;
    private Nav nav;
    LevelBuilder builder;
    private GlfData glf;
    /** PlrT_GunSelected_b : arme en main (index dans GLFT_ShootDefs). */
    private int gun = Integer.getInteger("rebirth.gun", 0);

    private Hud hud;
    private Sfx audio;
    /** File des bruitages demandes par la simulation pendant la frame. */
    /**
     * LA SIMULATION, montee d'un seul tenant.
     *
     * <p>Elle vient de {@code full}, ou elle a ete eprouvee contre le portage litteral. Le
     * remake ne la refait pas : il la regarde tourner et la dessine. Ses divergences a lui —
     * la visee par pente, la souris, la camera libre — passent par ce qu'il MET dedans, pas
     * par une fourche de son code.
     */
    private ab3d2.full.sim.World sim;

    private ab3d2.full.sim.SfxQueue sfxQueue = new ab3d2.full.sim.SfxQueue();
    /** File des messages de narration poussee par la simulation. */
    private ab3d2.full.sim.Messages msgQueue = new ab3d2.full.sim.Messages();
    private Node levelNode;
    /** Etat de la partie : en jeu, dematerialisation de sortie, ou mort. */
    private enum State { PLAYING, EXITING, DEAD }

    private State state = State.PLAYING;
    /** TELVAL : compteur de dematerialisation quand on est dans la zone de sortie. */
    /**
     * Game_TeleportFrame_w : le compteur de DEMATERIALISATION. La zone de sortie y ajoute 2 par
     * frame (add.w #2,TELVAL) et le niveau s'acheve a 9 ; un teleport le pose a 8. La
     * PRESENTATION en retire 1 et dessine la valeur obtenue (ScreenC.java:250-258).
     */
    private int exitShimmer;
    private ShimmerFilter shimmer;
    Lights3D lights3d;
    /** -PlightLog : trace l'allumage selectif quand le joueur change de zone. */
    final boolean lightLog = System.getProperty("rebirth.lightLog") != null;
    /** -PnoPvsLights : garde TOUTES les lumieres allumees (mesure de reference). */
    final boolean noPvsLights = System.getProperty("rebirth.noPvsLights") != null;
    /** -PfpsLog : imprime le temps de frame moyen toutes les 120 frames. */
    private final boolean fpsLog = System.getProperty("rebirth.fpsLog") != null;
    private boolean sceneCounted;
    private int fpsFrames;
    private float fpsAccum;
    private StatusPanel panel;
    /** La carte (TAB) ; refaite a chaque niveau. */
    AutoMap automap;
    /** Le menu du jeu (fond defilant + feu) et les reglages qu'il pilote. */
    ab3d2.rebirth.menu.MenuUi menu;
    final ab3d2.rebirth.menu.Options options = ab3d2.rebirth.menu.Options.load();
    /** Les ecrans de texte : introduction de niveau (TWEENTEXT) et texte de fin. */
    ab3d2.rebirth.menu.StoryText story;
    /**
     * L'arme tenue a SA PROPRE vue, rendue apres la scene avec le tampon de profondeur remis a
     * zero : c'est ce qui l'empeche de traverser les murs quand on s'y colle. La camera y est
     * une copie exacte de celle du jeu — meme position, meme orientation, MEME OUVERTURE — pour
     * que l'arme se projette exactement comme avant et que la visee ne bouge pas d'un pixel.
     */
    private com.jme3.renderer.ViewPort weaponView;
    private com.jme3.renderer.Camera weaponCam;
    private Node weaponNode;
    /** Zone dont la lampe est actuellement posee sur la vue de l'arme. */
    private int weaponLitZone = Integer.MIN_VALUE;
    private com.jme3.post.filters.BloomFilter bloom;
    /** Le brouillard de distance, ou {@code null} avec -Pnofog. */
    private FogFilter fog;
    private com.jme3.post.ssao.SSAOFilter ssao;
    /**
     * LE MENU ET LES ECRANS DE TEXTE SONT DES ETATS DE jME.
     *
     * <p>C'etaient deux drapeaux, {@code inMenu} et {@code inStory}, et une poignee de
     * {@code if} disperses : un pour router les touches, un pour cacher le HUD, un pour
     * figer la simulation, un autre pour masquer l'arme. Chaque ajout risquait d'en
     * oublier un. Un {@code BaseAppState} fait tenir cela en deux methodes, et
     * {@code isEnabled()} repond a la question que tout le monde se posait.
     */
    MenuState etatMenu;
    StoryState etatTexte;
    /** L'eclairage de la partie : sources du niveau, projectiles, ombres. */
    LightingState etatLumieres;

    /** Vrai tant que le menu est affiche : la simulation est alors a l'arret. */
    private boolean inMenu() {
        return etatMenu != null && etatMenu.isEnabled();
    }

    /** Vrai tant qu'un ecran de texte est affiche. */
    private boolean inStory() {
        return etatTexte != null && etatTexte.isEnabled();
    }
    /**
     * On demarre par le MENU, sauf si la ligne de commande demande un niveau precis : tous les
     * harnais de validation passent -Plevel et doivent continuer d'aller droit au jeu.
     */
    private final boolean startInMenu = System.getProperty("rebirth.startmenu") != null
            || (System.getProperty("rebirth.level") == null
                && System.getProperty("rebirth.freecam") == null
                && System.getProperty("rebirth.demo") == null
                && Integer.getInteger("rebirth.shot", 0) <= 0);
    /** Frames restantes avant de passer au niveau suivant / de recommencer. */
    private int stateTimer;
    String levelLetterCur;
    /** Le depart force (-Pspawn) ne vaut que pour le premier niveau charge. */
    private boolean firstLoad = true;

    private com.jme3.scene.Geometry sky;
    private ScreenshotAppState screenshot;
    private int frame;
    /** Frames de SIMULATION ecoulees (50 Hz) : anime l'eau comme le VBL du jeu. */
    private int simFrames;
    private float accumulator;

    // entrees
    private final Map<String, Boolean> keys = new HashMap<>();
    private float lookX;                  // accumulateur souris horizontal (unites d'angle)

    /**
     * COMBIEN D'IMAGES LA SOURIS RESTE MUETTE apres une prise du curseur.
     *
     * <p>Quand la fenetre saisit le curseur, le systeme signale d'un coup tout l'ecart
     * entre la ou il etait et le centre. Ce n'est pas un mouvement du joueur : c'est un
     * artefact de la saisie, et il faisait partir la vue de travers au lancement, au point
     * que deux captures du meme instant ne montraient pas la meme chose.
     *
     * <p>DEUX IMAGES, ET RIEN D'AUTRE. jME capture tres bien sans aide : tout ce qu'on
     * ajoute par-dessus se paie en souris qui ne repond pas. Deux tentatives, toutes deux
     * retirees :
     *
     * <ul>
     *   <li>un SEUIL sur l'ecart, pour jeter les sauts de saisie. Un coup d'oeil rapide
     *       depasse n'importe quel seuil : la visee etait bridee, impossible de se
     *       retourner d'un geste.</li>
     *   <li>le meme seuil REARME a chaque image de plus d'un quart de seconde, pour les
     *       retours d'alt-tab. Mais un chargement de niveau, une pause du ramasse-miettes
     *       ou les premieres images de la fenetre durent aussi longtemps : la souris se
     *       faisait museler en boucle, et c'est ce qu'on sentait manette en main.</li>
     * </ul>
     *
     * <p>Le decompte se fait en IMAGES, dans {@code simpleUpdate}. Il etait dans la
     * simulation : quand plusieurs pas tombent dans la meme image il s'epuisait d'un coup,
     * et quand elle ne tournait pas il ne s'epuisait jamais.
     */
    private static final int MOUSE_SETTLE = 2;

    /** Images restantes avant que la souris ne compte. */
    private int mouseSettle = MOUSE_SETTLE;
    private float pitch = Float.parseFloat(System.getProperty("rebirth.pitch", "0"));
    /** Mode demo (validation auto) : avance tout droit, action maintenue. */
    private final boolean demo = System.getProperty("rebirth.demo") != null;
    /** Validation visuelle : garde la gachette enfoncee (-Pfire). */
    private final boolean autoFire = System.getProperty("rebirth.fire") != null;
    /** Validation automatique de PLAY GAME apres N frames de menu (validation). */
    final int autoPlay = Integer.getInteger("rebirth.autoplay", 0);

    /**
     * Sensibilite souris : unites d'angle (8192 = tour complet) par unite analogique jME.
     * Vient de CONTROL OPTIONS ; -Psens la force pour les tests.
     */
    private float sensitivity = Float.parseFloat(System.getProperty("rebirth.sens", "-1"));

    public Main(String levelLetter, int shotAt, String camAt, boolean freeCam) {
        this.levelLetter = levelLetter;
        this.shotAt = shotAt;
        this.camAt = camAt;
        this.freeCam = freeCam;
    }

    public static void main(String[] args) {
        AppDirs.setupLogging();                   // build packagé : trace vers run/rebirth.log
        String letter = System.getProperty("rebirth.level", args.length > 0 ? args[0] : "a");
        int shot = Integer.getInteger("rebirth.shot", 0);
        String at = System.getProperty("rebirth.at");
        boolean free = System.getProperty("rebirth.freecam") != null;

        Main app = new Main(letter, shot, at, free);
        // La taille de fenetre, le plein ecran et la synchro viennent de SCREEN OPTIONS.
        ab3d2.rebirth.menu.Options opts = ab3d2.rebirth.menu.Options.load();
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Alien Breed 3D II - Rebirth");
        settings.setResolution(opts.width(), opts.height());
        settings.setFullscreen(opts.fullscreen);
        // -Pnovsync : sans elle, impossible de mesurer le cout REEL d'une frame.
        settings.setVSync(opts.vsync && System.getProperty("rebirth.novsync") == null);
        if (System.getProperty("rebirth.nosound") != null) {
            settings.setAudioRenderer(null);      // -Pnosound : aucun peripherique audio
        }
        // Correction gamma DESACTIVEE : les couleurs viennent de la palette d'origine et la carte
        // d'indices transporte des ENTIERS (sr) dans le canal rouge, qu'une conversion sRGB
        // fausserait.
        settings.setGammaCorrection(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        direQuelleCarte();
        // Le dossier d'assets devient une RACINE jME : c'est ce qui permet de charger les
        // materiaux .j3m, et demain les scenes .j3o, par assetManager plutot qu'en code.
        assetManager.registerLocator(Assets.root().toString(),
                com.jme3.asset.plugins.FileLocator.class);
        glf = Assets.json("glf.json", GlfData.class);
        viewPort.setBackgroundColor(new ColorRGBA(0.04f, 0.04f, 0.06f, 1f));
        // L'ouverture vient de SCREEN OPTIONS ; applyLiveOptions la reapplique a chaque
        // changement.
        cam.setFrustumPerspective(options.fovDegrees(),
                (float) cam.getWidth() / cam.getHeight(), 0.05f, 4000f);
        if (!freeCam) {
            initPlayerInput();
            hud = new Hud(assetManager, guiNode, cam.getWidth(), cam.getHeight());
            // Panneau de statut d'ORIGINE (le bandeau de newborderpacked). S'il est la, il
            // remplace le releve en texte du HUD.
            panel = StatusPanel.create(assetManager, guiNode, cam.getWidth(), cam.getHeight());
            hud.setTextReadout(panel == null);
            audio = new Sfx(assetManager, rootNode);
            audio.setEnabled(System.getProperty("rebirth.nosound") == null);
            // ECLAIRAGE EN UNE PASSE : par defaut jME redessine la geometrie une fois PAR
            // lumiere (MultiPass). Avec une lumiere par zone (134 au niveau A) c'est le mode a
            // ne pas garder — SinglePass traite un paquet de lumieres par passe.
            renderManager.setPreferredLightMode(
                    com.jme3.material.TechniqueDef.LightMode.SinglePass);
            renderManager.setSinglePassLightBatchSize(
                    Integer.getInteger("rebirth.lightBatch", 4));

            com.jme3.post.FilterPostProcessor fpp =
                    new com.jme3.post.FilterPostProcessor(assetManager);
            // BLOOM sur les objets marques emissifs (les glares : lampes, puits de lumiere,
            // eclats de tir) — cf. LevelBuilder.glow.
            if (System.getProperty("rebirth.retro") == null) {   // builder n'existe pas encore
                bloom = new com.jme3.post.filters.BloomFilter(
                                com.jme3.post.filters.BloomFilter.GlowMode.Objects);
                bloom.setBloomIntensity(2.2f);
                bloom.setExposurePower(3.5f);
                bloom.setBlurScale(1.6f);
                fpp.addFilter(bloom);
                if (System.getProperty("rebirth.noshadow") == null) {
                    shadows = new com.jme3.shadow.PointLightShadowFilter(assetManager, SHADOW_MAP);
                    shadows.setShadowIntensity(0.6f);
                    shadows.setEdgeFilteringMode(com.jme3.shadow.EdgeFilteringMode.PCF4);
                    // LES FACES ARRIERE NE CONVIENNENT PAS A CE DECOR. C'est le remede
                    // habituel aux zebrures — une surface qui se projette sur elle-meme —, mais
                    // il suppose des volumes FERMES. Ici un mur est un simple quadrilatere a une
                    // face : vu de la lampe, il n'a pas d'arriere, et ne rend donc RIEN dans la
                    // carte d'ombres. Mesure : le filtre ne changeait plus que trois pixels sur
                    // neuf cent mille — les ombres n'existaient pas.
                    //
                    // Les zebrures viennent de la lampe POSEE SUR L'OEIL : elle rasait le sol,
                    // et deux pixels voisins d'une surface vue de biais tombent alors dans le
                    // meme echantillon de la carte. Elle pend desormais au-dessus de la tete
                    // (Lights3D.hangShadowLamp), ou elle regarde le sol d'assez haut pour que
                    // chaque pixel ait son echantillon.
                    shadows.setRenderBackFacesShadows(false);
                    shadows.setEnabled(false);
                    fpp.addFilter(shadows);
                }
                // OCCLUSION AMBIANTE (-Pssao) : assombrit les recoins et les jonctions mur/sol.
                //
                // DESACTIVEE PAR DEFAUT, et le chiffre est sans appel : 4,17 ms par image
                // sans, 9,20 ms avec (niveau a, 1280x720, sans synchro verticale) -- elle
                // COUTE PLUS QUE TOUT LE RESTE DU RENDU REUNI. En face, elle assombrit
                // l'image entiere (moyenne 34,5 -> 29,3) plus qu'elle ne creuse les recoins :
                // ce decor est fait de grandes surfaces planes, et le SSAO paie sur le detail
                // fin. Le rayon resserre ci-dessous limite la bavure, mais le cout, lui, est
                // celui de sa passe profondeur/normales et ne baisse pas avec.
                // L'occlusion ambiante est toujours MONTEE mais desactivee tant que SCREEN
                // OPTIONS ne l'allume pas : l'ajouter apres coup demanderait de refaire le FPP.
                ssao = new com.jme3.post.ssao.SSAOFilter(0.9f, 1.6f, 0.28f, 0.28f);
                fpp.addFilter(ssao);
            }
            // BROUILLARD DE DISTANCE.
            //
            // Le jeu d'origine n'en avait pas : ses couloirs s'arretent net sur un mur
            // peint, et la profondeur ne se lit qu'a la taille des choses. Un fond qui
            // s'enfonce dans le noir la rend LISIBLE -- on devine qu'il y a plus loin
            // avant de le voir --, et c'est ce qu'un decor sombre demande.
            //
            // Sa teinte n'est pas le noir pur : un brouillard noir DECOUPE les silhouettes
            // au lieu de les fondre. Un gris tres sombre, legerement froid, les mange
            // proprement.
            if (System.getProperty("rebirth.nofog") == null) {
                fog = new FogFilter();
                fog.setRange(Float.parseFloat(System.getProperty("rebirth.fogstart", "26")),
                        Float.parseFloat(System.getProperty("rebirth.fogend", "95")));
                fog.setFrustum(cam.getFrustumNear(), cam.getFrustumFar());
                fpp.addFilter(fog);
            }
            // Dematerialisation : post-traitement, inactif tant que le compteur est a zero.
            if (Assets.exists("fx/shimmer.png")) {
                shimmer = new ShimmerFilter();
                fpp.addFilter(shimmer);
            } else {
                System.out.println("[Main] fx/shimmer.png absent : pas de dematerialisation "
                        + "(gradle -p rebirth extract -Pwhat=shimmer)");
            }
            viewPort.addProcessor(fpp);
            // Les compteurs de jME occupent le coin bas-gauche, la ou va notre bandeau : seul
            // SHOW FPS (Prefs_DisplayFPS_b) les rallume, ou -Pstats pour le diagnostic.
            setDisplayStatView(System.getProperty("rebirth.stats") != null);
            applyFpsOption();
        }
        if (!freeCam) {
            menu = ab3d2.rebirth.menu.MenuUi.create(assetManager, guiNode,
                    cam.getWidth(), cam.getHeight(), glf, options);
            story = ab3d2.rebirth.menu.StoryText.create(assetManager, guiNode,
                    cam.getWidth(), cam.getHeight());
        }
        // Les deux ecrans deviennent des ETATS : jME les met a jour lui-meme quand ils
        // sont actifs, et c'est leur activite qui repond a << sommes-nous dans le menu ? >>.
        etatMenu = new MenuState(this);
        etatTexte = new StoryState(this);
        etatLumieres = new LightingState(this);
        stateManager.attach(etatMenu);
        stateManager.attach(etatTexte);
        stateManager.attach(etatLumieres);
        if (!loadLevel(levelLetter, null)) {
            stop();
            return;
        }
        if (!freeCam) {
            initWeaponView();
        }
        guiReady = true;
        applyLiveOptions();
        if (menu != null && startInMenu) {
            etatMenu.setEnabled(true);
        }
        // -Pstory : ouvre directement un ecran de texte (validation visuelle). "end" = texte
        // de fin, sinon l'introduction du niveau charge.
        String showStory = System.getProperty("rebirth.story");
        if (showStory != null && story != null) {
            etatTexte.montrer("end".equalsIgnoreCase(showStory));
        }
        if (shotAt > 0) {
            screenshot = new ScreenshotAppState(System.getProperty("user.dir") + "/", "rebirth_shot");
            screenshot.setIsNumbered(shotCount > 1);
            stateManager.attach(screenshot);
        }
    }

    /**
     * Charge (ou recharge) un niveau : geometrie, simulation et entites. {@code carry} est
     * l'inventaire a conserver d'un niveau a l'autre (null = nouvelle partie, DEFAULTGAME).
     */
    boolean loadLevel(String letter, ab3d2.full.sim.Inventory carry) {
        levelLetterCur = letter.toUpperCase();
        level = Assets.json("levels/" + levelLetterCur + ".json", LevelData.class);
        if (level != null && level.objects != null) {
            // LE RANG, pose a la lecture : c'est par lui que le decor retrouvera dans la
            // simulation la chose qu'il dessine.
            for (int i = 0; i < level.objects.size(); i++) {
                level.objects.get(i).rank = i;
            }
        }
        if (level == null) {
            System.err.println("[Main] niveau introuvable : levels/" + levelLetterCur + ".json");
            return false;
        }
        if (levelNode != null) {
            levelNode.removeFromParent();
        }
        if (sky != null) {
            sky.removeFromParent();
            sky = null;
        }
        state = State.PLAYING;
        // Les lampes du niveau precedent n'existent plus : la vue de l'arme doit les lacher.
        if (weaponNode != null) {
            weaponNode.getLocalLightList().clear();
        }
        weaponLitZone = Integer.MIN_VALUE;
        if (audio != null) {
            audio.stopAll();
        }
        exitShimmer = 0;
        stateTimer = 0;
        accumulator = 0f;
        if (hud != null) {
            hud.message(null);
            hud.clearMessages();
        }

        builder = new LevelBuilder(assetManager, glf);
        // La SCENE d'abord : si assets/Scenes/level_<x>.j3o existe, c'est elle qu'on affiche —
        // c'est ce qui permet de corriger un niveau dans le SDK jME et de le voir en jeu. Sinon
        // on rebatit depuis le JSON, comme avant.
        Node geometry = null;
        if (System.getProperty("rebirth.nolevelscene") == null) {
            try {
                Node decor = (Node) assetManager.loadModel(
                        "Scenes/level_" + levelLetterCur.toLowerCase(java.util.Locale.ROOT)
                                + ".j3o");
                geometry = builder.buildFromScene(decor, level);
            } catch (RuntimeException absent) {
                geometry = null;                       // pas de scene : on rebatit
            }
        }
        if (geometry == null) {
            geometry = builder.build(level);
        }
        levelNode = geometry;
        rootNode.attachChild(geometry);

        boolean anyBackdrop = false;
        for (LevelData.Zone z : level.zones) {
            anyBackdrop |= z.drawBackdrop;             // ZoneT_DrawBackdrop_b
        }
        if (anyBackdrop) {
            sky = Sky.create(assetManager);
            if (sky != null) {
                sky.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
                rootNode.attachChild(sky);
            }
            System.out.printf("[Level] ciel : backdrop actif (%s)%n", sky != null ? "ok" : "texture absente");
        }

        if (freeCam) {
            initFreeCam();
        } else {
            // LA SIMULATION VERIFIEE, montee d'un coup. Tout ce que le remake assemblait a
            // la main — la collision, les portes, les objets, les balles, les monstres, et
            // les files de son et de texte qui les relient — tient dans World, dans l'ORDRE
            // que la boucle d'origine leur donne. Cet ordre n'est pas un detail : un monstre
            // riposterait une image plus tard, une porte se refermerait avant d'etre franchie.
            //
            // Le remake garde son JSON pour la GEOMETRIE 3D ; la simulation lit les fichiers
            // d'origine. simParity prouve que les deux decrivent le meme niveau.
            sim = new ab3d2.full.sim.World(
                    ab3d2.full.Data.level(Character.toLowerCase(levelLetterCur.charAt(0))),
                    ab3d2.full.Data.database());
            levelSim = sim.lvl;
            player = sim.player;
            anims = sim.anims;
            // -Pforcedoor=N : la porte N fait des allers-retours toute seule, le joueur
            // immobile. C'est le seul moyen de voir un CLIGNOTEMENT de battant sans jouer :
            // tout le reste de l'image est fige, et l'on compare image par image.
            doorSweep = Integer.getInteger("rebirth.forcedoor", -1);
            objects = sim.objects;
            shots = sim.shots;
            monsters = sim.aliens;
            nav = sim.nav;
            sfxQueue = sim.sfx;
            msgQueue = sim.messages;
            if (carry != null) {
                objects.inventory.copyFrom(carry); // on emporte armes, munitions et sante
            }                                      // sinon World a deja donne DEFAULTGAME
            if (System.getProperty("rebirth.health") != null) {
                objects.inventory.consumables[0] = Integer.getInteger("rebirth.health", 200);
            }
            if (System.getProperty("rebirth.gun") != null && glf != null) {
                // Debug (-Pgun=N) : on donne l'arme demandee et de quoi tirer, comme le harnais
                // de test du portage (LevelTest remplit Plr1_AmmoCounts_vw).
                objects.inventory.items[ab3d2.full.sim.Inventory.WEAPON_START + gun] = 0xFF;
                GlfData.Gun g = glf.gun(gun);
                if (g != null) {
                    objects.inventory.setAmmo(g.bulletType, 1000);
                }
            }
            geometry.attachChild(builder.buildShots(ab3d2.full.sim.Shots.POOL));
            geometry.attachChild(builder.buildAliens(monsters.aliens(), level));
            monsters.messages = msgQueue;
            if (audio != null) {
                // Lvl_MusicPtr_l : la meme musique pour tous les niveaux ; PLAY MUSIC la coupe.
                if (options.on(ab3d2.rebirth.menu.Options.PLAY_MUSIC)) {
                    audio.music("level", true);
                }
            }
            if (builder.modern()) {               // rendu MOTEUR : vraies lumieres jME
                if (lights3d == null) {
                    lights3d = new Lights3D(rootNode);
                }
                lights3d.build(level, glf, builder.lights());
                System.out.printf("[Level] lumieres : %d lampes + %d dynamiques%n",
                        lights3d.lampCount(), Lights3D.DYNAMIC);
                etatLumieres.poserOmbres();
            }
            // Lumieres dynamiques (plasma/roquette en vol, explosions, torches) : de vraies
            // PointLight en rendu MOTEUR, l'ecriture dans le light map — et la reconstruction
            // des tampons de sommets derriere — uniquement en -Pretro.
            ab3d2.full.sim.DynLight dl = builder.modern() && lights3d != null
                    ? new DynLights3D(lights3d) : builder.dynLights();
            // EN RENDU MOTEUR, LES PROJECTILES SONT ECLAIRES PAR LE RENDU (eclairerTirs) :
            // toutes les balles, pas seulement celles dont le jeu porte une luminosite, et
            // les impacts a part. Laisser en plus la simulation en allumer quelques-unes
            // gaspillerait des sources -- il n'y en a que douze -- a eclairer deux fois la
            // meme balle.
            shots.dynLight = builder.modern() ? null : dl;
            monsters.dynLight = dl;
            player.look(Integer.getInteger("rebirth.angle", 0));
            String spawn = firstLoad ? System.getProperty("rebirth.spawn") : null;
            firstLoad = false;
            if (spawn != null) {                  // debug : depart force "x,z,zone" (1er niveau)
                String[] sp = spawn.split(",");
                player.xOff = player.snapXOff = Integer.parseInt(sp[0].trim()) << 16;
                player.zOff = player.snapZOff = Integer.parseInt(sp[1].trim()) << 16;
                player.zone = Integer.parseInt(sp[2].trim());
                int floorH = levelSim.zones[player.zone].floorH;
                player.yOff = player.snapYOff = player.snapTYOff =
                        floorH - ab3d2.full.sim.PlayerSim.PLR_STAND_HEIGHT;
            }
            // La carte appartient au NIVEAU : elle se vide en changeant de niveau, comme le
            // jeu qui remet Lvl_CompactMap_vl a zero au chargement (hires.s).
            if (automap != null) {
                automap.detach();
            }
            automap = AutoMap.create(assetManager, guiNode, level,
                    cam.getWidth(), cam.getHeight());
            if (automap != null && System.getProperty("rebirth.map") != null) {
                automap.toggle();                 // -Pmap=<zoom> : carte ouverte au demarrage
                automap.setZoom(Integer.getInteger("rebirth.map", 3));
                if (System.getProperty("rebirth.mapAll") != null) {
                    automap.revealAll();
                }
            }
            System.out.printf("[Main] joueur : zone %d, %d portes, %d ascenseurs%n",
                    player.zone, level.doors == null ? 0 : level.doors.size(),
                    level.lifts == null ? 0 : level.lifts.size());
        }

        if (shotAt > 0) {
            screenshot = new ScreenshotAppState(System.getProperty("user.dir") + "/", "rebirth_shot");
            screenshot.setIsNumbered(shotCount > 1);
            stateManager.attach(screenshot);
        }
        System.out.printf("[Main] niveau %s charge. ZQSD/WASD = bouger, souris = regarder, "
                + "Maj = courir, Espace = sauter/jetpack, C = s'accroupir, E = action (portes), clic = tirer, "
                + "1..0/X = arme, TAB = carte (F1/F2 zoom, pave num. defile, 5 recentre, "
                + "Entree fondu), Echap = menu.%n", levelLetterCur);
        return true;
    }

    private void initFreeCam() {
        Vector3f start = LevelBuilder.playerStart(level);
        if (camAt != null) {
            String[] p = camAt.split(",");
            start = new Vector3f(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]));
        }
        cam.setLocation(start);
        cam.lookAtDirection(Vector3f.UNIT_Z.negate(), Vector3f.UNIT_Y);
        System.out.println("[Main] freecam a " + start);
        flyCam.setMoveSpeed(20f);
        flyCam.setZoomSpeed(0f);
        flyCam.setDragToRotate(true);
    }

    // ------------------------------------------------------------- entrees

    private void initPlayerInput() {
        flyCam.setEnabled(false);
        inputManager.setCursorVisible(false);
        mouseSettle = MOUSE_SETTLE;         // la saisie va sauter : on l'ignore

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
        inputManager.addMapping("fire", new com.jme3.input.controls.MouseButtonTrigger(
                MouseInput.BUTTON_LEFT));
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
        for (int i = 0; i <= 9; i++) {            // touches 1..9,0 : selection directe d'arme
            mapKey("gun" + i, KeyInput.KEY_1 + i);
        }

        ActionListener onKey = (name, pressed, tpf) -> {
            if (inStory()) {
                keys.put(name, pressed);               // seule la validation est lue
                return;
            }
            if (inMenu()) {
                if (pressed && menu != null && !menu.rebinding()) {
                    switch (name) {                    // mnu_waitmenu : avancer / reculer / feu
                        case "forward" -> menu.up();
                        case "backward" -> menu.down();
                        case "menuOk" -> menu.select();
                        case "menuLeft" -> menu.adjust(-1);
                        case "menuRight" -> menu.adjust(1);
                        case "quit" -> menu.cancel();
                        default -> { }
                    }
                }
                keys.put(name, false);                 // aucune touche ne fuit vers la simulation
                return;
            }
            if ("quit".equals(name) && pressed) {      // ECHAP : on quitte le NIVEAU vers le menu
                if (menu != null) {
                    etatMenu.setEnabled(true);
                } else {
                    stop();
                }
                return;
            }
            keys.put(name, pressed);
            if (pressed && name.startsWith("gun")) {
                selectGun(name.charAt(3) - '0');       // gun0 = touche 1 ... gun9 = touche 0
            }
            if (pressed && "nextWeapon".equals(name)) {
                nextWeapon();
            }
            if (pressed && "lookBehind".equals(name)) {
                lookBehindTap = true;
            }
            if (pressed && automap != null) {
                switch (name) {
                    case "map" -> automap.toggle();
                    case "mapZoomIn" -> automap.zoom(-1);
                    case "mapZoomOut" -> automap.zoom(1);
                    case "mapCentre" -> automap.centre();
                    case "mapFade" -> automap.toggleTransparent();
                    default -> { }
                }
            }
        };
        String[] names = new String[] { "forward", "backward", "left", "right", "run", "jump",
                "duck", "action", "quit", "fire", "nextWeapon", "gun0", "gun1", "gun2", "gun3",
                "gun4", "gun5", "gun6", "gun7", "gun8", "gun9",
                "map", "mapZoomIn", "mapZoomOut", "mapUp", "mapDown", "mapLeft", "mapRight",
                "mapCentre", "mapFade", "menuOk", "menuLeft", "menuRight",
                "turnLeft", "turnRight", "strafeMode", "lookUp", "lookDown", "centreView",
                "lookBehind" };
        inputManager.addListener(onKey, names);

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
                if (e.isPressed() && menu != null && menu.rebinding()) {
                    menu.rawKey(e.getKeyCode());
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
            inputManager.addMapping(name, t.toArray(new KeyTrigger[0]));
        }
        keys.put(name, false);
    }


    /** Ou accrocher l'arme : sa vue dediee, ou la scene si elle n'a pas pu etre montee. */
    private Node weaponParent() {
        // -PweaponInScene : remet l'arme DANS la scene (elle traverse alors les murs). Sert a
        // verifier que la vue dediee fait bien son office.
        if (System.getProperty("rebirth.weaponInScene") != null) {
            return rootNode;
        }
        return weaponNode != null ? weaponNode : rootNode;
    }

    /** Vrai une fois l'interface construite : {@link #reshape} n'agit qu'apres. */
    private boolean guiReady;

    /**
     * Changement de taille de la fenetre (y compris apres {@code restart()} quand SCREEN
     * OPTIONS change la resolution). Tout ce qui est pose en PIXELS — HUD, bandeau de statut,
     * carte, menu, ecrans de texte — est bati pour une taille donnee : il faut le refaire.
     */
    @Override
    public void reshape(int width, int height) {
        super.reshape(width, height);
        if (!guiReady || freeCam) {
            return;
        }
        rebuildGui();
    }

    private void rebuildGui() {
        int w = cam.getWidth();
        int h = cam.getHeight();
        if (hud != null) {
            hud.detach();
            hud = new Hud(assetManager, guiNode, w, h);
        }
        if (panel != null) {
            panel.detach();
            panel = StatusPanel.create(assetManager, guiNode, w, h);
        }
        if (hud != null) {
            hud.setTextReadout(panel == null);
            hud.setVisible(!inMenu() && !inStory());
        }
        if (panel != null) {
            panel.setVisible(!inMenu() && !inStory());
        }
        if (automap != null && level != null) {
            automap.detach();
            automap = AutoMap.create(assetManager, guiNode, level, w, h);
        }
        if (story != null) {
            story.detach();
            story = ab3d2.rebirth.menu.StoryText.create(assetManager, guiNode, w, h);
            etatTexte.fermerSansSuite();               // un texte ne survit pas au resize
        }
        if (menu != null) {
            int keep = menu.level();
            menu.detach();
            menu = ab3d2.rebirth.menu.MenuUi.create(assetManager, guiNode, w, h, glf, options);
            if (menu != null) {
                menu.setLevel(keep);
                if (inMenu()) {
                    etatMenu.setEnabled(false);        // le menu refait est neuf
                    etatMenu.setEnabled(true);
                }
            }
        }
        applyLiveOptions();
    }

    /**
     * Monte la vue de l'arme : une seconde vue principale, donc rendue APRES la scene (et avant
     * le HUD, qui est une vue « post »). Elle n'efface que la PROFONDEUR : la couleur de la
     * scene est conservee, l'arme se dessine par-dessus, et ses propres faces se masquent
     * correctement entre elles puisqu'elle garde son test de profondeur.
     */
    private void initWeaponView() {
        weaponCam = cam.clone();
        weaponView = renderManager.createMainView("arme", weaponCam);
        weaponView.setClearFlags(false, true, false);   // profondeur seulement
        weaponNode = new Node("arme_vue");
        weaponView.attachScene(weaponNode);
    }

    /**
     * Suit la camera et repose l'eclairage. La vue de l'arme a sa propre scene : elle n'herite
     * d'aucune des lumieres du niveau, on lui donne donc l'ambiante et la lampe de la zone ou se
     * tient le joueur — les deux qui l'eclairent reellement. Une source DYNAMIQUE (eclat de tir,
     * explosion) n'atteint pas l'arme ; c'est le prix de la vue separee.
     */
    private void updateWeaponView(float tpf) {
        if (weaponNode == null) {
            return;
        }
        // Rien a tenir pendant le menu ou un ecran de texte.
        weaponNode.setCullHint(inMenu() || inStory()
                ? com.jme3.scene.Spatial.CullHint.Always
                : com.jme3.scene.Spatial.CullHint.Inherit);
        weaponCam.copyFrom(cam);                       // meme ouverture, meme pose : meme visee
        int zone = player == null ? -1 : player.zone;
        if (lights3d != null && zone != weaponLitZone) {
            weaponLitZone = zone;
            weaponNode.getLocalLightList().clear();
            if (lights3d.ambientLight() != null) {
                weaponNode.addLight(lights3d.ambientLight());
            }
            com.jme3.light.PointLight zl = lights3d.zoneLight(zone);
            if (zl != null) {
                weaponNode.addLight(zl);
            }
        }
        weaponNode.updateLogicalState(tpf);
        weaponNode.updateGeometricState();
    }

    /** INVERT MOUSE : -1 inverse l'axe vertical de la visee. */
    private float invertMouse() {
        return options.invertMouse ? -1f : 1f;
    }

    /**
     * Applique les reglages de SCREEN / SOUND / CONTROL OPTIONS qui prennent effet TOUT DE
     * SUITE. La taille de fenetre, le plein ecran et la synchro verticale, eux, demandent de
     * recreer le contexte : c'est {@link #applyDisplayOptions()}.
     */
    void applyLiveOptions() {
        if (sensitivity < 0f) {                        // -Psens n'a pas force la valeur
            sensitivity = options.sensitivity();
        }
        cam.setFrustumPerspective(options.fovDegrees(),
                (float) cam.getWidth() / cam.getHeight(), 0.05f, 4000f);
        if (lights3d != null) {
            lights3d.setBrightness(options.brightnessFactor());
        }
        if (bloom != null) {
            bloom.setEnabled(options.bloom);
        }
        if (ssao != null) {
            // -Pssao force l'occlusion ambiante, pour la mesurer sans passer par le menu.
            ssao.setEnabled(options.ambientOcclusion
                    || System.getProperty("rebirth.ssao") != null);
        }
        if (audio != null) {
            audio.setVolumes(options.musicGain(), options.soundGain());
        }
        applyFpsOption();
    }

    /**
     * Taille de fenetre, plein ecran, synchro verticale : jME ne les change qu'en recreant le
     * contexte. {@link #reshape} rebatit ensuite tout ce qui est dessine en pixels.
     */
    void applyDisplayOptions() {
        // Teste AVANT d'allouer : cette methode est appelee a chaque frame du menu principal.
        if (settings.getWidth() == options.width() && settings.getHeight() == options.height()
                && settings.isFullscreen() == options.fullscreen
                && settings.isVSync() == options.vsync) {
            return;                                    // rien n'a change
        }
        AppSettings st = new AppSettings(false);
        st.copyFrom(settings);
        st.setResolution(options.width(), options.height());
        st.setFullscreen(options.fullscreen);
        st.setVSync(options.vsync && System.getProperty("rebirth.novsync") == null);
        setSettings(st);
        restart();
    }

    /** SHOW FPS : le compteur de jME, rallume par CUSTOM OPTIONS. */
    private void applyFpsOption() {
        setDisplayFps(options.on(ab3d2.rebirth.menu.Options.SHOW_FPS)
                || System.getProperty("rebirth.stats") != null);
    }


    /** Montre ou cache tout ce qui appartient a la PARTIE : bandeau, jauges, arme. */
    void montrerInterface(boolean visible) {
        if (hud != null) {
            hud.setVisible(visible);
        }
        if (panel != null) {
            panel.setVisible(visible);
        }
    }

    /** Reprend le curseur et laisse passer la secousse de saisie. */
    void reprendreCurseur() {
        inputManager.setCursorVisible(false);
        mouseSettle = MOUSE_SETTLE;         // la saisie va sauter : on l'ignore
    }

    /** Oublie les touches tenues : on change d'ecran, rien ne doit rester enfonce. */
    void relacherTouches() {
        keys.replaceAll((k, v) -> false);
    }

    /** 'A'..'P' -> 0..15. */
    static int levelIndex(String letter) {
        return letter == null || letter.isEmpty() ? 0 : (letter.charAt(0) - 'A') & 15;
    }

    /** Vitesse de rotation au clavier, en unites d'angle par frame de simulation. */
    private static final float TURN_SPEED = 48f;
    /** Vitesse de visee verticale au clavier, en radians par frame. */
    private static final float LOOK_SPEED = 0.04f;
    /** Front montant de LOOK BEHIND. */
    private boolean lookBehindTap;

    /** Front montant de la touche accroupi (le jeu efface la touche apres l'avoir lue). */
    private boolean prevDuck;

    boolean key(String name) {
        return Boolean.TRUE.equals(keys.get(name));
    }

    /** Selection directe d'arme (touches 1..9,0) : seulement une arme possedee. */
    private void selectGun(int g) {
        if (objects != null && objects.inventory.hasGun(g) && g != gun) {
            gun = g;
            announceGun();
        }
    }

    /** .find_next_weapon : arme suivante possedee, en boucle sur 0..9. */
    private void nextWeapon() {
        if (objects == null) {
            return;
        }
        int g = gun;
        for (int n = 0; n < 10; n++) {
            g = g + 1 > 9 ? 0 : g + 1;
            if (objects.inventory.hasGun(g)) {
                gun = g;
                announceGun();
                return;
            }
        }
    }

    private void announceGun() {
        GlfData.Gun g = glf == null ? null : glf.gun(gun);
        GlfData.Bullet b = g == null ? null : glf.bullet(g.bulletType);
        System.out.printf("[arme] %d %s : %d munitions%n", gun, g == null ? "?" : g.name,
                b == null ? 0 : objects.inventory.ammo(g.bulletType));
    }

    // -------------------------------------------------------------- update

    @Override
    public void simpleUpdate(float tpf) {
        if (mouseSettle > 0) {
            mouseSettle--;                             // en IMAGES, pas en pas de simulation
            lookX = 0;                                 // et ce qui serait deja tombe dedans
        }
        if (fpsLog) {                                  // -PfpsLog : cout reel d'une frame
            if (fpsFrames == 0 && fpsAccum == 0f) {
                renderer.getStatistics().setEnabled(true);   // sinon elles restent a zero
            }
            fpsAccum += tpf;
            if (++fpsFrames == 120) {
                com.jme3.renderer.Statistics st = renderer.getStatistics();
                int[] d = new int[st.getLabels().length];
                st.getData(d);
                StringBuilder sb = new StringBuilder();
                String[] lab = st.getLabels();
                for (int i = 0; i < lab.length; i++) {
                    if (d[i] > 0) {
                        sb.append(' ').append(lab[i]).append('=').append(d[i]);
                    }
                }
                System.out.printf("[fps] %.1f images/s  (%.2f ms par frame) %s%n",
                        fpsFrames / fpsAccum, fpsAccum * 1000f / fpsFrames, sb);
                if (!sceneCounted) {
                    sceneCounted = true;
                    dumpSceneCounts();
                }
                fpsFrames = 0;
                fpsAccum = 0f;
            }
        }
        // La vue de l'arme a sa propre scene : PERSONNE ne la met a jour a notre place, et jME
        // refuse de dessiner une scene laissee « sale ». Elle doit donc etre mise a jour a
        // CHAQUE frame, quelle que soit la branche prise ci-dessous — d'ou le finally : les
        // sorties anticipees (menu, ecran de texte) l'avaient oubliee et le jeu plantait des la
        // premiere frame passee dans le menu.
        try {
            updateFrame(tpf);
        } finally {
            updateWeaponView(tpf);
        }
    }

    private void updateFrame(float tpf) {
        // LE MENU ET LES ECRANS DE TEXTE SE METTENT A JOUR SEULS : ce sont des etats de
        // jME, et leur update ne tourne que lorsqu'ils sont actifs. Il ne reste ici qu'a
        // ne pas faire tourner la PARTIE derriere eux.
        if (inStory() || inMenu()) {
            captureShot();                             // -Pshot marche aussi sur eux
            return;
        }
        if (player != null) {
            accumulator += Math.min(tpf, 0.25f);
            while (accumulator >= SIM_STEP) {
                accumulator -= SIM_STEP;
                stepSim();
            }
            applyCamera();
            // Les deux tris qui suivent partent du PVS de la ZONE DU JOUEUR. En camera libre la
            // vue n'est plus celle du joueur : ils eteignaient la piece regardee et escamotaient
            // les monstres qui s'y trouvent. On les desarme donc avec -Pfreecam.
            etatLumieres.allumerCeQuiSeVoit(player.zone);
            builder.setVisibleFrom(freeCam ? -1 : player.zone); // tri de visibilite de la frame
            applyAnimatedGeometry();
            builder.updateShots(shots.shots());
            etatLumieres.eclairerTirs();
            builder.updateAliens();
            builder.updateWater(simFrames);
            if (hud != null) {
                hud.underwater(underwaterMode());
            }
            applyWeapon();
            etatLumieres.reglerOmbres();
            presentShimmer();                          // dematerialisation (sortie / teleport)
            // Les lampes qui clignotent : en rendu MOTEUR aussi, puisque c'est la couleur
            // par sommet qui porte desormais la clarte.
            builder.refreshLighting();
            builder.updateSpriteLights(player.angPos, cam);   // monstres : eclairage directionnel
            if (hud != null) {
                GlfData.Gun g = glf == null ? null : glf.gun(gun);
                int ammo = g == null ? 0 : objects.inventory.ammo(g.bulletType);
                hud.update(objects.inventory, g == null ? null : g.name, ammo, tpf);
                if (panel != null) {
                    panel.update(objects.inventory, gun, ammo);
                }
            }
            if (automap != null) {
                // Le defilement est MAINTENU (le jeu ne consomme pas ces touches), 4 pixels
                // par frame tant qu'on appuie.
                if (automap.visible()) {
                    int dx = (key("mapLeft") ? 1 : 0) - (key("mapRight") ? 1 : 0);
                    int dy = (key("mapDown") ? 1 : 0) - (key("mapUp") ? 1 : 0);
                    if (dx != 0 || dy != 0) {
                        automap.pan(dx, dy);
                    }
                }
                automap.update(player.xOff >> 16, player.zOff >> 16,
                        player.angPos, player.zone);
            }
            if (hud != null) {
                boolean show = options.on(ab3d2.rebirth.menu.Options.SHOW_MESSAGES);
                for (String m : msgQueue.drain()) {
                    if (show) {                        // Msg_Enabled : Prefs_ShowMessages_b
                        hud.pushMessage(m);
                    }
                }
            }
            if (audio != null) {
                for (ab3d2.full.sim.SfxQueue.Event e : sfxQueue.drain()) {
                    if (e.place == ab3d2.full.sim.SfxQueue.Place.VIEW) {
                        audio.play(e.num);
                        continue;
                    }
                    // Le jeu d'origine ne situe un bruit que par son ABSCISSE et sa
                    // PROFONDEUR : sa stereo n'est qu'un panoramique, et aucune routine ne
                    // transmet de hauteur. Le remake, lui, place ses sources en trois
                    // dimensions ; on les pose donc a hauteur d'OREILLE. Le son garde ainsi
                    // sa distance et son cote exacts, sans qu'une hauteur inventee le fasse
                    // venir du sol ou du plafond.
                    audio.playAtWorld(e.num, e.x, e.z, player == null ? 0 : player.yOff >> 8);
                }
                audio.update();
            }
        }
        captureShot();
    }

    /**
     * -Pshot=N : capture a la frame N puis quitte (validation visuelle).
     *
     * <p>-Pshotn=K en prend K DE SUITE, a partir de la. C'est ce qu'il faut pour voir un
     * CLIGNOTEMENT : une image seule ne dit rien d'un decor qui bat d'une frame a l'autre,
     * et deux executions ne tombent pas sur le meme instant.
     */
    private void captureShot() {
        if (shotAt <= 0 || screenshot == null) {
            return;
        }
        frame++;
        if (frame >= shotAt && frame < shotAt + shotCount) {
            screenshot.takeScreenshot();
        } else if (frame > shotAt + shotCount) {
            stop();
        }
    }

    /** Une frame de la simulation d'origine : entrees -&gt; chute -&gt; collision -&gt; animations. */
    private void stepSim() {
        if (state != State.PLAYING) {
            checkEndOfLevel();                         // le jeu d'origine QUITTE la boucle : on gele
            return;
        }
        if (lights3d != null) {
            // Les sources dynamiques ne durent qu'une frame : sans ca, un projectile disparu
            // laisserait sa lumiere allumee derriere lui.
            lights3d.fadeDynamic();
        }
        if (builder.lights() != null) {
            // doneallz : CurrentPointBrights repart des valeurs statiques AVANT objmoveanim.
            builder.lights().beginFrame();
        }
        // CE QUE LE JOUEUR DEMANDE pendant cette image. La simulation le prend d'un bloc :
        // c'est la seule chose que le remake lui donne, et c'est par la que passent ses
        // divergences a lui — la souris, la visee par pente, la camera libre.
        ab3d2.full.sim.World.Input vouloir = new ab3d2.full.sim.World.Input();
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
        // lateral, comme le « force sidestep » du jeu.
        boolean strafe = key("strafeMode");
        vouloir.stepLeft = key("left") || (strafe && key("turnLeft"));
        vouloir.stepRight = key("right") || (strafe && key("turnRight"));
        vouloir.run = key("run") || options.on(ab3d2.rebirth.menu.Options.ALWAYS_RUN);
        vouloir.jump = key("jump");
        boolean duck = key("duck");
        vouloir.duck = duck && !prevDuck;              // clr.b (a5,d7.w) : un seul front
        prevDuck = duck;

        vouloir.action = key("action") || demo;
        if (System.getProperty("rebirth.noAction") != null) {
            vouloir.action = false;                    // test : rester a portee SANS actionner
        }
        vouloir.fire = key("fire") || autoFire;
        // La visee se transmet par la PENTE du rayon, pas par les lignes d'ecran du jeu :
        // c'est elle qui fait suivre le rayon aux tirs, quelle que soit l'ouverture de la vue.
        vouloir.aim = Math.round(FastMath.tan(pitch) * 100f);

        // UNE IMAGE DE JEU, dans l'ordre de la boucle d'origine. Le remake l'ecrivait a la
        // main et s'y etait trompe : il donnait aux portes le LONG ENTIER des verrous, dont
        // DoorRoutine ne lit que le mot fort et LiftRoutine le mot faible — les portes se
        // verrouillaient avec les cles des ascenseurs, et aucun ascenseur ne l'etait.
        int santeAvant = objects.inventory.health();
        sim.gun = gun;
        if (doorSweep >= 0 && anims != null && level != null && level.doors != null
                && doorSweep < level.doors.size()) {
            LevelData.Liftable d = level.doors.get(doorSweep);
            int course = d.bottom - d.top;
            int pas = (simFrames * 8) % Math.max(1, course * 2);
            anims.forceDoor = doorSweep;
            anims.forceDoorPos = Integer.getInteger("rebirth.forcepos",
                    d.top + (pas < course ? pas : course * 2 - pas));
        }
        sim.frame(vouloir);

        for (ab3d2.full.sim.ObjectRuntime.Thing got : objects.collected()) {
            // Le RANG fait le pont entre les deux lectures du niveau : la simulation compte
            // les entites du fichier d'origine, le decor celles du json, et simParity prouve
            // que les deux listes se suivent.
            LevelData.Obj vu = got.rank < level.objects.size()
                    ? level.objects.get(got.rank) : null;
            if (vu != null) {
                builder.removeObject(vu);
                System.out.printf("[ramassage] %s -> sante %d, verrous 0x%04X%n",
                        vu.name, objects.inventory.health(), anims.doorLocks);
            }
        }
        int perdu = santeAvant - objects.inventory.health();
        if (perdu > 0) {
            if (hud != null) {
                hud.hurt(perdu);                       // l'ecran rougit : c'est au remake
            }
            System.out.printf("[douleur] -%d -> sante %d%n", perdu, objects.inventory.health());
        }
        if (builder.lights() != null) {
            builder.lights().tick();                   // brightanim (une frame sur six)
        }
        builder.stepObjectAnims(objects, sim.glf);     // DEFANIMOBJ : la simulation a anime
        if (builder.lights() != null) {
            builder.lights().endFrame();
        }
        if (System.getProperty("rebirth.dynLog") != null && builder.dynLights() != null) {
            ab3d2.rebirth.DynLights dl = builder.dynLights();
            int lit = dl.litAndReset();
            if (lit > 0) {
                System.out.printf("[dyn] frame %d : %d appels, %d zones, %d coins eclaircis%n",
                        simFrames, dl.dbgCalls, dl.dbgZones, lit);
            }
            dl.dbgCalls = 0;
            dl.dbgZones = 0;
        }
        simFrames++;
        checkEndOfLevel();
    }

    /**
     * Fin de niveau (game_main_loop, Hires.java:2613-2630) : dans la ZONE DE SORTIE, le compteur
     * de dematerialisation monte de 2 par frame et le niveau s'acheve a 9 ; a zero de sante,
     * le niveau s'acheve aussi (on le recommence).
     */
    private void checkEndOfLevel() {
        if (state != State.PLAYING) {
            if (--stateTimer <= 0) {
                if (state == State.EXITING) {
                    nextLevel();
                } else {
                    loadLevel(levelLetterCur, null);   // mort : on recommence le niveau a neuf
                }
            }
            return;
        }
        if (objects.inventory.health() <= 0) {          // tst.w Plr1_Health_w ; ble endlevel
            state = State.DEAD;
            stateTimer = 100;                           // 2 secondes avant de recommencer
            if (audio != null) {
                audio.music("gameover", false);         // move.l #gameover,mt_data ; mt_init
            }
            if (hud != null) {
                hud.message("VOUS ETES MORT");
            }
            System.out.println("[fin] mort du joueur");
            return;
        }
        if (player.zone == level.exitZone) {            // cmp.w Lvl_ExitZoneID_w
            exitShimmer += 2;                           // add.w #2,TELVAL
            if (exitShimmer >= 9) {                     // cmp.w #9 ; bge end
                state = State.EXITING;
                stateTimer = 100;
                if (audio != null) {
                    audio.music("welldone", false);     // move.l #welldone,mt_data ; mt_init
                }
                if (hud != null) {
                    hud.message("NIVEAU TERMINE");
                }
                System.out.println("[fin] niveau " + levelLetterCur + " termine");
            }
        } else {
            exitShimmer = 0;
        }
    }

    /** Niveau suivant (A -> B -> ... -> P), en emportant l'inventaire. */
    private void nextLevel() {
        char c = levelLetterCur.charAt(0);
        if (c >= 'P') {                                // ENDGAMESCROLL : le texte de fin
            state = State.EXITING;
            stateTimer = Integer.MAX_VALUE;
            if (story != null) {
                etatTexte.montrer(true);
            } else if (hud != null) {
                hud.message("PARTIE TERMINEE");
            }
            return;
        }
        loadLevel(String.valueOf((char) (c + 1)), objects.inventory);
        etatTexte.montrer(false);                             // chaque niveau ouvre sur sa narration
    }

    /**
     * POSE L'ARME EN MAIN, telle que la simulation vient de la placer.
     *
     * <p>Le jeu ne traite pas l'arme tenue a part : il lui donne l'ENTITE situee deux rangs
     * apres le joueur, et la repose ENTIEREMENT a chaque image — sa position, sa hauteur, son
     * angle, son pas d'animation. C'est de la que vient le recul, et c'est pourquoi elle prend
     * le meme eclairage que le reste du decor.
     *
     * <p>Le remake refaisait ce calcul a la main. Il ne reste ici que ce qui lui appartient :
     * le reglage SHOW WEAPON, et la descente sous l'oeil qui fait suivre la camera libre.
     */
    private void applyWeapon() {
        ab3d2.full.sim.Weapon arme = sim == null ? null : sim.weapon;
        // SHOW WEAPON (Prefs_ShowWeapon_b) : l'arme tenue peut etre masquee. Et seules les
        // armes VECTORIELLES se tiennent.
        if (arme == null || arme.shown == null || arme.gfxType != 1
                || !options.on(ab3d2.rebirth.menu.Options.SHOW_WEAPON)) {
            builder.updateWeapon(weaponParent(), -1, 0, 0, 0, 0, 0, 0, cam, 0f, 0f);
            return;
        }
        // Hauteur de l'arme SOUS L'OEIL : la difference entre la hauteur que le jeu lui donne
        // et celle de la camera. Elle garde donc le quart de taille, le decalage fixe et le
        // balancement une fois et demie de l'original, mais suit maintenant la vue.
        float drop = player.worldY() - (-arme.height / 64f);
        builder.updateWeapon(weaponParent(), arme.shown.gfx(), arme.shown.frame(),
                (short) (arme.x >> 16), (short) (arme.z >> 16), arme.height,
                arme.angle & 8190, arme.zone,
                cam, player.yawRadians(), drop);
        if (System.getProperty("rebirth.weaponLog") != null && simFrames % 30 == 1) {
            builder.logWeapon(cam);
        }
    }

    /**
     * Etat « sous l'eau » (fillscrnwater) : le jeu teinte tout l'ecran quand la surface est
     * au-dessus des yeux, et seulement la moitie basse quand elle est pile a hauteur d'yeux.
     */
    private int underwaterMode() {
        if (player.zone < 0 || player.zone >= levelSim.zones.length) {
            return 0;
        }
        ab3d2.full.sim.LevelSim.Zone z = levelSim.zones[player.zone];
        if (z.water >= z.floorH || player.stoodInTop) {
            return 0;                                  // zone non inondee
        }
        int eye = player.yOff;                         // Y croit vers le BAS
        if (Math.abs(eye - z.water) < 1024) {
            return 1;                                  // surface a hauteur d'yeux : moitie basse
        }
        return eye > z.water ? 2 : 0;                  // yeux sous la surface : plein ecran
    }

    private void applyCamera() {
        cam.setLocation(new Vector3f(player.worldX(), player.worldY(), player.worldZ()));
        // La lampe qui ombre pend plus haut que la torche, et le plafond lui sert de butee.
        float plafond = Float.NaN;
        if (levelSim != null && player.zone >= 0 && player.zone < levelSim.zones.length) {
            plafond = -levelSim.zones[player.zone].roofH / 8192f;
        }
        etatLumieres.suivreLeJoueur(player.worldX(), player.worldY(), player.worldZ(),
                plafond);
        if (sky != null) {
            sky.setLocalTranslation(cam.getLocation());   // le ciel reste a l'infini
        }
        Quaternion q = new Quaternion().fromAngles(-pitch, player.yawRadians(), 0f);
        cam.setRotation(q);
        // L'auditeur suit la camera : c'est lui qui donne la spatialisation des bruits.
        if (listener != null) {                        // null quand l'audio est coupe (-Pnosound)
            listener.setLocation(cam.getLocation());
            listener.setRotation(cam.getRotation());
        }
        if (audio != null) {
            audio.setListener(cam.getLocation());
        }
    }

    /**
     * SUR QUELLE CARTE GRAPHIQUE TOURNE-T-ON ? Dit a voix haute, et proteste si c'est la
     * mauvaise.
     *
     * <p>Sur un portable a deux cartes, le pilote donne par defaut la carte INTEGREE a un
     * {@code java.exe} : ce n'est pas un jeu que son profil connait. Le remake tournait
     * ainsi sur la Radeon d'un Ryzen 5800H alors que la machine a une RTX 3070, sans que
     * rien ne le signale -- et toute mesure de performance faite dessus est fausse. Le
     * cout de l'occlusion ambiante, du parallaxe et des ombres, tous mesures a l'aveugle.
     *
     * <p>UN PROCESSUS JAVA NE PEUT PAS CHOISIR SA CARTE sous Windows : il faudrait que
     * l'executable exporte {@code NvOptimusEnablement} ou
     * {@code AmdPowerXpressRequestHighPerformance}, ce que {@code java.exe} ne fait pas.
     * Tout ce qu'on peut faire est donc de le DIRE, et de donner le remede exact.
     */
    private void direQuelleCarte() {
        String carte = nomDuRendu();
        System.out.println("[Main] carte graphique : " + carte);
        if (!estIntegree(carte)) {
            return;
        }
        System.out.println();
        System.out.println("  ATTENTION : c'est une carte INTEGREE.");
        System.out.println("  Si cette machine en a une seconde, plus rapide, Windows ne la");
        System.out.println("  donne pas a java.exe tout seul -- il ne le prend pas pour un jeu.");
        System.out.println("  Parametres > Systeme > Affichage > Graphiques : ajoutez java.exe");
        System.out.println("  ET javaw.exe, puis passez-les en << Hautes performances >>.");
        System.out.println("  java.home = " + System.getProperty("java.home"));
        System.out.println();
    }

    /**
     * Le nom que le pilote donne au rendu.
     *
     * <p>jME ne l'expose pas : il le journalise au demarrage et le jette. On le redemande
     * donc a OpenGL, ce qui est licite ici -- {@code simpleInitApp} tourne sur le fil de
     * rendu, contexte courant.
     */
    private static String nomDuRendu() {
        try {
            String n = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            return n == null ? "" : n;
        } catch (Throwable absent) {
            return "";                           // pas de contexte OpenGL : tant pis
        }
    }

    /**
     * Ce nom est-il celui d'une carte integree ?
     *
     * <p>Les trois familles qu'on rencontre : les Intel HD/UHD/Iris, les Radeon des APU
     * Ryzen -- que le pilote nomme platement << AMD Radeon(TM) Graphics >>, sans numero,
     * la ou une carte dediee porte le sien (RX 7800, par exemple) -- et le rendu logiciel
     * de Windows quand aucun pilote ne repond.
     */
    static boolean estIntegree(String nom) {
        if (nom == null) {
            return false;
        }
        String n = nom.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("microsoft basic") || n.contains("llvmpipe")
                || n.contains("software rasterizer")) {
            return true;
        }
        if (n.contains("intel")
                && (n.contains("hd graphics") || n.contains("uhd") || n.contains("iris"))) {
            return true;
        }
        // Le cas du Ryzen : << amd radeon(tm) graphics >> et rien d'autre.
        return n.contains("radeon") && n.contains("graphics")
                && !n.matches(".*(rx|r9|r7 2|vega 5[0-9]|pro w).*");
    }

    /** Taille de la carte d'ombres (x6 faces pour une lumiere ponctuelle). */
    private static final int SHADOW_MAP = 1024;
    /**
     * FILTRE et non « renderer » : un {@code PointLightShadowRenderer} assombrit la scene MEME
     * quand sa lumiere est eteinte — il ne se pilote pas par la couleur. Le filtre, lui,
     * s'active et se desactive, ce qu'il faut ici puisque la source ombree n'existe que pendant
     * qu'un projectile vole.
     */
    com.jme3.shadow.PointLightShadowFilter shadows;

    /**
     * ScreenC.java:250-258 : la presentation decremente le compteur de dematerialisation et
     * dessine la valeur OBTENUE. Un teleport le repose a 8 (c2p_SetParamsTeleFx).
     */
    private void presentShimmer() {
        if (shimmer == null) {
            return;
        }
        if (System.getProperty("rebirth.shimmer") != null) {     // debug : frame figee
            shimmer.setFrame(Integer.getInteger("rebirth.shimmer", 0));
            return;
        }
        if (player.teleported) {
            exitShimmer = 8;                           // move.w #8,Game_TeleportFrame_w
        }
        boolean active = exitShimmer != 0;             // tst.w Game_TeleportFrame_w ; bne
        if (active) {
            exitShimmer--;                             // sub.w #1
        }
        shimmer.setFrame(active ? exitShimmer : -1);   // la derniere passe est bien la frame 0
    }

    /** Panneaux de portes (toit qui monte), murs deformes, et sols d'ascenseurs. */
    /**
     * Combien de geometries l'arbre porte-t-il, et sous quelle branche ?
     *
     * <p>Les compteurs du moteur donnent le nombre de SOUMISSIONS, pas le nombre d'objets :
     * une geometrie eclairee par plus de lumieres que le lot en compte est soumise plusieurs
     * fois. Pour savoir si un chiffre eleve vient de la geometrie ou de l'eclairage, il faut
     * les deux. Imprime une seule fois, avec -PfpsLog.
     */
    private void dumpSceneCounts() {
        java.util.Map<String, int[]> par = new java.util.LinkedHashMap<>();
        java.util.List<com.jme3.scene.Spatial> tops = new java.util.ArrayList<>();
        for (com.jme3.scene.Spatial top : rootNode.getChildren()) {
            // Un seul niveau de regroupement ne dit rien : la racine ne porte que le niveau et
            // le ciel. On descend d'un cran dans les noeuds pour voir Walls / Flats / Objets.
            if (top instanceof com.jme3.scene.Node n && !n.getChildren().isEmpty()) {
                tops.addAll(n.getChildren());
            } else {
                tops.add(top);
            }
        }
        for (com.jme3.scene.Spatial top : tops) {
            int[] n = {0, 0};
            top.depthFirstTraversal(sp -> {
                if (sp instanceof com.jme3.scene.Geometry g) {
                    n[0]++;
                    n[1] += g.getMesh().getTriangleCount();
                }
            });
            if (n[0] > 0) {
                par.put(top.getName() == null ? "?" : top.getName(), n);
            }
        }
        int tot = 0;
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, int[]> e : par.entrySet()) {
            sb.append(String.format("  %-16s %4d geo  %7d tri%n",
                    e.getKey(), e.getValue()[0], e.getValue()[1]));
            tot += e.getValue()[0];
        }
        System.out.printf("[scene] %d geometries dans l'arbre :%n%s", tot, sb);
    }

    private void applyAnimatedGeometry() {
        int nDoors = level.doors == null ? 0 : level.doors.size();
        if (level.doors != null) {
            for (int i = 0; i < nDoors; i++) {
                // Le BATTANT est un mur des pieces VOISINES, que le jeu deforme (son bord bas
                // monte vers le plafond). Les murs de la zone-porte elle-meme sont les jambages :
                // le jeu ne les bouge PAS, on ne les bouge pas non plus.
                builder.updateDeformed(i, anims.doorPosition(i), true);
                // ... et le DESSOUS du battant : le plafond de la zone-porte, que le port
                // remonte avec elle (sinon seuls les cotes bougent et la porte n'est pas un
                // volume). Meme conversion que le sol d'ascenseur : y = -hauteur/8192.
                builder.setDoorCeilingHeight(level.doors.get(i).zone,
                        -anims.doorRoofH[i] / 8192f);
            }
        }
        if (level.lifts != null) {
            for (int i = 0; i < level.lifts.size(); i++) {
                builder.updateDeformed(nDoors + i, anims.liftPosition(i), false);
            }
        }
        if (level.lifts != null) {
            for (int i = 0; i < level.lifts.size(); i++) {
                // le sol de la zone-ascenseur suit ZoneT_Floor (anime par LiftRoutine) ; la
                // hauteur de repos est celle du flat, que LevelBuilder est seul a connaitre
                builder.setLiftFloorHeight(level.lifts.get(i).zone,
                        -anims.liftFloorH[i] / 8192f);
            }
        }
    }
}
