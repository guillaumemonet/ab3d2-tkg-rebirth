package ab3d2.rebirth.menu;

import java.util.HashMap;
import java.util.Map;

import com.jme3.input.KeyInput;

/**
 * Le PAVE de touche affiche dans CONTROL OPTIONS.
 *
 * <p>La police du menu contient un dessin de touche pour chaque touche du clavier Amiga, range
 * au code de caractere {@code 132 + rawkey} — c'est ainsi que le jeu ecrit ses liaisons par
 * defaut ({@code lineKey("  TURN LEFT", 132 + 0x4f)}, 0x4f etant la fleche gauche).
 *
 * <p>Le remake lit des codes jME, pas des <i>rawkeys</i> Amiga : cette table refait le pont, en
 * PHYSIQUE (la touche a telle place du clavier donne le dessin de la touche a la meme place sur
 * l'Amiga). Une touche sans equivalent (les F11/F12, le pave numerique etendu) n'a pas de dessin
 * et retombe sur son nom en clair.
 */
public final class MenuKeys {

    /** Decalage du premier dessin de touche dans la police. */
    private static final int GLYPH_BASE = 132;

    private static final Map<Integer, Integer> RAWKEY = new HashMap<>();
    private static final Map<Integer, String> NAME = new HashMap<>();

    private MenuKeys() {
    }

    private static void put(int jme, int rawkey, String name) {
        RAWKEY.put(jme, rawkey);
        NAME.put(jme, name);
    }

    private static void named(int jme, String name) {
        NAME.put(jme, name);
    }

    static {
        // rangee des chiffres
        put(KeyInput.KEY_GRAVE, 0x00, "`");
        put(KeyInput.KEY_1, 0x01, "1");
        put(KeyInput.KEY_2, 0x02, "2");
        put(KeyInput.KEY_3, 0x03, "3");
        put(KeyInput.KEY_4, 0x04, "4");
        put(KeyInput.KEY_5, 0x05, "5");
        put(KeyInput.KEY_6, 0x06, "6");
        put(KeyInput.KEY_7, 0x07, "7");
        put(KeyInput.KEY_8, 0x08, "8");
        put(KeyInput.KEY_9, 0x09, "9");
        put(KeyInput.KEY_0, 0x0A, "0");
        put(KeyInput.KEY_MINUS, 0x0B, "-");
        put(KeyInput.KEY_EQUALS, 0x0C, "=");
        put(KeyInput.KEY_BACK, 0x41, "BSP");
        // rangee du haut
        put(KeyInput.KEY_TAB, 0x42, "TAB");
        put(KeyInput.KEY_Q, 0x10, "Q");
        put(KeyInput.KEY_W, 0x11, "W");
        put(KeyInput.KEY_E, 0x12, "E");
        put(KeyInput.KEY_R, 0x13, "R");
        put(KeyInput.KEY_T, 0x14, "T");
        put(KeyInput.KEY_Y, 0x15, "Y");
        put(KeyInput.KEY_U, 0x16, "U");
        put(KeyInput.KEY_I, 0x17, "I");
        put(KeyInput.KEY_O, 0x18, "O");
        put(KeyInput.KEY_P, 0x19, "P");
        put(KeyInput.KEY_LBRACKET, 0x1A, "[");
        put(KeyInput.KEY_RBRACKET, 0x1B, "]");
        put(KeyInput.KEY_RETURN, 0x44, "RET");
        // rangee de repos
        put(KeyInput.KEY_LCONTROL, 0x63, "CTRL");
        put(KeyInput.KEY_RCONTROL, 0x63, "CTRL");
        put(KeyInput.KEY_CAPITAL, 0x62, "CAPS");
        put(KeyInput.KEY_A, 0x20, "A");
        put(KeyInput.KEY_S, 0x21, "S");
        put(KeyInput.KEY_D, 0x22, "D");
        put(KeyInput.KEY_F, 0x23, "F");
        put(KeyInput.KEY_G, 0x24, "G");
        put(KeyInput.KEY_H, 0x25, "H");
        put(KeyInput.KEY_J, 0x26, "J");
        put(KeyInput.KEY_K, 0x27, "K");
        put(KeyInput.KEY_L, 0x28, "L");
        put(KeyInput.KEY_SEMICOLON, 0x29, ";");
        put(KeyInput.KEY_APOSTROPHE, 0x2A, "'");
        // rangee du bas
        put(KeyInput.KEY_LSHIFT, 0x60, "LSH");
        put(KeyInput.KEY_RSHIFT, 0x61, "RSH");
        put(KeyInput.KEY_BACKSLASH, 0x0D, "\\");
        put(KeyInput.KEY_Z, 0x31, "Z");
        put(KeyInput.KEY_X, 0x32, "X");
        put(KeyInput.KEY_C, 0x33, "C");
        put(KeyInput.KEY_V, 0x34, "V");
        put(KeyInput.KEY_B, 0x35, "B");
        put(KeyInput.KEY_N, 0x36, "N");
        put(KeyInput.KEY_M, 0x37, "M");
        put(KeyInput.KEY_COMMA, 0x38, ",");
        put(KeyInput.KEY_PERIOD, 0x39, ".");
        put(KeyInput.KEY_SLASH, 0x3A, "/");
        // barre d'espace et modificateurs
        put(KeyInput.KEY_SPACE, 0x40, "SPC");
        put(KeyInput.KEY_LMENU, 0x64, "LALT");
        put(KeyInput.KEY_RMENU, 0x64, "RALT");
        put(KeyInput.KEY_LMETA, 0x66, "LAMI");
        put(KeyInput.KEY_RMETA, 0x67, "RAMI");
        // bloc de navigation
        put(KeyInput.KEY_ESCAPE, 0x45, "ESC");
        put(KeyInput.KEY_DELETE, 0x46, "DEL");
        put(KeyInput.KEY_UP, 0x4C, "UP");
        put(KeyInput.KEY_DOWN, 0x4D, "DOWN");
        put(KeyInput.KEY_RIGHT, 0x4E, "RGHT");
        put(KeyInput.KEY_LEFT, 0x4F, "LEFT");
        // touches de fonction
        put(KeyInput.KEY_F1, 0x50, "F1");
        put(KeyInput.KEY_F2, 0x51, "F2");
        put(KeyInput.KEY_F3, 0x52, "F3");
        put(KeyInput.KEY_F4, 0x53, "F4");
        put(KeyInput.KEY_F5, 0x54, "F5");
        put(KeyInput.KEY_F6, 0x55, "F6");
        put(KeyInput.KEY_F7, 0x56, "F7");
        put(KeyInput.KEY_F8, 0x57, "F8");
        put(KeyInput.KEY_F9, 0x58, "F9");
        put(KeyInput.KEY_F10, 0x59, "F10");
        // sans equivalent Amiga : seulement un nom
        named(KeyInput.KEY_F11, "F11");
        named(KeyInput.KEY_F12, "F12");
        named(KeyInput.KEY_HOME, "HOME");
        named(KeyInput.KEY_END, "END");
        named(KeyInput.KEY_PRIOR, "PGUP");
        named(KeyInput.KEY_NEXT, "PGDN");
        named(KeyInput.KEY_INSERT, "INS");
        named(KeyInput.KEY_NUMPAD0, "NUM0");
        named(KeyInput.KEY_NUMPAD1, "NUM1");
        named(KeyInput.KEY_NUMPAD2, "NUM2");
        named(KeyInput.KEY_NUMPAD3, "NUM3");
        named(KeyInput.KEY_NUMPAD4, "NUM4");
        named(KeyInput.KEY_NUMPAD5, "NUM5");
        named(KeyInput.KEY_NUMPAD6, "NUM6");
        named(KeyInput.KEY_NUMPAD7, "NUM7");
        named(KeyInput.KEY_NUMPAD8, "NUM8");
        named(KeyInput.KEY_NUMPAD9, "NUM9");
    }

    /**
     * Code de caractere du DESSIN de la touche dans la police, ou 0 si cette touche n'en a pas
     * (il faut alors se rabattre sur {@link #name}).
     */
    public static int glyph(int jmeKey) {
        Integer raw = RAWKEY.get(jmeKey);
        return raw == null ? 0 : GLYPH_BASE + raw;
    }

    /** Nom court de la touche, pour celles que la police ne dessine pas. */
    public static String name(int jmeKey) {
        return NAME.getOrDefault(jmeKey, "?");
    }
}
