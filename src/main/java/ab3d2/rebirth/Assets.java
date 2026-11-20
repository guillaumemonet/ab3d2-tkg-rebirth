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

    /**
     * LES TEXTURES AGRANDIES, quand on les demande (-Phires).
     *
     * <p>Les textures du jeu font de seize a deux cent cinquante-six pixels de large : vues
     * de pres, un mur n'est plus qu'une poignee de carres. Un agrandisseur (DAT x4, cote
     * Stable Diffusion) en tire une version quatre fois plus fine, ecrite dans un dossier
     * PARALLELE -- << sub >> devient << sub4x >>, << floors >> devient << floors4x >>. Les
     * originaux ne bougent pas, et ce qui n'a pas de version agrandie se charge comme avant.
     *
     * <p>Le relief, la hauteur et la brillance se calculant a partir de l'image chargee,
     * ils gagnent la meme finesse sans qu'on ait a y toucher.
     */
    private static final boolean HIRES = System.getProperty("rebirth.hires") != null;

    /**
     * Le chemin agrandi correspondant, ou {@code null} s'il n'y en a pas.
     *
     * <p>TOLERANT SUR LE NOM. L'outil ecrit {@code floor_05.png} a cote de l'original, mais
     * une texture refaite a la main arrive sous le nom qu'on lui a donne --
     * {@code floor_05hd.jpg} par exemple. On accepte donc le meme debut de nom suivi de ce
     * qu'on veut, dans n'importe quel format d'image, a condition que la suite ne commence
     * pas par un chiffre : sans cela {@code wall_1_0_12} attraperait {@code wall_1_0_128}.
     *
     * <p>Et on le DIT au premier trouve. Un fichier mal nomme etait jusqu'ici ignore en
     * silence, ce qui est la pire facon d'echouer : on cherche le defaut dans le rendu
     * pendant que le fichier n'a simplement jamais ete lu.
     */
    private static String agrandi(String relative) {
        int barre = relative.lastIndexOf('/');
        if (barre <= 0) {
            return null;
        }
        String dossier = relative.substring(0, barre) + "4x";
        String fichier = relative.substring(barre + 1);
        int point = fichier.lastIndexOf('.');
        String base = point < 0 ? fichier : fichier.substring(0, point);
        java.nio.file.Path dir = path(dossier);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        // LE NOM LE PLUS LONG GAGNE. L'outil ecrit floor_05.png ; une texture refaite a la
        // main arrive sous floor_05hd.jpg et doit l'emporter -- un nom plus precis est un
        // choix delibere, le nom nu est ce que la chaine automatique produit.
        try (java.util.stream.Stream<java.nio.file.Path> liste = Files.list(dir)) {
            java.util.Optional<String> trouve = liste
                    .map(f -> f.getFileName().toString())
                    .filter(f -> convient(f, base))
                    .max(java.util.Comparator.comparingInt(String::length)
                            .thenComparing(java.util.Comparator.naturalOrder()));
            if (trouve.isPresent()) {
                String chemin = dossier + "/" + trouve.get();
                if (DITES.add(base)) {
                    System.out.println("[Assets] " + relative + " -> " + chemin);
                }
                return chemin;
            }
        } catch (IOException ignore) {
            return null;
        }
        return null;
    }

    /** Les substitutions deja annoncees : une ligne par texture, pas une par appel. */
    private static final java.util.Set<String> DITES = new java.util.HashSet<>();

    /** Ce fichier remplace-t-il la texture {@code base} ? */
    private static boolean convient(String fichier, String base) {
        int point = fichier.lastIndexOf('.');
        if (point < 0) {
            return false;
        }
        String ext = fichier.substring(point + 1).toLowerCase(java.util.Locale.ROOT);
        if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) {
            return false;
        }
        String nom = fichier.substring(0, point);
        if (!nom.startsWith(base)) {
            return false;
        }
        // Ce qui suit le nom de base ne doit pas etre un chiffre : voir le javadoc.
        return nom.length() == base.length() || !Character.isDigit(nom.charAt(base.length()));
    }

    /** Charge un PNG en BufferedImage (cache). Renvoie null si absent. */
    public static BufferedImage image(String relative) {
        if (IMAGES.containsKey(relative)) {
            return IMAGES.get(relative);
        }
        BufferedImage img = null;
        String voulu = relative;
        if (HIRES) {
            String gros = agrandi(relative);
            if (gros != null) {
                voulu = gros;
            }
        }
        Path p = path(voulu);
        if (Files.isRegularFile(p)) {
            try {
                img = ImageIO.read(p.toFile());
            } catch (IOException e) {
                System.err.println("[Assets] PNG illisible : " + p + " (" + e.getMessage() + ")");
            }
        }
        // UNE PLANCHE DE QUATRE CARTES se reconnait ici, et on n'en rend que la COULEUR.
        // Tout le jeu demande une texture par son chemin et attend une image a plaquer ;
        // lui rendre la planche entiere couvrirait les murs d'un quart de leur propre
        // texture. Les trois autres cartes restent disponibles par cartes().
        CartesPackees quatre = CartesPackees.decouper(img);
        if (quatre != null) {
            CARTES.put(relative, quatre);
            img = quatre.couleur;
        }
        // La cle du cache reste le chemin DEMANDE : tout le reste du jeu continue de nommer
        // les textures par leur chemin d'origine, qu'elles soient agrandies ou non.
        IMAGES.put(relative, img);
        return img;
    }

    /** Les planches de quatre cartes deja reconnues, par chemin demande. */
    private static final java.util.Map<String, CartesPackees> CARTES = new java.util.HashMap<>();

    /**
     * Les quatre cartes d'une texture, si elle est venue en planche.
     *
     * <p>Charge l'image au besoin : demander les cartes d'une texture jamais lue doit
     * marcher.
     */
    public static CartesPackees cartes(String relative) {
        if (!IMAGES.containsKey(relative)) {
            image(relative);
        }
        return CARTES.get(relative);
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
