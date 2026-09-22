package ab3d2.rebirth.extract;

import ab3d2.Mem;
import ab3d2.bss.Bss;
import ab3d2.data.DataSections;
import ab3d2.modules.FileIo;

import static ab3d2.HiresData.GLF_DatabaseName_vb;
import static ab3d2.HiresData.GLF_DatabasePtr_l;

/**
 * Boot headless du portage fidèle, utilisé comme LECTEUR autoritatif des formats d'origine.
 *
 * <p>Aucune fenêtre ni audio : {@link Bss#init} + {@link DataSections#init} suffisent à monter
 * la mémoire plate et les données statiques (dont la palette 256 couleurs, chargée par incbin
 * « 256pal »). On charge ensuite la base GLF (noms de textures, défs d'objets/aliens, anims,
 * modèles vectoriels). Chaque asset est ensuite lu depuis {@code Mem} et sérialisé en format
 * moderne par les extracteurs ({@link Textures}, …).
 */
public final class PortReader {

    private static boolean booted;
    private static boolean assetsLoaded;

    private PortReader() {
    }

    /**
     * Boot + chargement COMPLET des assets globaux (sons, textures murales, sols + texture-maps +
     * palette de shade, objets/sprites/modèles) via le pipeline du portage. Nécessaire aux
     * extracteurs qui lisent les pointeurs {@code Draw_*} peuplés (sols, sprites, modèles).
     */
    public static void bootWithAssets() {
        boot();
        if (assetsLoaded) {
            return;
        }
        try {
            ab3d2.modules.FileIo.IO_InitQueue();
            ab3d2.modules.Res.Res_LoadSoundFx();
            ab3d2.modules.Res.Res_LoadWallTextures();
            ab3d2.modules.Res.Res_LoadFloorsAndTextures();
            ab3d2.modules.Res.Res_LoadObjects();
            ab3d2.modules.FileIo.IO_FlushQueue();
            ab3d2.modules.Res.Res_PatchSoundFx();
        } catch (Throwable t) {
            System.out.println("[rebirth] chargement assets partiel : " + t);
        }
        assetsLoaded = true;
    }

    /** Monte la mémoire + les données statiques et charge la base GLF (idempotent). */
    public static void boot() {
        if (booted) {
            return;
        }
        Bss.init();
        DataSections.init();                                 // palette (256pal) + sections statiques
        long r = FileIo.IO_LoadFile(GLF_DatabaseName_vb);    // base GLF (test.lnk)
        Mem.wl(GLF_DatabasePtr_l, FileIo.addr(r));
        booted = true;
    }

    /** Adresse mémoire de la base GLF chargée. */
    public static int glf() {
        return Mem.l(GLF_DatabasePtr_l);
    }

    /** Palette 256 couleurs (draw_Palette_vw, 3 mots/couleur) → ARGB opaque. */
    public static int[] paletteRGB() {
        int pal = ab3d2.data.DrawData.draw_Palette_vw;
        int[] rgb = new int[256];
        for (int i = 0; i < 256; i++) {
            int rr = Mem.uw(pal + (i * 3) * 2) & 0xFF;
            int gg = Mem.uw(pal + (i * 3 + 1) * 2) & 0xFF;
            int bb = Mem.uw(pal + (i * 3 + 2) * 2) & 0xFF;
            rgb[i] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        }
        return rgb;
    }

    /**
     * Lit une chaîne à champ de longueur FIXE : au plus {@code maxLen} octets, s'arrête au premier
     * 0, espaces/nuls de fin retirés. Nécessaire pour les champs paddés d'espaces SANS terminateur
     * (ex. noms de niveau 40 o) que {@link Mem#cstr} lirait au-delà jusqu'au prochain null.
     */
    public static String fixedStr(int addr, int maxLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLen; i++) {
            int c = Mem.ub(addr + i);
            if (c == 0) {
                break;
            }
            sb.append((char) c);
        }
        return sb.toString().trim();
    }

    /** Nom d'origine sans chemin ni extension (« walls/STONEWALL.256wad » → « STONEWALL »). */
    public static String baseName(String s) {
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        s = s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }
}
