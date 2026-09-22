package ab3d2.rebirth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Ou se trouvent les fichiers, selon la facon dont le jeu a ete lance.
 *
 * <p>Deux situations :
 * <ul>
 *   <li><b>build redistribuable</b> ({@code gradle -p rebirth/game packageApp}) : le code tourne
 *       depuis {@code <App>/app/*.jar}, les assets sont dans {@code <App>/assets} et les fichiers
 *       ecrits par le jeu (sauvegardes, reglages, journal) dans {@code <App>/run} ;</li>
 *   <li><b>developpement</b> : on cherche {@code assets} en remontant depuis le repertoire
 *       courant, et on ecrit dans le repertoire courant.</li>
 * </ul>
 *
 * <p>Les proprietes {@code -Drebirth.assets} et {@code -Drebirth.run} priment toujours.
 */
public final class AppDirs {

    private static final Path BASE = appImageBase();

    private AppDirs() {
    }

    /** Racine d'un build jpackage ({@code <App>}), ou {@code null} en developpement. */
    private static Path appImageBase() {
        try {
            Path code = Paths.get(AppDirs.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            Path parent = code.getParent();
            if (Files.isRegularFile(code) && parent != null
                    && "app".equals(String.valueOf(parent.getFileName()))) {
                return parent.getParent();             // <App>/app/foo.jar -> <App>
            }
        } catch (Exception ignore) {
            // source indeterminable : on retombe sur le repertoire courant
        }
        return null;
    }

    public static boolean packaged() {
        return BASE != null;
    }

    /** Le dossier des assets extraits. */
    public static Path assets() {
        String prop = System.getProperty("rebirth.assets");
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop).toAbsolutePath().normalize();
        }
        if (BASE != null) {
            return BASE.resolve("assets");
        }
        for (String candidate : new String[] { "assets", "../assets", "../../assets" }) {
            Path p = Paths.get(candidate).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "Assets rebirth introuvables (lancer `gradle -p rebirth extract` ?)");
    }

    /** Le dossier ou le jeu ECRIT : sauvegardes, reglages, journal. */
    public static Path run() {
        String prop = System.getProperty("rebirth.run");
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop).toAbsolutePath().normalize();
        }
        Path dir = BASE != null ? BASE.resolve("run") : Paths.get(".").toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            System.err.println("[AppDirs] " + dir + " non creable (" + e.getMessage() + ")");
        }
        return dir;
    }

    /** Un fichier du dossier d'ecriture. */
    public static Path runFile(String name) {
        return run().resolve(name);
    }

    /**
     * Build redistribuable : l'executable Windows n'ouvre pas de console, donc tout ce que le
     * jeu ecrit serait perdu. On redirige vers {@code <App>/run/rebirth.log}.
     * {@code -Drebirth.log=off} garde la sortie telle quelle, {@code -Drebirth.log=<chemin>} la
     * detourne ailleurs.
     */
    public static void setupLogging() {
        String prop = System.getProperty("rebirth.log");
        if ("off".equalsIgnoreCase(prop)) {
            return;
        }
        if (prop == null && BASE == null) {
            return;                                    // developpement : on garde la console
        }
        Path log = prop != null ? Paths.get(prop) : runFile("rebirth.log");
        try {
            if (log.getParent() != null) {
                Files.createDirectories(log.getParent());
            }
            java.io.PrintStream out = new java.io.PrintStream(
                    new java.io.BufferedOutputStream(Files.newOutputStream(log)), true, "UTF-8");
            System.setOut(out);
            System.setErr(out);
            Runtime.getRuntime().addShutdownHook(new Thread(out::flush));
            System.out.println("[rebirth] journal " + log);
        } catch (Exception e) {
            System.err.println("[AppDirs] journal impossible (" + e.getMessage() + ")");
        }
    }
}
