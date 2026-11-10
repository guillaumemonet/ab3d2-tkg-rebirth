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
 * Oracle du SOUFFLE : ce qu'une explosion fait a tout ce qui l'entoure.
 *
 * <p>{@code ComputeBlast} (newanims.s:2845) est la seule routine du jeu qui parcoure la table
 * des objets ENTIERE en la triant par {@code ObjT_TypeID_b}. C'est donc le seul endroit d'ou
 * l'on voie ce que le jeu range dans cette table, et ce qu'il fait de chaque famille :
 *
 * <ul>
 *   <li>le type UN — les CHOSES — est ecarte d'emblee : aucune explosion n'a jamais casse un
 *       ordinateur ni allume une lampe ;</li>
 *   <li>le type TROIS — les auxiliaires — aussi ;</li>
 *   <li>le type ZERO (monstre) et les types QUATRE et CINQ (les joueurs) passent par le meme
 *       test de points de vie et recoivent degats et recul dans {@code EntT_ImpactX/Y/Z_w} ;</li>
 *   <li>le type DEUX (projectile) n'est retenu que s'il PESE, et sa poussee entre directement
 *       dans sa vitesse, avec un coup vertical mille fois plus fort.</li>
 * </ul>
 *
 * <p>Et la distance ne se mesure pas comme ailleurs. {@code ai_CalcSqrt} fait trois pas de
 * Newton ; la copie inline de {@code ComputeBlast} multiplie, au DEUXIEME pas, par l'ecart en Z
 * au lieu de carrer son approximation (newanims.s:2952). La meme routine inline une seconde
 * fois vingt lignes plus haut, dans {@code notasplut}, est correcte. On ne devine pas cela : on
 * le releve.
 *
 * <h2>Ce qu'on seme</h2>
 *
 * <p>Les monstres restent ou le fichier de niveau les pose. Le joueur et deux projectiles sont
 * POSES a chaque cas, et l'explosion est allumee depuis un troisieme projectile dont on choisit
 * la position, le secteur, l'etage et la definition de balle. Les origines se prennent autour
 * de chaque monstre, a des ecarts choisis pour border les deux seuils : celui de la portee
 * (au-dela de {@code (64+4)*8} unites plus rien ne porte) et celui du plancher de recul
 * vertical.
 *
 * <p>Les ecarts PUREMENT EN Z sont surrepresentes : c'est la que la racine carree fautive se
 * voit le mieux, puisque c'est l'ecart en Z qu'elle glisse dans son deuxieme pas.
 *
 * <p>Le generateur est remis a une graine connue avant chaque cas, et les flammes semees apres
 * les degats sont relevees avec le reste : elles consomment le hasard, et un rejeu qui ne les
 * poserait pas aux memes endroits ne serait pas le meme jeu.
 */
public final class BlastExport {

    /** Les niveaux releves. */
    private static final int[] NIVEAUX = {0, 2, 6, 12, 13, 14};

    /** Combien de monstres servent d'ancre, au plus. */
    private static final int ANCRES = 6;

    /** La graine remise avant chaque cas. */
    private static final int GRAINE = 234;

    /** Le premier emplacement du pool du joueur dans la table. */
    private static int plrShotSlot;
    /** Le premier emplacement du pool des aliens. */
    private static int aiShotSlot;
    /** L'emplacement de l'entite du joueur. */
    private static int plrSlot;
    /** Combien d'emplacements la table compte. */
    private static int slots;

    /** Les rangs des monstres du niveau, et leurs points de vie d'origine. */
    private static final List<Integer> MONSTRES = new ArrayList<>();
    private static final List<Integer> VIES = new ArrayList<>();

    /**
     * Les ecarts essayes, en unites monde. Les seuils de portee tombent a {@code 32} (ou le pas
     * de degats commence) et a {@code 544} (ou il finit) : on les borde des deux cotes.
     */
    private static final int[][] ECARTS = {
        {0, 0}, {8, 0}, {0, 8}, {32, 0}, {0, 32}, {40, 0}, {0, 40},
        {128, 0}, {0, 128}, {128, 128}, {256, 0}, {0, 256}, {0, -256},
        {400, 0}, {0, 400}, {0, -400}, {280, 280}, {536, 0}, {0, 536},
        {544, 0}, {0, 544}, {552, 0}, {0, 552}, {0, -552}, {700, 0}, {0, 700},
        {0, 1200}, {1200, 0},
    };

    private BlastExport() {
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
        root.put("seed", GRAINE);
        root.put("levels", niveaux);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("blast.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[blast] %d niveaux, %d explosions -> %s%n", NIVEAUX.length, cas, f);
    }

    private static Map<String, Object> unNiveau(int lvl) {
        LevelExport.loadLevelForTools(lvl);
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, ab3d2.Hires.PLR_SINGLE);

        int base = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
        plrSlot = (Mem.l(ab3d2.bss.PlayerBss.Plr1_ObjectPtr_l) - base) / Defs.ObjT_SizeOf_l;
        plrShotSlot = (Mem.l(ab3d2.bss.PlayerBss.Plr_ShotDataPtr_l) - base) / Defs.ObjT_SizeOf_l;
        aiShotSlot = (Mem.l(ab3d2.bss.LevelBss.AI_AlienShotDataPtr_l) - base) / Defs.ObjT_SizeOf_l;
        slots = 0;
        while (Mem.w(base + slots * Defs.ObjT_SizeOf_l) >= 0) {
            slots++;
        }

        // Les monstres, tels que le fichier les pose. Leur rang de POINT est la cle du rejeu :
        // c'est ce que le portage appelle l'index d'un alien.
        List<Integer> monstres = new ArrayList<>();
        List<Object> dessus = new ArrayList<>();
        MONSTRES.clear();
        VIES.clear();
        for (int k = 0; k < slots; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            if (Mem.ub(a + Defs.ObjT_TypeID_b) != 0 || Mem.w(a + Defs.ObjT_ZoneID_w) < 0) {
                continue;
            }
            monstres.add(k);
            MONSTRES.add(k);
            VIES.add(Mem.ub(a + Defs.EntT_HitPoints_b));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("point", Mem.w(a));
            e.put("slot", k);
            e.put("x", pointX(k));
            e.put("z", pointZ(k));
            e.put("height", Mem.w(a + 4));
            e.put("zone", Mem.w(a + Defs.ObjT_ZoneID_w));
            e.put("upper", Mem.ub(a + Defs.ShotT_InUpperZone_b));
            e.put("hitPoints", Mem.ub(a + Defs.EntT_HitPoints_b));
            dessus.add(e);
        }

        // Les definitions de balle : une qui PESE (pour le branche des projectiles) et une qui
        // ne pese pas (pour verifier qu'elle est ecartee).
        int pesante = -1;
        int legere = -1;
        for (int b = 0; b < 20 && (pesante < 0 || legere < 0); b++) {
            int def = Mem.l(ab3d2.HiresData.GLF_DatabasePtr_l) + Defs.GLFT_BulletDefs_l
                    + b * Defs.BulT_SizeOf_l;
            if (Mem.l(def + Defs.BulT_Gravity_l) != 0 && pesante < 0) {
                pesante = b;
            }
            if (Mem.l(def + Defs.BulT_Gravity_l) == 0 && legere < 0) {
                legere = b;
            }
        }

        List<Object> cases = new ArrayList<>();
        int combien = Math.min(ANCRES, monstres.size());
        for (int i = 0; i < combien; i++) {
            int ancre = monstres.get(i);
            int ax = pointX(ancre);
            int az = pointZ(ancre);
            int azone = Mem.w(base + ancre * Defs.ObjT_SizeOf_l + Defs.ObjT_ZoneID_w);
            int ahaut = Mem.ub(base + ancre * Defs.ObjT_SizeOf_l + Defs.ShotT_InUpperZone_b);
            int ah = Mem.w(base + ancre * Defs.ObjT_SizeOf_l + 4);
            for (int e = 0; e < ECARTS.length; e++) {
                int[] d = ECARTS[e];
                // La force tourne sur quelques valeurs : les trois paliers du plancher de
                // recul vertical (en-dessous de seize fois la distance, il est borne) et une
                // force assez grande pour faire deborder la division.
                int force = new int[] {64, 400, 2000, 12000}[e % 4];
                Map<String, Object> cas = new LinkedHashMap<>();
                cas.put("force", force);
                cas.put("anchor", ancre);
                Map<String, Object> ori = scene(ax + d[0], az + d[1], ah, azone, ahaut);
                cas.put("origin", ori);
                cas.put("bulletType", e % 2 == 0 ? pesante : legere);
                // Le joueur est pose de l'autre cote de l'ancre, pour qu'il ne soit pas
                // toujours a la meme distance que la cible.
                Map<String, Object> joueur = scene(ax - d[1] / 2, az - d[0] / 2, ah, azone, ahaut);
                cas.put("player", joueur);
                // Deux projectiles : un qui pese, un qui ne pese pas, chacun decale.
                List<Object> balles = new ArrayList<>();
                balles.add(balle(plrShotSlot, ax + d[1] / 2, az + d[0] / 2, ah, azone, ahaut,
                        pesante, 200 + e * 7));
                balles.add(balle(aiShotSlot, ax - d[0] / 2, az - d[1] / 2, ah, azone, ahaut,
                        legere, 90 - e * 11));
                cas.put("bullets", balles);

                cas.put("seedVel", e);         // de quoi semer des vitesses non nulles
                // Le DEUXIEME souffle, decale : c'est lui qui montre qu'un recul se REMPLACE
                // et non s'ajoute, et que le corps encaisse deux fois de suite.
                Map<String, Object> ori2 = scene(ax + d[1], az - d[0], ah, azone, ahaut);
                cas.put("origin2", ori2);
                cas.put("force2", force / 2 + 7);
                cas.put("after", unCas(base, force, ori, joueur, balles,
                        (Integer) cas.get("bulletType"), e, ori2, force / 2 + 7));
                cases.add(cas);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", String.valueOf((char) ('a' + lvl)));
        out.put("index", lvl);
        out.put("heavyBullet", pesante);
        out.put("lightBullet", legere);
        out.put("aliens", dessus);
        out.put("cases", cases);
        return out;
    }

    /** Une position complete, telle que le rejeu doit la reproduire. */
    private static Map<String, Object> scene(int x, int z, int h, int zone, int upper) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("z", z);
        m.put("height", h);
        m.put("zone", zone);
        m.put("upper", upper);
        return m;
    }

    private static Map<String, Object> balle(int slot, int x, int z, int h, int zone, int upper,
                                             int type, int vel) {
        Map<String, Object> m = scene(x, z, h, zone, upper);
        m.put("slot", slot);
        m.put("type", type);
        m.put("vel", vel);
        return m;
    }

    /** Pose la scene, allume l'explosion, et releve tout ce qu'elle a touche. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> unCas(int base, int force, Map<String, Object> ori,
                                             Map<String, Object> joueur, List<Object> balles,
                                             int bulletType, int graineVit,
                                             Map<String, Object> ori2, int force2) {
        // --- remise a zero de toute la table ---
        for (int k = 0; k < slots; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            Mem.wb(a + Defs.EntT_DamageTaken_b, 0);
            Mem.ww(a + Defs.EntT_ImpactX_w, 0);
            Mem.ww(a + Defs.EntT_ImpactZ_w, 0);
            Mem.ww(a + Defs.EntT_ImpactY_w, 0);
        }
        for (int k = plrShotSlot; k < plrShotSlot + 40; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            Mem.ww(a + Defs.ObjT_ZoneID_w, -1);        // emplacement libre
            Mem.wb(a + Defs.ObjT_TypeID_b, Defs.OBJ_TYPE_PROJECTILE);
            Mem.ww(a + Defs.ShotT_VelocityX_w, 0);
            Mem.ww(a + Defs.ShotT_VelocityZ_w, 0);
            Mem.ww(a + Defs.ShotT_VelocityY_w, 0);
            Mem.ww(a + Defs.ShotT_VelocityX_w + 2, 0);   // la FRACTION d'une vitesse 16.16
            Mem.ww(a + Defs.ShotT_VelocityZ_w + 2, 0);
            Mem.wl(a + Defs.ShotT_AccYPos_w, 0);
            Mem.ww(a + 4, 0);
            Mem.wb(a + Defs.ShotT_Status_b, 0);
            Mem.wb(a + Defs.ShotT_Anim_b, 0);
            Mem.wl(a + Defs.EntT_EnemyFlags_l, 0);
        }

        // --- le joueur, entite de plein droit ---
        int pa = base + plrSlot * Defs.ObjT_SizeOf_l;
        Mem.wb(pa + Defs.ObjT_TypeID_b, Defs.OBJ_TYPE_PLAYER1);
        Mem.wb(pa + Defs.EntT_HitPoints_b, 10);        // move.b #10,EntT_HitPoints_b(a0)
        pose(pa, joueur);

        // --- les deux projectiles semes ---
        for (Object o : balles) {
            Map<String, Object> b = (Map<String, Object>) o;
            int a = base + (Integer) b.get("slot") * Defs.ObjT_SizeOf_l;
            Mem.wb(a + Defs.ObjT_TypeID_b, Defs.OBJ_TYPE_PROJECTILE);
            Mem.wb(a + Defs.ShotT_Size_b, (Integer) b.get("type"));
            pose(a, b);
            // Des vitesses NON NULLES, et dont l'octet bas est garni : sans elles, ajouter et
            // remplacer donneraient le meme resultat, et l'addition en OCTET du souffle
            // passerait pour une addition en mot.
            int v = (Integer) b.get("vel");
            Mem.ww(a + Defs.ShotT_VelocityX_w, v);
            Mem.ww(a + Defs.ShotT_VelocityX_w + 2, 0x4000);
            Mem.ww(a + Defs.ShotT_VelocityZ_w, -v - 13);
            Mem.ww(a + Defs.ShotT_VelocityZ_w + 2, 0x8000);
            Mem.ww(a + Defs.ShotT_VelocityY_w, v * 3 - 900);
        }
        // Un monstre SANS POINTS DE VIE : le souffle doit l'ignorer. Lequel change de cas en
        // cas, pour qu'aucun ne soit toujours celui-la.
        int sansVie = -1;
        for (int k = 0; k < MONSTRES.size(); k++) {
            // Les points de vie se RENDENT a chaque cas : sans cela le niveau se viderait de
            // ses monstres a mesure que l'oracle avance.
            Mem.wb(base + MONSTRES.get(k) * Defs.ObjT_SizeOf_l + Defs.EntT_HitPoints_b,
                    Math.max(1, VIES.get(k)));
        }
        if (!MONSTRES.isEmpty()) {
            sansVie = MONSTRES.get(graineVit % MONSTRES.size());
            Mem.wb(base + sansVie * Defs.ObjT_SizeOf_l + Defs.EntT_HitPoints_b, 0);
        }
        // Et, un cas sur sept, le pool du joueur est presque PLEIN : les flammes doivent alors
        // s'arreter en route, et la recherche d'emplacement libre se voir.
        int bouches = graineVit % 7 == 3 ? 16 : 0;
        for (int k = 0; k < bouches; k++) {
            int a = base + (plrShotSlot + 1 + k) * Defs.ObjT_SizeOf_l;
            Mem.ww(a + Defs.ObjT_ZoneID_w, (Integer) joueur.get("zone"));
            Mem.wb(a + Defs.ShotT_Size_b, bulletType);
            Mem.wb(a + Defs.ShotT_Status_b, 0xFF);
            Mem.wb(a + Defs.ShotT_InUpperZone_b, (Integer) joueur.get("upper"));
            pose(a, joueur);
        }

        // --- la balle qui explose : le dernier emplacement du pool des aliens ---
        int src = aiShotSlot + 19;
        int sa = base + src * Defs.ObjT_SizeOf_l;
        Mem.wb(sa + Defs.ObjT_TypeID_b, Defs.OBJ_TYPE_PROJECTILE);
        Mem.wb(sa + Defs.ShotT_Size_b, bulletType);
        pose(sa, ori);
        Mem.ww(ab3d2.ObjectmoveData.Viewerx, (Integer) ori.get("x"));
        Mem.ww(ab3d2.ObjectmoveData.Viewerz, (Integer) ori.get("z"));
        Mem.ww(ab3d2.ObjectmoveData.Viewery, (Integer) ori.get("height"));
        Mem.wb(ab3d2.ObjectmoveData.ViewerTop, (Integer) ori.get("upper"));

        // BLOODYGREATBOMB : les flammes prennent la definition de balle du DERNIER projectile
        // passe par « notpopping ». Dans le jeu c'est toujours celui qui vient d'exploser —
        // il vole encore quand il touche. L'oracle le pose donc a la main, faute de boucle.
        Mem.wb(ab3d2.NewanimsData.BLOODYGREATBOMB, bulletType);
        // Les reglages de MoveObject sont des GLOBALES. Les flammes heritent de ceux que le
        // projectile a laisses en volant (newanims.s:2005 et suivantes) ; on les repose donc
        // tels quels, sans quoi l'oracle eprouverait une collision que le jeu ne fait jamais.
        Mem.ww(ab3d2.ObjectmoveData.wallflags, 1024);
        Mem.wl(ab3d2.ObjectmoveData.StepUpVal, 0);
        Mem.wl(ab3d2.ObjectmoveData.StepDownVal, 16777216);
        Mem.wl(ab3d2.ObjectmoveData.thingheight, 1280);
        Mem.ww(ab3d2.ObjectmoveData.Rand1, GRAINE);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("deadAlien", sansVie);        // le RANG dans la table
        after.put("busy", bouches);
        preparePlayer(joueur);
        after.put("one", unSouffle(base, force, sa, ori, pa, bulletType));
        after.put("two", unSouffle(base, force2, sa, ori2, pa, bulletType));
        return after;
    }

    /** Un souffle : on l'allume, on releve ce qu'il a touche, puis le corps l'encaisse. */
    private static Map<String, Object> unSouffle(int base, int force, int sa,
                                                 Map<String, Object> ori, int pa,
                                                 int bulletType) {
        pose(sa, ori);
        Mem.wb(sa + Defs.ObjT_TypeID_b, Defs.OBJ_TYPE_PROJECTILE);
        Mem.wb(sa + Defs.ShotT_Size_b, bulletType);
        Mem.ww(ab3d2.ObjectmoveData.Viewerx, (Integer) ori.get("x"));
        Mem.ww(ab3d2.ObjectmoveData.Viewerz, (Integer) ori.get("z"));
        Mem.ww(ab3d2.ObjectmoveData.Viewery, (Integer) ori.get("height"));
        Mem.wb(ab3d2.ObjectmoveData.ViewerTop, (Integer) ori.get("upper"));
        Mem.wb(ab3d2.NewanimsData.BLOODYGREATBOMB, bulletType);
        Mem.ww(ab3d2.ObjectmoveData.wallflags, 1024);
        Mem.wl(ab3d2.ObjectmoveData.StepUpVal, 0);
        Mem.wl(ab3d2.ObjectmoveData.StepDownVal, 16777216);
        Mem.wl(ab3d2.ObjectmoveData.thingheight, 1280);
        ab3d2.Newanims.ComputeBlast(force, sa);

        // --- le releve ---
        Map<String, Object> after = new LinkedHashMap<>();
        List<Object> mons = new ArrayList<>();
        List<Object> choses = new ArrayList<>();
        for (int k = 0; k < slots; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            int t = Mem.ub(a + Defs.ObjT_TypeID_b);
            if (t == 0 && Mem.w(a + Defs.ObjT_ZoneID_w) >= 0) {
                mons.add(touche(Mem.w(a), a));
            } else if (t == 1) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rank", k);
                m.put("damage", Mem.ub(a + Defs.EntT_DamageTaken_b));
                choses.add(m);
            }
        }
        after.put("aliens", mons);
        after.put("things", choses);
        after.put("player", touche(-1, pa));
        List<Object> pool = new ArrayList<>();
        for (int k = plrShotSlot; k < plrShotSlot + 40; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            if (Mem.w(a + Defs.ObjT_ZoneID_w) < 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", k);
            m.put("x", pointX(k));
            m.put("z", pointZ(k));
            m.put("accY", Mem.l(a + Defs.ShotT_AccYPos_w));
            m.put("height", Mem.w(a + 4));
            m.put("zone", Mem.w(a + Defs.ObjT_ZoneID_w));
            m.put("upper", Mem.ub(a + Defs.ShotT_InUpperZone_b));
            m.put("type", Mem.ub(a + Defs.ShotT_Size_b));
            m.put("velX", Mem.w(a + Defs.ShotT_VelocityX_w));
            m.put("velZ", Mem.w(a + Defs.ShotT_VelocityZ_w));
            m.put("velY", Mem.w(a + Defs.ShotT_VelocityY_w));
            m.put("popping", Mem.b(a + Defs.ShotT_Status_b) != 0 ? 1 : 0);
            m.put("anim", Mem.ub(a + Defs.ShotT_Anim_b));
            pool.add(m);
        }
        after.put("pool", pool);
        after.put("rand", Mem.w(ab3d2.ObjectmoveData.Rand1));

        // Et maintenant Plr1_Use, qui VIDE ces champs dans le corps du joueur. Le souffle
        // ecrit, l'image suivante encaisse : sans cette moitie-la, l'oracle dirait seulement
        // que les bons mots sont poses au bon endroit, pas qu'ils servent a quelque chose.
        ab3d2.Hires.Plr1_Use();
        Map<String, Object> corps = new LinkedHashMap<>();
        corps.put("xSpd", Mem.w(ab3d2.bss.PlayerBss.Plr1_SnapXSpdVal_l));
        corps.put("zSpd", Mem.w(ab3d2.bss.PlayerBss.Plr1_SnapZSpdVal_l));
        corps.put("yVel", Mem.l(ab3d2.bss.PlayerBss.Plr1_SnapYVel_l));
        corps.put("angSpd", Mem.w(ab3d2.bss.PlayerBss.Plr1_SnapAngSpd_w));
        corps.put("health", Mem.w(ab3d2.bss.PlayerBss.Plr1_Health_w));
        corps.put("rand", Mem.w(ab3d2.ObjectmoveData.Rand1));
        after.put("body", corps);
        return after;
    }

    /** Ce que Plr1_Use lit du joueur et de son secteur, pose une fois pour toutes. */
    private static void preparePlayer(Map<String, Object> joueur) {
        int zptr = Mem.l(Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l)
                + Math.max(0, (Integer) joueur.get("zone")) * 4);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_ZonePtr_l, zptr);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_StoodInTop_b, (Integer) joueur.get("upper"));
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_TmpAngPos_w, 1024);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_TmpYOff_l, (Integer) joueur.get("height") << 7);
        Mem.wl(ab3d2.bss.PlayerBss.plr1_TmpHeight_l, 0);
        // Un corps qui BOUGE DEJA, fractions comprises : les reculs horizontaux n'entrent
        // que par le mot fort, le vertical par le long entier, et rien ne le dirait d'un
        // corps a l'arret.
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_SnapXSpdVal_l, (300 << 16) | 0x1234);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_SnapZSpdVal_l, (-170 << 16) | 0xFEDC);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_SnapYVel_l, -777);
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_SnapAngSpd_w, 42);
        // Assez de sante pour que le joueur ne MEURE pas : Plr1_Use retirerait son entite de
        // la table, et le portage, lui, n'a pas d'entite a retirer.
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_Health_w, 20000);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_XOff_l, (Integer) joueur.get("x") << 16);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_ZOff_l, (Integer) joueur.get("z") << 16);
        // Le joueur DEUX n'existe pas en solo, mais Plr1_Use le pose quand meme avant de le
        // liberer : il faut donc que son secteur et sa sante soient lisibles.
        Mem.wl(ab3d2.bss.PlayerBss.Plr2_ZonePtr_l, zptr);
        Mem.ww(ab3d2.bss.PlayerBss.Plr2_Health_w, 0);
        Mem.ww(ab3d2.bss.PlayerBss.Plr2_TmpAngPos_w, 0);
        Mem.wl(ab3d2.bss.PlayerBss.Plr2_TmpYOff_l, 0);
        Mem.wl(ab3d2.bss.PlayerBss.plr2_TmpHeight_l, 0);
        Mem.wb(ab3d2.bss.PlayerBss.Plr2_StoodInTop_b, 0);
    }

    private static Map<String, Object> touche(int point, int a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("point", point);
        m.put("damage", Mem.ub(a + Defs.EntT_DamageTaken_b));
        m.put("impactX", Mem.w(a + Defs.EntT_ImpactX_w));
        m.put("impactZ", Mem.w(a + Defs.EntT_ImpactZ_w));
        m.put("impactY", Mem.w(a + Defs.EntT_ImpactY_w));
        return m;
    }

    /** Pose une entite : son point, sa hauteur, son secteur, son etage. */
    private static void pose(int a, Map<String, Object> ou) {
        int pts = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
        int pt = Mem.w(a);
        Mem.ww(pts + pt * 8, (Integer) ou.get("x"));
        Mem.ww(pts + pt * 8 + 4, (Integer) ou.get("z"));
        Mem.ww(a + 4, (Integer) ou.get("height"));
        Mem.ww(a + Defs.ObjT_ZoneID_w, (Integer) ou.get("zone"));
        Mem.wb(a + Defs.ShotT_InUpperZone_b, (Integer) ou.get("upper"));
    }

    private static int pointX(int slot) {
        int base = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
        int pts = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
        return Mem.w(pts + Mem.w(base + slot * Defs.ObjT_SizeOf_l) * 8);
    }

    private static int pointZ(int slot) {
        int base = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
        int pts = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
        return Mem.w(pts + Mem.w(base + slot * Defs.ObjT_SizeOf_l) * 8 + 4);
    }
}
