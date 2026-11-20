package ab3d2.rebirth;

import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.system.AppSettings;

/**
 * REGARDER UN MODELE SOUS TOUTES SES FACES, sans ouvrir le SDK.
 *
 * <p>Charge un fichier de modele -- {@code .j3o}, {@code .glb}, {@code .gltf}, {@code .obj} --
 * et en ecrit un TOURNIQUET : huit vues a quarante-cinq degres, dans une seule image. C'est
 * tout ce qu'il faut pour juger une silhouette, et c'est ce qui manquait des qu'on a commence
 * a fabriquer des modeles au lieu de les dessiner.
 *
 * <p>Le modele est CADRE automatiquement : on lit son volume englobant, on le centre et on
 * recule la camera de ce qu'il faut. Un modele rend ses unites a lui -- un GLB sort en metres,
 * un {@code .j3o} du jeu en soixante-quatriemes -- et il n'y a aucune raison de les connaitre
 * pour le regarder.
 *
 * <pre>
 * gradle -p rebirth preview -Pmodele=D:/IA/3D/work/out/ashnarg/0/mesh.glb
 * gradle -p rebirth preview -Pmodele=assets/models/CRAB/frame_000.j3o -Papercu=crab.png
 * </pre>
 */
public final class ModelPreview extends SimpleApplication {

    /** Combien de vues, reparties sur le tour. */
    private static final int VUES = 8;
    /** Taille d'une vue dans la planche. */
    private static final int COTE = 320;
    /** De combien la camera recule, en rayons du modele. */
    private static final float RECUL = 2.6f;
    /** Hauteur de l'oeil, en rayons : une vue de trois quarts plutot que de plain-pied. */
    private static final float HAUTEUR = 0.55f;

    private final String chemin;
    private final String sortie;
    private Spatial modele;
    private float rayon = 1f;
    private Vector3f centre = Vector3f.ZERO;
    private int prise;
    private final java.awt.image.BufferedImage planche =
            new java.awt.image.BufferedImage(COTE * 4, COTE * 2,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);

    private ModelPreview(String chemin, String sortie) {
        this.chemin = chemin;
        this.sortie = sortie;
    }

    public static void main(String[] args) {
        String chemin = System.getProperty("rebirth.modele");
        if (chemin == null || chemin.isBlank()) {
            System.err.println("Donner le modele : -Pmodele=<chemin vers .glb/.j3o/.obj>");
            return;
        }
        String sortie = System.getProperty("rebirth.apercu", "apercu.png");
        ModelPreview app = new ModelPreview(chemin, sortie);
        AppSettings s = new AppSettings(true);
        s.setResolution(COTE, COTE);
        s.setTitle("apercu : " + chemin);
        s.setSamples(4);
        app.setSettings(s);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();
    }

    @Override
    public void simpleInitApp() {
        flyCam.setEnabled(false);
        setDisplayStatView(false);
        setDisplayFps(false);
        viewPort.setBackgroundColor(new ColorRGBA(0.10f, 0.10f, 0.12f, 1f));

        // LE DOSSIER DU FICHIER DEVIENT UNE RACINE D'ASSETS. Un GLB pose n'importe ou doit
        // pouvoir se charger, et ses textures sont a cote de lui.
        java.io.File f = new java.io.File(chemin);
        java.io.File dossier = f.getAbsoluteFile().getParentFile();
        assetManager.registerLocator(dossier.getAbsolutePath(),
                com.jme3.asset.plugins.FileLocator.class);
        // ET LES ASSETS DU JEU : un .j3o du projet porte un materiau qui renvoie a un
        // nuanceur et a des textures, et ceux-la sont sous assets/, pas a cote du modele.
        try {
            assetManager.registerLocator(Assets.root().toString(),
                    com.jme3.asset.plugins.FileLocator.class);
        } catch (RuntimeException horsProjet) {
            // apercu d'un fichier quelconque, hors du projet : il se suffit a lui-meme
        }
        try {
            modele = assetManager.loadModel(f.getName());
        } catch (RuntimeException e) {
            System.err.println("[apercu] " + chemin + " illisible : " + e);
            stop();
            return;
        }

        com.jme3.bounding.BoundingVolume bv = modele.getWorldBound();
        if (bv instanceof com.jme3.bounding.BoundingBox bb) {
            centre = bb.getCenter().clone();
            rayon = Math.max(0.001f, Math.max(bb.getXExtent(),
                    Math.max(bb.getYExtent(), bb.getZExtent())));
        } else if (bv instanceof com.jme3.bounding.BoundingSphere bs) {
            centre = bs.getCenter().clone();
            rayon = Math.max(0.001f, bs.getRadius());
        }
        Node pivot = new Node("pivot");
        modele.setLocalTranslation(centre.negate());
        pivot.attachChild(modele);
        rootNode.attachChild(pivot);

        DirectionalLight jour = new DirectionalLight();
        jour.setDirection(new Vector3f(-0.4f, -0.7f, -0.6f).normalizeLocal());
        jour.setColor(ColorRGBA.White.mult(1.1f));
        rootNode.addLight(jour);
        DirectionalLight appoint = new DirectionalLight();
        appoint.setDirection(new Vector3f(0.6f, -0.2f, 0.5f).normalizeLocal());
        appoint.setColor(new ColorRGBA(0.45f, 0.48f, 0.55f, 1f));
        rootNode.addLight(appoint);
        rootNode.addLight(new AmbientLight(ColorRGBA.White.mult(0.35f)));

        cam.setFrustumPerspective(45f, 1f, rayon * 0.01f, rayon * 40f);
        System.out.printf("[apercu] %s : rayon %.3f, centre %s%n", chemin, rayon, centre);
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (modele == null || prise >= VUES) {
            return;
        }
        float a = FastMath.TWO_PI * prise / VUES;
        float d = rayon * RECUL;
        cam.setLocation(new Vector3f(FastMath.sin(a) * d, rayon * HAUTEUR,
                FastMath.cos(a) * d));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        rootNode.updateGeometricState();
    }

    @Override
    public void simpleRender(com.jme3.renderer.RenderManager rm) {
        if (modele == null || prise >= VUES) {
            return;
        }
        // On lit l'image APRES le rendu de cette vue, et on passe a la suivante.
        java.nio.ByteBuffer bb = com.jme3.util.BufferUtils.createByteBuffer(COTE * COTE * 4);
        renderer.readFrameBuffer(null, bb);
        // TYPE_4BYTE_ABGR, et pas autre chose : Screenshots.convertScreenShot ecrit
        // directement dans le tampon d'octets de l'image, et jette un ClassCastException sur
        // n'importe quel autre type.
        java.awt.image.BufferedImage vue = new java.awt.image.BufferedImage(COTE, COTE,
                java.awt.image.BufferedImage.TYPE_4BYTE_ABGR);
        com.jme3.util.Screenshots.convertScreenShot(bb, vue);
        planche.getGraphics().drawImage(vue, (prise % 4) * COTE, (prise / 4) * COTE, null);
        prise++;
        if (prise >= VUES) {
            try {
                javax.imageio.ImageIO.write(planche, "png", new java.io.File(sortie));
                System.out.println("[apercu] " + VUES + " vues -> " + sortie);
            } catch (java.io.IOException e) {
                System.err.println("[apercu] ecriture impossible : " + e);
            }
            stop();
        }
    }
}
