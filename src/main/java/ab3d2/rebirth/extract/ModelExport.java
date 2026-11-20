package ab3d2.rebirth.extract;

import ab3d2.rebirth.Assets;
import ab3d2.rebirth.ObjModels;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.ClasspathLocator;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ecrit les modeles vectoriels en <b>{@code .j3o}</b>, le format natif de jMonkeyEngine.
 *
 * <p>Ils arrivaient de l'extraction en {@code frame_NNN.obj} plus un {@code frame_NNN.lvl} a
 * cote, et un analyseur OBJ maison les relisait au chargement. Cela marchait, mais aucun des
 * deux fichiers n'existe pour le SDK : on ne pouvait ni ouvrir un modele, ni le regarder
 * tourner, ni voir sur quoi il etait plaque.
 *
 * <p>Un {@code .j3o} est le meme maillage, en binaire, avec son materiau accroche dessus. Le
 * SDK l'ouvre, le jeu le charge par {@code assetManager.loadModel}, et le couple OBJ+LVL
 * redevient ce qu'il aurait toujours du etre : une SOURCE d'extraction, pas un format de
 * chargement.
 *
 * <h2>Ou passent le niveau et l'angle des faces</h2>
 *
 * <p>Le {@code .lvl} donnait, par FACE : le niveau de luminosite de base, et l'octet d'angle
 * (secteur horizontal en quartet faible, position verticale en quartet fort). Le remake en a
 * besoin a chaque pose d'objet, parce que la luminosite d'une face depend de l'endroit du
 * niveau ou l'objet se trouve -- c'est {@code LevelBuilder.buildModelMesh} qui la calcule.
 *
 * <p>Ils voyagent donc dans le maillage lui-meme, en {@code TexCoord2}, un couple
 * {@code (niveau, angle)} repete sur les trois sommets de chaque triangle. C'est la seule
 * facon de les faire tenir dans un {@code .j3o} sans inventer un format a cote -- et un
 * tampon de sommets, contrairement a un fichier sidecar, ne peut pas se perdre en chemin.
 *
 * <p>Le maillage est volontairement NON INDEXE : les sommets arrivent par triangles, dans
 * l'ordre des lignes {@code f} de l'OBJ, et c'est cet ordre que le jeu relit.
 *
 * <pre>
 * gradle -p rebirth models
 * </pre>
 */
public final class ModelExport {

    /** Le materiau des modeles, ecrit par {@link MaterialExport}. */
    private static final String MATERIAU = "materials/model_shaded.j3m";

    private ModelExport() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Assets.root();
        Path dir = root.resolve("models");
        if (!Files.isDirectory(dir)) {
            System.err.println("[models] " + dir + " absent : lancer `gradle -p rebirth extract`");
            return;
        }

        AssetManager am = new DesktopAssetManager(true);
        am.registerLocator(root.toString(), FileLocator.class);
        am.registerLocator(null, ClasspathLocator.class);
        Material mat = null;
        try {
            mat = am.loadMaterial(MATERIAU);
        } catch (RuntimeException absent) {
            System.err.println("[models] AVERTISSEMENT " + MATERIAU + " introuvable ("
                    + absent.getMessage() + ") : lancer `gradle -p rebirth materials` d'abord."
                    + " Les .j3o sortiront sans materiau.");
        }

        int modeles = 0;
        int images = 0;
        try (java.util.stream.Stream<Path> noms = Files.list(dir)) {
            for (Path d : noms.filter(Files::isDirectory).sorted().toList()) {
                int n = 0;
                try (java.util.stream.Stream<Path> fs = Files.list(d)) {
                    for (Path obj : fs.filter(f -> f.toString().endsWith(".obj")).sorted()
                            .toList()) {
                        String rel = "models/" + d.getFileName() + "/"
                                + obj.getFileName().toString().replace(".obj", "");
                        ObjModels.Model m = ObjModels.load(rel + ".obj");
                        if (m == null) {
                            continue;
                        }
                        Geometry g = new Geometry(d.getFileName() + "_"
                                + obj.getFileName().toString().replace(".obj", ""), maillage(m));
                        if (mat != null) {
                            g.setMaterial(mat);
                        }
                        BinaryExporter.getInstance().save(g, root.resolve(rel + ".j3o").toFile());
                        n++;
                    }
                }
                if (n > 0) {
                    modeles++;
                    images += n;
                }
            }
        }
        System.out.printf("[models] %d modeles, %d images -> .j3o%s%n", modeles, images,
                mat == null ? " (sans materiau)" : " avec " + MATERIAU);
    }

    /**
     * Le maillage d'une image : positions, UV de l'atlas, et le couple (niveau, angle) de la
     * face sur chacun de ses trois sommets.
     */
    private static Mesh maillage(ObjModels.Model m) {
        int nv = m.tris.length * 3;
        float[] pos = new float[nv * 3];
        float[] uv = new float[nv * 2];
        float[] face = new float[nv * 2];
        for (int i = 0; i < m.tris.length; i++) {
            float[] t = m.tris[i];
            int f = i < m.triFace.length ? m.triFace[i] : 0;
            float niveau = f < m.faceLevel.length ? m.faceLevel[f] : 0f;
            float angle = f < m.faceAng.length ? m.faceAng[f] : 0f;
            for (int k = 0; k < 3; k++) {
                int v = i * 3 + k;
                pos[v * 3] = t[k * 5];
                pos[v * 3 + 1] = t[k * 5 + 1];
                pos[v * 3 + 2] = t[k * 5 + 2];
                uv[v * 2] = t[k * 5 + 3];
                uv[v * 2 + 1] = t[k * 5 + 4];
                face[v * 2] = niveau;
                face[v * 2 + 1] = angle;
            }
        }
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(uv));
        mesh.setBuffer(VertexBuffer.Type.TexCoord2, 2, BufferUtils.createFloatBuffer(face));
        mesh.updateBound();
        return mesh;
    }
}
