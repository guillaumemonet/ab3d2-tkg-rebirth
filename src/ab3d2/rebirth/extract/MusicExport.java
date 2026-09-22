package ab3d2.rebirth.extract;

import ab3d2.Defs;
import ab3d2.HiresData;
import ab3d2.Mem;
import ab3d2.host.CustomChips;
import ab3d2.modules.FileIo;
import ab3d2.modules.Music;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Rend les MUSIQUES (modules ProTracker) en WAV.
 *
 * <p>Plutôt que de réécrire un lecteur, on fait tourner le replay LITTÉRAL du port
 * ({@code ab3d2.modules.Music}, traduction de modules/music.s) et on mixe l'état de Paula
 * ({@code CustomChips.audLC/audLEN/audPER/audVOL} + DMA) à un taux FIXE. Le résultat est donc
 * l'original au sample près, sans dépendance à un lecteur tiers.
 *
 * <p>Le mixage suit celui de l'hôte ({@code host/Audio.queueFromPaula}) : gauche = canaux 0 et 3,
 * droite = canaux 1 et 2, échantillons 8 bits signés mis à l'échelle du volume (0..64) puis
 * décalés de 6 bits. Un canal consomme un octet tous les {@code période} cycles PAL, soit
 * {@code 3546895 / période} octets par seconde.
 *
 * <p><b>Ecart assume</b> : le replay d'origine n'arrete JAMAIS le morceau — le test de fin de
 * chanson est COMMENTE dans music.s ({@code mt_nex} : {@code addq.b #1,mt_songpos} puis
 * {@code and.b #$7f}), si bien qu'il defile les 128 positions de la table d'ordre, bien au-dela
 * de la longueur reelle du morceau, avant de reboucler (environ un quart d'heure). On rend donc
 * la LONGUEUR DECLAREE dans l'en-tete du module et le jeu boucle dessus : c'est ce que la musique
 * est censee faire, et ca tient dans un fichier raisonnable.
 *
 * <p><b>Un seul module en jeu</b> : {@code Res} calcule bien {@code niveau*64} pour indexer
 * {@code GLFT_LevelMusic_l} mais ne l'ajoute JAMAIS au pointeur (bug d'origine, cf. le commentaire
 * de Res.java:330) — tous les niveaux jouent donc le premier slot. On rend aussi les deux
 * musiques de fin ({@code quietwelldone} et {@code gameover}).
 */
public final class MusicExport {

    /** Horloge PAL (hires.s) : un canal lit un octet tous les `période` cycles. */
    private static final double PAL_CLOCK = 3546895.0;
    /** Taux de sortie du WAV. */
    private static final int RATE = 44100;
    /** Le replay est appelé une fois par VBL. */
    private static final int VBL_HZ = 50;
    /** Garde-fou : au-delà, on considère que le morceau ne boucle pas. */
    private static final int MAX_SECONDS = 360;

    private MusicExport() {
    }

    public static void extract(Path outRoot) throws IOException {
        PortReader.bootWithAssets();
        Path dir = outRoot.resolve("music");
        Files.createDirectories(dir);

        int glf = PortReader.glf();
        long r = FileIo.IO_LoadFile(glf + Defs.GLFT_LevelMusic_l);
        render(FileIo.addr(r), dir.resolve("level.wav"), "musique de jeu");
        render(HiresData.welldone, dir.resolve("welldone.wav"), "fin de niveau");
        render(HiresData.gameover, dir.resolve("gameover.wav"), "game over");
    }

    /** Un module rendu en WAV, du début jusqu'à son point de bouclage. */
    private static void render(int data, Path out, String label) throws IOException {
        if (data == 0) {
            System.out.println("[music] " + label + " : module introuvable");
            return;
        }
        resetPaula();
        Mem.wl(Music.mt_data, data);                   // move.l Lvl_MusicPtr_l,mt_data
        Music.mt_init();                               // jsr mt_init

        // ProTracker : 20 o de titre + 31 echantillons de 30 o = 930, puis @950 la LONGUEUR du
        // morceau (nombre de positions), @951 la position de reprise, @952 la table d'ordre.
        int songLength = Mem.ub(data + 950);
        if (songLength <= 0 || songLength > 128) {
            songLength = 128;
        }
        int samplesPerTick = RATE / VBL_HZ;
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        boolean advanced = false;
        int lastPos = 0;
        int ticks = 0;
        int maxTicks = MAX_SECONDS * VBL_HZ;
        while (ticks < maxTicks) {
            Music.mt_music();                          // jsr mt_music (une fois par VBL)
            consumeTriggers();
            for (int i = 0; i < samplesPerTick; i++) {
                int l = clamp16((channel(0) + channel(3)) << 6);
                int rr = clamp16((channel(1) + channel(2)) << 6);
                write16(pcm, l);
                write16(pcm, rr);
            }
            ticks++;
            if (Mem.b(Music.reachedend) != 0) {
                break;                                 // le morceau se termine (musiques de fin)
            }
            // Fin du morceau : sa LONGUEUR DECLAREE (octet 950 de l'en-tete ProTracker), ou un
            // saut arriere de la position de morceau.
            int pos = Mem.ub(Music.mt_songpos);
            if (pos >= songLength || (pos < lastPos && advanced)) {
                break;
            }
            if (pos > lastPos) {
                advanced = true;
            }
            lastPos = pos;
        }
        writeWav(out, pcm.toByteArray());
        System.out.printf("[music] %s -> %s (%.1f s, %d ticks)%n",
                label, out, ticks / (float) VBL_HZ, ticks);
    }

    // ------------------------------------------------------------- mixage Paula

    private static final int[] segLC = new int[4];
    private static final int[] segLEN = new int[4];
    private static final double[] segPos = new double[4];
    private static final boolean[] segActive = new boolean[4];

    private static void resetPaula() {
        for (int ch = 0; ch < 4; ch++) {
            CustomChips.audLC[ch] = 0;
            CustomChips.audLEN[ch] = 0;
            CustomChips.audPER[ch] = 0;
            CustomChips.audVOL[ch] = 0;
            CustomChips.dmaLC[ch] = 0;
            CustomChips.dmaLEN[ch] = 0;
            CustomChips.dmaTrig[ch] = false;
            segLC[ch] = 0;
            segLEN[ch] = 0;
            segPos[ch] = 0;
            segActive[ch] = false;
        }
        CustomChips.dmacon = 0;
        Mem.wb(Music.reachedend, 0);
    }

    /** Un declenchement DMA par note : (re)latche le segment a jouer. */
    private static void consumeTriggers() {
        for (int ch = 0; ch < 4; ch++) {
            if (CustomChips.dmaTrig[ch]) {
                segLC[ch] = CustomChips.dmaLC[ch];
                segLEN[ch] = CustomChips.dmaLEN[ch];
                segPos[ch] = 0;
                segActive[ch] = true;
                CustomChips.dmaTrig[ch] = false;
            }
            if ((CustomChips.dmacon & (1 << ch)) == 0) {
                segActive[ch] = false;                 // DMA du canal coupe
            }
        }
    }

    /**
     * Un echantillon du canal {@code ch}. Paula joue le segment latche une fois, puis boucle sur
     * AUDxLC/AUDxLEN (que le replay repose apres la note) — c'est ce que fait la vraie puce.
     */
    private static int channel(int ch) {
        int vol = CustomChips.audVOL[ch];
        int per = CustomChips.audPER[ch];
        if (!segActive[ch] || vol == 0 || per <= 0 || segLC[ch] <= 0) {
            return 0;
        }
        int segBytes = segLEN[ch] * 2;
        int p = (int) segPos[ch];
        if (segBytes < 2 || p >= segBytes) {           // fin du segment -> point de boucle
            segLC[ch] = CustomChips.audLC[ch];
            segLEN[ch] = CustomChips.audLEN[ch];
            segBytes = segLEN[ch] * 2;
            segPos[ch] = 0;
            p = 0;
            if (segBytes < 2 || segLC[ch] <= 0) {      // nullsample (LEN <= 1) -> silence
                return 0;
            }
        }
        int s = (byte) Mem.ub(segLC[ch] + p);
        segPos[ch] += PAL_CLOCK / per / RATE;          // un octet tous les `per` cycles PAL
        return s * vol / 64;
    }

    private static int clamp16(int v) {
        return v > 32767 ? 32767 : (v < -32768 ? -32768 : v);
    }

    // ------------------------------------------------------------------- WAV

    private static void write16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void writeWav(Path out, byte[] pcm) throws IOException {
        try (OutputStream os = Files.newOutputStream(out)) {
            int byteRate = RATE * 2 * 2;
            ByteArrayOutputStream h = new ByteArrayOutputStream();
            str(h, "RIFF");
            le32(h, 36 + pcm.length);
            str(h, "WAVE");
            str(h, "fmt ");
            le32(h, 16);
            le16(h, 1);
            le16(h, 2);
            le32(h, RATE);
            le32(h, byteRate);
            le16(h, 4);
            le16(h, 16);
            str(h, "data");
            le32(h, pcm.length);
            os.write(h.toByteArray());
            os.write(pcm);
        }
    }

    private static void str(OutputStream o, String s) throws IOException {
        for (char c : s.toCharArray()) {
            o.write(c);
        }
    }

    private static void le16(OutputStream o, int v) throws IOException {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void le32(OutputStream o, int v) throws IOException {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }
}
