package ab3d2.rebirth;

import java.util.ArrayList;
import java.util.List;

import com.jme3.light.AmbientLight;
import com.jme3.light.PointLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;

/**
 * Les lumieres du MOTEUR pour un niveau.
 *
 * <p>Le jeu d'origine n'a pas de positions de lampes : sa lumiere est un LIGHT MAP, une valeur
 * par coin de point de zone, que le remake cuit en couleur par sommet (MeshBuilder). Ce qui suit
 * s'ajoute par-dessus, et ne concerne que ce qui est VRAIMENT une source :
 * <ul>
 *   <li>une {@link AmbientLight} globale, pour que le light map soit visible ;</li>
 *   <li>une {@link PointLight} sur chaque objet « glare » pose dans le niveau ({@code Lampglare},
 *       {@code RoofGlare}) — ce sont les lampes que le joueur voit ;</li>
 *   <li>un petit jeu de lumieres DYNAMIQUES recyclees, pour les projectiles lumineux, les
 *       explosions et les torches (cf. {@link #dynamic(int)}).</li>
 * </ul>
 */
public final class Lights3D {

    /** Portee d'une lampe de decor, en unites jME (1 unite = 64 unites monde). */
    private static final float LAMP_RADIUS = 14f;
    /**
     * DE COMBIEN DEUX SOURCES D'UNE MEME REGION SONT ESPACEES, en unites jME.
     *
     * <p>Une piece n'a pas besoin d'une source par secteur : le decoupage en secteurs sert la
     * collision et la visibilite, pas l'eclairage. Ce qui compte pour l'oeil, c'est qu'une
     * region de meme clarte soit eclairee REGULIEREMENT — d'ou un pas fixe, et non une lampe
     * par morceau de geometrie.
     */
    private static final float SPACING = 18f;
    /** La portee d'une source de region : plus large que le pas, pour que les halos se joignent. */
    private static final float REGION_RADIUS = 40f;
    /**
     * De combien deux secteurs voisins peuvent differer de clarte en restant la MEME region.
     *
     * <p>Zero donnerait des regions minuscules — les clartes du jeu varient par petits pas.
     * Trop grand fondrait un couloir sombre dans la salle eclairee d'a cote.
     */
    private static final float SAME_LIGHT = 0.1f;
    /** Nombre de lumieres dynamiques recyclees (tirs, explosions, torches). */
    public static final int DYNAMIC = 12;
    /** Rayon donne a une lumiere ecartee : assez petit pour que le filtre de jME la rejette. */
    private static final float OFF_RADIUS = 0.01f;
    /**
     * LA CLARTE QU'UNE SURFACE ATTEINT SOUS SA SOURCE, au plus fort.
     *
     * <p>Caler ce maximum sur UN revient a poser les surfaces eclairees PILE au seuil de
     * saturation : la moindre texture claire y passe en blanc pur et perd son dessin. Le jeu
     * d'origine ne sature jamais — sa rampe d'ombrage s'arrete avant le blanc — et c'est ce
     * qui lui donne son relief a clarte moyenne EGALE. Mesure : 47,3 de moyenne et 0 % de
     * pixels satures chez lui, contre 43,7 et 1,3 % ici quand ce maximum valait un.
     */
    private static final float PEAK_LEVEL = 0.88f;
    /** Niveau de l'ambiante : ce qui reste visible d'une surface hors de portee de toute source. */
    private static final float AMBIENT_LEVEL = 0.22f;
    /** Reglage BRIGHTNESS du menu : multiplie l'ambiante (1,0 = valeur d'origine). */
    private float brightness = 1f;
    /**
     * L'EXPOSITION du rendu par light map.
     *
     * <p>La couleur par sommet vaut un pour la surface la plus claire du jeu, mais la texture
     * qu'elle module n'est pas blanche : le produit des deux reste loin du blanc. Ce facteur
     * remonte l'ensemble jusqu'a ce que les surfaces les plus claires occupent le haut de la
     * plage, comme chez le portage — sans toucher au RAPPORT entre clair et sombre, qui est
     * celui du jeu.
     */
    private static final float BAKED_EXPOSURE = 0.8f;
    /**
     * CE QUE L'AMBIANTE GARDE QUAND DE VRAIES LAMPES L'ACCOMPAGNENT.
     *
     * <p>Le light map cuit porte la clarte VOULUE de chaque surface ; pose seul, il lui
     * faut une ambiante a plein pour se montrer. Mais une ambiante a plein ecrase tout le
     * reste : une lampe ajoutee par-dessus ne fait qu'un leger halo blanc.
     *
     * <p>On la baisse donc, et la part qu'elle perd revient aux LAMPES REELLES, posees la
     * ou les auteurs ont mis les leurs.
     */
    private static final float HYBRID_AMBIENT = 0.58f;
    /** Ce qu'une lampe de decor ajoute par-dessus la clarte cuite. */
    private static final float LAMP_GAIN = 1.5f;
    /**
     * CE QUE LES SOURCES DE REGION GARDENT quand la clarte cuite les accompagne.
     *
     * <p>Les deux disent la meme chose -- ou il fait clair --, l'une a plat, l'autre avec
     * une vraie chute. Les additionner a plein brule les pieces eclairees ; on garde donc
     * la clarte cuite pour la CONDUITE et les sources pour le RELIEF.
     */
    private static final float REGION_GAIN = 0.70f;
    /** Le niveau d'ambiante de la pose courante : il depend du modele d'eclairage choisi. */
    private float ambientBase = AMBIENT_LEVEL;
    /** ZoneT_Roof_l : la valeur sentinelle « cette zone n'a pas de plafond ». */
    private static final int NO_ROOF = -32768;

    private final Node root;
    private final List<PointLight> lamps = new ArrayList<>();
    /** La lumiere de chaque zone, et sa couleur nominale (on l'eteint quand elle est ombree). */
    private final java.util.Map<Integer, PointLight> zoneLight = new java.util.HashMap<>();
    private final java.util.Map<Integer, ColorRGBA> zoneColour = new java.util.HashMap<>();
    /** Rayon d'origine de chaque lumiere de zone : c'est LUI qu'on annule pour l'ecarter. */
    private final java.util.Map<Integer, Float> zoneRadius = new java.util.HashMap<>();
    private final java.util.Map<PointLight, Float> lampRadius = new java.util.HashMap<>();
    /** Zones potentiellement visibles depuis chaque zone — le PVS du jeu d'origine. */
    private final java.util.Map<Integer, int[]> pvsOf = new java.util.HashMap<>();
    /** Zone de chaque lampe d'objet (les « glares »), pour l'éteindre avec sa pièce. */
    private final java.util.Map<PointLight, Integer> lampZone = new java.util.HashMap<>();
    /**
     * LES SECTEURS QUE CHAQUE SOURCE DESSERT.
     *
     * <p>Une source de region en couvre plusieurs, une lampe d'objet un seul. C'est par la que
     * l'allumage se decide : une source reste allumee tant qu'UN de ses secteurs est
     * potentiellement visible. Les deux familles se traitent donc pareil.
     */
    private final java.util.Map<PointLight, int[]> lampServes = new java.util.HashMap<>();

    /** Ce qu'on retient d'un secteur pour l'eclairer : ou il est, et a quelle clarte. */
    private static final class Zone {
        int id;
        float x, y, z;
        float sol;
        float clarte;
        int region;
    }
    private final java.util.Map<PointLight, ColorRGBA> lampColour = new java.util.HashMap<>();
    /** Dernière zone pour laquelle on a réglé l'allumage (évite de tout refaire par frame). */
    private int litFromZone = Integer.MIN_VALUE;
    /**
     * La seule lumiere qui porte des OMBRES : on ne peut pas en ombrer 134. Elle suit le joueur
     * et prend la place de la lumiere de sa zone — l'eclairage est donc inchange, seules les
     * ombres apparaissent, la ou le joueur les voit.
     */
    private PointLight shadowLight;
    private int shadowZone = -1;
    /**
     * CE QUE LE JOUEUR PORTE SUR LUI.
     *
     * <p>Le light map donne a chaque surface sa clarte, mais il est CUIT : rien n'y bouge, et
     * donc rien n'y porte d'ombre. Il fallait une source REELLE pour que les murs et les
     * monstres en projettent une, et elle ne pouvait pas etre une lumiere de piece — celles-la
     * ne font que reconstituer le light map, qui contient deja sa propre occlusion, et les
     * ombrer occlut DEUX FOIS (un couloir n'est alors eclaire que d'un cote, avec une coupure
     * nette).
     *
     * <p>Celle-ci n'est dans aucun light map : elle suit le joueur. Elle n'ajoute presque rien
     * a la clarte — ce n'est pas son role — mais elle donne un point d'ou les ombres partent,
     * et il se trouve toujours la ou l'on regarde.
     */
    private PointLight torch;
    /** Ce que la torche ajoute : assez pour detacher les volumes, trop peu pour eclairer. */
    private static final float TORCH_LEVEL = 0.22f;
    /** Sa portee, en unites jME : la piece autour de soi, pas au-dela. */
    private static final float TORCH_RADIUS = 22f;
    /**
     * LA LAMPE QUI PORTE LES OMBRES — et rien d'autre.
     *
     * <p>Elle n'est dans AUCUNE scene : le filtre d'ombres ne lui demande qu'une position et
     * une portee, pour en tirer ses six faces de carte. Elle n'eclaire donc rien, et ne coute
     * pas une passe de lumiere.
     *
     * <p>Elle est separee de la torche parce qu'une source POSEE SUR L'OEIL ne porte aucune
     * ombre visible : tout ce qu'elle eclaire lui fait face, et le peu qu'elle occulte se cache
     * pile derriere ce qui l'occulte. Mesure a l'appui — le filtre ne changeait que TROIS
     * pixels sur neuf cent mille. Celle-ci se tient au-dessus de la tete, comme une lampe de
     * plafond : les ombres tombent alors sur le sol tout autour, la ou on les voit.
     */
    private PointLight shadowLamp;
    /** De combien elle surplombe l'oeil, en unites jME. */
    private static final float LAMP_ABOVE = 1.8f;
    /** Ce qu'elle garde sous le plafond : collee dessus, elle passerait de l'autre cote. */
    private static final float LAMP_UNDER_ROOF = 0.4f;
    /** Sa portee : la piece entiere, sinon les ombres s'arretent net a mi-sol. */
    private static final float LAMP_REACH = 34f;
    /** Au-dela de quoi une vraie lampe est trop loin pour porter l'ombre du joueur. */
    private static final float SHADOW_FROM_LAMP = 26f;
    private final PointLight[] dyn = new PointLight[DYNAMIC];
    private AmbientLight ambient;
    private int nextDyn;

    public Lights3D(Node root) {
        this.root = root;
    }

    /** Pose les lumieres STATIQUES du niveau. A appeler apres avoir construit la geometrie. */
    /**
     * Les sources STATIQUES sont-elles posees ?
     *
     * <p>NON par defaut : la clarte d'origine porte l'eclairage a leur place, et les
     * reprendre en lampes la compterait DEUX FOIS. {@code -Pzonelights} les remet.
     *
     * <p>ET CELA NE MARCHE PAS ENCORE, pour une raison trouvee et non reparee : en rendu
     * MOTEUR, la geometrie n'est pas enregistree dans {@code litMeshes}. Rien ne garde donc la
     * luminosite par sommet, la couleur par sommet n'atteint jamais le shader (verifie : en la
     * forcant au ROUGE VIF, l'image ne rougit pas d'un pouce) et {@code refreshLighting} n'a
     * rien a rafraichir. Le drapeau se contente aujourd'hui d'eteindre les sources statiques,
     * ce qui donne une image structurellement plus juste mais trop sombre.
     *
     * <p>Pour que le modele tienne, il faudrait que le chemin MOTEUR enregistre ses maillages
     * comme le fait le chemin {@code -Pretro}. C'est un travail sur LevelBuilder, pas un
     * reglage.
     */
    private static boolean sourcesStatiques() {
        return System.getProperty("rebirth.nolamps") == null;
    }

    /**
     * LE MODELE PAR DEFAUT : la clarte cuite ET de vraies sources.
     *
     * <p>{@code -Pzonelights} ne garde que les sources (l'ancien modele tout-lampes),
     * {@code -Pnolamps} ne garde que la clarte cuite (l'ancien modele tout-cuit).
     */
    private static boolean hybride() {
        return System.getProperty("rebirth.zonelights") == null
                && System.getProperty("rebirth.nolamps") == null;
    }

    public void build(LevelData lvl, GlfData glf, LightAnim lightMap) {
        clear();
        // AMBIANTE : assez haute pour qu'une surface hors de portee garde sa TEXTURE. Le rendu
        // d'origine etait plat par zone ; une ambiante franche en est plus proche, et les
        // lumieres ci-dessous ajoutent le relief par-dessus.
        // L'AMBIANTE PORTE L'IMAGE. La couleur par sommet tient la clarte de chaque surface ;
        // l'ambiante n'est plus un fond de secours mais la lumiere qui la REVELE. On la pose
        // donc a plein, et c'est le light map qui fait l'ombre et la lumiere — comme chez le
        // jeu d'origine, dont l'ombrage est plat par surface.
        float parDefaut = hybride() ? HYBRID_AMBIENT
                : (sourcesStatiques() ? AMBIENT_LEVEL : BAKED_EXPOSURE);
        ambientBase = Float.parseFloat(System.getProperty("rebirth.ambient",
                String.valueOf(parDefaut)));
        ambient = new AmbientLight(ColorRGBA.White.clone());
        root.addLight(ambient);
        setBrightness(brightness);

        // DES REGIONS, PAS DES SECTEURS. Le light map du jeu est constant par secteur, et le
        // remake en faisait donc une source par secteur : cent trente-quatre au niveau A, cent
        // quatre-vingt-dix-neuf au C. Mais le decoupage en secteurs sert la COLLISION et la
        // VISIBILITE ; il ne dit rien de l'eclairage. Un couloir droit de vingt morceaux n'a
        // pas vingt lampes : il en a une, ou deux s'il est long.
        //
        // On reunit donc les secteurs VOISINS DE MEME CLARTE en regions, et chaque region
        // recoit des sources posees a PAS FIXE. L'eclairage ne depend plus de la facon dont
        // la geometrie est decoupee — c'est ce qui le rend uniforme.
        List<LevelData.Point> pts = lvl.points == null ? List.<LevelData.Point>of() : lvl.points;
        List<LevelData.Zone> zones = lvl.zones == null ? List.<LevelData.Zone>of() : lvl.zones;
        java.util.Map<Integer, Zone> vues = new java.util.LinkedHashMap<>();
        for (LevelData.Zone z : zones) {
            if (z.pvs != null) {
                int[] vis = new int[z.pvs.size()];
                for (int i = 0; i < vis.length; i++) {
                    vis[i] = z.pvs.get(i).zone;
                }
                pvsOf.put(z.id, vis);
            }
            List<Integer> bp = z.borderPoints;
            if (bp == null || bp.isEmpty()) {
                continue;
            }
            float cx = 0;
            float cz = 0;
            int n = 0;
            for (int i : bp) {
                if (i < 0 || i >= pts.size()) {
                    continue;
                }
                cx += pts.get(i).x;
                cz += pts.get(i).z;
                n++;
            }
            if (n == 0) {
                continue;
            }
            float f = brightnessFactor(lightMap, z);
            if (System.getProperty("rebirth.lightLog") != null) {
                System.out.printf("[zone] %3d f=%.2f%n", z.id, f);
            }
            if (f <= 0.02f) {
                continue;                              // secteur noir : pas de source
            }
            // HAUTEUR : aux deux tiers entre le sol et le plafond, pas collee au plafond. Une
            // source au plafond eclaire les sols de face mais RASE les murs (N.L faible), et
            // c'est ce qui les faisait tomber au noir.
            //
            // La sentinelle « pas de plafond » vaut EXACTEMENT -32768 (0x8000). Tester
            // « < -30000 » etait faux : les hauteurs sont en 8192e d'unite, un plafond de six
            // metres vaut -49152. Sur le niveau B, 115 secteurs sur 149 etaient ainsi pris pour
            // des secteurs sans plafond et leur lumiere posee au ras du sol.
            float sol = -z.floorH / 8192f;
            float toit = z.roofH != NO_ROOF ? -z.roofH / 8192f : sol + 2.5f;
            if (toit <= sol + 0.8f) {
                toit = sol + 2.5f;                     // plafond incoherent (sous le sol)
            }
            Zone v = new Zone();
            v.id = z.id;
            v.x = cx / n / 64f;
            v.z = -cz / n / 64f;
            v.y = sol + (toit - sol) * 0.66f;
            v.sol = sol;
            v.clarte = f;
            vues.put(z.id, v);
        }
        if (sourcesStatiques()) {
            poserRegions(zones, vues);
        }

        // LES LAMPES DU DECOR SONT DE VRAIES LUMIERES.
        //
        // Leur halo est deja cuit dans le light map, et c'est pour cela qu'on les avait
        // laissees de cote : les compter deux fois. Mais un halo cuit ne porte pas d'ombre,
        // ne varie pas avec l'angle de vue, et ne s'eteint pas. On les pose donc pour de
        // bon, et l'ambiante baisse d'autant (HYBRID_AMBIENT) : le light map garde la
        // conduite de l'ensemble -- quelle piece est claire, laquelle est noire --, les
        // lampes y ajoutent le point chaud, la chute, et les ombres portees.
        //
        // Elles sont peu nombreuses -- une poignee par niveau -- et ne s'allument que pour
        // les secteurs potentiellement visibles (cf. followPlayer).
        float gain = Float.parseFloat(System.getProperty("rebirth.lampgain",
                String.valueOf(LAMP_GAIN)));
        for (LevelData.Obj o : lvl.objects == null ? List.<LevelData.Obj>of() : lvl.objects) {
            if (o.typeId != 1 || o.zone < 0) {
                continue;
            }
            // C'EST L'OBJET QUI DIT QU'IL BRILLE, pas sa definition relue : l'extraction a
            // deja fait le test (ODefT_GFXType == 2) et l'a note sur lui. Le champ gfxType
            // du fichier relu ne porte pas la meme chose et ne valait jamais 2 -- aucune
            // lampe n'etait donc posee, sur aucun niveau.
            if (!o.additive) {
                continue;
            }
            GlfData.ObjDef def = glf == null ? null : glf.object(o.def);
            PointLight p = new PointLight();
            p.setColor(lampColour(def == null ? "" : def.name)
                    .mult(hybride() ? gain : 2.2f));
            p.setRadius(LAMP_RADIUS);
            p.setPosition(new Vector3f(o.x / 64f, -o.height / 64f, -o.z / 64f));
            root.addLight(p);
            lamps.add(p);
            lampZone.put(p, o.zone);
            lampServes.put(p, new int[] {o.zone});
            lampColour.put(p, p.getColor().clone());
            lampRadius.put(p, p.getRadius());
        }

        shadowLight = new PointLight();
        shadowLight.setColor(ColorRGBA.Black);
        shadowLight.setRadius(0.01f);
        root.addLight(shadowLight);
        shadowZone = -1;

        if (!sourcesStatiques()) {                     // la clarte vient du light map
            torch = new PointLight();
            torch.setColor(new ColorRGBA(1f, 0.94f, 0.82f, 1f).mult(TORCH_LEVEL));
            torch.setRadius(TORCH_RADIUS);
            root.addLight(torch);
        }

        for (int i = 0; i < DYNAMIC; i++) {
            dyn[i] = new PointLight();
            dyn[i].setColor(ColorRGBA.Black);
            dyn[i].setRadius(0.01f);
            root.addLight(dyn[i]);
        }
    }

    /**
     * Une lumiere dynamique prise dans le carrousel : celle-ci ecrase la plus ancienne. C'est ce
     * qui remplace l'ecriture CPU dans le light map ({@code anim_BrightenPoints}).
     *
     * @param bright luminosite d'origine (0..40 environ)
     */
    /**
     * N'allume que les lumières des zones POTENTIELLEMENT VISIBLES depuis celle du joueur.
     *
     * <p>Toutes les lumières sont attachées à la racine, donc jME les applique à TOUTE la
     * géométrie : avec une lumière par zone (134 au niveau A, 199 au C) et un paquet de 4 en
     * SinglePass, c'était près de quarante passes sur chaque surface. Or le jeu d'origine sait
     * déjà quelles zones peuvent se voir — c'est son PVS, qu'on extrait avec le reste. On s'en
     * sert ici : depuis une zone donnée, une vingtaine de zones sont visibles en médiane, jamais
     * plus de trente-six. Les autres lumières sont mises à NOIR, ce qui les rend gratuites sans
     * toucher à la liste (jME garde l'ordre, rien n'est réalloué).
     *
     * <p>Aucun changement visible : une lumière hors PVS n'éclairait rien de ce qu'on voit.
     *
     * @param zone zone du joueur ; &lt; 0 ou inconnue = on rallume tout (repli sûr)
     */
    public void lightZonesVisibleFrom(int zone) {
        if (zone == litFromZone) {
            return;                                    // rien n'a bougé
        }
        litFromZone = zone;
        int[] vis = pvsOf.get(zone);
        if (vis == null) {                             // zone inconnue : on ne prend pas de risque
            lampRadius.forEach(PointLight::setRadius);
            return;
        }
        java.util.Set<Integer> on = new java.util.HashSet<>();
        for (int v : vis) {
            on.add(v);
        }
        on.add(zone);                                  // la sienne d'abord
        // On annule le RAYON, pas la couleur : jME ecarte une lumiere en testant son rayon
        // contre le volume englobant de la geometrie (DefaultLightFilter). Une lumiere noire
        // mais de grand rayon reste dans le lot et continue de coûter une passe — mesure a
        // l'appui : le nombre d'objets soumis ne bougeait pas d'un pouce.
        //
        // Une source de region dessert plusieurs secteurs : il suffit que l'UN d'eux soit
        // visible pour qu'elle reste allumee.
        lampRadius.forEach((p, r) -> {
            int[] sert = lampServes.get(p);
            boolean allume = false;
            if (sert != null) {
                for (int z : sert) {
                    if (on.contains(z)) {
                        allume = true;
                        break;
                    }
                }
            }
            p.setRadius(allume ? r : OFF_RADIUS);
        });
    }

    /** Zone depuis laquelle l'allumage courant a été calculé. */
    public int litFrom() {
        return litFromZone;
    }

    /** Nombre de sources reellement allumees (diagnostic). */
    public int litCount() {
        int n = 0;
        for (PointLight p : lamps) {
            if (p.getRadius() > OFF_RADIUS) {
                n++;
            }
        }
        return n;
    }

    /**
     * POSE LES SOURCES DES REGIONS.
     *
     * <p>Trois temps. On reunit d'abord les secteurs voisins de meme clarte — voisins au sens
     * du jeu, c'est-a-dire relies par une arete qui joint deux secteurs. On decoupe ensuite
     * chaque region en cases d'un pas fixe, et l'on garde les cases ou la region se trouve
     * vraiment. Chaque case recoit une source, a la clarte MOYENNE de la region : c'est ce qui
     * fait qu'une grande salle s'eclaire d'un seul tenant au lieu d'etre un damier.
     *
     * <p>Une region eclaire aussi les secteurs NOIRS qui la touchent — un couloir sans lumiere
     * propre prend celle de la piece dont il part, ce que le rendu d'origine faisait aussi.
     */
    private void poserRegions(List<LevelData.Zone> zones, java.util.Map<Integer, Zone> vues) {
        if (vues.isEmpty()) {
            return;
        }
        // 1. REUNIR. Deux secteurs voisins de clarte proche sont la meme region.
        java.util.Map<Integer, Integer> chef = new java.util.HashMap<>();
        for (int id : vues.keySet()) {
            chef.put(id, id);
        }
        for (LevelData.Zone z : zones) {
            Zone a = vues.get(z.id);
            if (a == null || z.edges == null) {
                continue;
            }
            for (LevelData.ZoneEdge e : z.edges) {
                Zone b = vues.get(e.joinZone);
                if (b != null && Math.abs(a.clarte - b.clarte) <= SAME_LIGHT) {
                    unir(chef, a.id, b.id);
                }
            }
        }
        java.util.Map<Integer, List<Zone>> regions = new java.util.LinkedHashMap<>();
        for (Zone v : vues.values()) {
            v.region = racine(chef, v.id);
            regions.computeIfAbsent(v.region, k -> new ArrayList<>()).add(v);
        }

        // 2. DECOUPER chaque region en cases d'un pas fixe, et garder celles qu'elle occupe.
        for (List<Zone> region : regions.values()) {
            float clarte = 0;
            for (Zone v : region) {
                clarte += v.clarte;
            }
            clarte /= region.size();                   // la MEME clarte pour toute la region
            java.util.Map<Long, List<Zone>> cases = new java.util.LinkedHashMap<>();
            for (Zone v : region) {
                long cx = (long) Math.floor(v.x / SPACING);
                long cz = (long) Math.floor(v.z / SPACING);
                cases.computeIfAbsent((cx << 32) ^ (cz & 0xFFFFFFFFL),
                        k -> new ArrayList<>()).add(v);
            }
            // 3. UNE SOURCE PAR CASE, au milieu de ce que la region y occupe.
            for (List<Zone> dedans : cases.values()) {
                float x = 0;
                float y = 0;
                float z = 0;
                float sol = 0;
                for (Zone v : dedans) {
                    x += v.x;
                    y += v.y;
                    z += v.z;
                    sol += v.sol;
                }
                x /= dedans.size();
                y /= dedans.size();
                z /= dedans.size();
                sol /= dedans.size();
                // INTENSITE : le facteur du light map est une luminosite de SURFACE, pas une
                // puissance de source. La surface la plus exposee est le sol juste sous la
                // lumiere, ou l'attenuation vaut (1 - hauteur/portee) et N.L vaut 1 ; on cale
                // donc l'intensite pour que AMBIANTE + ce maximum fasse tout juste 1, sinon
                // les regions les plus claires partent en blanc pur.
                float chute = y - sol;
                float att = Math.max(0.15f, 1f - chute / REGION_RADIUS);
                // EN HYBRIDE, LA SOURCE NE PORTE QUE LE RELIEF.
                //
                // La division par l'attenuation ci-dessus sert au modele TOUT-LAMPES, ou la
                // source doit a elle seule amener la surface au niveau voulu. Gardee ici,
                // elle donnait a chaque source de quoi eclairer une piece entiere : les
                // dessus horizontaux, qu'elle regarde de face et de pres, partaient au BLANC
                // pendant que les murs, qu'elle rase, restaient sombres.
                //
                // La clarte cuite porte l'eclairage ; la source ajoute le point chaud et la
                // chute, a hauteur d'une fraction seulement.
                float intensite = hybride()
                        ? clarte * Float.parseFloat(System.getProperty(
                                "rebirth.regiongain", String.valueOf(REGION_GAIN)))
                        : clarte * (PEAK_LEVEL - AMBIENT_LEVEL) / att;
                PointLight p = new PointLight();
                p.setColor(new ColorRGBA(1f, 0.96f, 0.88f, 1f).mult(intensite));
                p.setRadius(REGION_RADIUS);
                p.setPosition(new Vector3f(x, y, z));
                root.addLight(p);
                lamps.add(p);
                lampColour.put(p, p.getColor().clone());
                lampRadius.put(p, p.getRadius());
                int[] sert = new int[dedans.size()];
                for (int i = 0; i < sert.length; i++) {
                    sert[i] = dedans.get(i).id;
                    zoneLight.putIfAbsent(dedans.get(i).id, p);
                }
                lampServes.put(p, sert);
            }
        }
    }

    private static int racine(java.util.Map<Integer, Integer> chef, int a) {
        int r = a;
        while (chef.get(r) != null && chef.get(r) != r) {
            r = chef.get(r);
        }
        return r;
    }

    private static void unir(java.util.Map<Integer, Integer> chef, int a, int b) {
        int ra = racine(chef, a);
        int rb = racine(chef, b);
        if (ra != rb) {
            chef.put(ra, rb);
        }
    }

    public void flash(float wx, float wy, float wz, int bright, ColorRGBA colour) {
        if (dyn[0] == null || bright <= 0) {
            return;
        }
        // La PREMIERE source de la frame prend la lumiere porteuse d'ombres : c'est la plus
        // ancienne encore vivante, donc la plus stable a l'image. Les suivantes eclairent sans
        // ombrer — six faces de carte d'ombres par source, on n'en rend qu'une.
        PointLight p;
        if (shadowLight != null && !shadowUsed) {
            p = shadowLight;
            shadowUsed = true;
        } else {
            p = dyn[nextDyn];
            nextDyn = (nextDyn + 1) % DYNAMIC;
        }
        p.setPosition(new Vector3f(wx, wy, wz));
        // Meme logique que les lumieres de zone : l'intensite reste sous la saturation une fois
        // l'ambiante ajoutee, sinon un plasma en vol blanchit tout le sol autour de lui.
        float b = Math.min(bright, 40);
        p.setColor(colour.mult(b / 40f * (1f - AMBIENT_LEVEL) * 1.3f));
        p.setRadius(3f + b * 0.32f);
    }

    /**
     * Eteint toutes les lumieres dynamiques. A appeler au DEBUT de chaque frame de simulation :
     * sans ca, un projectile qui disparait laisse sa lumiere allumee pour toujours.
     */
    public void fadeDynamic() {
        for (PointLight p : dyn) {
            if (p != null) {
                p.setColor(ColorRGBA.Black);
                p.setRadius(0.01f);
            }
        }
        if (shadowLight != null) {
            shadowLight.setColor(ColorRGBA.Black);
            shadowLight.setRadius(0.01f);
        }
        shadowUsed = false;
        nextDyn = 0;
    }

    private boolean shadowUsed;

    /**
     * OU SE TIENT CELUI QUI PORTE LA LUMIERE. A appeler une fois par image.
     *
     * <p>Un peu au-dessus de l'oeil et un peu en arriere : une source pile sur la camera
     * n'ombre RIEN, puisque tout ce qu'elle eclaire lui fait face.
     */
    public void carryTorch(float x, float y, float z) {
        if (torch != null) {
            torch.setPosition(new Vector3f(x, y + 0.6f, z));
        }
    }

    /**
     * OU PEND LA LAMPE QUI OMBRE. A appeler une fois par image, avec le plafond de la piece.
     *
     * <p>Au-dessus de la tete, mais sous le plafond : passee de l'autre cote, elle verrait la
     * piece par le dessus d'une surface qui lui tourne le dos, et TOUT y serait dans l'ombre.
     *
     * @param plafond hauteur du plafond en unites jME, ou {@code Float.NaN} si on l'ignore
     */
    public void hangShadowLamp(float x, float y, float z, float plafond) {
        if (shadowLamp == null) {
            shadowLamp = new PointLight();            // jamais ajoutee a la scene : voir plus haut
            shadowLamp.setColor(ColorRGBA.Black);
            shadowLamp.setRadius(LAMP_REACH);
        }
        // LA PLUS PROCHE DES VRAIES LAMPES, SI ELLE EST ALLUMEE.
        //
        // Une ombre dit d'ou vient la lumiere. Prise au-dessus de la tete, elle tombe a
        // l'aplomb de ce qui la porte et ne raconte rien ; prise a la lampe de la piece,
        // elle s'allonge DANS LE BON SENS, et le joueur lit la scene avec.
        PointLight proche = null;
        float best = SHADOW_FROM_LAMP * SHADOW_FROM_LAMP;
        for (PointLight l : lamps) {
            if (l.getRadius() <= OFF_RADIUS) {
                continue;                              // eteinte : sa piece n'est pas visible
            }
            float d = l.getPosition().distanceSquared(new Vector3f(x, y, z));
            if (d < best) {
                best = d;
                proche = l;
            }
        }
        if (proche != null) {
            shadowLamp.setPosition(proche.getPosition().clone());
            return;
        }
        float h = y + LAMP_ABOVE;
        if (!Float.isNaN(plafond)) {
            h = Math.min(h, plafond - LAMP_UNDER_ROOF);
            h = Math.max(h, y);                        // un couloir ecrase : on reste a l'oeil
        }
        shadowLamp.setPosition(new Vector3f(x, h, z));
    }

    /** La source qui porte les ombres. Elle n'eclaire pas : elle ne fait que les poser. */
    public PointLight shadowSource() {
        if (shadowLamp == null) {
            hangShadowLamp(0f, 0f, 0f, Float.NaN);
        }
        return shadowLamp;
    }

    /** Vrai si une source alimente la lumiere ombree cette frame. */
    public boolean hasShadowSource() {
        return true;                                   // la lampe qui ombre existe toujours
    }

    public int lampCount() {
        return lamps.size();
    }

    /**
     * L'ambiante globale et la lampe d'une zone. L'arme tenue est dessinee dans SA PROPRE vue
     * (pour ne pas traverser les murs) : elle n'herite donc pas des lumieres de la scene, et
     * Main lui repose ces deux-la, celles qui l'eclairent vraiment la ou se tient le joueur.
     */
    public AmbientLight ambientLight() {
        return ambient;
    }

    public PointLight zoneLight(int zone) {
        return zoneLight.get(zone);
    }

    /** La lumiere porteuse d'ombres, a donner au PointLightShadowRenderer. */
    public PointLight shadowLight() {
        return shadowLight;
    }

    /**
     * <b>Pourquoi les lumieres de ZONE ne portent PAS d'ombres.</b> Elles ne representent pas des
     * luminaires : elles reconstituent le LIGHT MAP du jeu, qui contient deja sa propre occlusion
     * (les auteurs ont assombri les zones sombres a la main). Les ombrer occlut donc deux fois —
     * essaye et verifie : un couloir n'est plus eclaire que d'un cote, avec une coupure nette la
     * ou s'arrete la portee de la source. Seules les sources REELLES portent des ombres, et ce
     * sont les dynamiques : projectiles, explosions, torches.
     */

    public void clear() {
        zoneLight.clear();
        zoneColour.clear();
        pvsOf.clear();
        lampZone.clear();
        lampColour.clear();
        zoneRadius.clear();
        lampRadius.clear();
        litFromZone = Integer.MIN_VALUE;
        if (shadowLight != null) {
            root.removeLight(shadowLight);
            shadowLight = null;
        }
        if (torch != null) {
            root.removeLight(torch);
            torch = null;
        }
        shadowZone = -1;
        if (ambient != null) {
            root.removeLight(ambient);
            ambient = null;
        }
        for (PointLight p : lamps) {
            root.removeLight(p);
        }
        lamps.clear();
        for (int i = 0; i < DYNAMIC; i++) {
            if (dyn[i] != null) {
                root.removeLight(dyn[i]);
                dyn[i] = null;
            }
        }
        nextDyn = 0;
    }

    /**
     * Facteur lumineux d'une zone, tire du light map d'origine : meme conversion que la couleur
     * par sommet (MeshBuilder.bakedColours), 1 = le plus clair, 0 = noir.
     */
    private static float brightnessFactor(LightAnim lightMap, LevelData.Zone z) {
        if (lightMap == null) {
            return 0.5f;
        }
        // PIEGE : il faut se limiter aux points REELLEMENT utilises par la zone. Un emplacement
        // vide a une valeur brute de 0, que la conversion du jeu transforme en 300 — c'est-a-dire
        // le MAXIMUM de clarte. En balayant les 40 emplacements, chaque zone heritait donc de la
        // clarte d'un emplacement vide et toutes ressortaient a 1,00.
        int used = z.borderPoints == null ? 0 : Math.min(10, z.borderPoints.size());
        if (used == 0) {
            return 0.5f;
        }
        float sum = 0;
        int n = 0;
        for (int i = 0; i < used; i++) {
            for (int corner = 0; corner < 4; corner++) {
                int v = lightMap.pointBright(z.id, i * 4 + corner);
                if (v == 0) {
                    continue;
                }
                float b = 2f * (Math.abs(v) - 300f);
                b = ((b + 128f) % 256f + 256f) % 256f - 128f;
                float block = Math.min((float) Math.ceil(Math.max(0f, Math.min(64f, b)) * 0.5f), 31f);
                sum += 1f - block / 31f;
                n++;
            }
        }
        return n == 0 ? 0.5f : sum / n;
    }

    /** Teinte d'une lampe : le jeu n'en donne pas, on la tire du nom de sa definition. */
    private static ColorRGBA lampColour(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.contains("roof")) {
            return new ColorRGBA(1f, 0.95f, 0.85f, 1f);    // plafonnier, blanc chaud
        }
        return new ColorRGBA(1f, 0.9f, 0.7f, 1f);
    }

    /** BRIGHTNESS (SCREEN OPTIONS) : eclaircit ou assombrit toute la scene. */
    public void setBrightness(float factor) {
        brightness = Math.max(0.25f, Math.min(2f, factor));
        if (ambient != null) {
            ambient.setColor(new ColorRGBA(ambientBase * brightness,
                    ambientBase * brightness, ambientBase * 1.06f * brightness, 1f));
        }
    }
}
