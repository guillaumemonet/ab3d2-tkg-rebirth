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

import ab3d2.rebirth.sim.Aliens;
import ab3d2.rebirth.sim.Anims;
import ab3d2.rebirth.sim.LevelSim;
import ab3d2.rebirth.sim.Los;
import ab3d2.rebirth.sim.Nav;
import ab3d2.rebirth.sim.PlayerSim;
import ab3d2.rebirth.sim.Shots;
import ab3d2.rebirth.sim.SinCos;

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
    private final String camAt;
    private final boolean freeCam;

    private LevelData level;
    private LevelSim levelSim;
    private PlayerSim player;
    private Anims anims;
    private ab3d2.rebirth.sim.ObjectRuntime objects;
    private Shots shots;
    private Aliens monsters;
    private Nav nav;
    /** Animation de l'arme en main (script ACTANIMOBJ de l'objet-arme). */
    private ObjectAnim weaponAnim;
    private int weaponGun = -1;
    private LevelBuilder builder;
    private GlfData glf;
    /** PlrT_GunSelected_b : arme en main (index dans GLFT_ShootDefs). */
    private int gun = Integer.getInteger("rebirth.gun", 0);

    private Hud hud;
    private Sfx audio;
    /** File des bruitages demandes par la simulation pendant la frame. */
    private final ab3d2.rebirth.sim.SfxQueue sfxQueue = new ab3d2.rebirth.sim.SfxQueue();
    /** File des messages de narration poussee par la simulation. */
    private final ab3d2.rebirth.sim.Messages msgQueue = new ab3d2.rebirth.sim.Messages();
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
    private Lights3D lights3d;
    /** -PlightLog : trace l'allumage selectif quand le joueur change de zone. */
    private final boolean lightLog = System.getProperty("rebirth.lightLog") != null;
    /** -PnoPvsLights : garde TOUTES les lumieres allumees (mesure de reference). */
    private final boolean noPvsLights = System.getProperty("rebirth.noPvsLights") != null;
    /** -PfpsLog : imprime le temps de frame moyen toutes les 120 frames. */
    private final boolean fpsLog = System.getProperty("rebirth.fpsLog") != null;
    private int fpsFrames;
    private float fpsAccum;
    private StatusPanel panel;
    /** La carte (TAB) ; refaite a chaque niveau. */
    private AutoMap automap;
    /** Le menu du jeu (fond defilant + feu) et les reglages qu'il pilote. */
    private ab3d2.rebirth.menu.MenuUi menu;
    private final ab3d2.rebirth.menu.Options options = ab3d2.rebirth.menu.Options.load();
    /** Les ecrans de texte : introduction de niveau (TWEENTEXT) et texte de fin. */
    private ab3d2.rebirth.menu.StoryText story;
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
    private com.jme3.post.ssao.SSAOFilter ssao;
    /** Vrai tant qu'un ecran de texte est affiche : la simulation est alors a l'arret. */
    private boolean inStory;
    /** Vrai tant que le menu est affiche : la simulation est alors a l'arret. */
    private boolean inMenu;
    private int menuFrames;
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
    private String levelLetterCur;
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
    private float pitch = Float.parseFloat(System.getProperty("rebirth.pitch", "0"));
    private boolean prevAction;
    /** Mode demo (validation auto) : avance tout droit, action maintenue. */
    private final boolean demo = System.getProperty("rebirth.demo") != null;
    /** Validation visuelle : garde la gachette enfoncee (-Pfire). */
    private final boolean autoFire = System.getProperty("rebirth.fire") != null;
    /** Validation automatique de PLAY GAME apres N frames de menu (validation). */
    private final int autoPlay = Integer.getInteger("rebirth.autoplay", 0);

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
                    shadows.setEnabled(false);
                    fpp.addFilter(shadows);
                }
                // OCCLUSION AMBIANTE (-Pssao) : assombrit les recoins et les jonctions mur/sol.
                // DESACTIVEE PAR DEFAUT : mesuree a +23 % (niveau J, 900 frames : 22,7 s contre
                // 18,5 s) pour un effet discret — ce decor est fait de grandes surfaces planes,
                // le SSAO paie surtout sur le detail fin. Le cout est celui de sa passe
                // profondeur/normales, il ne baisse pas en reduisant le rayon.
                // L'occlusion ambiante est toujours MONTEE mais desactivee tant que SCREEN
                // OPTIONS ne l'allume pas : l'ajouter apres coup demanderait de refaire le FPP.
                ssao = new com.jme3.post.ssao.SSAOFilter(2.2f, 2.4f, 0.35f, 0.1f);
                fpp.addFilter(ssao);
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
            enterMenu();
        }
        // -Pstory : ouvre directement un ecran de texte (validation visuelle). "end" = texte
        // de fin, sinon l'introduction du niveau charge.
        String showStory = System.getProperty("rebirth.story");
        if (showStory != null && story != null) {
            enterStory("end".equalsIgnoreCase(showStory));
        }
        if (shotAt > 0) {
            screenshot = new ScreenshotAppState(System.getProperty("user.dir") + "/", "rebirth_shot");
            screenshot.setIsNumbered(false);
            stateManager.attach(screenshot);
        }
    }

    /**
     * Charge (ou recharge) un niveau : geometrie, simulation et entites. {@code carry} est
     * l'inventaire a conserver d'un niveau a l'autre (null = nouvelle partie, DEFAULTGAME).
     */
    private boolean loadLevel(String letter, ab3d2.rebirth.sim.Inventory carry) {
        levelLetterCur = letter.toUpperCase();
        level = Assets.json("levels/" + levelLetterCur + ".json", LevelData.class);
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
        weaponAnim = null;
        weaponGun = -1;
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
            levelSim = LevelSim.of(level);
            player = new PlayerSim(levelSim, level, SinCos.load());
            anims = new Anims(levelSim, level);
            objects = new ab3d2.rebirth.sim.ObjectRuntime(levelSim, level, glf);
            if (carry != null) {
                objects.inventory.copyFrom(carry); // on emporte armes, munitions et sante
            } else {
                objects.inventory.defaultGame(glf); // DEFAULTGAME : 200 de sante, fusil + 20 cartouches
            }
            if (System.getProperty("rebirth.health") != null) {
                objects.inventory.consumables[0] = Integer.getInteger("rebirth.health", 200);
            }
            if (System.getProperty("rebirth.gun") != null && glf != null) {
                // Debug (-Pgun=N) : on donne l'arme demandee et de quoi tirer, comme le harnais
                // de test du portage (LevelTest remplit Plr1_AmmoCounts_vw).
                objects.inventory.items[ab3d2.rebirth.sim.Inventory.WEAPON_START + gun] = 0xFF;
                GlfData.Gun g = glf.gun(gun);
                if (g != null) {
                    objects.inventory.setAmmo(g.bulletType, 1000);
                }
            }
            shots = new Shots(levelSim, glf, SinCos.load());
            geometry.attachChild(builder.buildShots(Shots.POOL));
            nav = new Nav(level);
            Los los = new Los(levelSim, level);
            monsters = new Aliens(levelSim, level, glf, SinCos.load(), nav, los);
            shots.los = los;                      // le souffle ne traverse pas les murs
            shots.nav = nav;
            monsters.shots = shots;               // gerbe de morceaux a la mort
            player.inventory = objects.inventory;  // le jetpack et son carburant y vivent
            player.collide = new ab3d2.rebirth.sim.Collide(levelSim, level, glf);
            player.collide.aliens = monsters;      // on bute sur les monstres et le decor solide
            geometry.attachChild(builder.buildAliens(monsters.aliens(), level));
            shots.setTargets(monsters);           // les balles du joueur touchent les monstres
            shots.setPlayer(player);              // ... et celles des monstres touchent le joueur
            monsters.setFire(shots::fireAtPlayer);
            // Bruitages : la simulation empile les evenements, le rendu les joue.
            objects.sfx = sfxQueue;
            objects.messages = msgQueue;
            monsters.messages = msgQueue;
            player.sfx = sfxQueue;
            if (glf != null && glf.floorData != null) {   // GLFT_FloorData : pas + sols toxiques
                for (GlfData.Floor f : glf.floorData) {
                    if (f.index >= 0 && f.index < 16) {
                        player.floorDamage[f.index] = f.damage;
                        player.floorSfx[f.index] = f.sfx;
                    }
                }
            }
            shots.sfx = sfxQueue;
            monsters.sfx = sfxQueue;
            anims.sfx = sfxQueue;
            anims.nav = nav;
            if (glf != null && glf.ambientSfx != null) {  // GLFT_AmbientSFX_l : bruits d'ambiance
                anims.ambientSfx = new int[glf.ambientSfx.size()];
                for (int i = 0; i < anims.ambientSfx.length; i++) {
                    anims.ambientSfx[i] = glf.ambientSfx.get(i);
                }
            }
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
                installShadows();
            }
            // Lumieres dynamiques (plasma/roquette en vol, explosions, torches) : de vraies
            // PointLight en rendu MOTEUR, l'ecriture dans le light map — et la reconstruction
            // des tampons de sommets derriere — uniquement en -Pretro.
            ab3d2.rebirth.sim.DynLight dl = builder.modern() && lights3d != null
                    ? new DynLights3D(lights3d) : builder.dynLights();
            shots.dynLight = dl;
            monsters.dynLight = dl;
            anims.run();                          // pose les toits/sols initiaux des portes et lifts
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
                        floorH - ab3d2.rebirth.sim.PlayerSim.PLR_STAND_HEIGHT;
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
            screenshot.setIsNumbered(false);
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
            if (inStory) {
                keys.put(name, pressed);               // seule la validation est lue
                return;
            }
            if (inMenu) {
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
                    enterMenu();
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

    /**
     * Ouvre le MENU : la simulation s'arrete, le HUD s'efface et la souris redevient visible.
     * C'est ce que fait ECHAP en cours de partie ({@code Game_Begin} rend la main au menu).
     */
    private void enterMenu() {
        if (menu == null || inMenu) {
            return;
        }
        inMenu = true;
        menuFrames = 0;
        keys.replaceAll((k, v) -> false);
        menu.setVisible(true);
        if (objects != null) {
            menu.setGameState(levelIndex(levelLetterCur), objects.inventory);
        }
        menu.openMain();
        if (hud != null) {
            hud.setVisible(false);
        }
        if (panel != null) {
            panel.setVisible(false);
        }
        if (automap != null && automap.visible()) {
            automap.toggle();
        }
        inputManager.setCursorVisible(true);
    }

    /**
     * Affiche l'ECRAN DE TEXTE et gele la partie derriere. {@code end} choisit le texte de fin
     * plutot que l'introduction du niveau.
     */
    private void enterStory(boolean end) {
        if (story == null) {
            return;
        }
        boolean shown = end ? story.showEnd() : story.showIntro(levelIndex(levelLetterCur));
        if (!shown) {
            return;                                    // ce niveau n'a pas de narration
        }
        inStory = true;
        storyEnd = end;
        keys.replaceAll((k, v) -> false);
        if (hud != null) {
            hud.setVisible(false);
        }
        if (panel != null) {
            panel.setVisible(false);
        }
        inputManager.setCursorVisible(false);
    }

    /** L'ecran de texte affiche est celui de FIN : on rend la main au menu en le fermant. */
    private boolean storyEnd;

    private void leaveStory() {
        inStory = false;
        if (storyEnd) {
            storyEnd = false;
            if (menu != null) {
                enterMenu();
                return;
            }
        }
        if (hud != null) {
            hud.setVisible(true);
        }
        if (panel != null) {
            panel.setVisible(true);
        }
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
            hud.setVisible(!inMenu && !inStory);
        }
        if (panel != null) {
            panel.setVisible(!inMenu && !inStory);
        }
        if (automap != null && level != null) {
            automap.detach();
            automap = AutoMap.create(assetManager, guiNode, level, w, h);
        }
        if (story != null) {
            story.detach();
            story = ab3d2.rebirth.menu.StoryText.create(assetManager, guiNode, w, h);
            inStory = false;                           // un ecran de texte ne survit pas au resize
        }
        if (menu != null) {
            int keep = menu.level();
            menu.detach();
            menu = ab3d2.rebirth.menu.MenuUi.create(assetManager, guiNode, w, h, glf, options);
            if (menu != null) {
                menu.setLevel(keep);
                if (inMenu) {
                    menu.setVisible(true);
                    menu.openMain();
                    menuFrames = 0;
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
        weaponNode.setCullHint(inMenu || inStory
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
    private void applyLiveOptions() {
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
            ssao.setEnabled(options.ambientOcclusion);
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
    private void applyDisplayOptions() {
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

    /** Referme le menu et rend la main a la partie. */
    private void leaveMenu() {
        inMenu = false;
        applyLiveOptions();
        menu.setVisible(false);
        if (hud != null) {
            hud.setVisible(true);
        }
        if (panel != null) {
            panel.setVisible(true);
        }
        inputManager.setCursorVisible(false);
    }

    /** 'A'..'P' -> 0..15. */
    private static int levelIndex(String letter) {
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

    private boolean key(String name) {
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
        if (fpsLog) {                                  // -PfpsLog : cout reel d'une frame
            fpsAccum += tpf;
            if (++fpsFrames == 120) {
                System.out.printf("[fps] %.1f images/s  (%.2f ms par frame)%n",
                        fpsFrames / fpsAccum, fpsAccum * 1000f / fpsFrames);
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
        if (inStory) {
            // introPresentLoop : n'importe quelle validation passe l'ecran.
            if (!story.frame(key("menuOk") || key("action") || key("fire"))) {
                leaveStory();
            }
            captureShot();
            return;
        }
        if (inMenu) {
            menuFrames++;
            menu.setFade(Math.min(1f, menuFrames / 16f));   // mnu_fadein : 16 pas de 16
            menu.frame();
            // -Pautoplay=N : valide PLAY GAME a la frame N, pour exercer la transition
            // menu -> texte d'intro -> jeu sans avoir a appuyer sur une touche.
            if (autoPlay > 0 && menuFrames == autoPlay) {
                menu.select();
            }
            // Dans les pages de reglages, ce qui peut s'appliquer tout de suite s'applique
            // tout de suite : on entend le volume et on voit la luminosite en reglant.
            switch (menu.page()) {
                case VIDEO, SOUND, CONTROLS2 -> applyLiveOptions();
                // De retour au menu principal, on prend en compte ce qui demande de recreer
                // le contexte (taille de fenetre, plein ecran, synchro verticale).
                case MAIN -> applyDisplayOptions();
                default -> { }
            }
            switch (menu.outcome()) {
                case QUIT -> stop();
                case PLAY -> {
                    String letter = String.valueOf((char) ('A' + menu.level()));
                    ab3d2.rebirth.sim.Inventory carry = menu.pendingInventory();
                    menu.clearOutcome();
                    if (!letter.equals(levelLetterCur) || carry != null) {
                        loadLevel(letter, carry);
                    }
                    leaveMenu();
                    enterStory(false);                 // PLAYTHEGAME : le texte narratif d'abord
                }
                default -> { }
            }
            captureShot();                             // -Pshot marche aussi sur le menu
            return;
        }
        if (player != null) {
            accumulator += Math.min(tpf, 0.25f);
            while (accumulator >= SIM_STEP) {
                accumulator -= SIM_STEP;
                stepSim();
            }
            applyCamera();
            // N'allumer que ce qui peut se voir depuis la zone du joueur (PVS du jeu d'origine).
            if (lights3d != null && !noPvsLights) {
                int zoneBefore = lights3d.litFrom();
                lights3d.lightZonesVisibleFrom(player.zone);
                if (lightLog && player.zone != zoneBefore) {
                    System.out.printf("[lumieres] zone %d : %d allumees sur %d%n",
                            player.zone, lights3d.litCount(), lights3d.lampCount());
                }
            }
            applyAnimatedGeometry();
            builder.updateShots(shots.shots());
            builder.updateAliens();
            builder.updateWater(simFrames);
            if (hud != null) {
                hud.underwater(underwaterMode());
            }
            applyWeapon();
            if (shadows != null && lights3d != null) {
                // Les ombres ne coutent que pendant qu'une source dynamique existe — et que
                // SCREEN OPTIONS les laisse allumees.
                shadows.setEnabled(options.shadows && lights3d.hasShadowSource());
            }
            presentShimmer();                          // dematerialisation (sortie / teleport)
            if (!builder.modern()) {
                builder.refreshLighting();             // -Pretro : re-eclaire les tampons LUT
            }
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
                for (ab3d2.rebirth.sim.SfxQueue.Event e : sfxQueue.drain()) {
                    if (e.positional) {
                        audio.playAtWorld(e.num, e.x, e.z, e.height);
                    } else {
                        audio.play(e.num);
                    }
                }
                audio.update();
            }
        }
        captureShot();
    }

    /** -Pshot=N : capture a la frame N puis quitte (validation visuelle). */
    private void captureShot() {
        if (shotAt <= 0 || screenshot == null) {
            return;
        }
        frame++;
        if (frame == shotAt) {
            screenshot.takeScreenshot();
        } else if (frame > shotAt + 1) {
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
            if (!builder.modern()) {
                builder.lights().beginFrame();         // -Pretro seulement (recopie 8000 mots)
            }
        }
        player.beginFrame(1);                          // plr1_OldX_l : sert a XDiff/ZDiff
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
            player.look(delta & ~1);
        }
        if (lookBehindTap) {                           // LOOK BEHIND : demi-tour
            lookBehindTap = false;
            player.look(4096);
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
        player.forward = key("forward") || demo;
        player.backward = key("backward");
        // FORCE S/S : la touche de pas chasse transforme la rotation clavier en deplacement
        // lateral, comme le « force sidestep » du jeu.
        boolean strafe = key("strafeMode");
        player.stepLeft = key("left") || (strafe && key("turnLeft"));
        player.stepRight = key("right") || (strafe && key("turnRight"));
        player.run = key("run") || options.on(ab3d2.rebirth.menu.Options.ALWAYS_RUN);
        player.jump = key("jump");
        boolean duck = key("duck");
        player.duckTap = duck && !prevDuck;            // clr.b (a5,d7.w) : un seul front
        prevDuck = duck;

        boolean action = key("action") || demo;
        if (System.getProperty("rebirth.noAction") != null) {
            action = false;                            // test : rester a portee SANS actionner
        }
        boolean tap = action && !prevAction;          // Plr1_TmpSpcTap_b : front montant
        prevAction = action;

        player.keyboardControl();                      // intention (Snap*)
        player.fall();                                 // Y : gravite / sol / plafond
        player.control();                              // collision + zone -> position commise

        // Ramassage : les objets encore au sol verrouillent les portes dont ils tiennent la cle.
        int locks = objects.run(player, tap) | monsters.doorLocks();
        if (locks != anims.doorLocks) {
            System.out.printf("[verrous] portes verrouillees : 0x%04X -> 0x%04X%n",
                    anims.doorLocks, locks);
        }
        anims.doorLocks = locks;
        for (LevelData.Obj got : objects.collected()) {
            builder.removeObject(got);
            System.out.printf("[ramassage] %s -> sante %d, verrous 0x%04X%n",
                    got.name, objects.inventory.health(), anims.doorLocks);
        }
        // objmoveanim : Plr1_Shot (cadence + tir) puis ObjectHandler (vol des balles).
        shots.tempFrames = 1;
        // La visee se transmet par la PENTE du rayon, pas par les lignes d'ecran du jeu : c'est
        // elle qui fait suivre le rayon aux tirs, quelle que soit l'ouverture de la vue.
        if (shots.fire(player, objects.inventory, gun, key("fire") || autoFire,
                FastMath.tan(pitch))
                && System.getProperty("rebirth.shotLog") != null) {
            GlfData.Gun g = glf == null ? null : glf.gun(gun);
            System.out.printf("[tir] %s : %d munitions restantes%n", g == null ? "?" : g.name,
                    g == null ? 0 : objects.inventory.ammo(g.bulletType));
        }
        shots.update();

        // objmoveanim -> ObjectHandler : les aliens (IA + animation) apres les balles.
        monsters.tempFrames = 1;
        monsters.run(player);

        player.floorDamage();                          // sols toxiques (une frame sur cent)

        // Plr1_Use : le joueur encaisse ce qu'il a pris pendant la frame.
        int hurt = player.applyDamage(objects.inventory, nav);
        if (hurt != 0) {
            sfxQueue.play(19);                         // move.w #19,Aud_SampleNum_w (cri de douleur)
            if (hud != null) {
                hud.hurt(hurt);
            }
            System.out.printf("[douleur] -%d -> sante %d%n", hurt, objects.inventory.health());
        }

        anims.playerZone = player.zone;
        anims.playerSpaceTap = tap;
        anims.tempFrames = 1;
        anims.run();                                   // objmoveanim : lifts puis portes
        player.floorSpd = anims.playerFloorSpd;        // sol mobile sous le joueur
        if (builder.lights() != null) {
            builder.lights().tick();                   // brightanim (une frame sur six)
        }
        builder.stepObjectAnims();                     // DEFANIMOBJ : animation des objets
        stepWeaponAnim();
        if (builder.lights() != null) {
            if (!builder.modern()) {
                builder.lights().endFrame();           // -Pretro seulement
            }
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
                enterStory(true);
            } else if (hud != null) {
                hud.message("PARTIE TERMINEE");
            }
            return;
        }
        loadLevel(String.valueOf((char) (c + 1)), objects.inventory);
        enterStory(false);                             // chaque niveau ouvre sur sa narration
    }

    /**
     * Animation de l'arme en main : le jeu la traite comme une entite ordinaire — son script
     * ACTANIMOBJ avance d'un pas par frame (GUNHELD, Newaliencontrol.java:189) — et le tir y
     * ecrit le pas 1 ({@code move.w #1,ENT_NEXT_2+EntT_Timer1_w}, Newplayershoot.java:217).
     */
    private void stepWeaponAnim() {
        GlfData.Gun g = glf == null ? null : glf.gun(gun);
        GlfData.ObjDef def = g == null ? null : glf.object(g.gunObject);
        if (def == null) {
            weaponAnim = null;
            return;
        }
        if (weaponAnim == null || weaponGun != gun) {
            weaponAnim = ObjectAnim.always(def.actAnim);
            weaponGun = gun;
        }
        if (weaponAnim == null) {
            return;
        }
        if (shots.fired) {
            weaponAnim.setStep(1);                     // le recul repart au debut de la sequence
        } else {
            weaponAnim.advance();
        }
    }

    /** Pose l'arme en main : position du joueur, face a lui, a hauteur de hanche, avec le bobble. */
    private void applyWeapon() {
        GlfData.Gun g = glf == null ? null : glf.gun(gun);
        GlfData.ObjDef def = g == null ? null : glf.object(g.gunObject);
        // SHOW WEAPON (Prefs_ShowWeapon_b) : l'arme tenue peut etre masquee.
        if (!options.on(ab3d2.rebirth.menu.Options.SHOW_WEAPON)
                || def == null || weaponAnim == null || def.gfxType != 1) {
            builder.updateWeapon(weaponParent(), -1, 0, 0, 0, 0, 0, 0, cam, 0f, 0f);
            return;                                    // seules les armes VECTORIELLES sont tenues
        }
        ObjectAnim.Step st = weaponAnim.step();
        // Hauteur : yeux - un quart de la taille + 10*128, puis 1,5 fois le balancement.
        int h = (player.snapYOff + (player.height >> 2) + 10 * 128) >> 7;
        int bob = player.bobbleY >> 8;
        h += bob + (bob >> 1);
        // ANIMOBJ ajoute ensuite le DECALAGE VERTICAL du pas courant, double :
        // move.b 4(a3,d0.w),d1 ; ext.w d1 ; add.w d1,d1 ; add.w d1,4(a0)
        // (Newaliencontrol.java:714). Le blaster est la seule arme tenue dont le script en porte
        // un (5 sur ses quatre premiers pas) : sans lui il flottait trop haut dans la main.
        h += st.delta * 2;
        // ANIMOBJ, branche .vector : le pas d'animation AJOUTE son word2 a l'angle de
        // l'entite (newaliencontrol.s:842). Sans lui, seul le fusil pointait droit devant —
        // c'est le seul modele d'arme tenue dont les pas portent 2048, tous les autres portent 0,
        // et le quart de tour manquant envoyait les neuf autres armes hors du cadre.
        int angle = (player.angPos + 4096 + st.word2) & 8190;   // + SINE_SIZE + word2
        // Hauteur de l'arme SOUS L'OEIL : la difference entre la hauteur que le jeu lui donne
        // et celle de la camera. Elle garde donc le quart de taille, le decalage fixe et le
        // balancement une fois et demie de l'original, mais suit maintenant la vue.
        float drop = player.worldY() - (-h / 64f);
        builder.updateWeapon(weaponParent(), st.gfx, st.frame, (short) (player.xOff >> 16),
                (short) (player.zOff >> 16), h, angle, player.zone,
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
        ab3d2.rebirth.sim.LevelSim.Zone z = levelSim.zones[player.zone];
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
     * OMBRES PORTEES. On n'en met qu'UNE : ombrer 134 lumieres de zone est hors de question, et
     * un `PointLightShadowRenderer` en rend deja six faces. La lumiere ombree suit le joueur et
     * prend la place de celle de sa zone (cf. Lights3D.followPlayer), donc l'eclairage ne change
     * pas — les ombres apparaissent simplement la ou le joueur peut les voir.
     */
    private void installShadows() {
        if (shadows == null || lights3d == null || lights3d.shadowLight() == null) {
            return;
        }
        shadows.setLight(lights3d.shadowLight());
        rootNode.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive);
    }

    /** Taille de la carte d'ombres (x6 faces pour une lumiere ponctuelle). */
    private static final int SHADOW_MAP = 512;
    /**
     * FILTRE et non « renderer » : un {@code PointLightShadowRenderer} assombrit la scene MEME
     * quand sa lumiere est eteinte — il ne se pilote pas par la couleur. Le filtre, lui,
     * s'active et se desactive, ce qu'il faut ici puisque la source ombree n'existe que pendant
     * qu'un projectile vole.
     */
    private com.jme3.shadow.PointLightShadowFilter shadows;

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
                Node node = builder.liftFloors.get(level.lifts.get(i).zone);
                if (node != null) {
                    // le sol de la zone-ascenseur suit ZoneT_Floor (anime par LiftRoutine)
                    float y = -anims.liftFloorH[i] / 8192f;
                    float base = -level.lifts.get(i).bottom / 32f;   // hauteur initiale du flat
                    node.setLocalTranslation(0f, y - base, 0f);
                }
            }
        }
    }
}
