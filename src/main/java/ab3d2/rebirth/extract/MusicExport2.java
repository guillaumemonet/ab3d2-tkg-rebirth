package ab3d2.rebirth.extract;

import ab3d2.Mem;
import ab3d2.modules.Music;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle du LECTEUR DE MUSIQUE : la routine Protracker, image par image.
 *
 * <p>On charge le module du jeu, on appelle {@code mt_init}, puis {@code mt_music} un millier
 * de fois — vingt secondes de morceau — et on releve apres chaque appel tout ce que le lecteur
 * tient : son compteur, sa vitesse, sa position, et surtout les REGISTRES de la puce, qui sont
 * sa seule sortie.
 *
 * <p>Deux series : celle du JEU, ou une seule voix joue sur le canal zero, et celle de la
 * musique de fin, ou les quatre jouent. La premiere est la seule qui compte pour le jeu, mais
 * la seconde eprouve les trois voix que la premiere laisse dormir.
 */
public final class MusicExport2 {

    /** Combien d'images on joue. */
    private static final int IMAGES = 1000;

    private MusicExport2() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.boot();

        // Le module : le meme pour les seize niveaux.
        Path f = ab3d2.Assets.resolve("tkg2:music/packedtest");
        if (f == null || !Files.isRegularFile(f)) {
            throw new IllegalStateException("module introuvable");
        }
        byte[] module = Files.readAllBytes(f);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("frames", IMAGES);
        root.put("moduleLength", module.length);
        root.put("single", serie(module, false));
        root.put("all", serie(module, true));

        // Le module du jeu ne tient qu'une note et deux fondus : il n'eprouverait ni l'arpege,
        // ni les glissandos, ni le vibrato, ni les ruptures. On en FABRIQUE donc, un par
        // famille d'effets, et on les joue aussi.
        List<Object> faits = new ArrayList<>();
        for (byte[] m : modulesDeTest()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("module", Base64.getEncoder().encodeToString(m));
            e.put("frames", ESSAI);
            e.put("single", serie(m, false, ESSAI));
            e.put("all", serie(m, true, ESSAI));
            faits.add(e);
        }
        root.put("made", faits);

        Files.createDirectories(outRoot);
        Path out = outRoot.resolve("music.json");
        Files.writeString(out, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[music] module de %d octets, %d images x 2 series -> %s%n",
                module.length, IMAGES, out);
    }

    /** Combien d'images on joue sur un module fabrique. */
    private static final int ESSAI = 400;

    /**
     * Les modules FABRIQUES, un par famille d'effets.
     *
     * <p>Chacun porte un motif dont chaque ligne pose un effet different, et des echantillons
     * dont l'un boucle, l'autre non, et le troisieme est vide. Ce ne sont pas des morceaux :
     * ce sont des questions posees au lecteur.
     */
    private static List<byte[]> modulesDeTest() {
        List<byte[]> out = new ArrayList<>();
        // Les DIX-HUIT premieres periodes de la table, et rien au-dela.
        //
        // L'arpege lit la table a « rang de la note PLUS l'ecart de demi-tons », sans jamais
        // verifier qu'il y reste : une note haute et un grand ecart lui font lire ce qui SUIT
        // la table, c'est-a-dire les variables du lecteur lui-meme. Ce que l'original y trouve
        // n'est pas defini par le jeu, et aucun portage ne peut l'honorer. On reste donc dans
        // le domaine ou la question a une reponse.
        int[] notes = {0x358, 0x328, 0x2FA, 0x2D0, 0x2A6, 0x280, 0x25C, 0x23A, 0x21A,
            0x1FC, 0x1E0, 0x1C5, 0x1AC, 0x194, 0x17D, 0x168, 0x153, 0x140};

        // 1. ARPEGE : une note par ligne, et tous les ecarts de demi-tons.
        int[][] arpege = new int[64][];
        int[] params = {0x00, 0x37, 0x4C, 0xC0, 0x0C, 0x12, 0xCB, 0x99};
        for (int i = 0; i < 64; i++) {
            arpege[i] = new int[] {1, notes[i % notes.length], 0, params[i % params.length]};
        }
        out.add(build(new int[] {0}, arpege, false, 0));

        // 2. GLISSANDOS : monter, descendre, et viser une note.
        int[][] gliss = new int[64][];
        for (int i = 0; i < 64; i++) {
            gliss[i] = switch (i % 8) {
                case 0 -> new int[] {1, notes[3], 0, 0};
                case 1 -> new int[] {0, 0, 1, 0x10};
                case 2 -> new int[] {0, 0, 1, 0x7F};
                case 3 -> new int[] {0, 0, 2, 0x10};
                case 4 -> new int[] {0, 0, 2, 0x7F};
                case 5 -> new int[] {1, notes[10], 3, 0x08};
                case 6 -> new int[] {0, 0, 3, 0};
                default -> new int[] {1, notes[0], 3, 0x40};
            };
        }
        out.add(build(new int[] {0}, gliss, false, 0));

        // 3. VIBRATO : toutes les profondeurs et toutes les vitesses.
        int[][] vib = new int[64][];
        for (int i = 0; i < 64; i++) {
            vib[i] = i % 4 == 0
                    ? new int[] {2, notes[i % notes.length], 4, (i * 17) & 0xFF}
                    : new int[] {0, 0, 4, (i * 29) & 0xFF};
        }
        out.add(build(new int[] {0}, vib, false, 0));

        // 4. VOLUMES : fondus dans les deux sens, et poses jusqu'au-dela du plafond.
        int[][] vol = new int[64][];
        for (int i = 0; i < 64; i++) {
            vol[i] = switch (i % 6) {
                case 0 -> new int[] {1, notes[5], 0xC, 0x20};
                case 1 -> new int[] {0, 0, 0xA, 0x50};
                case 2 -> new int[] {0, 0, 0xA, 0x05};
                case 3 -> new int[] {0, 0, 0xC, 0x40};
                case 4 -> new int[] {0, 0, 0xC, 0x7F};
                default -> new int[] {0, 0, 0xC, 0xFF};
            };
        }
        out.add(build(new int[] {0}, vol, false, 0));

        // 5. VITESSE ET RUPTURES : la vitesse jusqu'au-dela du plafond, le saut de ligne et le
        // saut de position.
        int[][] tempo = new int[64][];
        for (int i = 0; i < 64; i++) {
            tempo[i] = switch (i % 8) {
                case 0 -> new int[] {3, notes[8], 0xF, 1};
                case 1 -> new int[] {0, 0, 0xF, 6};
                case 2 -> new int[] {0, 0, 0xF, 0x1F};
                case 3 -> new int[] {0, 0, 0xF, 0x20};
                case 4 -> new int[] {0, 0, 0xF, 0xFF};
                case 5 -> new int[] {0, 0, 0xF, 0};
                case 6 -> new int[] {0, 0, 0xD, 0};
                default -> new int[] {0, 0, 0xB, 1};
            };
        }
        out.add(build(new int[] {0, 1, 2, 1}, tempo, false, 0));

        // 6. REGLAGE FIN non nul, et un glissando qui tombe JUSTE : le volume d'un
        // echantillon est lu sur un MOT, donc le reglage fin arrive dans ses bits hauts, et
        // le glissando s'arrete a l'egalite exacte.
        int[][] fin = new int[64][];
        for (int i = 0; i < 64; i++) {
            fin[i] = switch (i % 4) {
                case 0 -> new int[] {1, notes[4], 0, 0};
                case 1 -> new int[] {2, notes[6], 3, 1};
                case 2 -> new int[] {0, 0, 3, 0};
                default -> new int[] {3, notes[2], 3, 1};
            };
        }
        out.add(build(new int[] {0}, fin, false, 7));

        // 7. LES SAUTS DE POSITION au-dela de cent vingt-sept, et la RUPTURE sur deux voix de
        // la meme ligne : elle s'INVERSE, donc deux ruptures n'en font aucune.
        int[][] sauts = new int[64][];
        for (int i = 0; i < 64; i++) {
            sauts[i] = switch (i % 6) {
                case 0 -> new int[] {1, notes[1], 0xB, 0x80};
                case 1 -> new int[] {0, 0, 0xB, 0xFF};
                case 2 -> new int[] {0, 0, 0xD, 0};
                case 3 -> new int[] {1, notes[9], 0xD, 0};
                case 4 -> new int[] {0, 0, 0xB, 0x7F};
                default -> new int[] {0, 0, 0, 0};
            };
        }
        out.add(build(new int[] {0, 1}, sauts, true, 0));

        // 8. LE GLISSANDO QUI TOMBE JUSTE. La cible est a quelques pas seulement, et le pas
        // vaut un : la periode l'atteint donc EXACTEMENT, et c'est la seule facon de voir si
        // l'arret se fait a l'egalite ou seulement au-dela.
        int[][] juste = new int[64][];
        for (int i = 0; i < 64; i++) {
            juste[i] = switch (i % 4) {
                case 0 -> new int[] {1, 0x2A6, 0, 0};
                case 1 -> new int[] {1, 0x2A2, 3, 1};  // quatre pas vers le BAS
                case 2 -> new int[] {1, 0x2A6, 0, 0};
                default -> new int[] {1, 0x2AA, 3, 1}; // quatre pas vers le HAUT
            };
        }
        out.add(build(new int[] {0}, juste, false, 0));

        // 9. LA RUPTURE SUR LES QUATRE VOIX d'une meme ligne. Elle s'INVERSE a chaque fois :
        // quatre ruptures n'en font donc aucune, et deux non plus.
        int[][] doubles = new int[64][];
        for (int i = 0; i < 64; i++) {
            doubles[i] = new int[] {1, notes[i % notes.length], 0xD, 0};
        }
        out.add(build(new int[] {0, 1}, doubles, true, 0));

        // 10. LES QUATRE VOIX, chacune son echantillon et son effet.
        int[][] quatre = new int[64][];
        for (int i = 0; i < 64; i++) {
            // Les effets UN a QUATRE : pas d'arpege ici, car les glissandos deplacent la
            // periode et le rang qu'il irait lire ne serait plus borne.
            quatre[i] = new int[] {1 + i % 3, notes[i % notes.length], 1 + i % 4,
                (i * 37) & 0xFF};
        }
        out.add(build(new int[] {0, 1}, quatre, true, 0));

        return out;
    }

    /**
     * Fabrique un module : trois echantillons, une table d'ordre, et des motifs.
     *
     * <p>Le premier echantillon BOUCLE, le second non, le troisieme est VIDE — le lecteur doit
     * donner a celui-la l'echantillon nul, et ne jamais le lire.
     */
    private static byte[] build(int[] ordre, int[][] lignes, boolean quatreVoix,
            int reglageFin) {
        int motifs = 0;
        for (int o : ordre) {
            motifs = Math.max(motifs, o + 1);
        }
        int[] longueurs = {600, 400, 0};               // en MOTS
        int[] reprises = {100, 0, 0};
        int[] reprLen = {200, 1, 0};
        int[] volumes = {64, 48, 0};
        int taille = 0x43C + motifs * 1024;
        for (int l : longueurs) {
            taille += l * 2;
        }
        byte[] m = new byte[taille];
        for (int sp = 0; sp < 3; sp++) {
            int at = 20 + sp * 0x1E;
            m[at + 22] = (byte) (longueurs[sp] >> 8);
            m[at + 23] = (byte) longueurs[sp];
            m[at + 24] = (byte) reglageFin;            // reglage fin
            m[at + 25] = (byte) volumes[sp];
            m[at + 26] = (byte) (reprises[sp] >> 8);
            m[at + 27] = (byte) reprises[sp];
            m[at + 28] = (byte) (reprLen[sp] >> 8);
            m[at + 29] = (byte) reprLen[sp];
        }
        m[0x3B6] = (byte) ordre.length;
        m[0x3B7] = 0;
        for (int i = 0; i < ordre.length; i++) {
            m[0x3B8 + i] = (byte) ordre[i];
        }
        m[0x438] = (byte) 0x4D;
        m[0x439] = (byte) 0x2E;
        m[0x43A] = (byte) 0x4B;
        m[0x43B] = (byte) 0x2E;
        for (int p = 0; p < motifs; p++) {
            for (int l = 0; l < 64; l++) {
                for (int v = 0; v < 4; v++) {
                    if (!quatreVoix && v != 0) {
                        continue;
                    }
                    int[] c = lignes[(l + p * 7 + (quatreVoix ? v * 11 : 0)) % lignes.length];
                    int at = 0x43C + p * 1024 + l * 16 + v * 4;
                    int ech = c[0];
                    int per = c[1];
                    m[at] = (byte) ((ech & 0xF0) | ((per >> 8) & 0xF));
                    m[at + 1] = (byte) per;
                    m[at + 2] = (byte) (((ech & 0xF) << 4) | (c[2] & 0xF));
                    m[at + 3] = (byte) c[3];
                }
            }
        }
        // Les ondes : de quoi ne pas lire du silence.
        int at = 0x43C + motifs * 1024;
        for (int sp = 0; sp < 3; sp++) {
            for (int k = 0; k < longueurs[sp] * 2; k++) {
                m[at + k] = (byte) ((k * 31 + sp * 7) & 0xFF);
            }
            at += longueurs[sp] * 2;
        }
        return m;
    }

    /** Une serie : une ou quatre voix. */
    private static List<Object> serie(byte[] module, boolean toutes) {
        return serie(module, toutes, IMAGES);
    }

    private static List<Object> serie(byte[] module, boolean toutes, int images) {
        // Le module est pose dans la memoire plate, et le lecteur ECRIT dedans : on le repose
        // a neuf pour chaque serie.
        int at = Mem.alloc(module.length + 8);
        System.arraycopy(module, 0, Mem.RAM, at, module.length);
        Mem.wl(Music.mt_data, at);
        Mem.wb(ab3d2.HiresData.UseAllChannels, toutes ? 0xFF : 0);
        for (int ch = 0; ch < 4; ch++) {
            ab3d2.host.CustomChips.audLC[ch] = 0;
            ab3d2.host.CustomChips.audLEN[ch] = 0;
            ab3d2.host.CustomChips.audPER[ch] = 0;
            ab3d2.host.CustomChips.audVOL[ch] = 0;
            ab3d2.host.CustomChips.dmaTrig[ch] = false;
            ab3d2.host.CustomChips.dmaLC[ch] = 0;
            ab3d2.host.CustomChips.dmaLEN[ch] = 0;
        }
        ab3d2.host.CustomChips.dmacon = 0;
        // Les quatre enregistrements de voix sont remis a ZERO. Le jeu ne le fait pas —
        // mt_init n'y touche pas, et ils gardent ce que le morceau precedent y avait laisse —
        // mais entre deux series d'un RELEVE, ce qui reste n'est que du bruit d'allocation :
        // des adresses d'un module qui n'est plus la. On part donc de propre.
        for (int voix : new int[] {Music.mt_voice1, Music.mt_voice2, Music.mt_voice3,
            Music.mt_voice4}) {
            for (int k = 0; k < 0x14; k++) {
                Mem.wb(voix + k, 0);
            }
            for (int k = 0x16; k < 0x1C; k++) {
                Mem.wb(voix + k, 0);
            }
        }
        // Ni mt_break ni reachedend ne sont remis a zero par mt_init : entre deux series d'un
        // releve, ce qui reste n'est que le souvenir du morceau precedent.
        Mem.wb(Music.mt_break, 0);
        Mem.wb(Music.reachedend, 0);
        Music.mt_init();

        List<Object> pas = new ArrayList<>();
        Map<String, Object> zero = etat(at, -1);       // l'etat juste apres mt_init
        // Une signature des donnees : mt_init ECRIT dedans (il met a zero les deux premiers
        // mots de chaque echantillon), et rien d'autre ne le montrerait.
        long somme = 0;
        for (int k = 0; k < module.length; k++) {
            somme = somme * 131 + Mem.b(at + k);
        }
        zero.put("moduleHash", somme);
        pas.add(zero);
        for (int i = 0; i < images; i++) {
            Music.mt_music();
            pas.add(etat(at, i));
        }
        return pas;
    }

    /**
     * Une adresse, rendue comparable : relative au module, ou le code convenu de l'echantillon
     * NUL — celui que le lecteur donne aux echantillons vides, et qui ne vit pas dans le
     * module.
     */
    private static int adresse(int a, int base) {
        if (a == 0) {
            return -1;
        }
        if (a == ab3d2.HiresData.nullsample) {
            return -2;
        }
        return a - base;
    }

    /** Tout ce que le lecteur tient, a cet instant. */
    private static Map<String, Object> etat(int base, int image) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("frame", image);
        e.put("speed", Mem.ub(Music.mt_speed));
        e.put("songpos", Mem.ub(Music.mt_songpos));
        e.put("pattpos", Mem.uw(Music.mt_pattpos));
        e.put("counter", Mem.ub(Music.mt_counter));
        e.put("brk", Mem.ub(Music.mt_break));
        e.put("reachedEnd", Mem.ub(Music.reachedend));
        e.put("dmacon", ab3d2.host.CustomChips.dmacon);

        List<Object> canaux = new ArrayList<>();
        for (int ch = 0; ch < 4; ch++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("lc", adresse(ab3d2.host.CustomChips.audLC[ch], base));
            c.put("len", ab3d2.host.CustomChips.audLEN[ch]);
            c.put("per", ab3d2.host.CustomChips.audPER[ch]);
            c.put("vol", ab3d2.host.CustomChips.audVOL[ch]);
            // Ce que la puce a COPIE au demarrage du canal : c'est lui qui joue, pas les
            // registres, et c'est sur cette copie que repose la ruse du point de boucle.
            c.put("segLc", adresse(ab3d2.host.CustomChips.dmaLC[ch], base));
            c.put("segLen", ab3d2.host.CustomChips.dmaLEN[ch]);
            canaux.add(c);
        }
        e.put("channels", canaux);

        int[] voix = {Music.mt_voice1, Music.mt_voice2, Music.mt_voice3, Music.mt_voice4};
        List<Object> vs = new ArrayList<>();
        for (int at : voix) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("note", Mem.uw(at));
            v.put("cmd", Mem.uw(at + 2));
            v.put("start", adresse(Mem.l(at + 4), base));
            v.put("length", Mem.uw(at + 8));
            v.put("loopStart", adresse(Mem.l(at + 0xA), base));
            v.put("loopLength", Mem.uw(at + 0xE));
            v.put("period", Mem.uw(at + 0x10));
            v.put("volume", Mem.uw(at + 0x12));
            v.put("portDir", Mem.ub(at + 0x16));
            v.put("portSpeed", Mem.ub(at + 0x17));
            v.put("portTarget", Mem.uw(at + 0x18));
            v.put("vibParam", Mem.ub(at + 0x1A));
            v.put("vibPos", Mem.ub(at + 0x1B));
            vs.add(v);
        }
        e.put("voices", vs);
        return e;
    }
}
