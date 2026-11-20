package ab3d2.rebirth;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.ClasspathLocator;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.bounding.BoundingBox;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture2D;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.FloatBuffer;

/**
 * LES PIXELS DU JEU, PLAQUES SUR LE VOLUME DEVINE.
 *
 * <p>L'outil sprite-vers-3D rend un maillage credible a partir d'UNE image, et lui invente une
 * texture : de face elle reprend l'image donnee, ailleurs elle la prolonge au jugé. Le resultat
 * est flou derriere, et sa palette n'est plus celle du jeu.
 *
 * <p>Or le jeu a les autres vues. Un monstre y est dessine de face, de profil et de dos --
 * c'est ce qui lui permet de tourner a l'ecran. Cette classe prend le maillage d'un cote, les
 * vues de l'autre, et PROJETTE les secondes sur le premier.
 *
 * <h2>Comment</h2>
 *
 * <p>On parcourt les triangles dans l'espace des COORDONNEES DE TEXTURE, pas a l'ecran : chaque
 * texel de l'atlas appartient a un triangle et un seul, et l'interpolation barycentrique donne
 * le point du monde et la normale qui lui correspondent. De la, pour chaque vue, on projette ce
 * point sur l'image et on lit sa couleur ; les vues sont melangees au prorata de ce qu'elles
 * VOIENT de la surface -- le produit scalaire de la normale et de la direction de regard, eleve
 * a une puissance pour que la vue la plus franche l'emporte nettement.
 *
 * <p>Le cadrage se fait tout seul : on compare la boite des pixels OPAQUES de chaque vue a la
 * boite du maillage projetee sur le meme plan. Il n'y a donc aucune echelle a connaitre, et une
 * vue recadree ou agrandie se pose aussi bien.
 *
 * <p>Ce qu'aucune vue ne voit -- le dessous, l'interieur d'un repli -- reste vide et se comble
 * par dilatation : on etale les texels voisins, sinon le filtrage bilineaire irait chercher du
 * fond et dessinerait un lisere.
 *
 * <pre>
 * gradle -p rebirth remap -Pmodele=D:/IA/3D/work/out/ashnarg/0/mesh.glb \
 *                         -Pvues=D:/IA/3D/work/in/ashnarg_vues \
 *                         -Psortie=ashnarg.j3o
 * </pre>
 *
 * <p>Le dossier des vues contient {@code face.png}, {@code profil.png}, {@code dos.png} -- ou
 * des noms commencant par {@code 0_}, {@code 1_}, {@code 2_}, dans cet ordre. Une vue absente
 * est simplement ignoree.
 */
public final class Remap {

    /** Cote de la texture produite. */
    private static final int COTE = 1024;
    /**
     * DE COMBIEN LA VUE LA PLUS FRANCHE L'EMPORTE.
     *
     * <p>Un melange lineaire etale le profil sur toute la face et noie le dessin. Eleve au
     * cube, le produit scalaire fait qu'une surface tournee a trente degres d'une vue lui doit
     * deja les deux tiers de sa couleur, et le raccord reste doux.
     */
    private static final float NETTETE = 3f;
    /** Combien de fois on etale les texels pour combler ce qu'aucune vue ne voit. */
    private static final int DILATATIONS = 6;
    /**
     * DE COMBIEN UN POINT PEUT ETRE DERRIERE LE PREMIER et compter quand meme, en fraction de
     * la taille du modele. Assez pour absorber le pas de la carte de profondeur, pas assez
     * pour laisser la gueule de devant se peindre dans le dos.
     */
    private static final float TOLERANCE = 0.02f;

    /** Une vue : son image, la direction d'ou on la regarde, et son cadrage. */
    private static final class Vue {
        final BufferedImage img;
        final Vector3f versLaCamera;
        /** Axes de l'image dans le monde : u suit {@code axeU}, v suit {@code axeV} (vers le bas). */
        final Vector3f axeU;
        final Vector3f axeV;
        float u0;
        float u1;
        float v0;
        float v1;                                      // boite opaque, en pixels
        float mu0;
        float mu1;
        float mv0;
        float mv1;                                     // boite du maillage, sur les memes axes
        /** Profondeur du point le plus proche de cette vue, par pixel. */
        float[] devant;
        /** Tolerance de profondeur, en unites du modele. */
        float tolerance;

        Vue(BufferedImage img, Vector3f versLaCamera, Vector3f axeU, Vector3f axeV) {
            this.img = img;
            this.versLaCamera = versLaCamera;
            this.axeU = axeU;
            this.axeV = axeV;
        }
    }

    private Remap() {
    }

    public static void main(String[] args) throws Exception {
        String chemin = System.getProperty("rebirth.modele");
        String dossierVues = System.getProperty("rebirth.vues");
        String sortie = System.getProperty("rebirth.sortie", "remap.j3o");
        if (chemin == null || dossierVues == null) {
            System.err.println("Usage : -Pmodele=<mesh.glb> -Pvues=<dossier> [-Psortie=x.j3o]");
            return;
        }

        AssetManager am = new DesktopAssetManager(true);
        File f = new File(chemin).getAbsoluteFile();
        am.registerLocator(f.getParentFile().getAbsolutePath(), FileLocator.class);
        am.registerLocator(null, ClasspathLocator.class);
        Spatial modele = am.loadModel(f.getName());
        Geometry geo = premiereGeometrie(modele);
        if (geo == null) {
            System.err.println("[remap] aucune geometrie dans " + chemin);
            return;
        }
        Mesh m = geo.getMesh();
        FloatBuffer pos = m.getFloatBuffer(VertexBuffer.Type.Position);
        FloatBuffer nor = m.getFloatBuffer(VertexBuffer.Type.Normal);
        FloatBuffer uv = m.getFloatBuffer(VertexBuffer.Type.TexCoord);
        if (pos == null || uv == null) {
            System.err.println("[remap] le maillage n'a ni positions ni coordonnees de texture");
            return;
        }
        int[] idx = indices(m);
        modele.updateGeometricState();
        BoundingBox bb = (BoundingBox) geo.getModelBound();
        System.out.printf("[remap] %s : %d triangles, boite %s%n", f.getName(), idx.length / 3, bb);

        java.util.List<Vue> vues = chargerVues(new File(dossierVues), bb);
        if (vues.isEmpty()) {
            System.err.println("[remap] aucune vue lisible dans " + dossierVues);
            return;
        }

        float uMin = Float.MAX_VALUE;
        float uMax = -Float.MAX_VALUE;
        float vMin = Float.MAX_VALUE;
        float vMax = -Float.MAX_VALUE;
        for (int i = 0; i < uv.limit() / 2; i++) {
            uMin = Math.min(uMin, uv.get(i * 2));
            uMax = Math.max(uMax, uv.get(i * 2));
            vMin = Math.min(vMin, uv.get(i * 2 + 1));
            vMax = Math.max(vMax, uv.get(i * 2 + 1));
        }
        System.out.printf("[remap] uv u %.3f..%.3f  v %.3f..%.3f  sommets %d  normales %s%n",
                uMin, uMax, vMin, vMax, pos.limit() / 3, nor != null);

        // CE QUE CHAQUE VUE VOIT VRAIMENT. Sans cela, un point du DOS dont la normale penche
        // un peu vers l'avant lit l'image de face a ses coordonnees -- c'est-a-dire la gueule
        // -- et se la peint sur l'echine. On l'a vu : des dents dans le dos.
        float taille = Math.max(bb.getXExtent(), Math.max(bb.getYExtent(), bb.getZExtent()));
        for (Vue v : vues) {
            poserLaProfondeur(v, pos, idx, taille);
        }

        float[] somme = new float[COTE * COTE * 3];
        float[] poids = new float[COTE * COTE];
        for (int t = 0; t < idx.length; t += 3) {
            peindreTriangle(idx[t], idx[t + 1], idx[t + 2], pos, nor, uv, vues, somme, poids);
        }

        BufferedImage atlas = assembler(somme, poids);
        File png = new File(sortie.replaceAll("\\.j3o$", "") + ".png");
        javax.imageio.ImageIO.write(atlas, "png", png);

        // LE MAILLAGE REPART AVEC SA NOUVELLE PEAU, en .j3o : c'est ce que le jeu et le SDK
        // savent lire, et `preview` le montre tel quel.
        Material mat = new Material(am, "Common/MatDefs/Light/Lighting.j3md");
        Texture2D tex = Assets.toTexture(atlas);
        mat.setTexture("DiffuseMap", tex);
        mat.setBoolean("UseMaterialColors", true);
        mat.setColor("Diffuse", ColorRGBA.White);
        mat.setColor("Ambient", ColorRGBA.White);
        mat.setColor("Specular", ColorRGBA.Black);
        mat.setFloat("Shininess", 1f);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        Geometry sorti = new Geometry(f.getParentFile().getName(), m);
        sorti.setMaterial(mat);
        BinaryExporter.getInstance().save(sorti, new File(sortie));

        int vides = 0;
        for (float p : poids) {
            if (p <= 0f) {
                vides++;
            }
        }
        System.out.printf("[remap] %d vues -> %s + %s (%.1f %% de l'atlas jamais vu)%n",
                vues.size(), sortie, png.getName(), 100.0 * vides / poids.length);
    }

    // ------------------------------------------------------------------ vues

    /**
     * Charge les trois vues et les cale sur le maillage.
     *
     * <p>Les axes supposent la convention de l'outil : le modele regarde vers +Z, Y vers le
     * haut. C'est ce que montre l'apercu -- la premiere vue, camera en +Z, donne le visage.
     */
    private static java.util.List<Vue> chargerVues(File dossier, BoundingBox bb) {
        java.util.List<Vue> out = new java.util.ArrayList<>();
        ajouter(out, dossier, new String[] {"face", "0_face", "0"},
                new Vector3f(0, 0, 1), new Vector3f(1, 0, 0), new Vector3f(0, -1, 0));
        ajouter(out, dossier, new String[] {"profil", "1_profil", "1"},
                new Vector3f(1, 0, 0), new Vector3f(0, 0, -1), new Vector3f(0, -1, 0));
        ajouter(out, dossier, new String[] {"dos", "2_dos", "2"},
                new Vector3f(0, 0, -1), new Vector3f(-1, 0, 0), new Vector3f(0, -1, 0));
        for (Vue v : out) {
            caler(v, bb);
        }
        return out;
    }

    private static void ajouter(java.util.List<Vue> out, File dossier, String[] noms,
            Vector3f versLaCamera, Vector3f axeU, Vector3f axeV) {
        for (String n : noms) {
            File f = new File(dossier, n + ".png");
            if (f.isFile()) {
                try {
                    BufferedImage img = javax.imageio.ImageIO.read(f);
                    if (img != null) {
                        out.add(new Vue(img, versLaCamera.normalizeLocal(), axeU, axeV));
                        System.out.println("[remap]   vue " + f.getName() + " "
                                + img.getWidth() + "x" + img.getHeight());
                        return;
                    }
                } catch (java.io.IOException e) {
                    System.err.println("[remap] " + f + " illisible : " + e.getMessage());
                }
            }
        }
    }

    /**
     * CALE UNE VUE SUR LE MAILLAGE, sans rien connaitre de son echelle.
     *
     * <p>On compare la boite des pixels OPAQUES de l'image a la boite du maillage projetee sur
     * les memes axes. Une vue recadree, agrandie, ou avec de la marge se pose donc aussi bien
     * -- et c'est le seul point ou une erreur se verrait tout de suite, en texture decalee.
     */
    private static void caler(Vue v, BoundingBox bb) {
        int w = v.img.getWidth();
        int h = v.img.getHeight();
        int x0 = w;
        int x1 = -1;
        int y0 = h;
        int y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((v.img.getRGB(x, y) >>> 24) > 8) {
                    x0 = Math.min(x0, x);
                    x1 = Math.max(x1, x);
                    y0 = Math.min(y0, y);
                    y1 = Math.max(y1, y);
                }
            }
        }
        if (x1 < x0) {                                 // image vide : on prend tout
            x0 = 0;
            x1 = w - 1;
            y0 = 0;
            y1 = h - 1;
        }
        v.u0 = x0;
        v.u1 = x1 + 1f;
        v.v0 = y0;
        v.v1 = y1 + 1f;

        Vector3f c = bb.getCenter();
        float ex = bb.getXExtent();
        float ey = bb.getYExtent();
        float ez = bb.getZExtent();
        float du = Math.abs(v.axeU.x) * ex + Math.abs(v.axeU.y) * ey + Math.abs(v.axeU.z) * ez;
        float dv = Math.abs(v.axeV.x) * ex + Math.abs(v.axeV.y) * ey + Math.abs(v.axeV.z) * ez;
        float cu = v.axeU.dot(c);
        float cv = v.axeV.dot(c);
        v.mu0 = cu - du;
        v.mu1 = cu + du;
        v.mv0 = cv - dv;
        v.mv1 = cv + dv;
    }

    /**
     * RASTERISE LE MAILLAGE DANS LA VUE pour savoir, pixel par pixel, ce qui est devant.
     *
     * <p>Meme projection que {@link #lire} : les deux doivent s'accorder au pixel pres, sinon
     * le test de visibilite rejette ce qu'il devrait garder.
     */
    private static void poserLaProfondeur(Vue v, FloatBuffer pos, int[] idx, float taille) {
        int w = v.img.getWidth();
        int h = v.img.getHeight();
        v.devant = new float[w * h];
        java.util.Arrays.fill(v.devant, -Float.MAX_VALUE);
        v.tolerance = taille * TOLERANCE;
        float[] xs = new float[3];
        float[] ys = new float[3];
        float[] ds = new float[3];
        for (int t = 0; t < idx.length; t += 3) {
            for (int k = 0; k < 3; k++) {
                int i = idx[t + k];
                float px = pos.get(i * 3);
                float py = pos.get(i * 3 + 1);
                float pz = pos.get(i * 3 + 2);
                xs[k] = ecranU(v, px, py, pz);
                ys[k] = ecranV(v, px, py, pz);
                ds[k] = v.versLaCamera.x * px + v.versLaCamera.y * py + v.versLaCamera.z * pz;
            }
            float aire = (xs[1] - xs[0]) * (ys[2] - ys[0]) - (xs[2] - xs[0]) * (ys[1] - ys[0]);
            if (Math.abs(aire) < 1e-9f) {
                continue;
            }
            int x0 = Math.max(0, (int) Math.floor(Math.min(xs[0], Math.min(xs[1], xs[2]))));
            int x1 = Math.min(w - 1, (int) Math.ceil(Math.max(xs[0], Math.max(xs[1], xs[2]))));
            int y0 = Math.max(0, (int) Math.floor(Math.min(ys[0], Math.min(ys[1], ys[2]))));
            int y1 = Math.min(h - 1, (int) Math.ceil(Math.max(ys[0], Math.max(ys[1], ys[2]))));
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    float px = x + 0.5f;
                    float py = y + 0.5f;
                    float l0 = ((xs[1] - px) * (ys[2] - py) - (xs[2] - px) * (ys[1] - py)) / aire;
                    float l1 = ((xs[2] - px) * (ys[0] - py) - (xs[0] - px) * (ys[2] - py)) / aire;
                    float l2 = 1f - l0 - l1;
                    if (l0 < 0f || l1 < 0f || l2 < 0f) {
                        continue;
                    }
                    float d = l0 * ds[0] + l1 * ds[1] + l2 * ds[2];
                    int j = y * w + x;
                    if (d > v.devant[j]) {
                        v.devant[j] = d;
                    }
                }
            }
        }
    }

    private static float ecranU(Vue v, float px, float py, float pz) {
        float u = (v.axeU.x * px + v.axeU.y * py + v.axeU.z * pz - v.mu0) / (v.mu1 - v.mu0);
        return v.u0 + u * (v.u1 - v.u0);
    }

    private static float ecranV(Vue v, float px, float py, float pz) {
        float w = (v.axeV.x * px + v.axeV.y * py + v.axeV.z * pz - v.mv0) / (v.mv1 - v.mv0);
        return v.v0 + w * (v.v1 - v.v0);
    }

    /**
     * La couleur de cette vue au point du monde, ou ZERO si elle n'y voit rien d'opaque.
     *
     * <p>Zero, et pas moins un. Une couleur ARGB opaque a son bit de poids fort a un : lue en
     * entier signee, elle est NEGATIVE. Tester {@code < 0} rejetait donc toutes les couleurs
     * opaques -- c'est-a-dire exactement celles qu'on cherche --, et l'atlas ressortait
     * entierement vide. Un retour valide a toujours son alpha au-dessus du seuil, donc jamais
     * zero : la sentinelle ne peut pas se confondre avec une couleur.
     */
    private static int lire(Vue v, float px, float py, float pz) {
        float u = (v.axeU.x * px + v.axeU.y * py + v.axeU.z * pz - v.mu0) / (v.mu1 - v.mu0);
        float w = (v.axeV.x * px + v.axeV.y * py + v.axeV.z * pz - v.mv0) / (v.mv1 - v.mv0);
        int x = Math.round(v.u0 + u * (v.u1 - v.u0) - 0.5f);
        int y = Math.round(v.v0 + w * (v.v1 - v.v0) - 0.5f);
        if (x < 0 || y < 0 || x >= v.img.getWidth() || y >= v.img.getHeight()) {
            return 0;
        }
        if (v.devant != null) {
            float d = v.versLaCamera.x * px + v.versLaCamera.y * py + v.versLaCamera.z * pz;
            if (d < v.devant[y * v.img.getWidth() + x] - v.tolerance) {
                return 0;                              // quelque chose est devant : elle ne voit pas
            }
        }
        int argb = v.img.getRGB(x, y);
        return (argb >>> 24) > 8 ? argb : 0;
    }

    // ------------------------------------------------------------- peinture

    private static void peindreTriangle(int ia, int ib, int ic, FloatBuffer pos, FloatBuffer nor,
            FloatBuffer uv, java.util.List<Vue> vues, float[] somme, float[] poids) {
        float ua = uv.get(ia * 2) * COTE;
        float va = uv.get(ia * 2 + 1) * COTE;
        float ub = uv.get(ib * 2) * COTE;
        float vb = uv.get(ib * 2 + 1) * COTE;
        float uc = uv.get(ic * 2) * COTE;
        float vc = uv.get(ic * 2 + 1) * COTE;

        int minX = Math.max(0, (int) Math.floor(Math.min(ua, Math.min(ub, uc))) - 1);
        int maxX = Math.min(COTE - 1, (int) Math.ceil(Math.max(ua, Math.max(ub, uc))) + 1);
        int minY = Math.max(0, (int) Math.floor(Math.min(va, Math.min(vb, vc))) - 1);
        int maxY = Math.min(COTE - 1, (int) Math.ceil(Math.max(va, Math.max(vb, vc))) + 1);
        float aire = (ub - ua) * (vc - va) - (uc - ua) * (vb - va);
        if (Math.abs(aire) < 1e-9f) {
            return;
        }

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;
                float l0 = ((ub - px) * (vc - py) - (uc - px) * (vb - py)) / aire;
                float l1 = ((uc - px) * (va - py) - (ua - px) * (vc - py)) / aire;
                float l2 = 1f - l0 - l1;
                // UNE MARGE D'UN DEMI-TEXEL : les bords de deux ilots voisins doivent se
                // toucher, sinon une couture claire apparait au filtrage.
                if (l0 < -0.02f || l1 < -0.02f || l2 < -0.02f) {
                    continue;
                }
                float wx = l0 * pos.get(ia * 3) + l1 * pos.get(ib * 3) + l2 * pos.get(ic * 3);
                float wy = l0 * pos.get(ia * 3 + 1) + l1 * pos.get(ib * 3 + 1)
                        + l2 * pos.get(ic * 3 + 1);
                float wz = l0 * pos.get(ia * 3 + 2) + l1 * pos.get(ib * 3 + 2)
                        + l2 * pos.get(ic * 3 + 2);
                float nx = 0f;
                float ny = 0f;
                float nz = 1f;
                if (nor != null) {
                    nx = l0 * nor.get(ia * 3) + l1 * nor.get(ib * 3) + l2 * nor.get(ic * 3);
                    ny = l0 * nor.get(ia * 3 + 1) + l1 * nor.get(ib * 3 + 1)
                            + l2 * nor.get(ic * 3 + 1);
                    nz = l0 * nor.get(ia * 3 + 2) + l1 * nor.get(ib * 3 + 2)
                            + l2 * nor.get(ic * 3 + 2);
                    float n = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (n > 1e-6f) {
                        nx /= n;
                        ny /= n;
                        nz /= n;
                    }
                }
                int t = y * COTE + x;
                for (Vue v : vues) {
                    float face = nx * v.versLaCamera.x + ny * v.versLaCamera.y
                            + nz * v.versLaCamera.z;
                    if (face <= 0.02f) {
                        continue;                      // cette vue regarde le dos du triangle
                    }
                    int argb = lire(v, wx, wy, wz);
                    if (argb == 0) {
                        continue;
                    }
                    float p = (float) Math.pow(face, NETTETE);
                    somme[t * 3] += ((argb >> 16) & 0xFF) * p;
                    somme[t * 3 + 1] += ((argb >> 8) & 0xFF) * p;
                    somme[t * 3 + 2] += (argb & 0xFF) * p;
                    poids[t] += p;
                }
            }
        }
    }

    /** Normalise, puis comble ce qu'aucune vue n'a vu en etalant les voisins. */
    private static BufferedImage assembler(float[] somme, float[] poids) {
        BufferedImage img = new BufferedImage(COTE, COTE, BufferedImage.TYPE_INT_RGB);
        boolean[] vu = new boolean[COTE * COTE];
        for (int i = 0; i < poids.length; i++) {
            if (poids[i] > 0f) {
                vu[i] = true;
                img.setRGB(i % COTE, i / COTE, couleur(somme, poids, i));
            }
        }
        for (int passe = 0; passe < DILATATIONS; passe++) {
            boolean[] ajoute = vu.clone();
            for (int y = 0; y < COTE; y++) {
                for (int x = 0; x < COTE; x++) {
                    int i = y * COTE + x;
                    if (vu[i]) {
                        continue;
                    }
                    long r = 0;
                    long g = 0;
                    long b = 0;
                    int n = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int ax = x + dx;
                            int ay = y + dy;
                            if (ax < 0 || ay < 0 || ax >= COTE || ay >= COTE) {
                                continue;
                            }
                            int j = ay * COTE + ax;
                            if (!vu[j]) {
                                continue;
                            }
                            int c = img.getRGB(ax, ay);
                            r += (c >> 16) & 0xFF;
                            g += (c >> 8) & 0xFF;
                            b += c & 0xFF;
                            n++;
                        }
                    }
                    if (n > 0) {
                        img.setRGB(x, y, (int) ((r / n) << 16 | (g / n) << 8 | (b / n)));
                        ajoute[i] = true;
                    }
                }
            }
            vu = ajoute;
        }
        return img;
    }

    private static int couleur(float[] somme, float[] poids, int i) {
        float p = poids[i];
        int r = Math.min(255, Math.round(somme[i * 3] / p));
        int g = Math.min(255, Math.round(somme[i * 3 + 1] / p));
        int b = Math.min(255, Math.round(somme[i * 3 + 2] / p));
        return (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------- menus

    private static Geometry premiereGeometrie(Spatial s) {
        if (s instanceof Geometry g) {
            return g;
        }
        if (s instanceof Node n) {
            for (Spatial e : n.getChildren()) {
                Geometry g = premiereGeometrie(e);
                if (g != null) {
                    return g;
                }
            }
        }
        return null;
    }

    private static int[] indices(Mesh m) {
        VertexBuffer ib = m.getBuffer(VertexBuffer.Type.Index);
        if (ib == null) {
            int n = m.getVertexCount();
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                out[i] = i;
            }
            return out;
        }
        java.nio.Buffer d = ib.getData();
        if (d instanceof java.nio.IntBuffer bi) {
            int[] out = new int[bi.limit()];
            for (int i = 0; i < out.length; i++) {
                out[i] = bi.get(i);
            }
            return out;
        }
        java.nio.ShortBuffer bs = (java.nio.ShortBuffer) d;
        int[] out = new int[bs.limit()];
        for (int i = 0; i < out.length; i++) {
            out[i] = bs.get(i) & 0xFFFF;
        }
        return out;
    }
}
