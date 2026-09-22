package ab3d2.rebirth.menu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jme3.input.KeyInput;

/**
 * Les reglages du joueur : les bascules de CUSTOM OPTIONS et les touches de CONTROL OPTIONS.
 *
 * <p>Les libelles et l'ordre sont ceux du jeu ({@code mnu_MYCUSTOMOPTSMENU} et les deux pages
 * {@code mnu_MYCONTROLSONE/TWO} dans menu/menunb.s). Deux reserves, « OPTION 7 » et « OPTION 8 »,
 * n'etaient deja rattachees a rien dans l'original : elles ne le sont pas davantage ici.
 *
 * <p>Le jeu gardait ses preferences dans un bloc binaire ({@code game_PropertiesFile}) ; le
 * remake ecrit un JSON a cote de l'executable.
 */
public final class Options {

    /**
     * Les bascules, dans l'ordre du menu. Les libelles sont ceux de {@code mnu_MYCUSTOMOPTSMENU},
     * a une exception pres : l'original affiche « OPTION 7 » alors que {@code customOptions}
     * bascule {@code Prefs_PlayMusic_b} dessus — on ecrit donc ce que la ligne fait vraiment.
     * « OPTION 8 » n'est branchee sur rien, ni dans l'original ni ici.
     */
    public static final String[] TOGGLES = {
        "  ORIGINAL MOUSE",
        "  ALWAYS RUN",
        "  SHOW MESSAGES",
        "  NO AUTO AIM",
        "  SHOW FPS",
        "  SHOW WEAPON",
        "  PLAY  MUSIC",
        "  OPTION 8",
    };
    public static final int ORIGINAL_MOUSE = 0;
    public static final int ALWAYS_RUN = 1;
    public static final int SHOW_MESSAGES = 2;
    public static final int NO_AUTO_AIM = 3;
    public static final int SHOW_FPS = 4;
    public static final int SHOW_WEAPON = 5;
    public static final int PLAY_MUSIC = 6;
    /** Sans effet, comme dans l'original (le dispatch ne la traite pas). */
    public static final int UNUSED = 7;

    /**
     * Les actions liables, dans l'ordre des deux pages du menu : onze sur la premiere, six sur
     * la seconde. {@code action} est le nom de la commande cote jeu.
     */
    public static final String[] KEY_LABELS = {
        "  TURN LEFT", "  TURN RIGHT", "  FORWARDS", "  BACKWARDS", "  FIRE", "  OPERATE",
        "  RUN", "  FORCE S/S", "  S/S LEFT", "  S/S RIGHT", "  CROUCH",
        "  LOOK BEHIND", "  JUMP", "  LOOK UP", "  LOOK DOWN", "  CENTRE VIEW", "  NEXT WEAPON",
    };
    public static final String[] KEY_ACTIONS = {
        "turnLeft", "turnRight", "forward", "backward", "fire", "action",
        "run", "strafeMode", "left", "right", "duck",
        "lookBehind", "jump", "lookUp", "lookDown", "centreView", "nextWeapon",
    };
    /** Nombre d'actions sur la premiere page du menu des controles. */
    public static final int KEYS_PAGE_ONE = 11;

    // --- reglages MODERNES, absents du jeu d'origine ---
    // Toutes les valeurs de curseur vont de 0 a SLIDER_MAX : c'est le nombre de crans que la
    // barre dessinee avec la police du menu peut montrer.
    /** Valeur maximale d'un curseur (9 crans, 0 a 8). */
    public static final int SLIDER_MAX = 8;

    /** Resolutions proposees par SCREEN SIZE. */
    public static final int[][] RESOLUTIONS = {
        { 1280, 720 }, { 1366, 768 }, { 1600, 900 }, { 1920, 1080 },
        { 2560, 1440 }, { 3840, 2160 }, { 1024, 768 }, { 1280, 1024 },
    };

    public int resolution;                             // index dans RESOLUTIONS
    public boolean fullscreen;
    public boolean vsync = true;
    /** Champ de vision, en crans : 0 = 60 degres, 8 = 100. */
    public int fov = 4;
    /** Luminosite, en crans : 4 = neutre. */
    public int brightness = 4;
    public boolean bloom = true;
    public boolean ambientOcclusion;
    public boolean shadows = true;
    public int musicVolume = 6;
    public int soundVolume = 8;
    /** Vitesse de la souris, en crans. */
    public int mouseSpeed = 4;
    public boolean invertMouse;

    public boolean[] toggles = defaultToggles();
    public Map<String, Integer> keys = new LinkedHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Options() {
    }

    /** Les liaisons par defaut du remake (celles que Main utilisait en dur). */
    private static Map<String, Integer> defaultKeys() {
        Map<String, Integer> k = new LinkedHashMap<>();
        k.put("turnLeft", KeyInput.KEY_LEFT);
        k.put("turnRight", KeyInput.KEY_RIGHT);
        k.put("forward", KeyInput.KEY_W);
        k.put("backward", KeyInput.KEY_S);
        k.put("fire", KeyInput.KEY_LCONTROL);
        k.put("action", KeyInput.KEY_E);
        k.put("run", KeyInput.KEY_LSHIFT);
        k.put("strafeMode", KeyInput.KEY_LMENU);
        k.put("left", KeyInput.KEY_A);
        k.put("right", KeyInput.KEY_D);
        k.put("duck", KeyInput.KEY_C);
        k.put("lookBehind", KeyInput.KEY_B);
        k.put("jump", KeyInput.KEY_SPACE);
        k.put("lookUp", KeyInput.KEY_PGUP);
        k.put("lookDown", KeyInput.KEY_PGDN);
        k.put("centreView", KeyInput.KEY_HOME);
        k.put("nextWeapon", KeyInput.KEY_X);
        return k;
    }

    /** Messages, arme et musique sont actives au depart ; le reste non. */
    private static boolean[] defaultToggles() {
        boolean[] t = new boolean[TOGGLES.length];
        t[SHOW_MESSAGES] = true;
        t[SHOW_WEAPON] = true;
        t[PLAY_MUSIC] = true;
        return t;
    }

    private static Path file() {
        String prop = System.getProperty("rebirth.options");
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop);
        }
        return ab3d2.rebirth.AppDirs.runFile("options.json");
    }

    public static Options load() {
        Options o = null;
        Path p = file();
        if (Files.isRegularFile(p)) {
            try (var r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                o = GSON.fromJson(r, Options.class);
            } catch (IOException | RuntimeException e) {
                System.err.println("[Options] " + p + " illisible (" + e.getMessage() + ")");
            }
        }
        if (o == null) {
            o = new Options();
        }
        if (o.toggles == null || o.toggles.length != TOGGLES.length) {
            o.toggles = defaultToggles();
        }
        if (o.keys == null) {
            o.keys = new LinkedHashMap<>();
        }
        defaultKeys().forEach(o.keys::putIfAbsent);        // une action nouvelle prend son defaut
        return o;
    }

    public void save() {
        Path p = file();
        try {
            Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture " + p, e);
        }
    }

    public boolean on(int toggle) {
        return toggles[toggle];
    }

    public int key(String action) {
        Integer v = keys.get(action);
        return v == null ? 0 : v;
    }

    public void bind(String action, int keyCode) {
        keys.put(action, keyCode);
    }

    // --- conversions des crans vers les valeurs du moteur ---

    public int width() {
        return RESOLUTIONS[clampRes()][0];
    }

    public int height() {
        return RESOLUTIONS[clampRes()][1];
    }

    public String resolutionLabel() {
        return width() + "x" + height();
    }

    private int clampRes() {
        return Math.max(0, Math.min(RESOLUTIONS.length - 1, resolution));
    }

    /** Champ de vision vertical, en degres : 60 a 100. */
    public float fovDegrees() {
        return 60f + clamp(fov) * 5f;
    }

    /** Facteur de luminosite applique a l'ambiance : 0,5 a 1,5 (4 crans = 1,0). */
    public float brightnessFactor() {
        return 0.5f + clamp(brightness) * 0.125f;
    }

    /** Gain audio, de 0 a 1. */
    public float musicGain() {
        return clamp(musicVolume) / (float) SLIDER_MAX;
    }

    public float soundGain() {
        return clamp(soundVolume) / (float) SLIDER_MAX;
    }

    /** Sensibilite souris, dans les unites attendues par le jeu (8192 = tour complet). */
    public float sensitivity() {
        return 2000f + clamp(mouseSpeed) * 2000f;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(SLIDER_MAX, v));
    }
}
