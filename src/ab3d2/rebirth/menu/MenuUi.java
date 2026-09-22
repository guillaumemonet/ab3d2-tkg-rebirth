package ab3d2.rebirth.menu;

import java.util.ArrayList;
import java.util.List;

import com.jme3.asset.AssetManager;
import com.jme3.scene.Node;

import ab3d2.rebirth.GlfData;
import ab3d2.rebirth.sim.Inventory;

/**
 * Le MENU du jeu : ses pages, son curseur, sa navigation.
 *
 * <p>L'arborescence, les libelles et les positions viennent de {@code menu/menunb.s}
 * ({@code mnu_MYMAINMENU} et ses voisins) et le dispatch de {@code game_ReadMainMenu}
 * (controlloop.s) :
 * <pre>
 *   0 PLAY GAME        lance le niveau courant
 *   1 1 PLAYER         basculait en 2 joueurs
 *   2 LEVEL &lt;x&gt;         pages de selection de niveau
 *   3 CONTROL OPTIONS  deux pages de touches
 *   4 GAME CREDITS
 *   5 LOAD POSITION    NEW GAME + 5 emplacements
 *   6 SAVE POSITION    5 emplacements
 *   7 CUSTOM OPTIONS   huit bascules
 *   8 EXIT
 * </pre>
 *
 * <p>Deux ecarts, tous deux assumes :
 * <ul>
 *   <li><b>1 PLAYER</b> ne fait rien. La ligne dit le mode courant, et le remake n'a pas de mode
 *       deux joueurs — il n'y a donc rien vers quoi basculer.</li>
 *   <li><b>GAME CREDITS</b> affiche l'image des credits. Dans l'original l'appel
 *       ({@code jsr mnu_viewcredz}) est COMMENTE : l'entree ne faisait rien alors que l'image
 *       etait livree. On la montre.</li>
 * </ul>
 */
public final class MenuUi {

    /** Ce que le menu demande a l'appelant une fois la selection faite. */
    public enum Outcome { NONE, PLAY, QUIT }

    public enum Page {
        MAIN, LEVELS1, LEVELS2, CONTROLS1, CONTROLS2, CUSTOM, VIDEO, SOUND, LOAD, SAVE, CREDITS
    }

    /** Ce qu'un item du menu sait faire en plus d'etre choisi. */
    private enum Kind {
        /** Rien : l'item ouvre une page ou revient en arriere. */
        PLAIN,
        /** Y / N a la colonne 17, comme CUSTOM OPTIONS. */
        TOGGLE,
        /** Une barre reglable a gauche et a droite. */
        SLIDER,
        /** Une liste de valeurs qu'on fait defiler. */
        CYCLE
    }

    /**
     * Un item reglable. Les curseurs et les selecteurs sont dessines avec les glyphes que la
     * police du menu porte deja pour eux : {@code :} la pointe gauche, {@code ;} la barre,
     * {@code <} la pointe droite et {@code >} le bouton — ceux dont se sert
     * {@code mnu_putslider}.
     */
    private static final class Item {
        final String label;
        final Kind kind;
        final java.util.function.IntSupplier get;
        final java.util.function.IntConsumer set;
        final int max;
        final String[] texts;

        Item(String label) {                           // PLAIN
            this(label, Kind.PLAIN, null, null, 0, null);
        }

        Item(String label, Kind kind, java.util.function.IntSupplier get,
             java.util.function.IntConsumer set, int max, String[] texts) {
            this.label = label;
            this.kind = kind;
            this.get = get;
            this.set = set;
            this.max = max;
            this.texts = texts;
        }

        static Item toggle(String label, java.util.function.IntSupplier g,
                           java.util.function.IntConsumer s) {
            return new Item(label, Kind.TOGGLE, g, s, 1, null);
        }

        static Item slider(String label, java.util.function.IntSupplier g,
                           java.util.function.IntConsumer s) {
            return new Item(label, Kind.SLIDER, g, s, Options.SLIDER_MAX, null);
        }

        static Item cycle(String label, java.util.function.IntSupplier g,
                          java.util.function.IntConsumer s, String[] texts) {
            return new Item(label, Kind.CYCLE, g, s, texts.length - 1, texts);
        }
    }

    /** Pointe gauche, barre, pointe droite et bouton du curseur ({@code mnu_putslider}). */
    private static final char SLIDER_LEFT = 58;
    private static final char SLIDER_BAR = 59;
    private static final char SLIDER_RIGHT = 60;
    private static final char SLIDER_KNOB = 62;
    /** Colonne ou commencent les curseurs et les selecteurs (les bascules restent en 17). */
    private static final int WIDGET_COLUMN = 9;

    /** {@code mnu_cursanim} : les huit images de la fleche, une frame sur deux. */
    private static final int[] CURSOR_ANIM = { 130, 129, 128, 127, 126, 125, 124, 123 };
    /** {@code mnu_spread} : l'ecart entre deux items. */
    private static final int SPREAD = 20;
    /** Longueur d'une ligne de menu ({@code LEVELNAME_DISPLAY_LEN}). */
    private static final int LINE_LEN = 20;
    /** Colonne ou {@code lineKey} pose le dessin de la touche. */
    private static final int KEY_COLUMN = 17;

    private final MenuScreen screen;
    private final Options options;
    private final String[] levelNames;
    private SaveGame saves;

    private Page page = Page.MAIN;
    /** {@code mnu_row} : compteur libre ; l'item courant est son reste modulo le nombre d'items. */
    private int row;
    private int items = 1;
    private int curX;
    private int curY;
    private int cursorStep;
    /** Les items reglables de la page courante (vide pour les pages sans reglage). */
    private final List<Item> pageItems = new ArrayList<>();

    /** Niveau selectionne dans le menu (0..15), celui que PLAY GAME lance. */
    private int currentLevel;
    /** Action en cours de reliage dans CONTROL OPTIONS, ou -1. */
    private int rebinding = -1;

    private Outcome outcome = Outcome.NONE;
    /** Inventaire a reprendre (chargement) ; {@code null} = nouvelle partie. */
    private Inventory pending;
    /** Etat de la partie en cours, pour SAVE POSITION. */
    private int gameLevel;
    private Inventory gameInventory;

    private MenuUi(MenuScreen screen, Options options, String[] levelNames) {
        this.screen = screen;
        this.options = options;
        this.levelNames = levelNames;
        this.saves = SaveGame.load();
    }

    /** {@code null} si les images du menu n'ont pas ete extraites. */
    public static MenuUi create(AssetManager assets, Node gui, int width, int height,
                                GlfData glf, Options options) {
        MenuScreen s = MenuScreen.create(assets, gui, width, height);
        if (s == null) {
            return null;
        }
        String[] names = new String[16];
        for (int i = 0; i < 16; i++) {
            String n = glf != null && glf.levels != null && i < glf.levels.size()
                    ? glf.levels.get(i).name : null;
            names[i] = n == null ? "LEVEL  " + (char) ('A' + i) : n;
        }
        return new MenuUi(s, options, names);
    }

    // ---- etat vu par l'appelant ----

    public Outcome outcome() {
        return outcome;
    }

    public int level() {
        return currentLevel;
    }

    /** Retablit le niveau selectionne (apres un changement de taille de fenetre). */
    public void setLevel(int level) {
        currentLevel = Math.max(0, Math.min(15, level));
    }

    /** L'inventaire a reprendre, ou {@code null} pour une partie neuve. */
    public Inventory pendingInventory() {
        return pending;
    }

    public void clearOutcome() {
        outcome = Outcome.NONE;
        pending = null;
    }

    /** L'appelant tient le menu au courant de la partie en cours (pour SAVE POSITION). */
    public void setGameState(int levelIndex, Inventory inv) {
        gameLevel = levelIndex;
        gameInventory = inv;
    }

    public void setVisible(boolean on) {
        screen.setVisible(on);
    }

    public void detach() {
        screen.detach();
    }

    // ---- ouverture des pages ----

    /** Ouvre le menu principal ({@code game_ReadMainMenu}). */
    public void openMain() {
        open(Page.MAIN);
    }

    private void open(Page p) {
        page = p;
        rebinding = -1;
        pageItems.clear();
        screen.clearText();
        switch (p) {
            case MAIN -> mainPage();
            case LEVELS1 -> levelPage(0);
            case LEVELS2 -> levelPage(8);
            case CONTROLS1 -> controlsPage(0, Options.KEYS_PAGE_ONE, "        MORE", 0);
            case CONTROLS2 -> controlsPage(Options.KEYS_PAGE_ONE, Options.KEY_LABELS.length,
                    "     MAIN  MENU", 40);
            case CUSTOM -> customPage();
            case VIDEO -> videoPage();
            case SOUND -> soundPage();
            case LOAD -> loadPage();
            case SAVE -> savePage();
            case CREDITS -> creditsPage();
        }
        // mnu_openmenu : mnu_row part de items*3000, pour que le modulo tombe sur l'item 0.
        row = items * 3000;
        cursorStep = 0;
    }

    private void setCursor(int x, int y, int count) {
        curX = x;
        curY = y;
        items = Math.max(1, count);
    }

    private void mainPage() {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("");
        lines.add("     PLAY  GAME");
        lines.add("      1 PLAYER");
        lines.add(centred(levelNames[currentLevel]));
        lines.add("  CONTROL  OPTIONS");
        lines.add("    GAME CREDITS");
        lines.add("   LOAD  POSITION");
        lines.add("   SAVE  POSITION");
        lines.add("   CUSTOM OPTIONS");
        lines.add("        EXIT");
        printLines(lines, 0, 0);
        setCursor(0, 40, 9);
    }

    private void levelPage(int first) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("");
        for (int i = 0; i < 8; i++) {
            lines.add(centred(levelNames[first + i]));
        }
        lines.add(first == 0 ? "     NEXT  PAGE" : "     MAIN  MENU");
        printLines(lines, 0, 0);
        setCursor(0, 40, 9);
    }

    /**
     * Une page de CONTROL OPTIONS. La seconde porte en plus les reglages de SOURIS : le remake
     * se joue a la souris, ils ont leur place avec les touches.
     */
    private void controlsPage(int from, int to, String last, int y) {
        for (int i = from; i < to; i++) {
            pageItems.add(new Item(keyLine(i)));
        }
        if (from > 0) {                                // seconde page
            pageItems.add(Item.slider("  MOUSE", () -> options.mouseSpeed,
                    v -> options.mouseSpeed = v));
            pageItems.add(Item.toggle("  INVERT  MOUSE",
                    () -> options.invertMouse ? 1 : 0, v -> options.invertMouse = v != 0));
        }
        pageItems.add(new Item(last));
        itemsPage(0, y);
    }

    /**
     * CUSTOM OPTIONS : les bascules du jeu, plus l'acces aux deux pages MODERNES. « OPTION 8 »
     * de l'original n'y figure pas : son dispatch ne la traitait pas, elle ne faisait rien.
     */
    private void customPage() {
        for (int i = 0; i < Options.UNUSED; i++) {
            final int n = i;
            pageItems.add(Item.toggle(Options.TOGGLES[n],
                    () -> options.on(n) ? 1 : 0, v -> options.toggles[n] = v != 0));
        }
        pageItems.add(new Item("  SCREEN  OPTIONS"));
        pageItems.add(new Item("  SOUND   OPTIONS"));
        pageItems.add(new Item("     MAIN  MENU"));
        itemsPage(0, 40);
    }

    /** SCREEN OPTIONS : taille de fenetre, plein ecran, et la qualite du rendu. */
    private void videoPage() {
        String[] res = new String[Options.RESOLUTIONS.length];
        for (int i = 0; i < res.length; i++) {
            res[i] = Options.RESOLUTIONS[i][0] + "x" + Options.RESOLUTIONS[i][1];
        }
        pageItems.add(Item.cycle("  SCREEN", () -> options.resolution,
                v -> options.resolution = v, res));
        pageItems.add(Item.toggle("  FULL  SCREEN",
                () -> options.fullscreen ? 1 : 0, v -> options.fullscreen = v != 0));
        pageItems.add(Item.toggle("  V-SYNC",
                () -> options.vsync ? 1 : 0, v -> options.vsync = v != 0));
        pageItems.add(Item.slider("  VIEW", () -> options.fov, v -> options.fov = v));
        pageItems.add(Item.slider("  BRIGHT", () -> options.brightness,
                v -> options.brightness = v));
        pageItems.add(Item.toggle("  GLOW",
                () -> options.bloom ? 1 : 0, v -> options.bloom = v != 0));
        pageItems.add(Item.toggle("  SHADING",
                () -> options.ambientOcclusion ? 1 : 0, v -> options.ambientOcclusion = v != 0));
        pageItems.add(Item.toggle("  SHADOWS",
                () -> options.shadows ? 1 : 0, v -> options.shadows = v != 0));
        pageItems.add(new Item("     MAIN  MENU"));
        itemsPage(0, 40);
    }

    /** SOUND OPTIONS : les deux volumes. */
    private void soundPage() {
        pageItems.add(Item.slider("  MUSIC", () -> options.musicVolume,
                v -> options.musicVolume = v));
        pageItems.add(Item.slider("  SOUND", () -> options.soundVolume,
                v -> options.soundVolume = v));
        pageItems.add(new Item("     MAIN  MENU"));
        itemsPage(0, 80);
    }

    /** Rend une page batie sur {@link #pageItems}. */
    private void itemsPage(int x, int y) {
        List<String> lines = new ArrayList<>();
        for (Item it : pageItems) {
            lines.add(render(it));
        }
        printLines(lines, x, y);
        setCursor(x, y, pageItems.size());
    }

    /** Le texte d'un item, widget compris. */
    private String render(Item it) {
        return switch (it.kind) {
            case TOGGLE -> pad(it.label) + (it.get.getAsInt() != 0 ? "Y" : "N");
            case SLIDER -> pad(it.label, WIDGET_COLUMN) + slider(it.get.getAsInt(), it.max);
            case CYCLE -> pad(it.label, WIDGET_COLUMN)
                    + it.texts[Math.max(0, Math.min(it.max, it.get.getAsInt()))];
            case PLAIN -> it.label;
        };
    }

    /**
     * La barre d'un curseur : pointe gauche, un cran par valeur possible dont celui de la
     * valeur courante porte le bouton, pointe droite.
     */
    private static String slider(int value, int max) {
        StringBuilder b = new StringBuilder();
        b.append(SLIDER_LEFT);
        int v = Math.max(0, Math.min(max, value));
        for (int i = 0; i <= max; i++) {
            b.append(i == v ? SLIDER_KNOB : SLIDER_BAR);
        }
        b.append(SLIDER_RIGHT);
        return b.toString();
    }

    /** Fleche GAUCHE / DROITE : regle l'item courant ({@code mnu_waitmenu}, drapeaux 41 et 42). */
    public void adjust(int dir) {
        if (pageItems.isEmpty() || rebinding >= 0) {
            return;
        }
        Item it = pageItems.get(selected());
        if (it.kind == Kind.PLAIN) {
            return;
        }
        int v = it.get.getAsInt() + dir;
        if (it.kind == Kind.CYCLE) {
            v = Math.floorMod(v, it.max + 1);              // les selecteurs bouclent
        } else {
            v = Math.max(0, Math.min(it.max, v));
        }
        it.set.accept(v);
        options.save();
        int keep = row;
        open(page);
        row = keep;                                        // on ne bouge pas de l'item regle
    }

    private void loadPage() {
        List<String> lines = new ArrayList<>();
        lines.add("   LOAD  POSITION");
        lines.add("      NEW GAME");
        for (int i = 0; i < SaveGame.SLOTS; i++) {
            lines.add(slotLine(i));
        }
        lines.add("       CANCEL");
        printLines(lines, 0, 40);
        setCursor(0, 60, 7);
    }

    private void savePage() {
        List<String> lines = new ArrayList<>();
        lines.add("   SAVE  POSITION");
        for (int i = 0; i < SaveGame.SLOTS; i++) {
            lines.add(slotLine(i));
        }
        lines.add("       CANCEL");
        printLines(lines, 0, 40);
        setCursor(0, 60, 6);
    }

    private void creditsPage() {
        screen.credits();
        setCursor(0, 0, 1);
    }

    /** Le nom d'un emplacement : le niveau atteint, ou vide. */
    private String slotLine(int i) {
        SaveGame.Slot s = saves.slots.get(i);
        return s.used() ? centred(levelNames[Math.max(0, Math.min(15, s.level))])
                        : "       EMPTY";
    }

    /**
     * Une ligne de CONTROL OPTIONS : le libelle, puis le dessin de la touche a la colonne 17
     * ({@code lineKey}). Une touche que la police ne dessine pas s'ecrit en clair.
     */
    private String keyLine(int i) {
        int code = options.key(Options.KEY_ACTIONS[i]);
        int glyph = rebinding == i ? 0 : MenuKeys.glyph(code);
        String label = pad(Options.KEY_LABELS[i]);
        if (rebinding == i) {
            return label.substring(0, KEY_COLUMN - 4) + "... ";
        }
        if (glyph != 0) {
            return label + (char) glyph;
        }
        String n = MenuKeys.name(code);
        return label.substring(0, Math.max(0, KEY_COLUMN - n.length())) + n;
    }

    private static String pad(String s) {
        return pad(s, KEY_COLUMN);
    }

    /** Complete (ou tronque) a {@code n} caracteres. */
    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) {
            b.append(' ');
        }
        return b.substring(0, n);
    }

    /** Centre un nom de niveau sur la largeur d'une ligne, comme les libelles du jeu. */
    private static String centred(String s) {
        String t = s.length() > LINE_LEN ? s.substring(0, LINE_LEN) : s;
        int left = (LINE_LEN - t.length()) / 2;
        return " ".repeat(left) + t;
    }

    private void printLines(List<String> lines, int x, int y) {
        for (int i = 0; i < lines.size(); i++) {
            screen.print(lines.get(i), x, y + i * SPREAD);
        }
    }

    // ---- navigation ----

    public void up() {
        row--;
    }

    public void down() {
        row++;
    }

    /** L'item courant : {@code mnu_row % mnu_items}. */
    public int selected() {
        return Math.floorMod(row, items);
    }

    /** ECHAP : remonte d'un niveau (le menu principal ne remonte nulle part). */
    public void cancel() {
        if (rebinding >= 0) {
            rebinding = -1;
            open(page);
            return;
        }
        switch (page) {
            case MAIN -> { }
            case CREDITS, LEVELS1, LEVELS2, CONTROLS1, CONTROLS2, CUSTOM, LOAD, SAVE ->
                    open(Page.MAIN);
        }
    }

    /**
     * Capture d'une touche pendant un reliage ({@code mnu_getrawvalue}). Renvoie vrai si la
     * touche a ete consommee par le reliage.
     */
    public boolean rawKey(int jmeCode) {
        if (rebinding < 0) {
            return false;
        }
        options.bind(Options.KEY_ACTIONS[rebinding], jmeCode);
        options.save();
        rebinding = -1;
        open(page);
        return true;
    }

    public boolean rebinding() {
        return rebinding >= 0;
    }

    public Page page() {
        return page;
    }

    /** ENTREE / ESPACE : valide l'item courant. */
    public void select() {
        int item = selected();
        switch (page) {
            case MAIN -> mainSelect(item);
            case LEVELS1 -> {
                if (item < 8) {
                    currentLevel = item;
                    open(Page.MAIN);
                } else {
                    open(Page.LEVELS2);
                }
            }
            case LEVELS2 -> {
                if (item < 8) {
                    currentLevel = 8 + item;
                    open(Page.MAIN);
                } else {
                    open(Page.MAIN);
                }
            }
            case CONTROLS1 -> {
                if (item < Options.KEYS_PAGE_ONE) {
                    rebinding = item;
                    open(page);
                } else {
                    open(Page.CONTROLS2);
                }
            }
            case CONTROLS2 -> {
                int keys = Options.KEY_LABELS.length - Options.KEYS_PAGE_ONE;
                if (item < keys) {
                    rebinding = Options.KEYS_PAGE_ONE + item;
                    open(page);
                } else if (item == pageItems.size() - 1) {
                    open(Page.MAIN);
                } else {
                    adjust(1);                         // les deux reglages de souris
                }
            }
            case CUSTOM -> {
                int n = pageItems.size();
                if (item == n - 3) {
                    open(Page.VIDEO);
                } else if (item == n - 2) {
                    open(Page.SOUND);
                } else if (item == n - 1) {
                    open(Page.MAIN);
                } else {
                    adjust(1);                         // une bascule : valider l'inverse
                }
            }
            case VIDEO, SOUND -> {
                if (item == pageItems.size() - 1) {
                    open(Page.MAIN);
                } else {
                    adjust(1);
                }
            }
            case LOAD -> loadSelect(item);
            case SAVE -> saveSelect(item);
            case CREDITS -> open(Page.MAIN);
        }
    }

    private void mainSelect(int item) {
        switch (item) {
            case 0 -> {                                // PLAY GAME
                outcome = Outcome.PLAY;
                pending = null;
            }
            case 1 -> { }                              // 1 PLAYER : rien a basculer (cf. l'entete)
            case 2 -> open(Page.LEVELS1);
            case 3 -> open(Page.CONTROLS1);
            case 4 -> open(Page.CREDITS);
            case 5 -> open(Page.LOAD);
            case 6 -> open(Page.SAVE);
            case 7 -> open(Page.CUSTOM);
            case 8 -> outcome = Outcome.QUIT;
            default -> { }
        }
    }

    private void loadSelect(int item) {
        if (item == 0) {                               // NEW GAME
            currentLevel = 0;
            pending = null;
            outcome = Outcome.PLAY;
            return;
        }
        if (item <= SaveGame.SLOTS) {
            Inventory inv = new Inventory(null);
            int lvl = saves.restore(item - 1, inv);
            if (lvl < 0) {                             // emplacement vide : sans effet
                return;
            }
            currentLevel = lvl;
            pending = inv;
            outcome = Outcome.PLAY;
            return;
        }
        open(Page.MAIN);                               // CANCEL
    }

    private void saveSelect(int item) {
        if (item < SaveGame.SLOTS) {
            if (gameInventory != null) {
                saves.store(item, gameLevel, gameInventory);
            }
            open(Page.MAIN);
            return;
        }
        open(Page.MAIN);                               // CANCEL
    }

    /** Une frame : le feu avance, le curseur s'anime et se replace. */
    public void frame() {
        screen.frame();
        if ((screen.counter() & 1) != 0) {             // mnu_animcursor : une frame sur deux
            cursorStep = (cursorStep + 1) % CURSOR_ANIM.length;
        }
        if (page == Page.CREDITS) {
            screen.setCursor(0, 0, 0);                 // les credits n'ont pas de curseur
        } else {
            screen.setCursor(CURSOR_ANIM[cursorStep], curX, selected() * SPREAD + curY);
        }
    }

    /** Fondu d'entree / de sortie ({@code mnu_fadein} : 16 pas de 16). */
    public void setFade(float f) {
        screen.setFade(f);
    }
}
