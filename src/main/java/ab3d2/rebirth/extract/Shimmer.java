package ab3d2.rebirth.extract;

import ab3d2.Mem;
import ab3d2.data.DrawData;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Extraction de la table de DÉMATÉRIALISATION (« teleport shimmer »).
 *
 * <p>Le jeu brouille l'écran déjà rendu en recopiant chaque groupe de 8 pixels depuis un endroit
 * décalé de la même ligne ({@code c2p_Convert1xTeleFx}, modules/c2p/teleport_fx/routines.s) :
 * <pre>
 *   move.w (a6)+,d0 ; move.l (a0,d0.w),d0   ; 4 pixels depuis a0 + offA
 *   move.l (a6)+,d1 ; move.l 4(a0,d1.w),d1  ; 4 pixels depuis a0 + 4 + offB
 *   addq  #8,a0                             ; APRÈS les deux lectures
 * </pre>
 * Les décalages sont des index dans le tampon CHUNKY (320 octets par ligne) : un offset vaut donc
 * {@code dy*320 + dx}. Le fichier {@code shimmerfile} contient 8 « frames » de 1024 octets, lues
 * 6 octets par groupe, et le curseur s'enroule sur 512 octets à chaque fin de ligne
 * ({@code and.l #255*2}) — c'est cet enroulement qui fait varier le motif d'une ligne à l'autre.
 *
 * <p>L'amplitude croît avec la frame : {@code dx ∈ [-2(f+1), 2(f+1)]}, {@code dy ∈ [-(f+1), f+1]}.
 *
 * <p>On exporte {@code assets/fx/shimmer.png} : 80 colonnes (les DEMI-groupes de 4 pixels
 * d'origine) × 256 lignes × 8 frames empilées verticalement, avec {@code R = dx + 128} et
 * {@code G = dy + 128}. Le rendu s'en sert comme carte de déplacement, en unités d'écran
 * d'origine (320×256), donc indépendamment de la résolution réelle.
 */
public final class Shimmer {

    /** L'écran d'origine (screen.h). */
    public static final int WIDTH = 320;
    public static final int HEIGHT = 256;
    /** 8 frames de 1024 octets dans {@code shimmerfile}. */
    public static final int FRAMES = 8;
    /** Une colonne par DEMI-groupe : 320 / 4. */
    public static final int COLS = WIDTH / 4;

    private Shimmer() {
    }

    public static void extract(Path outRoot) throws IOException {
        Path dir = outRoot.resolve("fx");
        Files.createDirectories(dir);

        BufferedImage img = new BufferedImage(COLS, HEIGHT * FRAMES, BufferedImage.TYPE_INT_RGB);
        int minDx = 0;
        int maxDx = 0;
        for (int frame = 0; frame < FRAMES; frame++) {
            int base = DrawData.draw_TeleportShimmerFXData_vb + (frame << 10);   // frame*1024
            int at = 0;                                    // a6, relatif au bloc de la frame
            for (int y = 0; y < HEIGHT; y++) {
                for (int g = 0; g < WIDTH / 8; g++) {
                    int offA = (short) Mem.uw(base + at);
                    at += 2;                               // move.w (a6)+,d0
                    int offB = (short) (Mem.l(base + at) & 0xFFFF);
                    at += 4;                               // move.l (a6)+,d1 ; seul d1.w sert
                    put(img, g * 2, y + frame * HEIGHT, offA);
                    put(img, g * 2 + 1, y + frame * HEIGHT, offB);
                    minDx = Math.min(minDx, dx(offA));
                    maxDx = Math.max(maxDx, dx(offA));
                }
                at &= 0x1FE;                               // and.l #255*2 : enroulement par ligne
            }
        }
        Path png = dir.resolve("shimmer.png");
        ImageIO.write(img, "png", png.toFile());
        System.out.printf("[shimmer] %d frames x %dx%d -> %s (dx %d..%d)%n",
                FRAMES, COLS, HEIGHT, png, minDx, maxDx);
    }

    /** Un offset d'index chunky décomposé en lignes puis colonnes. */
    private static int dy(int off) {
        return Math.round(off / (float) WIDTH);
    }

    private static int dx(int off) {
        return off - dy(off) * WIDTH;
    }

    private static void put(BufferedImage img, int x, int y, int off) {
        int r = clampByte(dx(off) + 128);
        int g = clampByte(dy(off) + 128);
        img.setRGB(x, y, (r << 16) | (g << 8));
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
