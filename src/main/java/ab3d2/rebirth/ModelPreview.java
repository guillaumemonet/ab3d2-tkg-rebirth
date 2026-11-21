package ab3d2.rebirth;

import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
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
 * <p>Avec {@code -Ppose}, il sert d'EPREUVE AU MELANGE : la meme pose, prise soit du maillage
 * de cette image, soit de la pose 0 que le moteur deforme. Les deux captures doivent etre
 * identiques au pixel. Une pose decimale -- {@code -Ppose=7,5} -- montre l'entre-deux, celui
 * que le jeu interpole entre deux pas d'animation et que l'Amiga ne pouvait pas montrer.
 *
 * <pre>
 * gradle -p rebirth preview -Pmodele=D:/IA/3D/work/out/ashnarg/0/mesh.glb
 * gradle -p rebirth preview -Pmodele=assets/models/CRAB/frame_000.j3o -Papercu=crab.png
 * gradle -p rebirth preview -Pmodele=assets/models/CRAB/frame_000.j3o -Ppose=7
 * gradle -p rebirth preview -Pmodele=assets/models/CRAB/frame_000.j3o -Ppose=7 -Pmelange
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
    /** Niveau de lumiere impose aux faces, ou -1 pour garder celui du modele. */
    private final int niveau;
    /** Pose a montrer, ou -1 pour charger le fichier tel quel. Reelle : 7,5 est un entre-deux. */
    private final float pose;
    /** -Pmelange : obtenir la pose par deformation de la pose 0, au lieu de son maillage. */
    private final boolean melange = System.getProperty("rebirth.melange") != null;
    /** Le volume sur lequel cadrer, impose en mode pose pour que deux captures se comparent. */
    private com.jme3.bounding.BoundingBox cadreSur;
    private Spatial modele;
    private float rayon = 1f;
    private Vector3f centre = Vector3f.ZERO;
    private int prise;
    private final java.awt.image.BufferedImage planche =
            new java.awt.image.BufferedImage(COTE * 4, COTE * 2,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);

    private ModelPreview(String chemin, String sortie, int niveau, float pose) {
        this.chemin = chemin;
        this.sortie = sortie;
        this.niveau = niveau;
        this.pose = pose;
    }

    public static void main(String[] args) {
        String chemin = System.getProperty("rebirth.modele");
        if (chemin == null || chemin.isBlank()) {
            System.err.println("Donner le modele : -Pmodele=<chemin vers .glb/.j3o/.obj>");
            return;
        }
        String sortie = System.getProperty("rebirth.apercu", "apercu.png");
        String quellePose = System.getProperty("rebirth.pose");
        ModelPreview app = new ModelPreview(chemin, sortie,
                Integer.getInteger("rebirth.niveau", -1),
                quellePose == null ? -1f : Float.parseFloat(quellePose.replace(',', '.')));
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
        if (pose >= 0) {
            modele = unePose(f);
            if (modele == null) {
                stop();
                return;
            }
        } else {
            try {
                modele = assetManager.loadModel(f.getName());
                adoucirLeMetal(modele);
            } catch (RuntimeException e) {
                System.err.println("[apercu] " + chemin + " illisible : " + e);
                stop();
                return;
            }
        }

        if (niveau >= 0) {
            eclairerLesFaces(modele, niveau);
        }

        com.jme3.bounding.BoundingVolume bv = cadreSur != null ? cadreSur : modele.getWorldBound();
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

    /**
     * UNE POSE, PRISE DE L'UNE DES DEUX FACONS, A LA MEME PLACE ET DANS LE MEME CADRE.
     *
     * <p>{@code -Ppose=7} montre le maillage de l'image 7, charge tel quel ;
     * {@code -Ppose=7 -Pmelange} montre le maillage de la pose 0 que le moteur DEFORME jusqu'a
     * cette meme image. Les deux images doivent etre IDENTIQUES AU PIXEL -- c'est la seule
     * facon honnete d'eprouver le melange, et c'est pourquoi les deux ne sont pas mises cote a
     * cote : en perspective, deux objets poses a deux endroits ne se voient pas sous le meme
     * angle, et la comparaison ne voudrait rien dire.
     *
     * <p>Le cadrage se prend sur la POSE 0 dans les deux cas, sans quoi deux poses de tailles
     * differentes seraient vues de deux distances differentes.
     *
     * <p>{@code -Pmodele} designe ici un fichier d'image quelconque du modele -- c'est son
     * DOSSIER qui nomme le modele.
     */
    private com.jme3.scene.Spatial unePose(java.io.File f) {
        String nom = f.getAbsoluteFile().getParentFile().getName();
        MorphModels.Poses poses = MorphModels.charger(assetManager, nom);
        if (poses == null) {
            System.err.println("[apercu] " + nom + " ne se melange pas : ses images n'ont pas"
                    + " le meme decoupage");
            return null;
        }
        if (pose > poses.nombre() - 1) {
            System.err.println("[apercu] " + nom + " n'a que " + poses.nombre() + " poses");
            return null;
        }
        int entiere = (int) pose;
        float entreDeux = pose - entiere;
        cadreSur = (com.jme3.bounding.BoundingBox) maillage(poses.base).getBound();
        Geometry g;
        if (melange) {
            g = new Geometry("melange", maillage(poses.base));
            g.setMaterial(materiauDuModele());
            MorphModels.preparer(g, poses);
            // UN ENTRE-DEUX SE DEMANDE EN DECIMALES : -Ppose=7,5 est le milieu du chemin
            // entre la septieme pose et la huitieme -- ce que le jeu montre entre deux pas
            // d'animation, et que l'Amiga ne pouvait pas montrer.
            g.setMorphState(entreDeux == 0f ? poses.poidsDe(entiere)
                    : MorphModels.Poses.melange(poses.poidsDe(entiere),
                            poses.poidsDe(entiere + 1), entreDeux, null));
        } else {
            ObjModels.Model tel = ObjModels.load(assetManager,
                    String.format("models/%s/frame_%03d.obj", nom, entiere));
            if (tel == null) {
                System.err.println("[apercu] pose " + pose + " introuvable");
                return null;
            }
            g = new Geometry("tel-quel", maillage(tel));
            g.setMaterial(materiauDuModele());
        }
        System.out.printf(java.util.Locale.ROOT, "[apercu] %s pose %.2f sur %d, %s%n", nom,
                pose, poses.nombre() - 1, melange ? "par melange" : "maillage tel quel");
        return g;
    }

    /** Le maillage brut d'un modele : positions, UV, normales plates. Pas d'eclairage de face. */
    private static com.jme3.scene.Mesh maillage(ObjModels.Model m) {
        MeshBuilder mb = new MeshBuilder();
        for (float[] t : m.tris) {
            for (int k = 0; k < 3; k++) {
                mb.vertex(t[k * 5], t[k * 5 + 1], t[k * 5 + 2], t[k * 5 + 3], t[k * 5 + 4]);
            }
        }
        return mb.build();
    }

    /** Le materiau du jeu en rendu moderne : l'atlas en couleurs, eclaire par le moteur. */
    /**
     * UN METAL SANS ENVIRONNEMENT REND NOIR.
     *
     * <p>Stable Fast 3D exporte ses modeles avec {@code Metallic = 1} et AUCUNE carte
     * metal-rugosite : tout ce qu'il produit se declare donc metal pur. Or un metal, en rendu
     * PBR, n'a pas de diffus -- sa couleur de base devient celle de ses REFLETS -- et sans
     * sonde d'environnement il n'a rien a refleter que les deux lampes de l'apercu. Le modele
     * sort presque noir, et on ne peut rien en juger.
     *
     * <p>On remet donc le metal a zero quand le modele n'a PAS de carte pour en decider -- s'il
     * en porte une, c'est elle qui commande et on n'y touche pas -- et on le DIT, pour que
     * personne ne croie voir la matiere que le fichier declare.
     */
    private void adoucirLeMetal(Spatial s) {
        if (s instanceof Node n) {
            for (Spatial c : n.getChildren()) {
                adoucirLeMetal(c);
            }
            return;
        }
        if (!(s instanceof Geometry g) || g.getMaterial() == null) {
            return;
        }
        com.jme3.material.Material m = g.getMaterial();
        if (m.getParam("MetallicRoughnessMap") != null
                || m.getMaterialDef().getMaterialParam("Metallic") == null) {
            return;
        }
        com.jme3.material.MatParam p = m.getParam("Metallic");
        if (p != null && p.getValue() instanceof Float f && f >= 0.9f) {
            m.setFloat("Metallic", 0f);
            System.out.printf("[apercu] %s se declare metal pur sans carte metal-rugosite :"
                    + " metal ramene a 0, sinon il n'a rien a refleter%n", g.getName());
        }
    }

    private com.jme3.material.Material materiauDuModele() {
        com.jme3.material.Material mat = new com.jme3.material.Material(assetManager,
                "Common/MatDefs/Light/Lighting.j3md");
        mat.setTexture("DiffuseMap", Assets.texture("textures/texturemaps_atlas.png"));
        mat.setBoolean("UseMaterialColors", true);
        mat.setColor("Diffuse", ColorRGBA.White);
        mat.setColor("Ambient", ColorRGBA.White);
        mat.setColor("Specular", ColorRGBA.Black);
        mat.setFloat("Shininess", 1f);
        mat.getAdditionalRenderState().setFaceCullMode(
                com.jme3.material.RenderState.FaceCullMode.Off);
        return mat;
    }

    /**
     * IMPOSE UN NIVEAU DE LUMIERE A TOUTES LES FACES.
     *
     * <p>Un modele vectoriel porte, en {@code TexCoord2}, le niveau de base de chaque face : le
     * jeu y ajoute l'anneau directionnel de l'endroit ou l'objet se trouve, et c'est seulement
     * la somme que le nuanceur lit. Hors niveau, personne n'ajoute rien -- l'apercu sort donc
     * presque noir, et on n'y voit pas la texture. {@code -Pniveau=31} la montre a plein jour.
     */
    private static void eclairerLesFaces(Spatial quoi, int niveau) {
        if (quoi instanceof Node n) {
            for (Spatial enfant : n.getChildren()) {
                eclairerLesFaces(enfant, niveau);
            }
            return;
        }
        if (!(quoi instanceof com.jme3.scene.Geometry g)) {
            return;
        }
        com.jme3.scene.VertexBuffer vb =
                g.getMesh().getBuffer(com.jme3.scene.VertexBuffer.Type.TexCoord2);
        if (vb == null || !(vb.getData() instanceof java.nio.FloatBuffer f)) {
            return;
        }
        for (int i = 0; i < f.limit(); i += 2) {
            f.put(i, niveau);
        }
        vb.updateData(f);
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
        // LE FORMAT SE DEMANDE, IL NE SE DEVINE PAS. `readFrameBuffer` sans format rend ce que
        // le pilote veut ; ici il rendait du RGBA quand `convertScreenShot` attend du BGRA, et
        // l'apercu ecrivait donc ses PNG avec le ROUGE ET LE BLEU INTERVERTIS. Invisible sur
        // les modeles du jeu, qui sont gris ; flagrant sur une trousse de soins, dont la croix
        // rouge sortait bleue. Les captures du JEU, elles, n'ont jamais eu ce defaut.
        renderer.readFrameBufferWithFormat(null, bb, com.jme3.texture.Image.Format.BGRA8);
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
