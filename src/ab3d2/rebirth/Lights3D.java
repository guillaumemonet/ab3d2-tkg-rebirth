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
    private final PointLight[] dyn = new PointLight[DYNAMIC];
    private AmbientLight ambient;
    private int nextDyn;

    public Lights3D(Node root) {
        this.root = root;
    }

    /** Pose les lumieres STATIQUES du niveau. A appeler apres avoir construit la geometrie. */
    public void build(LevelData lvl, GlfData glf, LightAnim lightMap) {
        clear();
        // AMBIANTE : assez haute pour qu'une surface hors de portee garde sa TEXTURE. Le rendu
        // d'origine etait plat par zone ; une ambiante franche en est plus proche, et les
        // lumieres ci-dessous ajoutent le relief par-dessus.
        ambient = new AmbientLight(new ColorRGBA(AMBIENT_LEVEL, AMBIENT_LEVEL,
                AMBIENT_LEVEL * 1.12f, 1f));
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
        poserRegions(zones, vues);

        for (LevelData.Obj o : lvl.objects == null ? List.<LevelData.Obj>of() : lvl.objects) {
            if (o.typeId != 1 || o.zone < 0) {
                continue;
            }
            GlfData.ObjDef def = glf == null ? null : glf.object(o.def);
            if (def == null || def.gfxType < 2) {
                continue;                              // gfxType 2 = « glare » : une lampe
            }
            PointLight p = new PointLight();
            p.setColor(lampColour(def.name).mult(2.2f));
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
                float intensite = clarte * (PEAK_LEVEL - AMBIENT_LEVEL) / att;
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

    /** Vrai si une source dynamique alimente la lumiere ombree cette frame. */
    public boolean hasShadowSource() {
        return shadowUsed;
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
            ambient.setColor(new ColorRGBA(AMBIENT_LEVEL * brightness,
                    AMBIENT_LEVEL * brightness, AMBIENT_LEVEL * 1.12f * brightness, 1f));
        }
    }
}
