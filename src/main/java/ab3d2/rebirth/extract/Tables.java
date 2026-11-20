package ab3d2.rebirth.extract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Tables numériques d'origine nécessaires à la SIMULATION du remake (pas au rendu).
 *
 * <p>{@code bigsine} = SinCosTable_vw (data/tables_data.s) : 8192 mots big-endian, soit deux
 * cycles complets de 4096 entrées ; valeur = {@code round(32767 * sin(2π·i/4096))}. Le remake
 * doit utiliser CETTE table (et pas un {@code Math.sin}) : la trajectoire du joueur dépend de
 * ses arrondis exacts. Copiée telle quelle en {@code assets/tables/sincos.bin}.
 */
public final class Tables {

    private Tables() {
    }

    public static void extract(Path out) throws IOException {
        Path dir = out.resolve("tables");
        Files.createDirectories(dir);
        // bigsine et guff ne sont sur AUCUNE disquette : ce sont des incbin, embarques dans
        // le depot. On passe donc par Assets, qui connait les deux sources.
        byte[] sin = ab3d2.Assets.bytes("bigsine");
        Path dst = dir.resolve("sincos.bin");
        Files.write(dst, sin);
        System.out.println("[tables] sincos.bin (" + sin.length + " o)");

        // guff : 16 positions verticales × 7 rangées × 16 directions — la grille de luminosité
        // appliquée aux sprites éclairés (drawBitmapLighted, Objdrawhires.java:1162).
        byte[] guff = ab3d2.Assets.bytes("includes/guff");
        if (guff != null) {
            Files.write(dir.resolve("guff.bin"), guff);
            System.out.println("[tables] guff.bin (" + guff.length + " o)");
        }
    }
}
