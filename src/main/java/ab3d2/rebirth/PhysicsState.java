package ab3d2.rebirth;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.shapes.CollisionShape;
import com.jme3.bullet.collision.shapes.MeshCollisionShape;
import com.jme3.bullet.objects.PhysicsBody;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.bullet.util.CollisionShapeFactory;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;

import java.util.ArrayList;
import java.util.List;

/**
 * LA PHYSIQUE, et ce qu'elle n'a PAS le droit de toucher.
 *
 * <p>Le deplacement du joueur, la collision contre les murs, les portes, les ascenseurs : tout
 * cela reste la simulation portee du jeu d'origine, au pas fixe de cinquante hertz. C'est elle
 * qui fait que le remake se joue comme l'original, et aucun moteur physique ne la remplacera.
 * Minie ne la voit meme pas : la simulation ne connait pas cet etat, et cet etat ne lui parle
 * jamais.
 *
 * <p>Ce qu'elle apporte, c'est ce qui n'existait pas et ne peut rien casser : <b>les debris</b>.
 * Un impact projette une poignee d'eclats qui rebondissent sur les murs, roulent sur le sol et
 * s'eteignent. Purement decoratif, et c'est exactement pour cela que c'est sans risque.
 *
 * <h2>Le decor, cote physique</h2>
 *
 * <p>Pour qu'un eclat rebondisse, il faut que les murs existent pour Bullet. On lui donne donc
 * un corps STATIQUE bati sur les maillages du decor -- {@code Walls} et {@code Flats}, et rien
 * d'autre : ni les objets, ni les monstres, ni les sprites, qui sont l'affaire de la
 * simulation.
 *
 * <p>C'est une forme de maillage, la plus chere des formes de Bullet. Elle ne coute rien ici :
 * elle est statique, batie une fois par niveau, et seuls des eclats la frappent.
 */
public final class PhysicsState extends BaseAppState {

    /** Combien d'eclats part un impact. */
    private static final int PAR_ECLAT = 7;
    /** Taille d'un eclat, en unites jME (1 unite = 64 unites monde). */
    private static final float TAILLE = 0.085f;
    /** De combien l'eclat nait AU-DESSUS du point d'impact, pour ne pas naitre dans le mur. */
    private static final float DECOLLE = 0.12f;
    /** Sa masse : assez pour retomber vite, pas assez pour traverser un mur. */
    private static final float MASSE = 0.35f;
    /** Vitesse de depart, en unites par seconde. */
    private static final float JET = 7.5f;
    /** Combien de secondes un eclat vit avant de disparaitre. */
    private static final float VIE = 2.4f;
    /** Au-dela, les plus vieux s'effacent : un couloir mitraille ne doit pas ramer. */
    private static final int MAX_VIVANTS = 72;
    /** La pesanteur, en unites jME par seconde carree (1 unite = 64 unites monde). */
    private static final float PESANTEUR = -24f;

    /** -Pnophysics : coupe tout, pour mesurer ce que cela coute. */
    private final boolean coupee = System.getProperty("rebirth.nophysics") != null;
    /** -PphysicsDebug : dessine les formes de collision. */
    private final boolean debug = System.getProperty("rebirth.physicsDebug") != null;
    /** -PphysicsLog : dit ce qui se passe, impact par impact. */
    private final boolean journal = System.getProperty("rebirth.physicsLog") != null;

    private final Main jeu;
    private BulletAppState bullet;
    private Node eclats;
    private final List<PhysicsRigidBody> dursCorps = new ArrayList<>();
    /**
     * LE NIVEAU DEMANDE AVANT QUE L'ETAT NE SOIT PRET.
     *
     * <p>jME ne construit un etat qu'a l'image SUIVANTE : {@code stateManager.attach} met en
     * file, {@code initialize} vient apres. Or le premier niveau se charge pendant
     * l'initialisation du jeu, donc AVANT. {@code poserLeDecor} tombait ainsi dans le vide --
     * sans erreur, sans trace -- et les eclats du premier niveau traversaient le sol.
     *
     * <p>On garde donc la demande, et on l'honore des qu'on existe.
     */
    private Node enAttente;
    private Material matiere;
    private final List<Eclat> vivants = new ArrayList<>();
    /** Tirage reproductible : deux captures du meme instant doivent se ressembler. */
    private final java.util.Random des = new java.util.Random(20261120L);

    /** Un eclat et le temps qu'il lui reste. */
    private static final class Eclat {
        final Geometry geometrie;
        float reste = VIE;

        Eclat(Geometry g) {
            geometrie = g;
        }
    }

    public PhysicsState(Main jeu) {
        this.jeu = jeu;
    }

    @Override
    protected void initialize(Application app) {
        if (coupee) {
            return;
        }
        bullet = new BulletAppState();
        // SEQUENTIEL, pas parallele. Le jeu avance sa simulation a pas fixe dans la boucle
        // d'image ; une physique qui tournerait a cote, sur son propre fil, rendrait deux
        // captures du meme instant differentes et l'outil de comparaison ne vaudrait plus
        // rien.
        bullet.setThreadingType(BulletAppState.ThreadingType.SEQUENTIAL);
        bullet.setDebugEnabled(debug);
        app.getStateManager().attach(bullet);
        bullet.getPhysicsSpace().setGravity(new Vector3f(0f, PESANTEUR, 0f));

        eclats = new Node("eclats");
        ((Main) app).getRootNode().attachChild(eclats);

        matiere = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        matiere.setColor("Color", ColorRGBA.White);
        matiere.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

        if (enAttente != null) {
            Node attendu = enAttente;
            enAttente = null;
            poserLeDecor(attendu);
        }
    }

    @Override
    protected void cleanup(Application app) {
        viderEclats();
        enleverDecor();
        if (bullet != null) {
            app.getStateManager().detach(bullet);
            bullet = null;
        }
        if (eclats != null) {
            eclats.removeFromParent();
            eclats = null;
        }
    }

    @Override
    protected void onEnable() {
    }

    @Override
    protected void onDisable() {
    }

    /**
     * LE DECOR DU NIVEAU DEVIENT UN CORPS STATIQUE.
     *
     * <p>A appeler apres chaque chargement. Seuls {@code Walls} et {@code Flats} y entrent :
     * les objets, les monstres et les sprites appartiennent a la simulation, et un eclat n'a
     * rien a leur demander.
     */
    public void poserLeDecor(Node niveau) {
        if (niveau == null) {
            return;
        }
        if (bullet == null) {
            enAttente = coupee ? null : niveau;        // on n'existe pas encore : on retiendra
            return;
        }
        viderEclats();
        enleverDecor();

        // LE MAILLAGE DE COLLISION, BATI A LA MAIN.
        //
        // Deux tentatives par CollisionShapeFactory, deux echecs silencieux -- et c'est la
        // forme de l'echec qui compte : la forme entrait dans l'espace, son volume englobant
        // tombait au bon endroit, et les eclats la traversaient quand meme. Une simple dalle
        // posee au meme endroit les arretait net, donc ni l'espace, ni la pesanteur, ni les
        // corps n'etaient en cause.
        //
        // L'explication est dans le COMPTE : la fabrique rendait 898 et 734 triangles pour un
        // decor qui en affiche 14 482. Elle ne descendait pas dans l'arbre -- le decor est
        // fait de noeuds par secteur (jambs_zone_N, lift_zone_N...) --, et le sol sous les
        // pieds du joueur n'etait tout simplement pas dedans. Le volume englobant, lui, avait
        // l'air juste : une geometrie lointaine suffit a l'etirer sur tout le niveau.
        //
        // On parcourt donc l'arbre nous-memes, on transforme chaque sommet dans le repere du
        // monde, et on donne a Bullet UN maillage dont on sait ce qu'il contient.
        java.util.List<Float> sommets = new java.util.ArrayList<>();
        java.util.List<Integer> index = new java.util.ArrayList<>();
        for (String nom : new String[] {"Walls", "Flats"}) {
            Spatial part = niveau.getChild(nom);
            if (part != null) {
                ramasser(part, sommets, index);
            }
        }
        if (index.isEmpty()) {
            return;
        }
        float[] sv = new float[sommets.size()];
        for (int i = 0; i < sv.length; i++) {
            sv[i] = sommets.get(i);
        }
        int[] ix = new int[index.size()];
        for (int i = 0; i < ix.length; i++) {
            ix[i] = index.get(i);
        }
        com.jme3.scene.Mesh maillage = new com.jme3.scene.Mesh();
        maillage.setBuffer(com.jme3.scene.VertexBuffer.Type.Position, 3,
                com.jme3.util.BufferUtils.createFloatBuffer(sv));
        maillage.setBuffer(com.jme3.scene.VertexBuffer.Type.Index, 3,
                com.jme3.util.BufferUtils.createIntBuffer(ix));
        maillage.updateBound();

        MeshCollisionShape forme = new MeshCollisionShape(maillage);
        PhysicsRigidBody corps = new PhysicsRigidBody(forme, PhysicsBody.massForStatic);
        corps.setFriction(0.9f);
        corps.setRestitution(0.2f);
        bullet.getPhysicsSpace().addCollisionObject(corps);
        dursCorps.add(corps);
        if (journal) {
            System.out.printf("[physique] decor : %d triangles, %s%n",
                    ix.length / 3, maillage.getBound());
        }
    }

    /** Ajoute les triangles de ce spatial (et de tout ce qu'il porte) dans le repere du monde. */
    private static void ramasser(Spatial s, java.util.List<Float> sommets,
            java.util.List<Integer> index) {
        if (s instanceof Node n) {
            for (Spatial enfant : n.getChildren()) {
                ramasser(enfant, sommets, index);
            }
            return;
        }
        if (!(s instanceof Geometry g)) {
            return;
        }
        com.jme3.scene.Mesh m = g.getMesh();
        java.nio.FloatBuffer pos = m.getFloatBuffer(com.jme3.scene.VertexBuffer.Type.Position);
        com.jme3.scene.VertexBuffer ib = m.getBuffer(com.jme3.scene.VertexBuffer.Type.Index);
        if (pos == null || ib == null) {
            return;
        }
        com.jme3.math.Matrix4f versLeMonde = g.getWorldMatrix();
        int base = sommets.size() / 3;
        Vector3f v = new Vector3f();
        for (int i = 0; i < pos.limit() / 3; i++) {
            v.set(pos.get(i * 3), pos.get(i * 3 + 1), pos.get(i * 3 + 2));
            versLeMonde.mult(v, v);
            sommets.add(v.x);
            sommets.add(v.y);
            sommets.add(v.z);
        }
        java.nio.Buffer brut = ib.getData();
        if (brut instanceof java.nio.IntBuffer bi) {
            for (int i = 0; i < bi.limit(); i++) {
                index.add(base + bi.get(i));
            }
        } else if (brut instanceof java.nio.ShortBuffer bs) {
            for (int i = 0; i < bs.limit(); i++) {
                index.add(base + (bs.get(i) & 0xFFFF));
            }
        }
    }

    private void enleverDecor() {
        if (bullet != null) {
            for (PhysicsRigidBody c : dursCorps) {
                bullet.getPhysicsSpace().removeCollisionObject(c);
            }
        }
        dursCorps.clear();
    }

    /**
     * UN IMPACT PROJETTE DES ECLATS.
     *
     * <p>Appele depuis {@link LightingState}, au meme endroit que l'eclair de l'impact : c'est
     * le seul endroit du remake qui sache qu'un projectile vient de toucher quelque chose.
     */
    public void eclat(float x, float y, float z, int zone, ColorRGBA teinte) {
        if (bullet == null || eclats == null || !isEnabled()) {
            return;
        }
        // AU-DESSUS DU SOL, QUOI QU'EN DISE LA SIMULATION.
        //
        // La hauteur d'un projectile ne se compte pas dans le meme repere que le decor : un
        // impact au ras du sol arrive ici a une demi-unite SOUS lui. Les eclats naissaient
        // donc de l'autre cote du plancher et tombaient dans le vide -- on les a vus
        // descendre tout droit a travers un sol dont la forme de collision etait pourtant
        // juste, et une dalle d'essai posee au meme endroit les arretait net.
        //
        // On remonte donc le point de depart au sol du secteur. Un eclat est decoratif : le
        // voir partir dix centimetres trop haut ne se remarque pas, le voir tomber a travers
        // le monde, si.
        float sol = solDe(zone);
        float depart = Float.isNaN(sol) ? y + DECOLLE : Math.max(y, sol) + DECOLLE;
        if (journal) {
            System.out.printf("[physique] eclat a (%.2f, %.2f, %.2f) zone %d ; sol %.2f"
                    + " -> depart %.2f%n", x, y, z, zone, sol, depart);
        }
        for (int i = 0; i < PAR_ECLAT; i++) {
            if (vivants.size() >= MAX_VIVANTS) {
                retirer(0);                            // le plus vieux cede la place
            }
            Geometry g = new Geometry("eclat", new Box(TAILLE, TAILLE, TAILLE));
            Material m = matiere.clone();
            m.setColor("Color", teinte.clone());
            g.setMaterial(m);
            g.setShadowMode(RenderQueue.ShadowMode.Off);
            g.setLocalTranslation(x, y, z);
            eclats.attachChild(g);

            RigidBodyControl corps = new RigidBodyControl(
                    new BoxCollisionShape(TAILLE), MASSE);
            g.addControl(corps);
            bullet.getPhysicsSpace().add(corps);
            corps.setPhysicsLocation(new Vector3f(x, depart, z));
            // LA DETECTION CONTINUE, sans quoi rien ne rebondit.
            //
            // Un eclat fait huit centiemes d'unite et depasse vite vingt unites par seconde :
            // un tiers d'unite par pas de simulation, quatre fois sa propre taille. Bullet
            // teste les contacts a chaque POSE, pas le long du trajet : l'eclat se retrouve
            // d'un cote du sol puis de l'autre sans l'avoir jamais touche. On l'a vu tomber
            // tout droit a travers un sol dont la forme de collision etait pourtant juste
            // -- le dessin des formes le montrait, exactement sur le sol.
            corps.setCcdMotionThreshold(TAILLE);
            corps.setCcdSweptSphereRadius(TAILLE);
            corps.setLinearVelocity(new Vector3f(
                    (des.nextFloat() - 0.5f) * 2f * JET,
                    des.nextFloat() * JET,
                    (des.nextFloat() - 0.5f) * 2f * JET));
            corps.setAngularVelocity(new Vector3f(
                    (des.nextFloat() - 0.5f) * 12f,
                    (des.nextFloat() - 0.5f) * 12f,
                    (des.nextFloat() - 0.5f) * 12f));
            corps.setRestitution(0.35f);
            corps.setFriction(0.8f);
            vivants.add(new Eclat(g));
        }
    }

    private int tic;

    @Override
    public void update(float tpf) {
        if (journal && !vivants.isEmpty() && ++tic % 15 == 0) {
            Geometry g = vivants.get(0).geometrie;
            System.out.printf("[physique] %d eclats ; le premier a %s%n",
                    vivants.size(), g.getWorldTranslation());
        }
        for (int i = vivants.size() - 1; i >= 0; i--) {
            Eclat e = vivants.get(i);
            e.reste -= tpf;
            if (e.reste <= 0f) {
                retirer(i);
            } else if (e.reste < 0.6f) {
                // IL S'EFFACE au lieu de disparaitre d'un coup : un eclat qui s'evanouit se
                // remarque moins qu'un eclat qui cesse d'exister.
                Material m = e.geometrie.getMaterial();
                ColorRGBA c = m.getParamValue("Color");
                if (c != null) {
                    m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
                    m.setColor("Color", new ColorRGBA(c.r, c.g, c.b, e.reste / 0.6f));
                }
            }
        }
    }

    private void retirer(int i) {
        Eclat e = vivants.remove(i);
        RigidBodyControl c = e.geometrie.getControl(RigidBodyControl.class);
        if (c != null && bullet != null) {
            bullet.getPhysicsSpace().remove(c);
        }
        e.geometrie.removeFromParent();
    }

    private void viderEclats() {
        while (!vivants.isEmpty()) {
            retirer(vivants.size() - 1);
        }
    }

    /** Le sol d'un secteur en unites jME, ou NaN s'il est inconnu. */
    private float solDe(int zone) {
        ab3d2.full.sim.LevelSim sim = jeu.levelSim;
        if (sim == null || sim.zones == null || zone < 0 || zone >= sim.zones.length) {
            return Float.NaN;
        }
        return -sim.zones[zone].floorH / 8192f;
    }

    /** Combien d'eclats volent (diagnostic). */
    public int eclatsVivants() {
        return vivants.size();
    }

    /** L'espace physique, pour qui voudrait y ajouter autre chose. */
    public PhysicsSpace espace() {
        return bullet == null ? null : bullet.getPhysicsSpace();
    }
}
