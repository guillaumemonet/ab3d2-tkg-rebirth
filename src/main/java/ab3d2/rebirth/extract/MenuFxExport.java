package ab3d2.rebirth.extract;

import ab3d2.Mem;
import ab3d2.MenuNb;
import ab3d2.c.MenuC;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle du MENU : la source de bruit, le feu, la palette et la police.
 *
 * <p>L'écran du menu est une image à HUIT plans de bits — deux pour le fond qui défile, trois
 * pour le feu, trois pour la police — et c'est le FEU qui est difficile. Ce n'est pas un
 * automate sur pixels mais trois blits du blitter Amiga par frame, {@code D = A_décalé | (B & C)}
 * où A est un plan de POLICE (d'où le texte qui brûle), B une source de bruit précalculée et C
 * le plan de feu une ligne plus bas. Décalage, rotation des sources et avance du bruit changent
 * à chaque frame sur un cycle de quatre.
 *
 * <p>Porter cela à l'aveugle est hors de question : une erreur d'un bit dans le décalage donne
 * un feu qui a l'air juste et ne l'est pas. On relève donc les plans EUX-MÊMES, frame par
 * frame.
 *
 * <h2>Ce qu'on relève, et pourquoi</h2>
 *
 * <ul>
 *   <li><b>la source de bruit</b> ({@code mnu_initrnd}) — dix-huit mille quatre cent trente-deux
 *       octets produits par un registre à décalage dont la graine est {@code 'TBL!'} et qui
 *       passe par une table de PARITÉ. C'est la pièce la plus facile à porter de travers, et
 *       tout le feu en dépend ;</li>
 *   <li><b>les trois plans de feu</b> à six instants — zéro, une, deux, trois, huit et
 *       trente-deux frames. Les quatre premiers couvrent le cycle entier des décalages, le
 *       huitième montre un feu établi, le trente-deuxième un feu qui a eu le temps de dériver
 *       s'il devait dériver ;</li>
 *   <li><b>la palette composite</b> ({@code mnu_createpalette}) — deux cent cinquante-six
 *       couleurs, où la teinte d'une flamme dépend AUSSI du fond qu'elle recouvre ;</li>
 *   <li><b>les trois plans de police</b> après impression d'une chaîne connue
 *       ({@code mnu_printxy}), car la police est la SOURCE du feu : une lettre mal posée fait
 *       brûler le mauvais endroit.</li>
 * </ul>
 *
 * <p>Un point n'est pas bit-exact et ne peut pas l'être : {@code mnu_dofire} ajoute au compteur
 * de bruit la position du faisceau vidéo ({@code vhposr}), qu'aucune machine moderne n'a. Le
 * portage lui substitue {@code 0xA5 + (main_counter & 0xFF)}, et la conversion doit substituer
 * LA MÊME CHOSE. Ce relevé le fige : c'est sa raison d'être autant que le reste.
 */
public final class MenuFxExport {

    /** Largeur d'une ligne de plan, en octets. */
    private static final int ROWSIZE = 40;

    /** Taille d'un plan de bits. */
    private static final int PLANESIZE = ROWSIZE * 256;

    /** La source de bruit : {@code 40*256 + 8192} octets. */
    private static final int BRUIT = ROWSIZE * 256 + 8192;

    /** Les instants releves. */
    private static final int[] INSTANTS = {0, 1, 2, 3, 8, 32};

    /** La chaine imprimee pour eprouver la police : des majuscules, des chiffres, des signes. */
    private static final String TEXTE = "PLAY GAME 0123456789 ()[]<>.,:;!?-+*/ ABCXYZ";

    private MenuFxExport() {
    }

    public static void extract(Path out) throws Exception {
        int morescreen = ab3d2.bss.MenunbBss.mnu_morescreen;
        int screen = ab3d2.bss.MenunbBss.mnu_screen;
        int palette = ab3d2.bss.VidBss.mnu_palette;

        // mnu_init : la source de bruit, la palette, le fond double, et les plans de feu a zero.
        MenuC.mnu_init();

        Base64.Encoder encodeur = Base64.getEncoder();
        Map<String, Object> racine = new LinkedHashMap<>();

        racine.put("noise", encodeur.encodeToString(
                lire(morescreen + 6 * PLANESIZE, BRUIT)));
        racine.put("background", encodeur.encodeToString(lire(screen, 4 * PLANESIZE)));

        List<Long> couleurs = new ArrayList<>(256);
        for (int c = 0; c < 256; c++) {
            couleurs.add(Mem.l(palette + c * 4) & 0xFFFFFFFFL);
        }
        racine.put("palette", couleurs);

        // La POLICE : on imprime une chaine connue et l'on releve les trois plans.
        MenuNb.mnu_cls();
        Mem.wl(ab3d2.data.MenunbData.mnu_printdelay, 0);   // pas d'attente machine a ecrire
        int chaine = Mem.alloc(TEXTE.length() + 1);
        for (int i = 0; i < TEXTE.length(); i++) {
            Mem.wb(chaine + i, TEXTE.charAt(i));
        }
        Mem.wb(chaine + TEXTE.length(), 0);
        MenuNb.mnu_printxy(chaine, 2, 40);
        racine.put("textX", 2);
        racine.put("textY", 40);
        racine.put("text", TEXTE);
        racine.put("fontPlanes", encodeur.encodeToString(
                lire(morescreen + 3 * PLANESIZE, 3 * PLANESIZE)));

        // Le FEU : on part de l'etat qu'on vient d'ecrire et l'on compte les frames.
        List<Object> instants = new ArrayList<>();
        int courant = 0;
        for (int cible : INSTANTS) {
            while (courant < cible) {
                frame();
                courant++;
            }
            Map<String, Object> pas = new LinkedHashMap<>();
            pas.put("frame", cible);
            pas.put("counter", Mem.l(ab3d2.data.MenunbData.main_counter));
            pas.put("screenpos", Mem.uw(ab3d2.data.MenunbData.mnu_screenpos));
            pas.put("firePlanes", encodeur.encodeToString(lire(morescreen, 3 * PLANESIZE)));
            instants.add(pas);
        }
        racine.put("steps", instants);

        Files.createDirectories(out);
        Path f = out.resolve("menufx.json");
        Files.writeString(f, new GsonBuilder().create().toJson(racine));
        System.out.printf("[menufx] bruit %d o, %d instants, police %d o -> %s%n",
                BRUIT, INSTANTS.length, 3 * PLANESIZE, f);
    }

    /**
     * Une frame du menu, SANS la presentation : {@code mnu_vblint} fait defiler le fond, avance
     * le feu et anime le curseur, et {@code WaitTOF} ne fait rien de plus qui compte ici.
     */
    private static void frame() {
        Mem.wl(ab3d2.data.MenunbData.main_counter,
                Mem.l(ab3d2.data.MenunbData.main_counter) + 1);
        MenuNb.mnu_vblint();
    }

    private static byte[] lire(int at, int n) {
        byte[] out = new byte[n];
        System.arraycopy(Mem.RAM, at, out, 0, n);
        return out;
    }
}
