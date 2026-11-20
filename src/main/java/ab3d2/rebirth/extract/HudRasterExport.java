package ab3d2.rebirth.extract;

import ab3d2.Mem;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle du TABLEAU DE BORD : la bordure, ses chiffres, le reticule et le texte des messages.
 *
 * <p>Tout cela se joue dans {@code c/draw.c}, et rien n'en etait eprouve. Le releve prend la
 * voie RTG — celle de la cible, plein ecran 320x256 — et la suit de bout en bout :
 *
 * <ul>
 *   <li>{@code Draw_Init} depaquette la bordure (huit plans de bits) en octets d'ecran et
 *       remet a plat les cinq jeux de chiffres, qui sont ENTRELACES par dix dans le fichier ;
 *   </li>
 *   <li>{@code Draw_UpdateBorder_RTG} pose les munitions, l'energie et les dix cases d'armes.
 *       On releve TOUTE la bande visible, et pas seulement les trois rectangles ecrits : une
 *       ecriture qui deborde se verrait ;</li>
 *   <li>{@code Draw_Crosshair} ({@code modules/draw.s}) pose huit pixels autour du milieu ;</li>
 *   <li>{@code Draw_ChunkyTextProp} ecrit une ligne de message en police proportionnelle.</li>
 * </ul>
 *
 * <p>Deux series de cas EN SUITE sont ajoutees. Le jeu d'origine ne redessine un chiffre que
 * s'il a change, parce que son image d'ecran persiste d'une image a l'autre ; le portage pur,
 * lui, repart d'un tampon efface et redessine tout. Les cas « en suite » posent un premier
 * etat, puis un second, et ne relevent que le second : si la paresse du jeu changeait le
 * resultat, ils ne coincideraient pas avec le dessin complet du meme etat.
 */
public final class HudRasterExport {

    private static final int W = 320;
    private static final int H = 256;

    /** La premiere ligne que la vue ne recouvre pas : C2P_FS_HEIGHT (screen.h). */
    private static final int BAR_TOP = 232;

    /** PLR_SINGLE (hires.s:62) : un seul joueur, donc les compteurs du premier. */
    private static final int PLR_SINGLE = 'n';

    private static final int[] AMMOS = {0, 1, 5, 9, 10, 11, 99, 100, 101, 999, 1000, 1001, 4660};
    private static final int[] ENERGIES = {0, 1, 9, 10, 200, 999, 1000, 32767};
    private static final int[] MASKS = {0, 0x001, 0x002, 0x155, 0x2AA, 0x3FF, 0x201, 0x0F0};

    /** Les quatre encres des etiquettes de message (msg_TagPens, c/message.c:18). */
    private static final int[] PENS = {255, 254, 125, 252};

    /** Un caractere de chaque bord : commande, 127, 128, 159, 160, 161, 255, accents. */
    private static final String BORDS = new String(new char[] {
        1, 0x1F, 'A', 0x7F, 0x80, 0x9F, 0xA0, 0xA1, 0xFF, ' ', 0xE9, 0xC0,
    });

    private static final String[] TEXTES = {
        "Shotgun",
        "I can't carry any more of these just now.",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "abcdefghijklmnopqrstuvwxyz",
        "0123456789 !\"#%&'()*+,-./:;<=>?@[]^_{|}~",
        "  deux  espaces  et  fin  ",
        // Les BORDS du jeu de caracteres : les codes de commande, le cent vingt-septieme,
        // et la plage haute de l'Amiga ou commencent les accents. On l'ecrit par codes,
        // parce que les echappements d'unicode de Java se defont AVANT la compilation.
        BORDS,
    };

    private HudRasterExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.boot();

        // L'ecran de la cible : plein ecran, 320x256, sans bande noire.
        Mem.ww(ab3d2.bss.VidBss.Vid_ScreenWidth, W);
        Mem.ww(ab3d2.bss.VidBss.Vid_ScreenHeight, H);
        Mem.wb(ab3d2.bss.VidBss.Vid_FullScreen_b, 0xFF);
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, PLR_SINGLE);

        if (ab3d2.c.DrawC.Draw_Init() == 0) {
            throw new IllegalStateException("Draw_Init a echoue");
        }

        List<Object> cases = borderCases();
        List<Object> reticules = crosshairCases();
        List<Object> textes = textCases();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("width", W);
        root.put("height", H);
        root.put("barTop", BAR_TOP);
        root.put("border", b64(ab3d2.c.DrawC.draw_Border, W * H));
        root.put("digits", digits());
        root.put("cases", cases);
        root.put("crosshair", reticules);
        root.put("text", textes);
        root.put("splits", splitCases());

        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("hudraster.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[hudraster] bordure %dx%d, %d etats de bandeau, %d reticules,"
                + " %d lignes de texte -> %s%n", W, H,
                cases.size(), reticules.size(), textes.size(), f);
    }

    /** Les cinq jeux de chiffres, tels que Draw_Init les a remis a plat. */
    private static Map<String, Object> digits() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("good", b64(ab3d2.c.DrawC.draw_BorderDigitsGood, 8 * 7 * 10));
        d.put("warn", b64(ab3d2.c.DrawC.draw_BorderDigitsWarn, 8 * 7 * 10));
        d.put("item", b64(ab3d2.c.DrawC.draw_BorderDigitsItem, 8 * 5 * 10));
        d.put("itemFound", b64(ab3d2.c.DrawC.draw_BorderDigitsItemFound, 8 * 5 * 10));
        d.put("itemSelected", b64(ab3d2.c.DrawC.draw_BorderDigitsItemSelected, 8 * 5 * 10));
        return d;
    }

    private static List<Object> borderCases() {
        List<Object> cas = new ArrayList<>();
        // Les compteurs : les valeurs de part et d'autre du seuil d'alerte et de la limite
        // d'affichage, croisees.
        for (int ammo : AMMOS) {
            for (int energy : ENERGIES) {
                cas.add(unCas(ammo, energy, 0x3FF, 0, null));
            }
        }
        // Les cases d'armes : chaque arme choisie, contre plusieurs jeux d'armes tenues.
        for (int selected = 0; selected < 10; selected++) {
            for (int mask : MASKS) {
                cas.add(unCas(50, 100, mask, selected, null));
            }
        }
        // En suite : un premier etat, puis un second. Le releve est celui du SECOND, et il doit
        // valoir son dessin complet — c'est ce qui autorise le portage a tout redessiner.
        int[][] suites = {
            {0, 0, 0x001, 0, 999, 999, 0x3FF, 9},
            {999, 999, 0x3FF, 9, 0, 0, 0x001, 0},
            {10, 10, 0x155, 3, 10, 10, 0x155, 3},
            {9, 9, 0x002, 1, 10, 9, 0x002, 1},
            {100, 50, 0x0F0, 4, 100, 51, 0x0F0, 4},
            {7, 7, 0x201, 0, 7, 7, 0x201, 5},
        };
        for (int[] s : suites) {
            cas.add(unCas(s[4], s[5], s[6], s[7], new int[] {s[0], s[1], s[2], s[3]}));
        }
        return cas;
    }

    /**
     * Un etat du bandeau. On repart de la bordure NEUVE et des compteurs reamorces, on joue
     * l'etat precedent s'il y en a un, puis l'etat releve.
     */
    private static Map<String, Object> unCas(int ammo, int energy, int mask, int selected,
            int[] avant) {
        Mem.copy(ab3d2.c.DrawC.draw_Border, ab3d2.c.DrawC.Vid_HostBitmap, W * H);
        ab3d2.c.DrawC.draw_ResetHUDCounters();
        if (avant != null) {
            poser(avant[0], avant[1], avant[2], avant[3]);
            ab3d2.c.DrawC.Draw_UpdateBorder_RTG(ab3d2.c.DrawC.Vid_HostBitmap, W);
        }
        poser(ammo, energy, mask, selected);
        ab3d2.c.DrawC.Draw_UpdateBorder_RTG(ab3d2.c.DrawC.Vid_HostBitmap, W);

        Map<String, Object> un = new LinkedHashMap<>();
        un.put("ammo", ammo);
        un.put("energy", energy);
        un.put("slots", mask);
        un.put("selected", selected);
        if (avant != null) {
            un.put("prevAmmo", avant[0]);
            un.put("prevEnergy", avant[1]);
            un.put("prevSlots", avant[2]);
            un.put("prevSelected", avant[3]);
        }
        un.put("bar", b64(ab3d2.c.DrawC.Vid_HostBitmap + BAR_TOP * W, (H - BAR_TOP) * W));
        return un;
    }

    private static void poser(int ammo, int energy, int mask, int selected) {
        Mem.ww(ab3d2.bss.DrawBss.draw_DisplayAmmoCount_w, ammo & 0xFFFF);
        Mem.ww(ab3d2.bss.DrawBss.draw_DisplayEnergyCount_w, energy & 0xFFFF);
        for (int i = 0; i < 10; i++) {
            Mem.ww(ab3d2.bss.PlayerBss.Plr1_Weapons_vb + i * 2,
                    (mask >> i & 1) != 0 ? 0xFF : 0);
        }
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_TmpGunSelected_b, selected);
    }

    /**
     * Le reticule : huit pixels, pour chacune des huit encres et quinze regards verticaux.
     *
     * <p>Le tampon n'est pas efface mais SEME d'un damier dont aucune valeur n'est nulle, et on
     * ne releve que ce qui a change. Deux choses s'en suivent : une ecriture n'importe ou dans
     * l'ecran se verrait, et une encre NULLE posee par erreur aussi — sur un tampon efface,
     * elle ne se distinguerait pas du vide.
     *
     * <p>Les regards verticaux sont choisis pour franchir chaque seuil du calcul : ceux qui
     * donnent un decalage IMPAIR, que l'arrondi doit ramener au pair inferieur, et ceux qui
     * font deborder l'addition de seize bits.
     */
    private static final int[] REGARDS = {0, 9, -9, 40, -40, 46, -46, 64, -64, 100, -100,
        768, -768, 1200, -1200};

    private static List<Object> crosshairCases() {
        List<Object> cas = new ArrayList<>();
        int fast = Mem.l(ab3d2.bss.VidBss.Vid_FastBufferPtr_l);
        Mem.ww(ab3d2.HiresData.Vid_CentreX_w, W / 2);
        Mem.ww(ab3d2.HireswallData.Vid_BottomY_w, 240);     // FS_HEIGHT
        for (int couleur = 0; couleur < 8; couleur++) {
            for (int regard : REGARDS) {
                semer(fast);
                Mem.wb(ab3d2.ControlloopData.Prefs_CrossHairColour_b, couleur);
                Mem.ww(ab3d2.HireswallData.STOPOFFSET, regard);
                ab3d2.modules.Draw.Draw_Crosshair();

                Map<String, Object> un = new LinkedHashMap<>();
                un.put("colour", couleur);
                un.put("stopOffset", regard);
                un.put("written", ecrits(fast));
                cas.add(un);
            }
        }
        return cas;
    }

    /** Le damier seme dans le tampon : jamais nul, et different d'un pixel a l'autre. */
    static int graine(int at) {
        return (at * 7 + (at / W) * 13) % 251 + 1;
    }

    private static void semer(int base) {
        for (int i = 0; i < W * H; i++) {
            Mem.wb(base + i, graine(i));
        }
    }

    /** Tout ce que la routine a ECRIT dans l'ecran entier : rang, puis valeur. */
    private static List<Integer> ecrits(int base) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < W * H; i++) {
            int v = Mem.ub(base + i);
            if (v != graine(i)) {
                out.add(i);
                out.add(v);
            }
        }
        return out;
    }

    /** Une ligne de message, en police proportionnelle. */
    private static List<Object> textCases() {
        List<Object> cas = new ArrayList<>();
        int fast = Mem.l(ab3d2.bss.VidBss.Vid_FastBufferPtr_l);
        for (String texte : TEXTES) {
            // Le zero terminal est EXPLICITE : les routines d'origine s'arretent dessus,
            // et Mem.dcStr ne l'ajoute pas.
            int at = Mem.dcStr(texte + " ");
            // La longueur maximale compte autant que le texte : une ligne TRONQUEE doit
            // reprendre apres le caractere qu'on a lu sans le poser, et non sur lui.
            int[] longueurs = {texte.length(), texte.length() / 2, 1, 0, texte.length() + 5};
            for (int pen : PENS) {
              for (int maxLen : longueurs) {
                for (int x : new int[] {4, 40}) {
                    semer(fast);
                    int y = 4;
                    int reste = ab3d2.c.DrawC.Draw_ChunkyTextProp(fast, W, maxLen,
                            at, x, y, pen);
                    Map<String, Object> un = new LinkedHashMap<>();
                    un.put("text", texte);
                    un.put("pen", pen);
                    un.put("x", x);
                    un.put("y", y);
                    un.put("maxLen", maxLen);
                    un.put("rest", reste == 0 ? -1 : reste - at);
                    un.put("width", ab3d2.c.DrawC.Draw_CalcPropWidth(at, maxLen));
                    un.put("written", ecrits(fast));
                    cas.add(un);
                }
              }
            }
        }
        return cas;
    }

    /**
     * La DECOUPE d'un texte long en lignes ({@code Draw_CalcPropTextSplit}).
     *
     * <p>On rejoue la boucle de {@code Msg_PushLine} : tant qu'il reste du texte, on demande
     * combien de caracteres tiennent, et on note le morceau rendu ainsi que le rang ou le
     * suivant commence. Les largeurs vont de l'ecran entier a quelques pixels, pour que la
     * routine ait a reculer, a sauter des espaces, et a buter sur un mot plus long que la
     * ligne.
     */
    private static List<Object> splitCases() {
        List<Object> cas = new ArrayList<>();
        String[] longs = {
            "One readout in particular occupies my attention. Red numbers spin towards zero"
                + " as my invisible agent of destruction spins towards oblivion.",
            "Unmotdetreslonguesansaucuneespacepourlecouperproprementdutoutniailleurs",
            "   des   espaces   partout   et   au   debut   ",
            "court",
            "",
        };
        for (String texte : longs) {
            int at = Mem.dcStr(texte + "\0");
            for (int largeur : new int[] {304, 160, 80, 40, 16, 7, 1}) {
                int[] curseur = {at};
                int reste = texte.length();
                List<Object> morceaux = new ArrayList<>();
                for (int tour = 0; tour < 24; tour++) {
                    int debut = curseur[0];
                    int tient = ab3d2.c.DrawC.Draw_CalcPropTextSplit(curseur, reste, largeur);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("from", debut - at);
                    m.put("fit", tient);
                    m.put("next", curseur[0] == 0 ? -1 : curseur[0] - at);
                    morceaux.add(m);
                    reste -= tient;
                    if (curseur[0] == 0) {
                        break;
                    }
                }
                Map<String, Object> un = new LinkedHashMap<>();
                un.put("text", texte);
                un.put("width", largeur);
                un.put("pieces", morceaux);
                cas.add(un);
            }
        }
        return cas;
    }

    private static String b64(int at, int len) {
        byte[] out = new byte[len];
        System.arraycopy(Mem.RAM, at, out, 0, len);
        return Base64.getEncoder().encodeToString(out);
    }
}
