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
    /** Nombre de lumieres dynamiques recyclees (tirs, explosions, torches). */
    public static final int DYNAMIC = 12;
    /** Niveau de l'ambiante : ce qui reste visible d'une surface hors de portee de toute source. */
    private static final float AMBIENT_LEVEL = 0.34f;
    /** Reglage BRIGHTNESS du menu : multiplie l'ambiante (1,0 = valeur d'origine). */
    private float brightness = 1f;
    /** ZoneT_Roof_l : la valeur sentinelle « cette zone n'a pas de plafond ». */
    private static final int NO_ROOF = -32768;

    private final Node root;
    private final List<PointLight> lamps = new ArrayList<>();
    /** La lumiere de chaque zone, et sa couleur nominale (on l'eteint quand elle est ombree). */
    private final java.util.Map<Integer, PointLight> zoneLight = new java.util.HashMap<>();
    private final java.util.Map<Integer, ColorRGBA> zoneColour = new java.util.HashMap<>();
    /** Zones potentiellement visibles depuis chaque zone — le PVS du jeu d'origine. */
    private final java.util.Map<Integer, int[]> pvsOf = new java.util.HashMap<>();
    /** Zone de chaque lampe d'objet (les « glares »), pour l'éteindre avec sa pièce. */
    private final java.util.Map<PointLight, Integer> lampZone = new java.util.HashMap<>();
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

        // UNE LUMIERE PAR ZONE. Le light map du jeu est constant par zone (toutes les bornes
        // d'une zone partagent la meme valeur) : sa granularite lumineuse EST la zone. On la
        // rend donc par une vraie source, posee au centre de la zone sous son plafond, dont
        // l'intensite vient de la luminosite d'origine et la portee de la taille de la piece.
        List<LevelData.Point> pts = lvl.points == null ? List.<LevelData.Point>of() : lvl.points;
        for (LevelData.Zone z : lvl.zones == null ? List.<LevelData.Zone>of() : lvl.zones) {
            List<Integer> bp = z.borderPoints;
            if (bp == null || bp.isEmpty()) {
                continue;
            }
            float cx = 0;
            float cz = 0;
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            int n = 0;
            for (int i : bp) {
                if (i < 0 || i >= pts.size()) {
                    continue;
                }
                float x = pts.get(i).x;
                float zz = pts.get(i).z;
                cx += x;
                cz += zz;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, zz);
                maxZ = Math.max(maxZ, zz);
                n++;
            }
            if (n == 0) {
                continue;
            }
            cx /= n;
            cz /= n;
            float f = brightnessFactor(lightMap, z);
            if (System.getProperty("rebirth.lightLog") != null) {
                System.out.printf("[zone] %3d f=%.2f%n", z.id, f);
            }
            if (f <= 0.02f) {
                continue;                              // zone noire : pas de source
            }
            // HAUTEUR : aux deux tiers entre le sol et le plafond, pas collee au plafond. Une
            // source au plafond eclaire les sols de face mais RASE les murs (N.L faible), et
            // c'est ce qui les faisait tomber au noir.
            //
            // La sentinelle « pas de plafond » vaut EXACTEMENT -32768 (0x8000). Tester
            // « < -30000 » etait faux : les hauteurs sont en 8192e d'unite, un plafond de six
            // metres vaut -49152. Sur le niveau B, 115 zones sur 149 etaient ainsi prises pour
            // des zones sans plafond et leur lumiere posee au ras du sol.
            float floor = -z.floorH / 8192f;
            float roof = z.roofH != NO_ROOF ? -z.roofH / 8192f : floor + 2.5f;
            if (roof <= floor + 0.8f) {
                roof = floor + 2.5f;                   // plafond incoherent (sous le sol)
            }
            float y = floor + (roof - floor) * 0.66f;
            // PORTEE : jME attenue lineairement (1 - d/rayon). En la prenant a deux fois la
            // demi-etendue de la piece plus une marge, l'attenuation reste douce jusqu'aux murs
            // au lieu de s'annuler dessus.
            float half = Math.max(maxX - minX, maxZ - minZ) / 128f;
            float radius = 2f * half + 6f;
            // INTENSITE : le facteur du light map est une luminosite de SURFACE, pas une
            // puissance de source. La surface la plus exposee est le sol juste sous la lumiere,
            // ou l'attenuation vaut (1 - hauteur/portee) et N.L vaut 1 ; on cale donc l'intensite
            // pour que AMBIANTE + ce maximum fasse tout juste 1, sinon les zones les plus claires
            // (la passerelle du niveau A) partent en blanc pur.
            float drop = y - floor;
            float peakAtt = Math.max(0.15f, 1f - drop / radius);
            float intensity = f * (1f - AMBIENT_LEVEL) / peakAtt;
            PointLight p = new PointLight();
            p.setColor(new ColorRGBA(1f, 0.96f, 0.88f, 1f).mult(intensity));
            p.setRadius(radius);
            p.setPosition(new Vector3f(cx / 64f, y, -cz / 64f));
            root.addLight(p);
            lamps.add(p);
            zoneLight.put(z.id, p);
            zoneColour.put(z.id, p.getColor().clone());
            if (z.pvs != null) {
                int[] vis = new int[z.pvs.size()];
                for (int i = 0; i < vis.length; i++) {
                    vis[i] = z.pvs.get(i).zone;
                }
                pvsOf.put(z.id, vis);
            }
        }

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
            lampColour.put(p, p.getColor().clone());
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
            zoneLight.forEach((z, p) -> p.setColor(zoneColour.get(z)));
            lampColour.forEach(PointLight::setColor);
            return;
        }
        java.util.Set<Integer> on = new java.util.HashSet<>();
        for (int v : vis) {
            on.add(v);
        }
        on.add(zone);                                  // la sienne d'abord
        zoneLight.forEach((z, p) ->
                p.setColor(on.contains(z) ? zoneColour.get(z) : ColorRGBA.BlackNoAlpha));
        lampColour.forEach((p, c) ->
                p.setColor(on.contains(lampZone.getOrDefault(p, -1)) ? c : ColorRGBA.BlackNoAlpha));
    }

    /** Zone depuis laquelle l'allumage courant a été calculé. */
    public int litFrom() {
        return litFromZone;
    }

    /** Nombre de lumières de zone réellement allumées (diagnostic). */
    public int litCount() {
        int n = 0;
        for (PointLight p : zoneLight.values()) {
            if (p.getColor().r + p.getColor().g + p.getColor().b > 0f) {
                n++;
            }
        }
        return n;
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
