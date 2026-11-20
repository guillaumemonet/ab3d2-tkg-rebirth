package ab3d2.rebirth;

import com.jme3.asset.AssetManager;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.MorphTarget;
import com.jme3.util.BufferUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UN MODELE ET SES POSES, pretes pour le morphing du moteur.
 *
 * <h2>Ce que les donnees sont vraiment</h2>
 *
 * <p>Un modele vectoriel du jeu arrive en vingt fichiers, {@code frame_000.obj} a
 * {@code frame_019.obj}, et on les traitait comme vingt modeles : a chaque pas d'animation on
 * rebatissait le maillage et on le remplacait. C'est une lecture naive de ce qu'ils sont.
 *
 * <p>Mesure faite sur les trois boss -- CRAB, MANTIS, WASP : d'une image a l'autre, la LISTE
 * DE FACES est identique, les INDICES DE POINTS sont identiques, les COORDONNEES DE TEXTURE
 * sont identiques, et le NIVEAU DE BASE de chaque face est identique. Seules les POSITIONS
 * bougent. Ce ne sont pas vingt modeles : c'est un modele et vingt poses, autrement dit une
 * animation par sommets, deja faite, et exacte.
 *
 * <h2>Pourquoi pas un squelette</h2>
 *
 * <p>Poser des os la-dessus reviendrait a inventer une hierarchie, puis a APPROXIMER par
 * ponderation un mouvement qu'on possede au point pres. On perdrait de la fidelite sans rien
 * gagner. Le morphing, lui, est la forme exacte de ce que la donnee dit : la pose 0 est le
 * maillage, et chaque pose suivante est un ECART par rapport a elle.
 *
 * <h2>Les normales aussi</h2>
 *
 * <p>Le decor est a facettes : chaque triangle porte sa propre normale, calculee de ses trois
 * points. Un modele qui se deforme change donc aussi de normales, et ne morpher que les
 * positions figerait son eclairage sur la premiere pose -- une patte qui se leve resterait
 * eclairee comme si elle etait posee. Chaque pose porte donc DEUX tampons, position et
 * normale, ce que {@code Common/ShaderLib/MorphAnim.glsllib} sait melanger.
 *
 * <h2>Ce que ca donne en plus</h2>
 *
 * <p>Entre deux poses, le moteur interpole. L'Amiga ne le pouvait pas : elle sautait d'une
 * pose a l'autre. Le mouvement devient continu sans qu'aucune donnee ne soit inventee --
 * les deux poses melangees sont celles du jeu, c'est le chemin entre elles qui est nouveau.
 */
public final class MorphModels {

    /** Jusqu'ou on cherche des poses sur le disque. Le plus fourni du jeu en a vingt et une. */
    private static final int POSES_MAX = 64;

    /** Ce que la bibliotheque de melange de jME sait tenir en une passe (MorphAnim.glsllib). */
    public static final int POSES_GPU = 14;

    /**
     * Combien d'ecarts PESENT en meme temps, au plus.
     *
     * <p>Rien a voir avec le nombre de poses : un chemin d'une pose a l'autre n'en fait peser
     * que deux, trois le temps d'un changement de cible en cours de route. Quatre laisse de la
     * marge et tient sur le materiel le plus pauvre -- chaque ecart coute DEUX attributs de
     * sommet, position et normale, et une carte integree n'en offre que seize en tout.
     */
    public static final int ECARTS_SIMULTANES = 4;

    /** Combien de tampons porte un ecart : la position et la normale. */
    public static final int TAMPONS_PAR_ECART = 2;

    private static final Map<String, Poses> CACHE = new HashMap<>();

    private MorphModels() {
    }

    /** Un modele et ses poses : la pose 0 fait le maillage, les suivantes sont des ecarts. */
    public static final class Poses {

        /** Le nom du modele, tel que la base GLF le nomme (CRAB, MANTIS, WASP...). */
        public final String nom;

        /** La pose 0 : c'est d'elle qu'on batit le maillage, par le chemin habituel. */
        public final ObjModels.Model base;

        /** Les poses 1..n-1, chacune en ECART (position et normale) par rapport a la pose 0. */
        private final MorphTarget[] ecarts;

        private Poses(String nom, ObjModels.Model base, MorphTarget[] ecarts) {
            this.nom = nom;
            this.base = base;
            this.ecarts = ecarts;
        }

        /** Combien de poses en tout, la pose 0 comprise. */
        public int nombre() {
            return ecarts.length + 1;
        }

        /** Accroche les ecarts au maillage qu'on vient de batir depuis {@link #base}. */
        public void accrocher(Mesh mesh) {
            for (MorphTarget mt : ecarts) {
                mesh.addMorphTarget(mt);
            }
        }

        /**
         * Les poids qui montrent la pose {@code p}, seule et telle quelle.
         *
         * <p>La pose 0 n'a pas d'ecart -- c'est le maillage lui-meme -- et son poids est donc
         * implicite : tous les poids a zero, c'est elle qu'on voit.
         */
        public float[] poidsDe(int p) {
            float[] w = new float[ecarts.length];
            int borne = Math.max(0, Math.min(nombre() - 1, p));
            if (borne >= 1) {
                w[borne - 1] = 1f;
            }
            return w;
        }

        /**
         * LE CHEMIN ENTRE DEUX POSES SE PARCOURT SUR LES POIDS, pas sur leur numero.
         *
         * <p>Aller de la pose 3 a la pose 7 en faisant glisser un numero traverserait les
         * poses 4, 5 et 6 -- que l'animation n'a pas demandees. Mais la position d'un sommet
         * est une somme LINEAIRE des ecarts : melanger deux jeux de poids, c'est exactement
         * melanger les deux poses qu'ils decrivent. On interpole donc les poids eux-memes, et
         * un changement de cible en cours de route repart simplement d'ou l'on est.
         */
        public static float[] melange(float[] a, float[] b, float t, float[] sortie) {
            float[] out = (sortie != null && sortie.length == a.length) ? sortie
                    : new float[a.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = a[i] + (b[i] - a[i]) * t;
            }
            return out;
        }
    }

    /**
     * PREPARE UNE GEOMETRIE A SE MELANGER : ses ecarts, son materiau, son controle.
     *
     * <p>Les trois reglages du materiau ne sont pas facultatifs, et c'est le piege de toute
     * cette affaire. jME les pose LUI-MEME, mais dans {@code MorphControl.getMaxGPUTargets} --
     * une methode qui commence par :
     *
     * <pre>
     *   if (geom.getNbSimultaneousGPUMorph() &gt; -1) {
     *       return geom.getNbSimultaneousGPUMorph();
     *   }
     * </pre>
     *
     * <p>Ce nombre vaut -1 tant que personne n'y a touche : c'est une VALEUR CALCULEE, que le
     * premier rendu determine d'apres les attributs de sommet qui restent, puis range la. En le
     * reglant soi-meme -- ce qu'il faut faire ici, parce que le calcul de jME est trop optimiste
     * d'un ou deux attributs et plante net sur une carte qui n'en offre que seize -- on prend la
     * sortie anticipee, et tout ce que la methode faisait ENSUITE n'arrive jamais :
     * {@code NumberOfTargetsBuffers}, {@code NumberOfMorphTargets}, {@code MorphWeights}.
     * Sans ces definitions, {@code Morph_Compute} est compile hors du nuanceur et le modele
     * reste a sa pose 0 -- sans la moindre erreur, sans le moindre avertissement.
     *
     * <p>On refait donc a la main ce que la sortie anticipee escamote.
     */
    public static void preparer(com.jme3.scene.Geometry g, Poses poses) {
        poses.accrocher(g.getMesh());
        com.jme3.material.Material mat = g.getMaterial();
        mat.setInt("NumberOfTargetsBuffers", TAMPONS_PAR_ECART);
        mat.setInt("NumberOfMorphTargets", ECARTS_SIMULTANES);
        mat.setParam("MorphWeights", com.jme3.shader.VarType.FloatArray,
                new float[ECARTS_SIMULTANES]);
        g.setNbSimultaneousGPUMorph(ECARTS_SIMULTANES);
        g.addControl(new com.jme3.anim.MorphControl());
    }

    /**
     * DEFAIT ce que {@link #preparer} a pose : l'entite change de modele.
     *
     * <p>Il faut tout reprendre, parce que {@code Geometry.setMesh} ne touche a RIEN de ce qui
     * concerne le melange : ni le controle, ni les reglages du materiau, ni l'etat des poids.
     * Un maillage neuf sans ecarts laisserait derriere lui un nuanceur qui compte encore
     * quatre ecarts et va lire des attributs de sommet qui n'existent plus.
     */
    public static void oublier(com.jme3.scene.Geometry g) {
        com.jme3.anim.MorphControl ctrl = g.getControl(com.jme3.anim.MorphControl.class);
        if (ctrl != null) {
            g.removeControl(ctrl);
        }
        com.jme3.material.Material mat = g.getMaterial();
        if (mat != null) {
            mat.clearParam("MorphWeights");
            mat.clearParam("NumberOfMorphTargets");
            mat.clearParam("NumberOfTargetsBuffers");
        }
        g.setNbSimultaneousGPUMorph(-1);               // que jME le recalcule s'il le faut
    }

    /**
     * POSE LES POIDS, meme si un modele precedent en a laisse plus.
     *
     * <p>jME garde l'etat des poids dans la GEOMETRIE, et sa longueur est celle du maillage qui
     * l'a cree. {@code setMesh} ne la revise pas, et {@code setMorphState} recopie AVEUGLEMENT
     * cette longueur-la depuis le tableau qu'on lui donne :
     *
     * <pre>
     *   System.arraycopy(state, 0, morphState, 0, morphState.length);
     * </pre>
     *
     * <p>Un modele de vingt poses suivi d'un modele de cinq fait donc lire vingt flottants dans
     * un tableau de cinq -- et le jeu s'arrete sur un {@code ArrayIndexOutOfBoundsException}
     * venu du fond du moteur. On complete donc de zeros jusqu'a la longueur qu'il attend ; les
     * poids en trop ne designent aucun ecart du maillage courant, et le controle, qui compte
     * les ecarts du MAILLAGE, ne les lit pas.
     */
    public static void poser(com.jme3.scene.Geometry g, float[] poids) {
        float[] etat = g.getMorphState();
        if (etat != null && etat.length > poids.length) {
            float[] large = new float[etat.length];
            System.arraycopy(poids, 0, large, 0, poids.length);
            g.setMorphState(large);
            return;
        }
        g.setMorphState(poids);
    }

    /**
     * Les poses d'un modele, ou {@code null} s'il ne s'y prete pas.
     *
     * <p>Un modele ne s'y prete pas quand ses images ne partagent pas le meme decoupage : le
     * GENERATOR, par exemple, allume et eteint des faces d'une image a l'autre (les drapeaux
     * du format), et il n'y a alors aucune correspondance de sommet a tenir. Ceux-la gardent
     * l'echange de maillage, qui reste juste.
     */
    public static Poses charger(AssetManager am, String nom) {
        if (CACHE.containsKey(nom)) {
            return CACHE.get(nom);
        }
        Poses p = construire(am, nom);
        CACHE.put(nom, p);
        return p;
    }

    private static Poses construire(AssetManager am, String nom) {
        List<ObjModels.Model> images = new ArrayList<>();
        for (int i = 0; i < POSES_MAX; i++) {
            ObjModels.Model m = ObjModels.load(am, String.format("models/%s/frame_%03d.obj",
                    nom, i));
            if (m == null) {
                break;
            }
            images.add(m);
        }
        if (images.size() < 2) {
            return null;                               // une seule pose : rien a melanger
        }
        ObjModels.Model base = images.get(0);
        float[] p0 = positions(base);
        float[] n0 = MeshBuilder.flatNormals(p0);
        List<MorphTarget> ecarts = new ArrayList<>();
        for (int i = 1; i < images.size(); i++) {
            ObjModels.Model img = images.get(i);
            if (!memeDecoupe(base, img)) {
                return null;
            }
            float[] pi = positions(img);
            float[] ni = MeshBuilder.flatNormals(pi);
            float[] dp = new float[p0.length];
            float[] dn = new float[n0.length];
            for (int k = 0; k < p0.length; k++) {
                dp[k] = pi[k] - p0[k];
                dn[k] = ni[k] - n0[k];
            }
            MorphTarget mt = new MorphTarget("pose" + i);
            mt.setBuffer(VertexBuffer.Type.Position, BufferUtils.createFloatBuffer(dp));
            mt.setBuffer(VertexBuffer.Type.Normal, BufferUtils.createFloatBuffer(dn));
            ecarts.add(mt);
        }
        return new Poses(nom, base, ecarts.toArray(new MorphTarget[0]));
    }

    /** Les positions d'un modele, a plat, dans l'ordre ou le maillage les emet. */
    private static float[] positions(ObjModels.Model m) {
        float[] p = new float[m.tris.length * 9];
        for (int i = 0; i < m.tris.length; i++) {
            float[] t = m.tris[i];
            for (int k = 0; k < 3; k++) {
                p[i * 9 + k * 3] = t[k * 5];
                p[i * 9 + k * 3 + 1] = t[k * 5 + 1];
                p[i * 9 + k * 3 + 2] = t[k * 5 + 2];
            }
        }
        return p;
    }

    /**
     * Deux images partagent-elles le meme decoupage ?
     *
     * <p>Memes triangles dans le meme ordre, memes faces derriere eux, memes niveaux de base,
     * memes coordonnees de texture. L'octet d'ANGLE, lui, a le droit de changer : c'est la
     * direction de la face, et une face qui bouge change de direction. Il ne sert qu'au rendu
     * d'origine, qui ne passe pas par le morphing.
     */
    private static boolean memeDecoupe(ObjModels.Model a, ObjModels.Model b) {
        if (a.tris.length != b.tris.length
                || !java.util.Arrays.equals(a.triFace, b.triFace)
                || !java.util.Arrays.equals(a.faceLevel, b.faceLevel)) {
            return false;
        }
        for (int i = 0; i < a.tris.length; i++) {
            for (int k = 0; k < 3; k++) {
                if (a.tris[i][k * 5 + 3] != b.tris[i][k * 5 + 3]
                        || a.tris[i][k * 5 + 4] != b.tris[i][k * 5 + 4]) {
                    return false;
                }
            }
        }
        return true;
    }
}
