package ab3d2.rebirth.extract;

import java.util.ArrayList;
import java.util.List;

/**
 * Dépaqueteur <b>StoneCracker 4.04</b> et chargeur du flux de hunks qui le suit.
 *
 * <p>L'intro du jeu ({@code intro.exe}, lancé par la séquence de démarrage des disquettes de
 * boot avant {@code tkg1:tkg}) est un exécutable Amiga CRUNCHÉ : 24 724 octets qui se déplient
 * en 69 474. Son hunk 0 ne fait que 76 octets et son hunk 1 est alloué bien plus grand qu'il
 * n'est chargé — la signature d'un dépaqueteur qui se déplie sur place.
 *
 * <p>Le code ci-dessous n'est pas reconstitué de mémoire : il est porté <b>instruction par
 * instruction</b> du 68k que le fichier transporte lui-même. Le hunk 1 commence par 0x188
 * octets de dépaqueteur, puis le marqueur {@code 'S404'}, puis les données. Les étiquettes des
 * commentaires ({@code L26}, {@code L9C}…) sont les adresses dans ce code.
 *
 * <h2>Le dépaquetage</h2>
 * Le flux se lit <b>à l'envers</b> : le pointeur de lecture descend mot par mot depuis la fin
 * des données packées, celui d'écriture descend octet par octet depuis la fin de la sortie, et
 * on s'arrête quand les deux se rejoignent. Les bits sortent par le <b>haut</b> d'une fenêtre
 * de 16 bits ({@code add.w d6,d6} : la retenue EST le bit).
 *
 * <p>Que les deux pointeurs se rencontrent <b>exactement</b> est le contrôle : un dépaqueteur
 * faux ne tomberait pas juste au bit près.
 *
 * <h2>Le flux de hunks</h2>
 * Ce qu'on obtient n'est pas une image mémoire plate mais un flux
 * {@code [nb-1][tailles…]} suivi, pour chaque hunk, d'un bloc de données puis de blocs de
 * relocation à deltas de largeur variable. {@link #load} le déroule — c'est la suite du même
 * code 68k (0xDC..0x186) — et rend les hunks séparés, ce qui sépare enfin le code des données.
 */
public final class StoneCracker {

    private StoneCracker() {
    }

    /** Un exécutable déplié : ses hunks, leur taille allouée et leur type de mémoire. */
    public record Image(List<byte[]> hunks, List<Integer> sizes, List<Boolean> chip) { }

    // ------------------------------------------------------------------ dépaquetage

    /**
     * Déplie en place les données packées de {@code mem}, dont le marqueur {@code 'S404'} est
     * à {@code x}. {@code mem} doit avoir la taille ALLOUÉE du hunk, pas sa taille chargée :
     * la sortie déborde largement ce qui vient du fichier.
     *
     * @return le flux décompressé
     */
    public static byte[] unpack(byte[] mem, int x) {
        if (!(mem[x] == 'S' && mem[x + 1] == '4' && mem[x + 2] == '0' && mem[x + 3] == '4')) {
            throw new IllegalArgumentException("marqueur S404 absent en 0x" + Integer.toHexString(x));
        }
        int off = i32(mem, x + 4);
        int rawLen = i32(mem, x + 8);
        int pakLen = i32(mem, x + 12);

        int a5 = x + off;                      // borne basse de la sortie
        int a2 = a5 + rawLen;                  // écriture (descend)
        int a1 = x + 12 + pakLen;              // lecture  (descend)

        // movem.w (a1),d2/d6-d7 : trois mots, étendus en signe
        final int d2 = (short) i16(mem, a1);
        int d6 = (short) i16(mem, a1 + 2);
        int d7 = (short) i16(mem, a1 + 4);
        d6 &= 0xFFFFFFFF;

        final int d0 = 15, d4 = 0x0000FFFF, d5 = 16;
        int d1 = 0, d3 = 0, a0 = 0;
        boolean zflag = false;

        // Un petit état mutable pour les routines de bits : Java n'a pas les registres du 68k.
        int[] st = {a1, d6, d7};

        String pc = "LD2";                     // subq.w #1,d4 pose C -> bra L72 -> bcs LD2
        while (true) {
            switch (pc) {
                case "L22" -> {
                    word(mem, st);
                    st[2] = 15;
                    pc = "L26";
                }
                case "L26" -> {
                    d3 = 0;
                    if (bit(st) == 0) {
                        pc = "L6A";                                  // bcc : un seul littéral
                    } else {
                        d1 = 1;
                        tick(mem, st);
                        if (bit(st) == 1) {
                            pc = "L9C";                              // bcs : d1=1, d3=0
                        } else {
                            d1 = 2;
                            d3 = 2;
                            tick(mem, st);
                            if (bit(st) == 1) {
                                pc = "L9C";                          // bcs : d1=2, d3=2
                            } else {
                                tick(mem, st);
                                if (bit(st) == 0) {
                                    d3 = 0x15;                       // L98
                                    d1 = 8;
                                    pc = "L9C";
                                } else {
                                    d1 = get(mem, st, 4, d4, d5);
                                    d3 = 6 + d1;
                                    if (d1 < d0) {
                                        zflag = false;               // cmp.w d0,d1 ; bcs LA2
                                        pc = "LA2";
                                    } else {
                                        d1 = get(mem, st, 5, d4, d5);
                                        d3 = 13 + d1;
                                        pc = "L6A";
                                    }
                                }
                            }
                        }
                    }
                }
                case "L6A" -> {                                      // littéraux : d3+1 octets
                    d1 = get(mem, st, 8, d4, d5);
                    mem[--a2] = (byte) d1;
                    d3 = (d3 - 1) & 0xFFFF;
                    pc = d3 == 0xFFFF ? "LD2" : "L6A";
                }
                case "L9A" -> {
                    d1 = 8;
                    pc = "L9C";
                }
                case "L9C" -> {
                    d1 = get(mem, st, d1, d4, d5);
                    d3 = (d3 + d1) & 0xFFFF;
                    zflag = (d1 & 0xFF) == 0xFF;                     // not.b d1 : Z si c'était $FF
                    pc = "LA2";
                }
                case "LA2" -> {                                      // dbeq d7,LAC
                    if (zflag) {
                        pc = "L9A";                                  // beq L9A : encore 8 bits
                    } else {
                        st[2]--;
                        if (st[2] == -1) {
                            word(mem, st);
                            st[2] = 15;
                        }
                        pc = "LAC";
                    }
                }
                case "LAC" -> {
                    if (bit(st) == 1) {
                        d1 = d2;                                     // L76 : distance longue
                        a0 = a2 + 0x220;
                    } else {
                        d1 = 9;
                        a0 = a2 + 0x20;
                        tick(mem, st);
                        if (bit(st) != 0) {
                            d1 = 5;                                  // distance courte
                            a0 = a2;
                        }
                    }
                    pc = "LC8";
                }
                case "LC8" -> {                                      // L7C : d1 = get(d1) puis copie
                    d1 = get(mem, st, d1, d4, d5);
                    a0 += (short) d1;                                // adda.w d1,a0
                    mem[--a2] = mem[a0];                             // move.b (a0),-(a2)
                    do {                                             // move.b -(a0),-(a2) ; dbra d3
                        a0--;
                        mem[--a2] = mem[a0];
                        d3 = (d3 - 1) & 0xFFFF;
                    } while (d3 != 0xFFFF);
                    pc = "LD2";
                }
                default -> {                                         // LD2
                    if (a5 >= a2) {                                  // cmpa.l a2,a5 : plus de retenue
                        byte[] out = new byte[rawLen];
                        System.arraycopy(mem, a5, out, 0, rawLen);
                        return out;
                    }
                    st[2]--;
                    pc = st[2] != -1 ? "L26" : "L22";
                }
            }
        }
    }

    /** move.w -(a1),d6 : remplace le MOT BAS de d6, le mot haut est conservé. */
    private static void word(byte[] mem, int[] st) {
        st[0] -= 2;
        st[1] = (st[1] & 0xFFFF0000) | i16(mem, st[0]);
    }

    /** add.w d6,d6 : la retenue est le bit sorti. */
    private static int bit(int[] st) {
        int lo = st[1] & 0xFFFF;
        st[1] = (st[1] & 0xFFFF0000) | ((lo << 1) & 0xFFFF);
        return (lo >>> 15) & 1;
    }

    /** dbra d7,&lt;suite&gt; : recharge quand d7 passe à -1. */
    private static void tick(byte[] mem, int[] st) {
        st[2]--;
        if (st[2] == -1) {
            word(mem, st);
            st[2] = 15;
        }
    }

    /** La routine en 0x7E : n bits, pris par le haut, à cheval sur deux mots si besoin. */
    private static int get(byte[] mem, int[] st, int n, int d4, int d5) {
        st[1] &= d4;                                                 // and.l d4,d6
        st[2] -= n;                                                  // sub.w d1,d7
        if (st[2] >= 0) {
            st[1] <<= n;
        } else {
            int avail = n + st[2];                                   // add.w d7,d1
            st[1] <<= avail;
            int need = -st[2];                                       // move.w d7,d1 ; neg.w d1
            word(mem, st);
            st[2] += d5;                                             // add.w d5,d7
            st[1] <<= need;
        }
        return (st[1] >>> 16) & 0xFFFF;                              // move.l d6,d1 ; swap d1
    }

    // ------------------------------------------------------------------ flux de hunks

    /** Déroule le flux rendu par {@link #unpack} : le code 68k qui suit le dépaqueteur. */
    public static Image load(byte[] blob) {
        int[] p = {0};
        int n = i32(blob, adv(p, 4)) + 1;                            // move.l (a2)+,d7 : nb-1
        List<Integer> sizes = new ArrayList<>();
        List<Boolean> chip = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int s = i32(blob, adv(p, 4));
            chip.add((s & 0xC0000000) != 0);                         // lsl.l #2 ; bcc : bit haut = CHIP
            sizes.add((s & 0x3FFFFFFF) * 4);
        }
        List<byte[]> hunks = new ArrayList<>();
        int[] base = new int[n];
        int a = 0x1000;
        for (int i = 0; i < n; i++) {
            hunks.add(new byte[sizes.get(i)]);
            base[i] = a;
            a += sizes.get(i) + 0x100;
        }

        int cur = -1;                                                // d7
        while (true) {
            int flag = i16(blob, adv(p, 2));                         // move.w (a2)+,d1
            if (flag != 0) {                                         // L148 : bloc de DONNÉES
                cur++;
                if ((flag & 0x4000) != 0) {                          // btst #$1e après swap
                    int cnt = i16(blob, adv(p, 2)) * 4;              // lsl.l #2
                    System.arraycopy(blob, p[0], hunks.get(cur), 0, cnt);
                    p[0] += cnt;
                }
            } else {                                                 // section de RELOCATION
                while (true) {
                    if ((p[0] & 1) != 0) {                           // alignement pair (L112)
                        p[0]++;
                    }
                    int cnt = i16(blob, adv(p, 2));
                    if (cnt == 0) {
                        break;
                    }
                    int ref = i16(blob, adv(p, 2));
                    int first = i32(blob, adv(p, 4));
                    int sel = (first >>> 24) & 0xFF;                 // jmp $130(pc,d2.w)
                    int d3 = first & 0xFFFFFF;
                    byte[] h = hunks.get(cur);
                    for (int i = 0; i < cnt; i++) {
                        if (i > 0) {
                            int d4 = 0;
                            for (int k = 0; k < deltaBytes(sel); k++) {
                                d4 = (d4 << 8) | (blob[p[0]++] & 0xFF);
                            }
                            d3 += d4;
                        }
                        put32(h, d3, i32(h, d3) + base[ref]);        // add.l d0,(a0,d3.l)
                    }
                }
            }
            if (i16(blob, p[0]) == 0xFFFF) {                         // cmp.w (a2),d6
                break;
            }
        }
        return new Image(hunks, sizes, chip);
    }

    /**
     * Le sélecteur de delta est un point d'entrée dans une suite de {@code move.b}/{@code lsl},
     * d'où 3, 2, 1 ou 0 octets. Il n'est lu qu'à partir de la DEUXIÈME entrée d'un bloc.
     */
    private static int deltaBytes(int sel) {
        return switch (sel) {
            case 2 -> 3;
            case 4, 6 -> 2;
            case 8, 10 -> 1;
            case 12, 14 -> 0;
            default -> throw new IllegalStateException("sélecteur de delta inconnu : " + sel);
        };
    }

    // ------------------------------------------------------------------ exécutable Amiga

    /** Charge un exécutable Amiga ({@code HUNK_HEADER} = 0x3F3) et rend ses hunks bruts. */
    public static List<byte[]> hunkBodies(byte[] d, List<Integer> allocOut) {
        if (i32(d, 0) != 0x3F3) {
            throw new IllegalArgumentException("pas un executable Amiga");
        }
        int[] p = {8};
        int first = i32(d, adv(p, 4) + 4);                           // (table, premier, dernier)
        p[0] += 8;
        int last = i32(d, p[0] - 4);
        int count = last - first + 1;
        for (int i = 0; i < count; i++) {
            allocOut.add((i32(d, adv(p, 4)) & 0x3FFFFFFF) * 4);
        }
        List<byte[]> bodies = new ArrayList<>();
        while (p[0] < d.length - 3) {
            int t = i32(d, adv(p, 4)) & 0x3FFFFFFF;
            if (t == 0x3E9 || t == 0x3EA) {                          // CODE / DATA
                int nl = i32(d, adv(p, 4));
                byte[] b = new byte[nl * 4];
                System.arraycopy(d, p[0], b, 0, nl * 4);
                p[0] += nl * 4;
                bodies.add(b);
            } else if (t == 0x3EB) {                                 // BSS
                p[0] += 4;
                bodies.add(new byte[0]);
            } else if (t != 0x3F2) {                                 // END
                break;
            }
        }
        return bodies;
    }

    /** Chaîne complète : exécutable packé S404 -&gt; hunks séparés. */
    public static Image unpackExe(byte[] exe) {
        List<Integer> alloc = new ArrayList<>();
        List<byte[]> bodies = hunkBodies(exe, alloc);
        byte[] mem = new byte[alloc.get(1)];
        System.arraycopy(bodies.get(1), 0, mem, 0, bodies.get(1).length);
        int x = indexOf(mem, "S404".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (x < 0) {
            throw new IllegalArgumentException("pas de marqueur S404 : fichier non packe ?");
        }
        return load(unpack(mem, x));
    }

    // ------------------------------------------------------------------ petits outils

    private static int adv(int[] p, int n) {
        int v = p[0];
        p[0] += n;
        return v;
    }

    private static int i16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int i32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 24);
        b[o + 1] = (byte) (v >> 16);
        b[o + 2] = (byte) (v >> 8);
        b[o + 3] = (byte) v;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (hay[i + k] != needle[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
