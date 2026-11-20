package ab3d2.rebirth.extract;

import ab3d2.Mem;
import ab3d2.c.ScreenC;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle de la DÉMATÉRIALISATION : l'image regardée de travers.
 *
 * <p>L'effet ne dépend que de deux choses — la table {@code shimmerfile} et l'image qu'on lui
 * donne. Il n'a besoin ni de niveau, ni de joueur, ni de position : on peut donc l'éprouver sur
 * une image FABRIQUÉE, et c'est bien mieux qu'une capture de jeu.
 *
 * <h2>Pourquoi trois motifs, et pas un</h2>
 *
 * <p>Un seul motif laisserait passer des erreurs entières. Chacun de ceux-ci tue une faute
 * précise :
 *
 * <ul>
 *   <li><b>le damier fin</b> — chaque octet diffère de ses voisins immédiats. Un décalage d'un
 *       seul pixel se voit ; une image unie ne le montrerait pas ;</li>
 *   <li><b>les bandes horizontales</b> — tous les octets d'une ligne sont égaux, et la ligne
 *       dit son numéro. Un décalage VERTICAL mal appliqué saute aux yeux, alors que le damier
 *       le cacherait à moitié ;</li>
 *   <li><b>le bruit</b> — une suite pseudo-aléatoire, où aucune coïncidence heureuse ne peut
 *       faire passer un mauvais index pour le bon.</li>
 * </ul>
 *
 * <p>Les HUIT pas de l'effet sont relevés pour chacun : le premier est presque net, le dernier
 * illisible, et c'est la table seule qui fait la différence.
 *
 * <p>Deux pièges guettent le portage, et ces relevés les attrapent tous les deux. Le pointeur
 * de source avance de huit APRÈS les deux lectures — l'oublier donne des traînées horizontales.
 * Et le curseur de la table s'enroule à la fin de chaque LIGNE, pas du bloc : l'oublier fait
 * dériver le motif d'une ligne à l'autre.
 */
public final class ShimmerExport {

    /** La largeur d'une ligne de l'écran chunky. */
    private static final int LARGEUR = 320;

    /** La hauteur de l'écran. */
    private static final int HAUTEUR = 256;

    /** Les huit blocs de la table : autant de pas à l'effet. */
    private static final int PAS = 8;

    private ShimmerExport() {
    }

    public static void extract(Path out) throws Exception {
        // Toucher DrawData suffit à charger shimmerfile : c'est son initialisation statique qui
        // appelle Assets.incbin("includes/shimmerfile").
        int table = ab3d2.data.DrawData.draw_TeleportShimmerFXData_vb;

        int src = Mem.alloc(LARGEUR * HAUTEUR);
        int dst = Mem.alloc(LARGEUR * HAUTEUR);
        Base64.Encoder encodeur = Base64.getEncoder();

        List<Object> cas = new ArrayList<>();
        for (String motif : new String[] {"damier", "bandes", "bruit"}) {
            semer(src, motif);
            byte[] entree = lire(src);
            List<String> sorties = new ArrayList<>(PAS);
            for (int f = 0; f < PAS; f++) {
                ScreenC.applyTeleportShimmer(src, dst, f);
                sorties.add(encodeur.encodeToString(lire(dst)));
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("pattern", motif);
            c.put("width", LARGEUR);
            c.put("height", HAUTEUR);
            c.put("source", encodeur.encodeToString(entree));
            c.put("frames", sorties);
            cas.add(c);
        }

        Map<String, Object> racine = new LinkedHashMap<>();
        racine.put("tableBytes", tailleTable(table));
        racine.put("cases", cas);

        Files.createDirectories(out);
        Path f = out.resolve("shimmer.json");
        Files.writeString(f, new GsonBuilder().create().toJson(racine));
        System.out.printf("[shimmer] %d motifs x %d pas -> %s%n", cas.size(), PAS, f);
    }

    /** Le motif de départ, écrit dans la RAM du portage. */
    private static void semer(int buffer, String motif) {
        int graine = 0x2F6E2B1;
        for (int y = 0; y < HAUTEUR; y++) {
            for (int x = 0; x < LARGEUR; x++) {
                int v;
                switch (motif) {
                    case "damier" -> v = ((x + y) & 1) == 0 ? (x * 7 + y * 13) & 0xFF
                            : 255 - ((x * 7 + y * 13) & 0xFF);
                    case "bandes" -> v = y & 0xFF;
                    default -> {
                        graine = graine * 1103515245 + 12345;
                        v = (graine >>> 16) & 0xFF;
                    }
                }
                Mem.wb(buffer + y * LARGEUR + x, v);
            }
        }
    }

    private static byte[] lire(int buffer) {
        byte[] out = new byte[LARGEUR * HAUTEUR];
        System.arraycopy(Mem.RAM, buffer, out, 0, out.length);
        return out;
    }

    /** Huit blocs de mille vingt-quatre : on le relève pour que la vérification le confirme. */
    private static int tailleTable(int table) {
        return PAS * 1024;
    }
}
