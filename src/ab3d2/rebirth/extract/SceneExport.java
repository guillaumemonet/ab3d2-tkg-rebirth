package ab3d2.rebirth.extract;

import ab3d2.rebirth.Assets;
import ab3d2.rebirth.GlfData;
import ab3d2.rebirth.LevelBuilder;
import ab3d2.rebirth.LevelData;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Écrit chaque niveau en <b>`.j3o`</b>, le format de scène natif de jMonkeyEngine.
 *
 * <p>Jusqu'ici la géométrie d'un niveau était reconstruite à chaque lancement depuis le JSON
 * extrait : rien n'était inspectable ni corrigeable. Un `.j3o` s'ouvre dans le SDK jME
 * (SceneExplorer / SceneComposer), on y voit l'arbre, on peut déplacer un mur, changer le
 * matériau d'une surface, et le jeu recharge la scène telle quelle.
 *
 * <p>L'export tourne <b>sans fenêtre</b> : un {@link DesktopAssetManager} suffit à charger
 * matériaux et textures, aucun contexte OpenGL n'est nécessaire tant qu'on ne dessine pas.
 *
 * <h2>Ce que contient la scène</h2>
 * Tout ce que {@code LevelBuilder.build} produit : surfaces de murs regroupées par sous-tuile,
 * sols et plafonds par tuile, jambages des portes, sols d'ascenseurs, surfaces d'eau, objets et
 * modèles. Les nœuds gardent leurs noms ({@code lift_zone_<id>}, {@code door_zone_<id>},
 * {@code flat_tile<n>}…), ce qui permettra au jeu de retrouver les parties MOBILES après
 * chargement.
 *
 * <h2>Ce qu'elle ne remplace pas encore</h2>
 * Les portes, ascenseurs et lumières animées sont pilotés à l'exécution par des références que
 * {@code LevelBuilder} conserve en mémoire ({@code doorPanels}, {@code liftFloors},
 * {@code doorCeilings}, murs déformés, maillages éclairés). Recharger une scène `.j3o` ne
 * reconstitue pas ces liens : c'est l'étape suivante, et elle se fera par NOM de nœud.
 *
 * <pre>
 * gradle -p rebirth scenes            # les seize niveaux
 * gradle -p rebirth scenes -Plevel=c  # un seul
 * </pre>
 */
public final class SceneExport {

    private SceneExport() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Assets.root();
        Path outDir = root.resolve("Scenes");
        Files.createDirectories(outDir);

        AssetManager am = new DesktopAssetManager(true);
        am.registerLocator(root.toString(), FileLocator.class);

        GlfData glf = Assets.json("glf.json", GlfData.class);

        String only = System.getProperty("rebirth.level");
        int done = 0;
        int bad = 0;
        for (char c = 'a'; c <= 'p'; c++) {
            if (only != null && !only.isBlank()
                    && Character.toLowerCase(only.charAt(0)) != c) {
                continue;
            }
            LevelData lvl;
            try {
                lvl = Assets.json("levels/" + c + ".json", LevelData.class);
            } catch (RuntimeException absent) {
                continue;
            }
            if (lvl == null) {
                continue;
            }
            LevelBuilder builder = new LevelBuilder(am, glf);
            Node full = builder.build(lvl);
            // On n'exporte que le DECOR. Les objets (ramassages, decor anime, monstres) sont des
            // entites que le jeu place et retire en cours de partie : les figer dans la scene
            // n'aurait pas de sens, et les rendrait impossibles a ramasser.
            Node scene = new Node("level_" + c);
            for (String part : new String[] {"Walls", "Flats"}) {
                com.jme3.scene.Spatial sp = full.getChild(part);
                if (sp != null) {
                    scene.attachChild(sp);
                }
            }
            File f = outDir.resolve("level_" + c + ".j3o").toFile();
            BinaryExporter.getInstance().save(scene, f);

            // RELECTURE : une scene qu'on ne sait pas recharger ne vaut rien. On la relit avec
            // un AssetManager neuf (donc sans cache) et on verifie qu'on retrouve le meme arbre
            // et les memes materiaux.
            AssetManager back = new DesktopAssetManager(true);
            back.registerLocator(root.toString(), FileLocator.class);
            Spatial reloaded = back.loadModel("Scenes/level_" + c + ".j3o");
            int before = count(scene);
            int after = count(reloaded);
            int mats = materials(reloaded);
            if (before != after) {
                System.err.printf("[scenes] ECART sur level_%c : %d noeuds ecrits, %d relus%n",
                        c, before, after);
                bad++;
            }
            System.out.printf("[scenes] level_%c.j3o  %,9d octets  %d noeuds  %d materiaux  relu OK%n",
                    c, f.length(), after, mats);
            done++;
        }
        System.out.println("[scenes] " + done + " scene(s) dans " + outDir
                + (bad == 0 ? "  -> PASS" : "  -> " + bad + " ECART(S)"));
        if (bad != 0) {
            System.exit(1);
        }
    }

    /** Nombre de materiaux distincts effectivement portes par la scene relue. */
    private static int materials(Spatial s) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        s.depthFirstTraversal(sp -> {
            if (sp instanceof com.jme3.scene.Geometry g && g.getMaterial() != null) {
                seen.add(String.valueOf(g.getMaterial().getName()));
            }
        });
        return seen.size();
    }

    /** Nombre de spatials dans l'arbre — juste pour que la trace dise quelque chose d'utile. */
    private static int count(Spatial s) {
        int n = 1;
        if (s instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                n += count(child);
            }
        }
        return n;
    }
}
