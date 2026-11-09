package ab3d2.rebirth.extract;

import ab3d2.HiresData;
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
 * Oracle du MOTEUR AUDIO : le choix d'une voix, et le melange des huit dans les quatre canaux.
 *
 * <p>Trois choses y sont relevees, et chacune a sa difficulte :
 *
 * <ul>
 *   <li>la TABLE DES RAMPES, soixante-cinq volumes de deux cent cinquante-six valeurs,
 *       construite au demarrage du niveau ;</li>
 *   <li>les BRUITAGES eux-memes : leur longueur une fois depaquetes, et une signature de leur
 *       onde. C'est ce qui eprouve le depaqueteur a differences de Fibonacci et l'ecretage qui
 *       le suit ;</li>
 *   <li>une SUITE d'appels : poser un bruit, melanger une trame, recommencer. Apres chaque pas
 *       on releve l'etat des huit cases, des huit voix, et les deux cents octets de chacun des
 *       quatre canaux.</li>
 * </ul>
 *
 * <h2>Pourquoi des echantillons INVENTES</h2>
 *
 * <p>Le melangeur lit par trames de deux cents octets et ne s'arrete qu'APRES avoir depasse la
 * fin : il lit donc jusqu'a cent quatre-vingt-dix-neuf octets au-dela de l'echantillon. Sur
 * l'Amiga ces octets appartiennent au tas et ne valent rien de defini — les comparer n'aurait
 * aucun sens. Le releve pose donc ses propres echantillons, suivis de silence, pour que la
 * derniere trame de chacun soit comparable comme les autres. Les vrais echantillons sont
 * eprouves a part, par leur longueur et leur signature.
 */
public final class PaulaExport {

    private static final int FRAME = 200;
    private static final int PAD = 256;

    /** Combien d'echantillons inventes on pose. */
    private static final int FAUX = 6;

    /** Leurs longueurs, choisies autour d'un multiple de deux cents. */
    private static final int[] LONGUEURS = {200, 201, 400, 199, 1000, 37};

    private PaulaExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        LevelExport.loadLevelForTools(0);              // Game_Begin : construit la table

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("frameBytes", FRAME);
        root.put("table", b64(HiresData.tab, 256 * 65));
        root.put("samples", echantillons());

        int[][] faux = poserFaux();
        root.put("fake", fauxJson(faux));
        root.put("sweep", balayage());
        root.put("steps", suite(faux));

        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("paula.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[paula] table %d octets, %d bruitages, %d pas -> %s%n",
                256 * 65, ((List<?>) root.get("samples")).size(),
                ((List<?>) root.get("steps")).size(), f);
    }

    /** Les vrais bruitages, tels que le chargeur d'origine les a depaquetes. */
    private static List<Object> echantillons() {
        int liste = ab3d2.bss.TablesBss.Aud_SampleList_vl;
        int glf = PortReader.glf();
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < ab3d2.Defs.NUM_SFX; i++) {
            int debut = Mem.l(liste + i * 8);
            int fin = Mem.l(liste + i * 8 + 4);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("file", PortReader.fixedStr(glf + ab3d2.Defs.GLFT_SFXFilenames_l + i * 64, 64));
            int taille = debut == 0 ? 0 : fin - debut;
            e.put("length", taille);
            // Une signature plutot que l'onde entiere : quatre cent mille octets de JSON pour
            // rien, alors qu'une somme et quelques octets suffisent a separer deux ondes.
            long somme = 0;
            for (int k = 0; k < taille; k++) {
                somme = somme * 131 + Mem.b(debut + k);
            }
            e.put("hash", somme);
            e.put("head", taille == 0 ? "" : b64(debut, Math.min(64, taille)));
            e.put("tail", taille == 0 ? "" : b64(debut + Math.max(0, taille - 64),
                    Math.min(64, taille)));
            out.add(e);
        }
        return out;
    }

    /**
     * Pose des echantillons INVENTES dans la table, chacun suivi de silence.
     *
     * @return pour chacun, {debut, fin, longueur}
     */
    private static int[][] poserFaux() {
        int liste = ab3d2.bss.TablesBss.Aud_SampleList_vl;
        int[][] out = new int[FAUX][3];
        for (int i = 0; i < FAUX; i++) {
            int n = LONGUEURS[i];
            int at = Mem.alloc(n + PAD);
            for (int k = 0; k < n; k++) {
                // Une onde qui prend toutes les valeurs d'un octet, et pas la meme d'un
                // echantillon a l'autre.
                Mem.wb(at + k, (k * 37 + i * 101) & 0xFF);
            }
            for (int k = 0; k < PAD; k++) {
                Mem.wb(at + n + k, 0);
            }
            Mem.wl(liste + i * 8, at);
            Mem.wl(liste + i * 8 + 4, at + n);
            out[i] = new int[] {at, at + n, n};
        }
        return out;
    }

    private static List<Object> fauxJson(int[][] faux) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < faux.length; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("length", faux[i][2]);
            e.put("wave", b64(faux[i][0], faux[i][2]));
            out.add(e);
        }
        return out;
    }

    /**
     * Le BALAYAGE de l'arithmetique : l'importance et le partage gauche/droite, seuls.
     *
     * <p>Toutes les cases sont declarees prises, donc rien n'est place et rien ne bouge — on ne
     * releve que ce que le calcul a produit. Les valeurs vont chercher chaque seuil : le zero,
     * le un, le plafond de soixante-quatre, celui de trente-deux mille sept cent soixante-sept
     * (que le volume atteint des cinq cent douze, puisqu'il est multiplie par soixante-quatre),
     * et les bords du mot.
     */
    private static List<Object> balayage() {
        int[] xs = {0, 1, -1, 3, -3, 7, -7, 15, -15, 63, -63, 100, -100, 500, -500,
            2000, -2000, 10000, -10000, 32767, -32768};
        int[] zs = {0, 1, -1, 11, -11, 255, -255, 3000, -3000, 20000, -20000, 32767};
        int[] vols = {0, 1, 2, 9, 10, 63, 64, 65, 100, 511, 512, 513, 1000, 20000, 32767};
        List<Object> out = new ArrayList<>();
        for (int x : xs) {
            for (int z : zs) {
                for (int v : vols) {
                    remise(0xFF, true);                // tout est pris : rien ne sera place
                    Mem.ww(HiresData.Aud_SampleNum_w, 0);
                    Mem.ww(HiresData.Aud_NoiseX_w, x);
                    Mem.ww(HiresData.Aud_NoiseZ_w, z);
                    Mem.ww(HiresData.Aud_NoiseVol_w, v);
                    Mem.ww(HiresData.IDNUM, 1);
                    Mem.wb(HiresData.notifplaying, 0);
                    ab3d2.Hires.MakeSomeNoise();
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("x", x);
                    e.put("z", z);
                    e.put("vol", v);
                    e.put("noiseloud", Mem.w(HiresData.noiseloud));
                    e.put("leftOffset", Mem.l(HiresData.LEFTOFFSET));
                    e.put("rightOffset", Mem.l(HiresData.RIGHTOFFSET));
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** La suite d'appels : poser des bruits, melanger, recommencer. */
    private static List<Object> suite(int[][] faux) {
        List<Object> pas = new ArrayList<>();

        // Chaque ligne est un pas : {quoi, a, b, c, d, e, f}
        //   quoi 0 = remise a zero (a = masque des cases prises par la musique, b = stereo)
        //   quoi 1 = poser un bruit  (a=echantillon b=x c=z d=volume e=identifiant f=notIfPlaying)
        //   quoi 2 = melanger une trame
        int[][] script = {
            {0, 0b00000101, 1, 0, 0, 0, 0},            // l'etat du jeu : la musique tient 0 et 2 a gauche
            {1, 0, 0, 0, 100, 1, 0},                   // un bruit a l'oreille : distance nulle
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {1, 1, 500, 0, 80, 2, 0},                  // franchement a droite
            {1, 2, -500, 0, 80, 3, 0},                 // franchement a gauche
            {2, 0, 0, 0, 0, 0, 0},
            {1, 3, 100, 2000, 64, 4, 0},               // loin devant
            {1, 4, -3000, -3000, 127, 5, 0},           // loin en diagonale
            {2, 0, 0, 0, 0, 0, 0},
            {1, 5, 0, 1, 1, 6, 0},                     // tres faible : ne doit chasser personne
            {2, 0, 0, 0, 0, 0, 0},
            {1, 0, 0, 0, 100, 1, 1},                   // meme identifiant, et « pas si ca joue »
            {2, 0, 0, 0, 0, 0, 0},
            {1, 1, 32767, 32767, 255, 7, 0},           // les bords du mot
            {1, 2, -32768, -32768, 255, 8, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},                     // jusqu'a epuisement des echantillons
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},

            {0, 0, 1, 0, 0, 0, 0},                     // aucune case prise : les quatre canaux
            {1, 0, 10, 10, 64, 100, 0},
            {1, 1, -10, 10, 64, 101, 0},
            {1, 2, 10, -10, 64, 102, 0},
            {1, 3, -10, -10, 64, 103, 0},
            {1, 4, 0, 0, 64, 104, 0},                  // la cinquieme doit en chasser une
            {2, 0, 0, 0, 0, 0, 0},
            {1, 5, 0, 0, 1, 105, 0},                   // trop faible pour chasser
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},

            {0, 0, 0, 0, 0, 0, 0},                     // en MONO, les huit cases d'un trait
            {1, 0, 0, 0, 100, 200, 0},
            {1, 1, 400, 0, 90, 201, 0},
            {1, 2, -400, 0, 90, 202, 0},
            {1, 3, 0, 400, 90, 203, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {1, 4, 0, -400, 90, 204, 0},
            {1, 5, 50, 50, 90, 205, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {1, 0, 1, 1, 120, -1, 1},                  // identifiant moins un : jamais reconnu
            {1, 1, 1, 1, 120, -1, 1},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},

            {0, 0b11111111, 1, 0, 0, 0, 0},            // toutes les cases prises : rien ne passe
            {1, 0, 0, 0, 100, 300, 0},
            {2, 0, 0, 0, 0, 0, 0},

            // Toute la GAUCHE est prise, la droite est libre. Le jeu cherche d'abord a gauche,
            // n'y trouve rien, et SORT : la droite n'est meme pas tentee.
            {0, 0b00001111, 1, 0, 0, 0, 0},
            {1, 0, 0, 0, 100, 400, 0},
            {1, 1, 300, 0, 100, 401, 0},
            {2, 0, 0, 0, 0, 0, 0},

            // Le MEME identifiant, mais sans « pas si ca joue » : la case est reprise telle
            // quelle, et l'importance de ce qui y jouait n'est PAS consultee.
            {0, 0, 1, 0, 0, 0, 0},
            {1, 0, 0, 0, 120, 7, 0},
            {1, 1, 0, 0, 10, 7, 0},                    // bien plus faible, et pourtant accepte
            {2, 0, 0, 0, 0, 0, 0},
            {1, 2, 0, 0, 5, 7, 0},
            {2, 0, 0, 0, 0, 0, 0},

            // L'identifiant MOINS UN, depuis un etat propre : il est remplace a l'ecriture.
            {0, 0, 1, 0, 0, 0, 0},
            {1, 3, 20, -20, 90, -1, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {1, 4, 20, -20, 90, -1, 1},                // et « pas si ca joue » ne le reconnait pas
            {2, 0, 0, 0, 0, 0, 0},

            // Des bruits TRES importants remplissent les huit cases, puis d'autres de meme
            // importance se presentent : c'est la que la gauche et la droite se separent.
            {0, 0, 1, 0, 0, 0, 0},
            {1, 0, 0, 0, 20000, 500, 0},
            {1, 1, 0, 0, 20000, 501, 0},
            {1, 2, 0, 0, 20000, 502, 0},
            {1, 3, 0, 0, 20000, 503, 0},
            {1, 4, 0, 0, 20000, 504, 0},
            {1, 5, 0, 0, 20000, 505, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {1, 0, 0, 0, 20000, 506, 0},               // meme importance : qui cede ?
            {1, 1, 0, 0, 20001, 507, 0},               // un cran au-dessus
            {1, 2, 0, 0, 19999, 508, 0},               // un cran au-dessous
            {2, 0, 0, 0, 0, 0, 0},
            {1, 3, 0, 0, 9000, 509, 0},                // au-dessous du seuil de la droite
            {1, 4, 0, 0, 10000, 510, 0},               // exactement ce seuil
            {1, 5, 0, 0, 10001, 511, 0},
            {2, 0, 0, 0, 0, 0, 0},

            // Huit bruits d'echantillons DIFFERENTS, meme volume et meme place, puis de longs
            // melanges : chaque voix atteint le bout de son echantillon a son tour.
            {0, 0, 1, 0, 0, 0, 0},
            {1, 0, 0, 0, 64, 600, 0},
            {1, 1, 0, 0, 64, 601, 0},
            {1, 2, 0, 0, 64, 602, 0},
            {1, 3, 0, 0, 64, 603, 0},
            {1, 4, 0, 0, 64, 604, 0},
            {1, 5, 0, 0, 64, 605, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},

            // Les memes, mais en MONO, ou les huit cases se parcourent d'un trait.
            {0, 0, 0, 0, 0, 0, 0},
            {1, 0, 0, 0, 64, 700, 0},
            {1, 1, 0, 0, 64, 701, 0},
            {1, 2, 0, 0, 64, 702, 0},
            {1, 3, 0, 0, 64, 703, 0},
            {1, 4, 0, 0, 64, 704, 0},
            {1, 5, 0, 0, 64, 705, 0},
            {1, 0, 0, 0, 64, 706, 0},
            {1, 1, 0, 0, 64, 707, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0},
        };

        for (int[] ligne : script) {
            Map<String, Object> un = new LinkedHashMap<>();
            un.put("op", ligne[0]);
            switch (ligne[0]) {
                case 0 -> {
                    remise(ligne[1], ligne[2] != 0);
                    un.put("busyMask", ligne[1]);
                    un.put("stereo", ligne[2]);
                }
                case 1 -> {
                    Mem.ww(HiresData.Aud_SampleNum_w, ligne[1]);
                    Mem.ww(HiresData.Aud_NoiseX_w, ligne[2]);
                    Mem.ww(HiresData.Aud_NoiseZ_w, ligne[3]);
                    Mem.ww(HiresData.Aud_NoiseVol_w, ligne[4]);
                    Mem.ww(HiresData.IDNUM, ligne[5]);
                    Mem.wb(HiresData.notifplaying, ligne[6]);
                    ab3d2.Hires.MakeSomeNoise();
                    un.put("sample", ligne[1]);
                    un.put("x", ligne[2]);
                    un.put("z", ligne[3]);
                    un.put("vol", ligne[4]);
                    un.put("id", ligne[5]);
                    un.put("notIfPlaying", ligne[6]);
                    un.put("noiseloud", Mem.w(HiresData.noiseloud));
                    un.put("leftOffset", Mem.l(HiresData.LEFTOFFSET));
                    un.put("rightOffset", Mem.l(HiresData.RIGHTOFFSET));
                }
                default -> {
                    ab3d2.Hires.newsampbitl();
                    List<Object> canaux = new ArrayList<>();
                    for (int c = 0; c < 4; c++) {
                        Map<String, Object> ch = new LinkedHashMap<>();
                        ch.put("vol", ab3d2.host.CustomChips.audVOL[c]);
                        ch.put("bytes", b64(ab3d2.host.CustomChips.audLC[c], FRAME));
                        canaux.add(ch);
                    }
                    un.put("channels", canaux);
                }
            }
            un.put("slots", cases());
            un.put("voices", voix(faux));
            pas.add(un);
        }
        return pas;
    }

    /** Remet les huit voix au silence et pose quelles cases la musique tient. */
    private static void remise(int masque, boolean stereo) {
        int vide = ab3d2.bss.TablesBss.Aud_EmptyBuffer_vl;
        int finVide = ab3d2.bss.TablesBss.Aud_EmptyBufferEnd;
        int[] pos = {HiresData.pos0LEFT, HiresData.pos1LEFT, HiresData.pos2LEFT,
            HiresData.pos3LEFT, HiresData.pos0RIGHT, HiresData.pos1RIGHT,
            HiresData.pos2RIGHT, HiresData.pos3RIGHT};
        int[] fin = {HiresData.Samp0endLEFT, HiresData.Samp1endLEFT, HiresData.Samp2endLEFT,
            HiresData.Samp3endLEFT, HiresData.Samp0endRIGHT, HiresData.Samp1endRIGHT,
            HiresData.Samp2endRIGHT, HiresData.Samp3endRIGHT};
        for (int i = 0; i < 8; i++) {
            Mem.wl(pos[i], vide);
            Mem.wl(fin[i], finVide);
            Mem.wb(VOLS[i], 0);
        }
        for (int i = 0; i < 8; i++) {
            Mem.wb(HiresData.CHANNELDATA + i * 4, (masque >> i & 1) != 0 ? 0xFF : 0);
            Mem.ww(HiresData.CHANNELDATA + i * 4 + 2, 0);
            Mem.ww(HiresData.CHANNELDATA + i * 4 + 32, 0);
        }
        Mem.wb(HiresData.Aud_Stereo_b, stereo ? 0xFF : 0);
        for (int c = 0; c < 4; c++) {
            ab3d2.host.CustomChips.audVOL[c] = 0;
        }
        Mem.wl(HiresData.LEFTOFFSET, 0);
        Mem.wl(HiresData.RIGHTOFFSET, 0);
    }

    /** L'ordre du jeu : les quatre voix de gauche, puis les quatre de droite. */
    private static final int[] VOLS = {HiresData.vol0left, HiresData.vol1left,
        HiresData.vol2left, HiresData.vol3left, HiresData.vol0right, HiresData.vol1right,
        HiresData.vol2right, HiresData.vol3right};

    private static final int[] POS = {HiresData.pos0LEFT, HiresData.pos1LEFT,
        HiresData.pos2LEFT, HiresData.pos3LEFT, HiresData.pos0RIGHT, HiresData.pos1RIGHT,
        HiresData.pos2RIGHT, HiresData.pos3RIGHT};

    private static final int[] FIN = {HiresData.Samp0endLEFT, HiresData.Samp1endLEFT,
        HiresData.Samp2endLEFT, HiresData.Samp3endLEFT, HiresData.Samp0endRIGHT,
        HiresData.Samp1endRIGHT, HiresData.Samp2endRIGHT, HiresData.Samp3endRIGHT};

    private static List<Object> cases() {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("busy", Mem.ub(HiresData.CHANNELDATA + i * 4) != 0 ? 1 : 0);
            e.put("importance", Mem.uw(HiresData.CHANNELDATA + i * 4 + 2));
            e.put("id", Mem.uw(HiresData.CHANNELDATA + i * 4 + 32));
            out.add(e);
        }
        return out;
    }

    /** L'etat des huit voix, avec la position rendue relative a son echantillon. */
    private static List<Object> voix(int[][] faux) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int p = Mem.l(POS[i]);
            int f = Mem.l(FIN[i]);
            int quel = -1;
            int base = ab3d2.bss.TablesBss.Aud_EmptyBuffer_vl;
            for (int k = 0; k < faux.length; k++) {
                if (p >= faux[k][0] && p < faux[k][0] + faux[k][2] + PAD) {
                    quel = k;
                    base = faux[k][0];
                    break;
                }
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("sample", quel);
            e.put("pos", p - base);
            e.put("end", f - base);
            // Les volumes sont declares en MOTS et ecrits en OCTETS : c'est donc
            // l'octet de POIDS FORT du mot qui les porte.
            e.put("vol", Mem.ub(VOLS[i]));
            out.add(e);
        }
        return out;
    }

    private static String b64(int at, int len) {
        byte[] out = new byte[len];
        System.arraycopy(Mem.RAM, at, out, 0, len);
        return Base64.getEncoder().encodeToString(out);
    }
}
