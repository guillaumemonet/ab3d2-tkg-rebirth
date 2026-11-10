package ab3d2.rebirth.extract;

import ab3d2.Defs;
import ab3d2.Mem;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle du COUP AU BUT : {@code notasplut} (newanims.s:2478), la fin de {@code ItsABullet}.
 *
 * <p>C'est l'endroit ou une balle decide qui elle touche. Le jeu y parcourt la table des
 * objets ENTIERE, d'un seul tenant, et applique a tout ce qu'il retient le meme traitement :
 * les degats s'ajoutent en OCTET, et le recul — {@code EntT_ImpactX_w} et
 * {@code EntT_ImpactZ_w} — est REMPLACE par la vitesse de la balle. Monstres, choses et
 * joueurs passent par la meme ligne.
 *
 * <p>Ce qui s'y decide :
 * <ul>
 *   <li>l'ETAGE : un octet XOR un octet, pas un oui-ou-non ;</li>
 *   <li>le TYPE : {@code btst typeID,EnemyFlags} — une balle du joueur porte {@code %100011}
 *       et ne peut donc pas le toucher lui-meme (type QUATRE), mais bien le joueur DEUX ;</li>
 *   <li>une CHOSE n'est retenue que si sa definition la dit cassable ({@code Behaviour == 2}) ;</li>
 *   <li>la HAUTEUR : cinquante unites d'ecart au plus — mais seulement si la balle a BOUGE.
 *       {@code MOVING} est pose avant {@code MoveObject}, d'apres les positions VOULUES ; une
 *       balle arretee net par un mur a donc bouge pour ce test-la et pas pour la geometrie ;</li>
 *   <li>la geometrie : distance perpendiculaire au segment parcouru, quatre-vingts unites pour
 *       un monstre ou une chose, QUARANTE au-dela — le joueur est du nombre — et les deux
 *       extremites dans un rayon de {@code (longueur + 80)} ;</li>
 *   <li>l'ORDRE : la premiere entite retenue dans l'ordre de la TABLE arrete tout. Le corps du
 *       joueur y a son rang comme les autres.</li>
 * </ul>
 *
 * <h2>Ce qu'on pose</h2>
 *
 * <p>Les cibles restent ou le fichier de niveau les pose : monstres et choses cassables, avec
 * leurs points de vie rendus a chaque cas. Le corps du joueur, lui, est POSE — tantot loin,
 * tantot pile sur la cible, pour que l'ordre de la table se voie.
 *
 * <p>Une seule balle vole par cas, depuis le pool du JOUEUR. On la lance sur la cible avec un
 * ecart lateral qui borde les deux seuils (79, 80, 81 unites), une hauteur qui borde le
 * troisieme (49, 50, 51), et une vitesse qui tantot s'arrete avant la cible, tantot la
 * depasse d'un bond — c'est ce dernier cas qui montre que le test porte sur le SEGMENT et non
 * sur le point d'arrivee.
 */
public final class HitExport {

    /** Les niveaux releves. */
    private static final int[] NIVEAUX = {0, 2, 6, 12, 13, 14};

    /** Combien de cibles servent d'ancre, au plus. */
    private static final int ANCRES = 8;

    /** La graine remise avant chaque cas. */
    private static final int GRAINE = 234;

    /** Les ecarts lateraux essayes, en unites monde : les seuils sont 40 et 80. */
    private static final int[] COTES = {0, 20, 39, 40, 41, 60, 79, 80, 81, 120};

    /** De combien la balle part en arriere de la cible, et a quelle vitesse (mot fort du 16.16). */
    private static final int[][] ELANS = {
        {64, 48}, {200, 150}, {200, 600}, {700, 400}, {64, 1200}, {0, 0}, {40, 1},
    };

    /** Les ecarts de hauteur essayes : le seuil est 50. */
    private static final int[] HAUTS = {0, 49, 50, 51, 200};

    /**
     * Les drapeaux d'ennemis essayes. {@code %100011} est celui d'une balle du joueur UN,
     * {@code %110010} celui d'une balle d'alien (qui, elle, touche le joueur UN).
     *
     * <p>Le bit DEUX n'y est jamais, et c'est volontaire. Le type deux est le PROJECTILE : une
     * balle qui le porterait se blesserait elle-meme, puisqu'elle est dans la table comme les
     * autres et que ses « points de vie » sont alors l'octet fort de sa vitesse
     * ({@code EntT_HitPoints_b} et {@code ShotT_VelocityX_w} partagent l'offset dix-huit). Elle
     * s'ecrit alors son propre recul par-dessus sa vitesse verticale et le mot fort de sa
     * hauteur ({@code EntT_ImpactX_w} vaut quarante-deux, {@code ShotT_VelocityY_w} aussi). Le
     * jeu ne pose jamais ce bit ; l'essayer ne mesurerait qu'une ruine.
     */
    private static final int[] DRAPEAUX = {0b100011, 0b110010, 0b100001, 0b100010,
        0b010011, 0};

    /** Les quatre directions cardinales : (dx, dz) et la perpendiculaire (px, pz). */
    private static final int[][] CAPS = {
        {1, 0, 0, 1}, {0, 1, 1, 0}, {-1, 0, 0, -1}, {0, -1, -1, 0},
    };

    private static int base;
    private static int pts;
    private static int plrSlot;
    private static int plrShotSlot;
    private static int aiShotSlot;
    private static int slots;

    /**
     * Le tirage qui decide des reglages d'un cas.
     *
     * <p>PIEGE EVITE : les prendre sur l'indice de boucle les verrouille les uns aux autres.
     * La boucle interieure a cinquante pas ; un reglage pris sur cinq valeurs devient alors
     * une fonction de l'elan, et les drapeaux d'ennemi comme la hauteur ne varient plus qu'avec
     * lui. Le corps du joueur n'etait jamais pose sur la cible QUE lorsque les drapeaux
     * l'excluaient, et pas un seul des deux mille quatre cents coups ne l'a touche.
     */
    private static int alea;

    private static int tire(int k) {
        alea = alea * 1103515245 + 12345;
        return ((alea >>> 16) & 0x7FFF) % k;
    }

    /** Les rangs des cibles du niveau (monstres et choses) et leurs points de vie d'origine. */
    private static final List<Integer> CIBLES = new ArrayList<>();
    private static final List<Integer> VIES = new ArrayList<>();

    private HitExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();

        lesSeize();

        List<Object> niveaux = new ArrayList<>();
        int cas = 0;
        for (int lvl : NIVEAUX) {
            Map<String, Object> un = unNiveau(lvl);
            niveaux.add(un);
            cas += ((List<?>) un.get("cases")).size();
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("seed", GRAINE);
        root.put("levels", niveaux);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("hit.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[hit] %d niveaux, %d coups -> %s%n", NIVEAUX.length, cas, f);
    }

    /**
     * Ce que les SEIZE niveaux portent, avant toute mise en scene : combien de choses
     * cassables, et quelles valeurs prend l'octet d'etage d'une entite.
     *
     * <p>Les deux comptent pour ce que l'oracle peut separer. Le jeu compare l'etage par OU
     * EXCLUSIF d'octets, et non par oui-ou-non : la difference ne se verrait que sur un octet
     * qui ne vaudrait ni zero ni deux cent cinquante-cinq.
     */
    private static void lesSeize() {
        int cassables = 0;
        int etranges = 0;
        for (int lvl = 0; lvl < 16; lvl++) {
            LevelExport.loadLevelForTools(lvl);
            base = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
            int n = 0;
            while (Mem.w(base + n * Defs.ObjT_SizeOf_l) >= 0) {
                n++;
            }
            int c = 0;
            for (int k = 0; k < n; k++) {
                int a = base + k * Defs.ObjT_SizeOf_l;
                int e = Mem.ub(a + Defs.ShotT_InUpperZone_b);
                if (e != 0 && e != 255) {
                    etranges++;
                    System.out.printf("[hit]   %c:%d octet d'etage %d, type %d, cassable %b%n",
                            (char) ('a' + lvl), k, e, Mem.ub(a + Defs.ObjT_TypeID_b),
                            Mem.ub(a + Defs.ObjT_TypeID_b) == 1 && cassable(k));
                }
                if (Mem.ub(a + Defs.ObjT_TypeID_b) == 1 && Mem.w(a + Defs.ObjT_ZoneID_w) >= 0
                        && cassable(k)) {
                    c++;
                }
            }
            cassables += c;
            System.out.printf("[hit] %c : %d entites, %d choses cassables%n",
                    (char) ('a' + lvl), n, c);
        }
        System.out.printf("[hit] SEIZE niveaux : %d choses cassables, %d octets d'etage "
                + "autres que zero ou deux cent cinquante-cinq%n", cassables, etranges);
    }

    private static int pointX(int slot) {
        return Mem.w(pts + Mem.w(base + slot * Defs.ObjT_SizeOf_l) * 8);
    }

    private static int pointZ(int slot) {
        return Mem.w(pts + Mem.w(base + slot * Defs.ObjT_SizeOf_l) * 8 + 4);
    }

    /** Une chose est-elle CASSABLE ? C'est la seule que la balle retienne (newanims.s:2563). */
    private static boolean cassable(int slot) {
        int a = base + slot * Defs.ObjT_SizeOf_l;
        int defs = Mem.l(ab3d2.HiresData.GLF_DatabasePtr_l) + Defs.GLFT_ObjectDefs;
        int d = Mem.ub(a + Defs.EntT_Type_b) * Defs.ODefT_SizeOf_l;
        return Mem.w(defs + d + Defs.ODefT_Behaviour_w) == 2;
    }

    private static Map<String, Object> unNiveau(int lvl) {
        LevelExport.loadLevelForTools(lvl);
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, ab3d2.Hires.PLR_SINGLE);

        base = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
        pts = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
        plrSlot = (Mem.l(ab3d2.bss.PlayerBss.Plr1_ObjectPtr_l) - base) / Defs.ObjT_SizeOf_l;
        plrShotSlot = (Mem.l(ab3d2.bss.PlayerBss.Plr_ShotDataPtr_l) - base) / Defs.ObjT_SizeOf_l;
        aiShotSlot = (Mem.l(ab3d2.bss.LevelBss.AI_AlienShotDataPtr_l) - base) / Defs.ObjT_SizeOf_l;
        slots = 0;
        while (Mem.w(base + slots * Defs.ObjT_SizeOf_l) >= 0) {
            slots++;
        }

        // Les cibles telles que le fichier les pose : les monstres (type ZERO) et les choses
        // CASSABLES (type UN dont la definition dit deux). Ce sont les seules que le portage
        // garde dans sa liste ; les autres, le jeu les ecarte au meme endroit.
        CIBLES.clear();
        VIES.clear();
        List<Object> dessus = new ArrayList<>();
        for (int k = 0; k < slots; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            int type = Mem.ub(a + Defs.ObjT_TypeID_b);
            if (Mem.w(a + Defs.ObjT_ZoneID_w) < 0) {
                continue;
            }
            if (type != 0 && !(type == 1 && cassable(k))) {
                continue;
            }
            CIBLES.add(k);
            VIES.add(Mem.ub(a + Defs.EntT_HitPoints_b));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("slot", k);
            e.put("point", Mem.w(a));
            e.put("typeId", type);
            e.put("x", pointX(k));
            e.put("z", pointZ(k));
            e.put("height", Mem.w(a + 4));
            e.put("zone", Mem.w(a + Defs.ObjT_ZoneID_w));
            e.put("upper", Mem.ub(a + Defs.ShotT_InUpperZone_b));
            e.put("hitPoints", Mem.ub(a + Defs.EntT_HitPoints_b));
            dessus.add(e);
        }

        // Une definition de balle SOBRE : rien qui pese, rien qui rebondisse, rien qui explose.
        // Le souffle a son oracle a lui ; ici on ne veut voir que le coup au but.
        int sobre = -1;
        for (int b = 0; b < 32; b++) {
            int def = Mem.l(ab3d2.HiresData.GLF_DatabasePtr_l) + Defs.GLFT_BulletDefs_l
                    + b * Defs.BulT_SizeOf_l;
            if (Mem.l(def + Defs.BulT_Gravity_l) == 0
                    && Mem.l(def + Defs.BulT_BounceHoriz_l) == 0
                    && Mem.l(def + Defs.BulT_BounceVert_l) == 0
                    && Mem.l(def + Defs.BulT_ExplosiveForce_l) == 0
                    && Mem.l(def + Defs.BulT_IsHitScan_l) == 0) {
                sobre = b;
                break;
            }
        }
        System.out.printf("[hit] %c : %d cibles, balle sobre %d, joueur au rang %d%n",
                (char) ('a' + lvl), CIBLES.size(), sobre, plrSlot);

        // Les ancres : TOUTES les choses cassables d'abord, puis des monstres etales dans la
        // table. Prises autrement, pas une seule n'etait une chose — les niveaux commencent par
        // leurs monstres — et toute la branche des choses ne se voyait que par accident, quand
        // l'une d'elles se trouvait sur le chemin d'une balle visant un monstre.
        List<Integer> ancres = new ArrayList<>();
        List<Integer> monstres2 = new ArrayList<>();
        for (int k : CIBLES) {
            if (Mem.ub(base + k * Defs.ObjT_SizeOf_l + Defs.ObjT_TypeID_b) == 1) {
                ancres.add(k);
            } else {
                monstres2.add(k);
            }
        }
        int reste = Math.max(0, ANCRES - ancres.size());
        for (int i = 0; i < Math.min(reste, monstres2.size()); i++) {
            ancres.add(monstres2.get(i * monstres2.size() / Math.min(reste, monstres2.size())));
        }

        List<Object> cases = new ArrayList<>();
        int combien = Math.min(ANCRES, ancres.size());
        alea = 987654321;
        int idx = 0;
        for (int i = 0; i < combien; i++) {
            int ancre = ancres.get(i);
            int ax = pointX(ancre);
            int az = pointZ(ancre);
            int aa = base + ancre * Defs.ObjT_SizeOf_l;
            int azone = Mem.w(aa + Defs.ObjT_ZoneID_w);
            int ahaut = Mem.ub(aa + Defs.ShotT_InUpperZone_b);
            int ah = Mem.w(aa + 4);
            int[] cap = CAPS[i % CAPS.length];
            for (int c = 0; c < COTES.length; c++) {
                for (int e = 0; e < ELANS.length; e++) {
                    int cote = COTES[c] * (tire(2) == 0 ? 1 : -1);
                    int recul = ELANS[e][0];
                    int vit = ELANS[e][1];
                    Map<String, Object> cas = new LinkedHashMap<>();
                    cas.put("anchor", ancre);
                    cas.put("bulletType", sobre);
                    cas.put("x", ax - cap[0] * recul + cap[2] * cote);
                    cas.put("z", az - cap[1] * recul + cap[3] * cote);
                    cas.put("velX", cap[0] * vit);
                    cas.put("velZ", cap[1] * vit);
                    cas.put("velY", 0);
                    cas.put("height", ah + HAUTS[tire(HAUTS.length)] * (tire(2) == 0 ? 1 : -1));
                    cas.put("zone", azone);
                    // Un cas sur cinq, la balle est a l'AUTRE etage : rien ne doit etre
                    // touche, ni les cibles ni le corps du joueur.
                    cas.put("upper", tire(5) == 2 ? 255 - ahaut : ahaut);
                    cas.put("power", 1 + tire(7) * 37);
                    cas.put("enemyFlags", DRAPEAUX[tire(DRAPEAUX.length)]);
                    cas.put("lifetime", tire(13) == 7 ? 30000 : 0);
                    cas.put("frames", 1 + tire(3));
                    // Le corps du joueur : tantot pile sur l'ancre — il a alors son rang dans
                    // la table comme les autres, et c'est le PREMIER retenu qui arrete tout —
                    // tantot a mille lieues.
                    boolean surAncre = tire(2) == 0;
                    cas.put("playerX", surAncre ? ax : ax + 8000);
                    cas.put("playerZ", surAncre ? az : az + 8000);
                    cas.put("playerHeight", ah);
                    cas.put("playerZone", azone);
                    cas.put("playerUpper", ahaut);
                    // Une cible sans points de vie, qui change d'un cas a l'autre.
                    // La cible sans points de vie : une fois sur trois c'est l'ANCRE
                    // elle-meme, sans quoi la mort ne tomberait presque jamais sur celle que
                    // la balle rencontre, et le filtre ne prouverait rien.
                    cas.put("deadTarget", CIBLES.isEmpty() ? -1
                            : (tire(3) == 0 ? ancre : CIBLES.get(tire(CIBLES.size()))));
                    // Un SECOND coup, d'une autre direction et d'une autre force, sans
                    // rien effacer entre les deux. Il en faut deux : les degats s'AJOUTENT et
                    // le recul se REMPLACE, et un coup unique ne saurait pas les distinguer.
                    int[] cap2 = CAPS[(i + 1) % CAPS.length];
                    int vit2 = ELANS[(e + 2) % ELANS.length][1];
                    int recul2 = ELANS[(e + 2) % ELANS.length][0];
                    // Une fois sur deux le second coup est POSE sur l'ancre, immobile : il la
                    // touche alors a coup sur, et c'est le seul moyen qu'une meme cible encaisse
                    // DEUX fois. Sans cela les degats qui debordent l'octet et le recul qui
                    // remplace ne se separent pas de ceux qui s'ajoutent.
                    boolean colle = tire(2) == 0;
                    cas.put("x2", colle ? ax : ax - cap2[0] * recul2 + cap2[2] * (cote / 2));
                    cas.put("z2", colle ? az : az - cap2[1] * recul2 + cap2[3] * (cote / 2));
                    cas.put("velX2", colle ? 0 : cap2[0] * vit2);
                    cas.put("velZ2", colle ? 0 : cap2[1] * vit2);
                    // Assez fort pour que deux coups sur la meme cible DEBORDENT l'octet
                    // des degats : c'est le seul moyen de voir qu'ils s'y accumulent.
                    cas.put("power2", 100 + tire(7) * 22);
                    cas.put("after", unCoup(cas));
                    cases.add(cas);
                    idx++;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", String.valueOf((char) ('a' + lvl)));
        out.put("index", lvl);
        out.put("soberBullet", sobre);
        out.put("playerSlot", plrSlot);
        out.put("targets", dessus);
        out.put("cases", cases);
        return out;
    }

    /** Pose la scene, fait voler la balle UNE image, et releve tout ce qu'elle a touche. */
    private static Map<String, Object> unCoup(Map<String, Object> cas) {
        // --- la table remise a plat ---
        for (int k = 0; k < slots; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            Mem.wb(a + Defs.EntT_DamageTaken_b, 0);
            Mem.ww(a + Defs.EntT_ImpactX_w, 0);
            Mem.ww(a + Defs.EntT_ImpactZ_w, 0);
            Mem.ww(a + Defs.EntT_ImpactY_w, 0);
        }
        // --- les deux pools vides : une SEULE balle vole ---
        for (int k = plrShotSlot; k < plrShotSlot + 40; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            Mem.ww(a + Defs.ObjT_ZoneID_w, -1);
            Mem.wb(a + Defs.ObjT_TypeID_b, Defs.OBJ_TYPE_PROJECTILE);
            Mem.wl(a + Defs.ShotT_VelocityX_w, 0);
            Mem.wl(a + Defs.ShotT_VelocityZ_w, 0);
            Mem.ww(a + Defs.ShotT_VelocityY_w, 0);
            Mem.wl(a + Defs.ShotT_AccYPos_w, 0);
            Mem.ww(a + 4, 0);
            Mem.wb(a + Defs.ShotT_Status_b, 0);
            Mem.wb(a + Defs.ShotT_Anim_b, 0);
            Mem.ww(a + Defs.ShotT_Lifetime_w, 0);
            Mem.ww(a + Defs.ShotT_Gravity_w, 0);
            Mem.ww(a + Defs.ShotT_Flags_w, 0);
            Mem.wl(a + Defs.EntT_EnemyFlags_l, 0);
        }
        // --- les points de vie rendus, sauf a une cible ---
        for (int k = 0; k < CIBLES.size(); k++) {
            Mem.wb(base + CIBLES.get(k) * Defs.ObjT_SizeOf_l + Defs.EntT_HitPoints_b,
                    Math.max(1, VIES.get(k)));
        }
        int mort = (Integer) cas.get("deadTarget");
        if (mort >= 0) {
            Mem.wb(base + mort * Defs.ObjT_SizeOf_l + Defs.EntT_HitPoints_b, 0);
        }

        // --- le corps du joueur, entite de plein droit ---
        int pa = base + plrSlot * Defs.ObjT_SizeOf_l;
        Mem.wb(pa + Defs.ObjT_TypeID_b, Defs.OBJ_TYPE_PLAYER1);
        Mem.wb(pa + Defs.EntT_HitPoints_b, 10);        // move.b #10,EntT_HitPoints_b(a0)
        Mem.ww(pa + Defs.ObjT_ZoneID_w, (Integer) cas.get("playerZone"));
        Mem.wb(pa + Defs.ShotT_InUpperZone_b, (Integer) cas.get("playerUpper"));
        Mem.ww(pa + 4, (Integer) cas.get("playerHeight"));
        int ppt = Mem.w(pa);
        Mem.wl(pts + ppt * 8, (Integer) cas.get("playerX") << 16);
        Mem.wl(pts + ppt * 8 + 4, (Integer) cas.get("playerZ") << 16);

        // --- la balle ---
        int a0 = base + plrShotSlot * Defs.ObjT_SizeOf_l;
        int bpt = Mem.w(a0);
        Mem.wb(a0 + Defs.ObjT_TypeID_b, Defs.OBJ_TYPE_PROJECTILE);
        Mem.ww(a0 + Defs.ObjT_ZoneID_w, (Integer) cas.get("zone"));
        Mem.wb(a0 + Defs.ShotT_InUpperZone_b, (Integer) cas.get("upper"));
        Mem.wb(a0 + Defs.ShotT_Size_b, (Integer) cas.get("bulletType"));
        Mem.wb(a0 + Defs.ShotT_Status_b, 0);
        Mem.wb(a0 + Defs.ShotT_Anim_b, 0);
        Mem.wb(a0 + Defs.ShotT_Worry_b, 0xFF);
        // Un OCTET, et le PREMIER du mot : le jeu ecrit « move.b BulT_HitDamage_l+3,ShotT_Power_w »
        // et relit « move.b ShotT_Power_w(a0),d6 ». Le reste du mot ne sert a personne.
        Mem.wb(a0 + Defs.ShotT_Power_w, (Integer) cas.get("power"));
        Mem.wl(a0 + Defs.EntT_EnemyFlags_l, (Integer) cas.get("enemyFlags"));
        Mem.ww(a0 + Defs.ShotT_Lifetime_w, (Integer) cas.get("lifetime"));
        Mem.ww(a0 + Defs.ShotT_Gravity_w, 0);
        Mem.ww(a0 + Defs.ShotT_Flags_w, 0);
        Mem.wl(a0 + Defs.ShotT_VelocityX_w, (Integer) cas.get("velX") << 16);
        Mem.wl(a0 + Defs.ShotT_VelocityZ_w, (Integer) cas.get("velZ") << 16);
        Mem.ww(a0 + Defs.ShotT_VelocityY_w, (Integer) cas.get("velY"));
        int h = (Integer) cas.get("height");
        Mem.wl(a0 + Defs.ShotT_AccYPos_w, h << 7);
        Mem.ww(a0 + 4, h);
        Mem.wl(pts + bpt * 8, (Integer) cas.get("x") << 16);
        Mem.wl(pts + bpt * 8 + 4, (Integer) cas.get("z") << 16);

        Mem.ww(ab3d2.bss.AnimBss.Anim_TempFrames_w, (Integer) cas.get("frames"));
        Mem.ww(ab3d2.ObjectmoveData.Rand1, GRAINE);

        ab3d2.Newanims.ItsABullet(a0);
        Map<String, Object> premiere = balle(a0, bpt);
        // Ce que le PREMIER coup, a lui seul, a touche. Sans ce releve, un second coup pose
        // sur l'ancre masque tout ce que le premier decide : le rang ou la table s'arrete, et
        // la tolerance de chaque famille.
        Map<String, Object> touche1 = new LinkedHashMap<>();
        int blesse = -1;
        for (int k : CIBLES) {
            if (Mem.ub(base + k * Defs.ObjT_SizeOf_l + Defs.EntT_DamageTaken_b) != 0) {
                blesse = k;
                break;
            }
        }
        touche1.put("slot", blesse);
        touche1.put("damage", blesse < 0 ? 0
                : Mem.ub(base + blesse * Defs.ObjT_SizeOf_l + Defs.EntT_DamageTaken_b));
        touche1.put("playerDamage", Mem.ub(pa + Defs.EntT_DamageTaken_b));
        touche1.put("playerImpactX", Mem.w(pa + Defs.EntT_ImpactX_w));
        touche1.put("playerImpactZ", Mem.w(pa + Defs.EntT_ImpactZ_w));

        // --- le SECOND coup : meme balle, autre cap, autre force, rien d'efface ---
        Mem.ww(a0 + Defs.ObjT_ZoneID_w, (Integer) cas.get("zone"));
        Mem.wb(a0 + Defs.ShotT_InUpperZone_b, (Integer) cas.get("upper"));
        Mem.wb(a0 + Defs.ShotT_Status_b, 0);
        Mem.wb(a0 + Defs.ShotT_Anim_b, 0);
        Mem.wb(a0 + Defs.ShotT_Power_w, (Integer) cas.get("power2"));
        Mem.ww(a0 + Defs.ShotT_Lifetime_w, (Integer) cas.get("lifetime"));
        Mem.wl(a0 + Defs.ShotT_VelocityX_w, (Integer) cas.get("velX2") << 16);
        Mem.wl(a0 + Defs.ShotT_VelocityZ_w, (Integer) cas.get("velZ2") << 16);
        Mem.ww(a0 + Defs.ShotT_VelocityY_w, 0);
        Mem.wl(a0 + Defs.ShotT_AccYPos_w, h << 7);
        Mem.ww(a0 + 4, h);
        Mem.wl(pts + bpt * 8, (Integer) cas.get("x2") << 16);
        Mem.wl(pts + bpt * 8 + 4, (Integer) cas.get("z2") << 16);

        ab3d2.Newanims.ItsABullet(a0);

        // --- ce qu'il en reste ---
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("bullet1", premiere);
        after.put("hit1", touche1);
        List<Object> cibles = new ArrayList<>();
        for (int k : CIBLES) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", k);
            m.put("damage", Mem.ub(a + Defs.EntT_DamageTaken_b));
            m.put("impactX", Mem.w(a + Defs.EntT_ImpactX_w));
            m.put("impactZ", Mem.w(a + Defs.EntT_ImpactZ_w));
            m.put("hitPoints", Mem.ub(a + Defs.EntT_HitPoints_b));
            cibles.add(m);
        }
        after.put("targets", cibles);

        Map<String, Object> joueur = new LinkedHashMap<>();
        joueur.put("damage", Mem.ub(pa + Defs.EntT_DamageTaken_b));
        joueur.put("impactX", Mem.w(pa + Defs.EntT_ImpactX_w));
        joueur.put("impactZ", Mem.w(pa + Defs.EntT_ImpactZ_w));
        after.put("player", joueur);

        after.put("bullet", balle(a0, bpt));
        return after;
    }

    /** Tout ce qu'une balle porte apres son image de vol. */
    private static Map<String, Object> balle(int a0, int bpt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zone", Mem.w(a0 + Defs.ObjT_ZoneID_w));
        m.put("x", Mem.l(pts + bpt * 8));
        m.put("z", Mem.l(pts + bpt * 8 + 4));
        m.put("velX", Mem.l(a0 + Defs.ShotT_VelocityX_w));
        m.put("velZ", Mem.l(a0 + Defs.ShotT_VelocityZ_w));
        m.put("velY", Mem.w(a0 + Defs.ShotT_VelocityY_w));
        m.put("accY", Mem.l(a0 + Defs.ShotT_AccYPos_w));
        m.put("height", Mem.w(a0 + 4));
        m.put("status", Mem.ub(a0 + Defs.ShotT_Status_b));
        m.put("anim", Mem.ub(a0 + Defs.ShotT_Anim_b));
        m.put("lifetime", Mem.w(a0 + Defs.ShotT_Lifetime_w));
        return m;
    }
}
