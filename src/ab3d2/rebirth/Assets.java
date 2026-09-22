package ab3d2.rebirth;

import com.google.gson.Gson;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Acces aux assets modernes extraits par le sous-projet rebirth (../assets).
 *
 * <p>Le dossier assets/ reste la SOURCE DE VERITE neutre : on le lit au runtime (PNG/JSON/OBJ),
 * sans passer par le gestionnaire d'assets de jME (pas de duplication, pas de format proprietaire).
 */
public final class Assets {

    private static final Gson GSON = new Gson();
    private static final Map<String, BufferedImage> IMAGES = new HashMap<>();
    private static final Map<String, Texture2D> TEXTURES = new HashMap<>();

    /** Racine des assets ; sa resolution (dev ou build package) vit dans {@link AppDirs}. */
    private static final Path ROOT = AppDirs.assets();

    private Assets() {
    }

    public static Path root() {
        return ROOT;
    }

    public static Path path(String relative) {
        return ROOT.resolve(relative);
    }

    public static boolean exists(String relative) {
        return Files.isRegularFile(path(relative));
    }

    /** Lit un JSON d'assets et le mappe sur une classe. Renvoie null si le fichier manque. */
    public static <T> T json(String relative, Class<T> type) {
        Path p = path(relative);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try (var r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return GSON.fromJson(r, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture JSON " + p, e);
        }
    }

    /** Lit un fichier texte (OBJ, MTL...). Renvoie null si absent. */
    public static String text(String relative) {
        Path p = path(relative);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture texte " + p, e);
        }
    }

    /** Charge un PNG en BufferedImage (cache). Renvoie null si absent. */
    public static BufferedImage image(String relative) {
        if (IMAGES.containsKey(relative)) {
            return IMAGES.get(relative);
        }
        BufferedImage img = null;
        Path p = path(relative);
        if (Files.isRegularFile(p)) {
            try {
                img = ImageIO.read(p.toFile());
            } catch (IOException e) {
                System.err.println("[Assets] PNG illisible : " + p + " (" + e.getMessage() + ")");
            }
        }
        IMAGES.put(relative, img);
        return img;
    }

    /** Charge un PNG en Texture2D jME, filtre "au plus proche" (look retro). Null si absent. */
    public static Texture2D texture(String relative) {
        if (TEXTURES.containsKey(relative)) {
            return TEXTURES.get(relative);
        }
        BufferedImage img = image(relative);
        Texture2D tex = img == null ? null : toTexture(img);
        TEXTURES.put(relative, tex);
        return tex;
    }

    /**
     * BufferedImage -> Texture2D.
     *
     * <p>flipY=FALSE volontairement : les lignes sont televersees dans l'ordre du PNG, donc
     * v=0 correspond au HAUT de l'image (convention du port et de l'extraction, ou V est ancree
     * au monde avec Y vers le bas). Avec le defaut jME (flipY=true) il faudrait inverser V.
     */
    public static Texture2D toTexture(BufferedImage img) {
        Texture2D tex = new Texture2D(new AWTLoader().load(img, false));
        tex.setMagFilter(Texture.MagFilter.Nearest);
        tex.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        tex.setWrap(Texture.WrapMode.Repeat);
        return tex;
    }
}
