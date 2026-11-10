package ab3d2.rebirth.extract;

import ab3d2.Defs;
import ab3d2.Mem;
import ab3d2.ObjectmoveData;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Oracle de {@code MoveObject} (objectmove.s:11) : la collision du jeu, au mot pres.
 *
 * <p>C'est la routine la plus appelee de la simulation et la seule que rien n'eprouvait. Tout
 * ce qui se deplace horizontalement y passe : les deux joueurs, chaque balle, chaque monstre
 * qui glisse, chaque flamme d'explosion. Elle fait trois choses d'affilee, et les trois
 * comptent :
 *
 * <ul>
 *   <li>{@code checkwalls} — le CONTOUR de la zone (premier groupe de la liste d'aretes). Un
 *       mur qu'on traverse ne renvoie pas : il PROJETTE le point sur la ligne du mur, et l'on
 *       glisse le long ;</li>
 *   <li>{@code checkotherwalls} — le meme travail avec un RAYON ({@code Obj_ExtLen_w}) et sur
 *       TOUS les groupes, pas seulement le contour : c'est ce qui empeche de raser un angle ;</li>
 *   <li>{@code CheckMoreFloorLines} — la marche de portails, qui change de zone et recommence
 *       tout, jusqu'a cinquante fois.</li>
 * </ul>
 *
 * <h2>Ce que le rejeu doit reproduire</h2>
 *
 * <p>Pas seulement le point d'arrivee : la ZONE d'arrivee, l'etage ({@code StoodInTop}), la
 * hauteur du contact ({@code wallhitheight}), le drapeau {@code hitwall}, les donnees du mur
 * touche ({@code WallXSize_w / WallZSize_w / WallLength_w}, que le rebond relit), le chemin de
 * zones traversees ({@code Obj_RoomPath_vw}) — et les ARETES ARMEES. Ce dernier point n'est pas
 * decoratif : le bit pose dans {@code EdgeT_Flags_w} est le seul handshake qui ouvre les portes.
 * Une collision juste qui n'arme pas la bonne arete laisse le joueur devant une porte close.
 *
 * <h2>Les reglages sont des globales</h2>
 *
 * <p>{@code MoveObject} ne prend aucun parametre : tout passe par des globales que l'appelant
 * pose — et parfois ne pose pas. Les neuf configurations relevees ici sont celles des appelants
 * reels (hires.s:2971 et 3165 pour les deux joueurs, newplayershoot.s et newaliencontrol.s pour
 * les balles, newanims.s:2350 pour un projectile anime et newanims.s:3137 pour une flamme), plus
 * une derniere, synthetique, qui pousse le decalage de {@code Obj_AwayFromWall_b} a deux bits :
 * aucun appelant ne le fait, mais rien dans la routine ne l'interdit, et un portage qui ignore
 * le decalage ne se trahirait pas autrement.
 *
 * <p>{@code Obj_AwayFromWall_b} vaut $FF pour une balle : NEGATIF, donc pas de rayon du tout,
 * l'arete est prise telle quelle. Il vaut 0 pour un joueur : le rayon est l'octet de normale
 * sans decalage. Il vaut 1 pour une flamme : le meme octet, decale d'un bit.
 *
 * <h2>Ou l'on part</h2>
 *
 * <p>Du barycentre des sommets du contour de chaque zone — les zones sont convexes, c'est ce
 * que {@code checkwalls} suppose — vers seize directions et trois distances. Les courtes
 * restent dans la zone, les longues en sortent, et les tres longues en traversent plusieurs.
 * La hauteur bouge aussi d'un bout a l'autre du pas : sans cela l'interpolation de hauteur au
 * point de franchissement ne serait jamais exercee.
 */
public final class MoveExport {

    /** Les niveaux releves. */
    private static final int[] NIVEAUX = {0, 2, 6, 12, 13, 14};

    /**
     * Combien de zones par niveau, au plus — assez pour les prendre TOUTES. C'est le sol et le
     * plafond d'un secteur qui decident si une arete est un mur ou un passage : n'en echantillonner
     * qu'une poignee, c'est n'eprouver qu'une poignee de geometries.
     */
    private static final int ZONES = 4000;

    /** Les distances parcourues en un pas. */
    private static final int[] PAS = {96, 640, 2600};

    /**
     * Le decalage de hauteur entre le depart et l'arrivee. Il faut qu'il soit parfois ENORME :
     * c'est le seul moyen que l'etage releve au point de franchissement differe de l'etage
     * releve a l'arrivee, et donc que l'interpolation de hauteur se voie.
     */
    private static final int[] MONTEES = {0, 256, -700, 2000, -5000, 12000};

    /**
     * Les dix reglages : nom, {@code Obj_ExtLen_w}, {@code Obj_AwayFromWall_b},
     * {@code Obj_WallBounce_b}, {@code exitfirst}, {@code wallflags}, {@code StepUpVal},
     * {@code thingheight}. {@code StepDownVal} vaut $1000000 partout.
     */
    private static final Object[][] REGLAGES = {
        {"joueur1", 40, 0, 0, 0, 0b100000000, 40 * 256, 56 * 256},
        {"joueur1Accroupi", 40, 0, 0, 0, 0b100000000, 10 * 256, 20 * 256},
        {"joueur2", 40, 0, 0, 0, 0b100000000000, 40 * 256, 56 * 256},
        {"balle", 0, 0xFF, 0, 1, 0b10000000000, 0, 0},
        {"balleAnim", 0, 0xFF, 0, 1, 0b10000000000, 0, 10 * 128},
        {"balleRebond", 0, 0xFF, 1, 0, 0b10000000000, 0, 10 * 128},
        {"flamme", 80, 1, 0, 1, 0b10000000000, 0, 10 * 128},
        {"flammeRebond", 80, 1, 1, 0, 0b10000000000, 0, 10 * 128},
        {"largeDecale", 80, 2, 0, 0, 0b10000000000, 0, 10 * 128},
        // Synthetique, et pour deux raisons a la fois : $81 est un octet NEGATIF dont les six
        // bits bas valent un, de sorte qu'un portage qui lirait le reglage comme un entier
        // decalerait l'arete d'un bit la ou le jeu ne la decale pas du tout ; et une hauteur
        // NULLE fait tomber l'ouverture juste a la mesure de l'objet, la ou la comparaison du
        // rayon etendu se joue a l'egalite.
        {"rayonNu", 80, 0x81, 0, 0, 0b10000000000, 0, 0},
    };

    /** Les aretes que le niveau nomme, toutes zones confondues. */
    private static final TreeSet<Integer> ARETES = new TreeSet<>();

    private MoveExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();

        List<Object> niveaux = new ArrayList<>();
        int cas = 0;
        for (int lvl : NIVEAUX) {
            Map<String, Object> un = unNiveau(lvl);
            niveaux.add(un);
            cas += ((List<?>) un.get("cases")).size();
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("levels", niveaux);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("move.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[move] %d niveaux, %d deplacements -> %s%n", NIVEAUX.length, cas, f);
    }

    private static Map<String, Object> unNiveau(int lvl) {
        LevelExport.loadLevelForTools(lvl);

        int nz = Mem.uw(ab3d2.bss.LevelBss.Lvl_NumZones_w);
        int zptrs = Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l);
        int edgeBase = Mem.l(ab3d2.bss.LevelBss.Lvl_ZoneEdgePtr_l);

        // Toutes les aretes que ce niveau nomme : ce sont les seules que MoveObject peut armer.
        ARETES.clear();
        for (int z = 0; z < nz; z++) {
            for (int ei : listeAretes(Mem.l(zptrs + z * 4))) {
                if (ei >= 0) {
                    ARETES.add(ei);
                }
            }
        }

        List<Object> cases = new ArrayList<>();
        int combien = Math.min(ZONES, nz);
        int idx = 0;
        for (int i = 0; i < combien; i++) {
            int z = nz <= ZONES ? i : i * nz / ZONES;
            int zn = Mem.l(zptrs + z * 4);
            int[] centre = barycentre(zn, edgeBase);
            if (centre == null) {
                continue;
            }
            List<int[]> milieux = milieux(zn, edgeBase);
            int sol = Mem.l(zn + Defs.ZoneT_Floor_l);
            int toit = Mem.l(zn + Defs.ZoneT_Roof_l);
            int solHaut = Mem.l(zn + Defs.ZoneT_UpperFloor_l);
            int toitHaut = Mem.l(zn + Defs.ZoneT_UpperRoof_l);
            for (int dir = 0; dir < 16; dir++) {
                for (int p = 0; p < PAS.length; p++) {
                    Object[] r = REGLAGES[idx % REGLAGES.length];
                    int montee = MONTEES[(idx / 10) % MONTEES.length];
                    int thing = (Integer) r[7];
                    // Ou l'objet se tient en hauteur. Les deux premieres ancres sont FRANCHES —
                    // la tete pile au plafond, les pieds pile sur le sol — parce que les
                    // comparaisons de hauteur de la routine sont des {@code cmp.l} dont les
                    // bornes sont justement le sol et le plafond des zones voisines, et que
                    // deux zones mitoyennes partagent tres souvent ces hauteurs-la. Sans une
                    // ancre franche, aucune de ces comparaisons ne tombe jamais a egalite, et
                    // le fait qu'elles soient inclusives ou strictes ne se demontre pas.
                    int oldy = switch ((idx / 3) % 8) {
                        case 0 -> sol - thing;
                        case 1 -> toit;
                        case 2 -> sol;
                        case 3 -> toit - thing;
                        case 4 -> (sol + toit) / 2;
                        // L'etage SUPERIEUR, quand la zone en a un : c'est la seule facon
                        // d'atteindre les deux comparaisons de hauteur que la routine ne fait
                        // qu'apres avoir ecarte l'etage bas.
                        case 5 -> solHaut < -1000000 ? sol - thing : solHaut - thing;
                        case 6 -> toitHaut < -1000000 ? toit : toitHaut;
                        default -> sol - thing - 1600;
                    };
                    int d = PAS[p];
                    // Seize directions prises a la main : pas de table de sinus ici, l'oracle
                    // doit pouvoir etre rejoue sans elle.
                    int dx = (int) Math.round(Math.cos(dir * Math.PI / 8) * d);
                    int dz = (int) Math.round(Math.sin(dir * Math.PI / 8) * d);
                    // Un cas sur deux part du barycentre, l'autre d'un point pousse aux trois
                    // quarts vers le milieu d'une arete — toujours dedans, la zone etant
                    // convexe, mais tout contre le mur. C'est la que les egalites se jouent :
                    // un depart au milieu de la piece ne rase jamais rien.
                    int[] dep = centre;
                    if (idx % 2 == 1 && !milieux.isEmpty()) {
                        int[] m = milieux.get((idx / 2) % milieux.size());
                        dep = new int[] {centre[0] + (m[0] - centre[0]) * 3 / 4,
                                         centre[1] + (m[1] - centre[1]) * 3 / 4};
                    }
                    Map<String, Object> cas = new LinkedHashMap<>();
                    cas.put("config", r[0]);
                    cas.put("zone", z);
                    cas.put("oldx", dep[0]);
                    cas.put("oldz", dep[1]);
                    cas.put("oldy", oldy);
                    cas.put("newx", (int) (short) (dep[0] + dx));
                    cas.put("newz", (int) (short) (dep[1] + dz));
                    cas.put("newy", oldy + montee);
                    cas.put("stoodInTop", idx % 2);
                    cas.put("extLen", r[1]);
                    cas.put("awayFromWall", r[2]);
                    cas.put("wallBounce", r[3]);
                    cas.put("exitFirst", r[4]);
                    cas.put("wallFlags", r[5]);
                    cas.put("stepUp", r[6]);
                    cas.put("stepDown", 0x1000000);
                    cas.put("thingHeight", thing);
                    cas.put("after", unCas(cas, zptrs));
                    cases.add(cas);
                    idx++;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", String.valueOf((char) ('a' + lvl)));
        out.put("index", lvl);
        out.put("numZones", nz);
        out.put("cases", cases);
        return out;
    }

    /** Pose les globales, appelle MoveObject, et releve tout ce qu'elle a laisse derriere elle. */
    private static Map<String, Object> unCas(Map<String, Object> cas, int zptrs) {
        int edgeBase = Mem.l(ab3d2.bss.LevelBss.Lvl_ZoneEdgePtr_l);
        int bit = (Integer) cas.get("wallFlags");

        // Les drapeaux d'arete sont CUMULATIFS : le jeu ne les efface qu'une fois par image. On
        // efface le seul bit qu'on va poser, pour que ce qu'on releve soit bien l'oeuvre de CE
        // deplacement et non la somme des precedents.
        for (int ei : ARETES) {
            int a = edgeBase + (ei << 4) + Defs.EdgeT_Flags_w;
            Mem.ww(a, Mem.uw(a) & ~bit);
        }

        Mem.ww(ObjectmoveData.oldx, (Integer) cas.get("oldx"));
        Mem.ww(ObjectmoveData.oldz, (Integer) cas.get("oldz"));
        Mem.wl(ObjectmoveData.oldy, (Integer) cas.get("oldy"));
        Mem.ww(ObjectmoveData.newx, (Integer) cas.get("newx"));
        Mem.ww(ObjectmoveData.newz, (Integer) cas.get("newz"));
        Mem.wl(ObjectmoveData.newy, (Integer) cas.get("newy"));
        Mem.wl(ObjectmoveData.Obj_ZonePtr_l, Mem.l(zptrs + (Integer) cas.get("zone") * 4));
        Mem.wb(ObjectmoveData.StoodInTop, (Integer) cas.get("stoodInTop") != 0 ? 0xFF : 0);
        Mem.ww(ObjectmoveData.Obj_ExtLen_w, (Integer) cas.get("extLen"));
        Mem.wb(ObjectmoveData.Obj_AwayFromWall_b, (Integer) cas.get("awayFromWall"));
        Mem.wb(ObjectmoveData.Obj_WallBounce_b, (Integer) cas.get("wallBounce") != 0 ? 0xFF : 0);
        Mem.wb(ObjectmoveData.exitfirst, (Integer) cas.get("exitFirst") != 0 ? 0xFF : 0);
        Mem.ww(ObjectmoveData.wallflags, bit);
        Mem.wl(ObjectmoveData.StepUpVal, (Integer) cas.get("stepUp"));
        Mem.wl(ObjectmoveData.StepDownVal, (Integer) cas.get("stepDown"));
        Mem.wl(ObjectmoveData.thingheight, (Integer) cas.get("thingHeight"));
        // Les trois mots du mur touche sont des SORTIES, mais le jeu ne les efface pas : on les
        // met a une valeur reconnaissable pour voir si le deplacement les a ecrites ou non.
        Mem.ww(ObjectmoveData.WallXSize_w, 0x5A5A);
        Mem.ww(ObjectmoveData.WallZSize_w, 0x5A5A);
        Mem.ww(ObjectmoveData.WallLength_w, 0x5A5A);
        Mem.wl(ObjectmoveData.wallhitheight, 0);
        // Et le chemin de zones, pour qu'un chemin plus court que le precedent se voie.
        for (int k = 0; k < 100; k++) {
            Mem.ww(ab3d2.bss.TablesBss.Obj_RoomPath_vw + k * 2, 0x7FFF);
        }

        ab3d2.Objectmove.MoveObject();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("newx", Mem.w(ObjectmoveData.newx));
        out.put("newz", Mem.w(ObjectmoveData.newz));
        out.put("newy", Mem.l(ObjectmoveData.newy));
        int zn = Mem.l(ObjectmoveData.Obj_ZonePtr_l);
        out.put("zone", Mem.w(zn + Defs.ZoneT_ID_w));
        out.put("hitwall", Mem.ub(ObjectmoveData.hitwall) != 0 ? 1 : 0);
        out.put("stoodInTop", Mem.ub(ObjectmoveData.StoodInTop) != 0 ? 1 : 0);
        out.put("wallhitheight", Mem.l(ObjectmoveData.wallhitheight));
        out.put("wallXSize", Mem.w(ObjectmoveData.WallXSize_w));
        out.put("wallZSize", Mem.w(ObjectmoveData.WallZSize_w));
        out.put("wallLength", Mem.w(ObjectmoveData.WallLength_w));

        // ERRORINMOVEMENT — la cinquantieme zone traversee — rend la main SANS poser le mot
        // terminateur. On ne peut donc pas lire jusqu au premier negatif et s en tenir la : il
        // faut aussi savoir ou la routine a cesse d ecrire, d ou le temoin pose avant l appel.
        List<Integer> chemin = new ArrayList<>();
        for (int k = 0; k < 100; k++) {
            int v = Mem.w(ab3d2.bss.TablesBss.Obj_RoomPath_vw + k * 2);
            if (v == 0x7FFF) {
                break;                                 // jamais ecrit
            }
            chemin.add(v);
            if (v < 0) {
                break;
            }
        }
        out.put("roomPath", chemin);

        List<Integer> armees = new ArrayList<>();
        for (int ei : ARETES) {
            if ((Mem.uw(edgeBase + (ei << 4) + Defs.EdgeT_Flags_w) & bit) != 0) {
                armees.add(ei);
            }
        }
        out.put("flagged", armees);
        return out;
    }

    /** La liste brute d'aretes d'une zone (indices, -1 fin de groupe, -2 fin). */
    private static List<Integer> listeAretes(int zn) {
        List<Integer> out = new ArrayList<>();
        int elist = zn + (short) Mem.uw(zn + Defs.ZoneT_EdgeListOffset_w);
        for (int i = 0; i < 512; i++) {
            int ei = Mem.w(elist + i * 2);
            out.add(ei);
            if (ei == -2) {
                break;
            }
        }
        return out;
    }

    /** Les milieux des aretes du CONTOUR, dans l'ordre ou la zone les nomme. */
    private static List<int[]> milieux(int zn, int edgeBase) {
        List<int[]> out = new ArrayList<>();
        for (int ei : listeAretes(zn)) {
            if (ei < 0) {
                break;
            }
            int e = edgeBase + (ei << 4);
            out.add(new int[] {
                Mem.w(e + Defs.EdgeT_XPos_w) + Mem.w(e + Defs.EdgeT_XLen_w) / 2,
                Mem.w(e + Defs.EdgeT_ZPos_w) + Mem.w(e + Defs.EdgeT_ZLen_w) / 2});
        }
        return out;
    }

    /**
     * Le barycentre des sommets du CONTOUR (premier groupe). Les zones sont convexes — c'est ce
     * que {@code checkwalls} suppose en traitant chaque arete independamment — donc le
     * barycentre est dedans, et c'est un point de depart legitime.
     */
    private static int[] barycentre(int zn, int edgeBase) {
        long sx = 0;
        long sz = 0;
        int n = 0;
        for (int ei : listeAretes(zn)) {
            if (ei < 0) {
                break;
            }
            sx += Mem.w(edgeBase + (ei << 4) + Defs.EdgeT_XPos_w);
            sz += Mem.w(edgeBase + (ei << 4) + Defs.EdgeT_ZPos_w);
            n++;
        }
        if (n == 0) {
            return null;
        }
        return new int[] {(int) (sx / n), (int) (sz / n)};
    }
}
