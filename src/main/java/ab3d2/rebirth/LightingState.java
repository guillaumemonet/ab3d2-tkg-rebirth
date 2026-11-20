package ab3d2.rebirth;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.ColorRGBA;

/**
 * L'ECLAIRAGE DE LA PARTIE, rassemble en un seul endroit.
 *
 * <p>Il etait eparpille dans {@code Main} : les sources du niveau d'un cote, l'allumage par
 * visibilite au milieu de la boucle, la lumiere des projectiles ailleurs, le filtre d'ombres
 * dans un troisieme coin. Quatre fragments qui parlent de la meme chose et se lisent a quatre
 * endroits.
 *
 * <p>Ce qui RESTE dans {@code Main} : la construction du filtre d'ombres, parce que son rang
 * dans la chaine de post-traitement decide de l'image -- bloom, ombres, brouillard,
 * dematerialisation, dans cet ordre. L'etat ne fait que le piloter.
 */
public final class LightingState extends BaseAppState {

    private final Main jeu;

    /** Ce qu'une balle ordinaire eclaire (echelle de {@code Lights3D.flash} : 0 a 40). */
    private static final int BALLE = 24;
    /** Ce qu'un impact eclaire : presque le maximum, sur un rayon d'une demi-piece. */
    private static final int ECLAT = 38;
    /** Sa teinte : le feu. */
    private static final ColorRGBA ECLAT_TEINTE = new ColorRGBA(1f, 0.72f, 0.38f, 1f);
    /** Ce que le depart du coup eclaire, le temps d'une image. */
    private static final int BOUCHE = 30;
    /** Sa teinte : la flamme, pas la balle. */
    private static final ColorRGBA BOUCHE_TEINTE = new ColorRGBA(1f, 0.86f, 0.62f, 1f);

    /** Combien de projectiles volaient a l'image d'avant (pour voir un depart de coup). */
    private int tirsVivants;

    /**
     * LES IMPACTS DEJA FETES.
     *
     * <p>{@code popping} reste vrai plusieurs images -- c'est l'animation de l'eclatement.
     * Sans memoire, chaque impact crachait sept eclats PAR IMAGE, et un tir de fusil a pompe
     * remplissait le couloir. L'identite suffit : les projectiles sont recycles dans un tableau
     * fixe, et un projectile qui repart efface sa marque.
     */
    private final java.util.Set<ab3d2.full.sim.Shots.Shot> dejaEclate =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public LightingState(Main jeu) {
        this.jeu = jeu;
    }

    @Override
    protected void initialize(Application app) {
    }

    @Override
    protected void cleanup(Application app) {
    }

    @Override
    protected void onEnable() {
    }

    @Override
    protected void onDisable() {
    }

    /**
     * N'ALLUMER QUE CE QUI PEUT SE VOIR depuis la zone du joueur (le PVS du jeu d'origine).
     *
     * <p>Desarme en camera libre : la vue n'est alors plus celle du joueur, et ce tri eteignait
     * la piece qu'on regarde.
     */
    public void allumerCeQuiSeVoit(int zone) {
        Lights3D l = jeu.lights3d;
        if (l == null || jeu.noPvsLights || jeu.freeCam) {
            return;
        }
        int avant = l.litFrom();
        l.lightZonesVisibleFrom(zone);
        if (jeu.lightLog && zone != avant) {
            System.out.printf("[lumieres] zone %d : %d allumees sur %d%n",
                    zone, l.litCount(), l.lampCount());
        }
    }

    /**
     * CHAQUE PROJECTILE EN VOL EST UNE LUMIERE.
     *
     * <p>Le jeu d'origine n'en allumait que quelques-uns -- ceux dont le pas d'animation porte
     * une luminosite --, et il ne s'agissait meme pas d'une lumiere : il ajoutait une valeur au
     * light map des points alentour. Les balles ordinaires ne faisaient rien du tout.
     *
     * <p>Ici toutes eclairent, de leur propre couleur, et la simulation n'en sait rien : elle
     * n'a pas bouge d'une ligne. Un IMPACT, lui, eclaire autrement -- c'est le moment le plus
     * lumineux du jeu, et sa teinte est celle du feu, pas celle du projectile.
     *
     * <p>DOUZE SOURCES EN TOUT : le ring de {@link Lights3D} garde les dernieres. Les
     * projectiles sont rarement plus nombreux, et les plus recents sont ceux qu'on regarde.
     */
    public void eclairerTirs() {
        Lights3D l = jeu.lights3d;
        if (l == null || jeu.shots == null || jeu.player == null) {
            return;
        }
        int vivants = 0;
        for (ab3d2.full.sim.Shots.Shot s : jeu.shots.shots()) {
            if (!s.active) {
                continue;
            }
            if (!s.popping) {
                vivants++;                             // un impact n'est plus un tir en vol
            }
            boolean eclate = s.popping;
            float ex = (s.x >> 16) / 64f;
            float ey = -s.heightWord / 64f;
            float ez = -(s.z >> 16) / 64f;
            l.flash(ex, ey, ez, eclate ? ECLAT : BALLE,
                    eclate ? ECLAT_TEINTE : jeu.builder.shotTint(s));
            // LES ECLATS PARTENT D'ICI, au meme endroit que l'eclair : c'est le seul endroit
            // du remake qui sache qu'un projectile vient de toucher quelque chose. On ne les
            // lance qu'une fois par impact -- popping dure plusieurs images.
            if (eclate && jeu.etatPhysique != null && !dejaEclate.contains(s)) {
                dejaEclate.add(s);
                jeu.etatPhysique.eclat(ex, ey, ez, s.zone, ECLAT_TEINTE);
            }
            if (!eclate) {
                dejaEclate.remove(s);                  // le projectile revit : il repartira
            }
        }
        // LA BOUCHE DU CANON. Un tir qui part eclaire la piece le temps d'une image : c'est ce
        // qu'on voit d'une arme, bien plus que la balle elle-meme. On le reconnait a
        // l'apparition d'un projectile de plus que l'image d'avant.
        if (vivants > tirsVivants) {
            l.flash(jeu.player.worldX(), jeu.player.worldY(), jeu.player.worldZ(),
                    BOUCHE, BOUCHE_TEINTE);
        }
        tirsVivants = vivants;
    }

    /**
     * Les ombres ne coutent que pendant qu'une source existe -- et que SCREEN OPTIONS les
     * laisse allumees.
     */
    public void reglerOmbres() {
        if (jeu.shadows != null && jeu.lights3d != null) {
            jeu.shadows.setEnabled(jeu.options.shadows && jeu.lights3d.hasShadowSource());
        }
    }

    /**
     * OMBRES PORTEES. On n'en met qu'UNE : ombrer soixante-dix lumieres est hors de question,
     * et une seule source ponctuelle en rend deja six faces de carte.
     */
    public void poserOmbres() {
        if (jeu.shadows == null || jeu.lights3d == null || jeu.lights3d.shadowLight() == null) {
            return;
        }
        jeu.shadows.setLight(jeu.lights3d.shadowSource());
        jeu.getRootNode().setShadowMode(
                com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive);
    }

    /** OU SE TIENT LA LUMIERE QU'ON PORTE, et celle qui jette les ombres. A chaque image. */
    public void suivreLeJoueur(float x, float y, float z, float plafond) {
        Lights3D l = jeu.lights3d;
        if (l == null) {
            return;
        }
        l.carryTorch(x, y, z);
        l.hangShadowLamp(x, y, z, plafond);
    }
}
