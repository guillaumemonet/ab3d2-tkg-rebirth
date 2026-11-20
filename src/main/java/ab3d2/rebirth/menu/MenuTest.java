package ab3d2.rebirth.menu;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.RawInputListener;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.system.AppSettings;

import ab3d2.rebirth.Assets;
import ab3d2.rebirth.GlfData;

/**
 * Harnais du MENU : fond qui defile, feu, texte qui brule, navigation complete.
 *
 * <pre>
 * gradle -p rebirth/game menuTest                    # interactif (fleches, Entree, Echap)
 * gradle -p rebirth/game menuTest -Pshot=120         # capture apres 120 frames
 * gradle -p rebirth/game menuTest -Ppage=custom -Pshot=60
 * </pre>
 *
 * <p>{@code -Ppage} ouvre directement une page : {@code main}, {@code levels}, {@code controls},
 * {@code custom}, {@code load}, {@code save}, {@code credits}.
 */
public final class MenuTest extends SimpleApplication {

    private MenuUi menu;
    private ScreenshotAppState screenshot;
    private int frames;
    private final int shotAt = Integer.getInteger("rebirth.shot", 0);

    public static void main(String[] args) {
        MenuTest app = new MenuTest();
        AppSettings s = new AppSettings(true);
        s.setTitle("AB3D2 Rebirth — menu");
        s.setResolution(1280, 720);
        s.setVSync(true);
        app.setSettings(s);
        app.setShowSettings(false);
        app.setDisplayStatView(false);
        app.setDisplayFps(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        // Racine d'assets jME : shaders, materiaux .j3m et scenes .j3o.
        assetManager.registerLocator(ab3d2.rebirth.Assets.root().toString(),
                com.jme3.asset.plugins.FileLocator.class);
        GlfData glf = Assets.json("glf.json", GlfData.class);
        menu = MenuUi.create(assetManager, guiNode, cam.getWidth(), cam.getHeight(),
                glf, Options.load());
        if (menu == null) {
            stop();
            return;
        }
        menu.setVisible(true);
        menu.openMain();
        openRequestedPage();
        bindKeys();
        if (shotAt > 0) {
            screenshot = new ScreenshotAppState(System.getProperty("user.dir") + "/", "menu_shot");
            screenshot.setIsNumbered(false);
            stateManager.attach(screenshot);
        }
    }

    /** -Ppage : ouvre une page donnee en simulant la selection depuis le menu principal. */
    private void openRequestedPage() {
        String p = System.getProperty("rebirth.page");
        if (p == null) {
            return;
        }
        int item = switch (p) {
            case "levels" -> 2;
            case "controls", "controls2" -> 3;
            case "credits" -> 4;
            case "load" -> 5;
            case "save" -> 6;
            case "custom", "video", "sound" -> 7;
            default -> -1;
        };
        for (int i = 0; i < item; i++) {
            menu.down();
        }
        if (item >= 0) {
            menu.select();
        }
        // Pages de second niveau : on descend jusqu'a l'entree qui les ouvre.
        int sub = switch (p) {
            case "video" -> 7;                         // CUSTOM : ... SCREEN OPTIONS
            case "sound" -> 8;                         // CUSTOM : ... SOUND OPTIONS
            case "controls2" -> 11;                    // CONTROLS page 1 : MORE
            default -> -1;
        };
        if (sub >= 0) {
            for (int i = 0; i < sub; i++) {
                menu.down();
            }
            menu.select();
        }
    }

    private void bindKeys() {
        inputManager.addMapping("up", new KeyTrigger(KeyInput.KEY_UP));
        inputManager.addMapping("down", new KeyTrigger(KeyInput.KEY_DOWN));
        inputManager.addMapping("ok", new KeyTrigger(KeyInput.KEY_RETURN),
                new KeyTrigger(KeyInput.KEY_SPACE));
        inputManager.addMapping("back", new KeyTrigger(KeyInput.KEY_ESCAPE));
        ActionListener l = (name, pressed, tpf) -> {
            if (!pressed || menu.rebinding()) {
                return;
            }
            switch (name) {
                case "up" -> menu.up();
                case "down" -> menu.down();
                case "ok" -> menu.select();
                case "back" -> menu.cancel();
                default -> { }
            }
        };
        inputManager.addListener(l, "up", "down", "ok", "back");
        // Pendant un reliage, on veut la touche BRUTE, pas une commande : on ecoute l'entree crue.
        inputManager.addRawInputListener(new RawKeyGrabber());
    }

    /** Capture d'une touche pour CONTROL OPTIONS. */
    private final class RawKeyGrabber implements RawInputListener {
        @Override
        public void onKeyEvent(KeyInputEvent e) {
            if (e.isPressed() && menu.rebinding()) {
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
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (menu == null) {
            return;
        }
        frames++;
        menu.setFade(Math.min(1f, frames / 16f));      // mnu_fadein
        menu.frame();
        if (menu.outcome() == MenuUi.Outcome.QUIT) {
            stop();
            return;
        }
        if (menu.outcome() == MenuUi.Outcome.PLAY) {
            System.out.println("[menuTest] PLAY niveau " + (char) ('A' + menu.level())
                    + (menu.pendingInventory() == null ? " (partie neuve)" : " (position chargee)"));
            menu.clearOutcome();
        }
        if (shotAt > 0) {
            if (frames == shotAt) {
                screenshot.takeScreenshot();
            } else if (frames > shotAt + 1) {
                stop();
            }
        }
    }
}
