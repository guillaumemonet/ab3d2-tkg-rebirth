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
 * Oracle de la GERBE DE MORCEAUX : {@code Anim_ExplodeIntoBits} (newanims.s:605).
 *
 * <p>Ce qu'une entite laisse en mourant. Elle est le seul endroit du jeu qui RELISE
 * {@code EntT_ImpactX_w} d'un monstre : le dernier recul encaisse, celui qui l'a tue, repart
 * de moitie dans chaque morceau. Un alien abattu de face ne s'eparpille donc pas comme un
 * alien abattu de dos.
 */
public final class BitsExport {

    private static final int[] NIVEAUX = {0, 2, 6, 12, 13, 14};

    /** Les valeurs de d2 : le nombre de morceaux moins un, plafonne a sept. */
    private static final int[] COMPTES = {0, 1, 2, 3, 7, 8, 31};

    /** Quels emplacements du pool des aliens sont DEJA pris, en binaire. */
    private static final int[] OCCUPES = {
        0x00000, 0x0001F, 0x55555, 0xAAAAA, 0xFFFFE, 0xFFFFF, 0x3E000, 0x3FFFF,
    };

    /** Le recul du mort : {@code EntT_ImpactX_w} et {@code EntT_ImpactZ_w}. */
    private static final int[][] RECULS = {
        {0, 0}, {1, 1}, {-1, -1}, {3, -5}, {255, -255},
        {4096, -4096}, {-32768, 32767}, {12345, -6789},
    };

    /** Les hauteurs du mort, bornes du mot comprises. */
    private static final int[] HAUTEURS = {0, 1280, -500, 32767, -32768, 20000};

    private BitsExport() {
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
        Path f = outRoot.resolve("bits.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[bits] %d niveaux, %d gerbes -> %s%n", NIVEAUX.length, cas, f);
    }

    private static int base;
    private static int pts;
    private static int aiShotSlot;
    private static int plrSlot;
    /** Le secteur des emplacements DEJA pris : toujours reel, meme quand le mort n en a pas. */
    private static int zoneOccupee;

    private static Map<String, Object> unNiveau(int lvl) {
        LevelExport.loadLevelForTools(lvl);
        base = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
        pts = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
        plrSlot = (Mem.l(ab3d2.bss.PlayerBss.Plr1_ObjectPtr_l) - base) / Defs.ObjT_SizeOf_l;
        aiShotSlot = (Mem.l(ab3d2.bss.LevelBss.AI_AlienShotDataPtr_l) - base) / Defs.ObjT_SizeOf_l;

        // Le rang de POINT de chaque emplacement du pool : c est lui qui dit ou la routine
        // ecrit, et c est lui que le 16(a2) deborde.
        List<Integer> rangs = new ArrayList<>();
        for (int k = 0; k < Defs.NUM_ALIEN_SHOT_DATA; k++) {
            rangs.add(Mem.w(base + (aiShotSlot + k) * Defs.ObjT_SizeOf_l));
        }
        // Le pool arrive du FICHIER DE NIVEAU avec ObjT_TypeID_b deja a deux : c est pour
        // cela que le « move.b #2,16(a2) » manque a personne.
        System.out.printf("[bits] %c : premier emplacement %d, joueur %d, premier point %d%n",
                (char) (97 + lvl), aiShotSlot, plrSlot, rangs.get(0));

        // Un secteur et une position reels, pour que la scene ne soit pas de pure fantaisie.
        int zone = 0;
        int ax = 0;
        int az = 0;
        int slots = 0;
        while (Mem.w(base + slots * Defs.ObjT_SizeOf_l) >= 0) {
            slots++;
        }
        for (int k = 0; k < slots; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            if (Mem.ub(a + Defs.ObjT_TypeID_b) == 0 && Mem.w(a + Defs.ObjT_ZoneID_w) >= 0) {
                zone = Mem.w(a + Defs.ObjT_ZoneID_w);
                ax = Mem.w(pts + Mem.w(a) * 8);
                az = Mem.w(pts + Mem.w(a) * 8 + 4);
                break;
            }
        }

        zoneOccupee = zone;
        List<Object> cases = new ArrayList<>();
        int idx = 0;
        for (int c = 0; c < COMPTES.length; c++) {
            for (int o = 0; o < OCCUPES.length; o++) {
                for (int r = 0; r < RECULS.length; r++) {
                    Map<String, Object> cas = new LinkedHashMap<>();
                    cas.put("count", COMPTES[c]);
                    cas.put("busy", OCCUPES[o]);
                    cas.put("impactX", RECULS[r][0]);
                    cas.put("impactZ", RECULS[r][1]);
                    cas.put("height", HAUTEURS[idx % HAUTEURS.length]);
                    cas.put("splat", idx % 8);
                    cas.put("radius", 31 - (idx % 64));
                    cas.put("newx", ax + (idx % 17) * 64 - 512);
                    cas.put("newz", az - (idx % 13) * 96 + 300);
                    cas.put("busyZone", zone);
                    // Un cas sur vingt-trois, le mort n a PAS de secteur. Le jeu ne le fait
                    // jamais — l aiguillage des objets ecarte une entite sans secteur avant
                    // meme d arriver a sa mort — mais c est ce qui dit que la routine POSE un
                    // secteur negatif au lieu de prendre l emplacement.
                    cas.put("zone", idx % 23 == 11 ? -1 : zone);
                    cas.put("upper", idx % 3 == 0 ? 255 : 0);
                    cas.put("seed", 234 + idx * 7);
                    cas.put("after", uneGerbe(cas));
                    cases.add(cas);
                    idx++;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", String.valueOf((char) (97 + lvl)));
        out.put("index", lvl);
        out.put("firstSlot", aiShotSlot);
        out.put("points", rangs);
        out.put("cases", cases);
        return out;
    }

    /** L etat STALE d un emplacement libre : rien n y est a zero, pour que l oubli se voie. */
    private static void salir(int k, boolean busy, Map<String, Object> cas) {
        int a = base + (aiShotSlot + k) * Defs.ObjT_SizeOf_l;
        int pt = Mem.w(a);
        Mem.ww(a + Defs.ObjT_ZoneID_w, busy ? zoneOccupee : -1);
        Mem.wb(a + Defs.ObjT_TypeID_b, 3);                  // OBJ_TYPE_AUX : jamais remis a 2
        Mem.ww(a + Defs.ShotT_VelocityX_w, 700 + k * 13);
        Mem.ww(a + Defs.ShotT_VelocityX_w + 2, 0x1234 + k * 0x111);
        Mem.ww(a + Defs.ShotT_VelocityZ_w, -400 - k * 7);
        Mem.ww(a + Defs.ShotT_VelocityZ_w + 2, 0xFEDC - k * 0x101);
        Mem.ww(a + Defs.ShotT_VelocityY_w, 333 - k * 5);
        Mem.ww(a + Defs.ShotT_Power_w, 0x55AA);
        Mem.wb(a + Defs.ShotT_Status_b, busy ? 0xFF : 0);
        Mem.wb(a + Defs.ShotT_Size_b, 7);
        Mem.wl(a + Defs.ShotT_AccYPos_w, 123456 + k * 97);
        Mem.wb(a + Defs.ShotT_Anim_b, k & 0xFF);
        Mem.ww(a + Defs.ShotT_Gravity_w, 40 + k);
        Mem.ww(a + Defs.ShotT_Lifetime_w, 500 + k);
        Mem.ww(a + Defs.ShotT_Flags_w, 0x0101);             // les DEUX rebonds armes
        Mem.wb(a + Defs.ShotT_Worry_b, 0);
        Mem.wb(a + Defs.ShotT_InUpperZone_b, 0);
        Mem.wl(a + Defs.EntT_EnemyFlags_l, 0x12345678);
        Mem.ww(a + 4, 900 + k);
        Mem.wl(pts + pt * 8, ((1000 + k * 3) << 16) | 0xABCD);
        Mem.wl(pts + pt * 8 + 4, ((-2000 - k * 5) << 16) | 0x4321);
    }

    /** Pose la scene, allume la gerbe, et releve tout le pool. */
    private static Map<String, Object> uneGerbe(Map<String, Object> cas) {
        int busy = (Integer) cas.get("busy");
        for (int k = 0; k < Defs.NUM_ALIEN_SHOT_DATA; k++) {
            salir(k, (busy & (1 << k)) != 0, cas);
        }

        // Le mort. Son recul, sa hauteur, son secteur et son etage sont tout ce que la routine
        // lui prend — et le recul se lit en un seul LONG, X dans le mot fort.
        int a0 = base + plrSlot * Defs.ObjT_SizeOf_l;
        Mem.ww(a0 + Defs.ObjT_ZoneID_w, (Integer) cas.get("zone"));
        Mem.ww(a0 + 4, (Integer) cas.get("height"));
        Mem.wb(a0 + Defs.ShotT_InUpperZone_b, (Integer) cas.get("upper"));
        Mem.ww(a0 + Defs.EntT_ImpactX_w, (Integer) cas.get("impactX"));
        Mem.ww(a0 + Defs.EntT_ImpactZ_w, (Integer) cas.get("impactZ"));

        Mem.ww(ab3d2.ObjectmoveData.newx, (Integer) cas.get("newx"));
        Mem.ww(ab3d2.ObjectmoveData.newz, (Integer) cas.get("newz"));
        Mem.wb(ab3d2.bss.AnimBss.Anim_SplatType_w, (Integer) cas.get("splat"));
        Mem.ww(ab3d2.ObjectmoveData.Rand1, (Integer) cas.get("seed"));

        ab3d2.Newanims.Anim_ExplodeIntoBits(0, (Integer) cas.get("count"),
                (Integer) cas.get("radius"), a0);

        Map<String, Object> after = new LinkedHashMap<>();
        List<Object> pool = new ArrayList<>();
        for (int k = 0; k < Defs.NUM_ALIEN_SHOT_DATA; k++) {
            int a = base + (aiShotSlot + k) * Defs.ObjT_SizeOf_l;
            int pt = Mem.w(a);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("zone", Mem.w(a + Defs.ObjT_ZoneID_w));
            m.put("typeId", Mem.ub(a + Defs.ObjT_TypeID_b));
            m.put("x", Mem.l(pts + pt * 8));
            m.put("z", Mem.l(pts + pt * 8 + 4));
            m.put("velX", Mem.l(a + Defs.ShotT_VelocityX_w));
            m.put("velZ", Mem.l(a + Defs.ShotT_VelocityZ_w));
            m.put("velY", Mem.w(a + Defs.ShotT_VelocityY_w));
            m.put("power", Mem.ub(a + Defs.ShotT_Power_w));
            m.put("status", Mem.ub(a + Defs.ShotT_Status_b));
            m.put("size", Mem.ub(a + Defs.ShotT_Size_b));
            m.put("accY", Mem.l(a + Defs.ShotT_AccYPos_w));
            m.put("anim", Mem.ub(a + Defs.ShotT_Anim_b));
            m.put("gravity", Mem.w(a + Defs.ShotT_Gravity_w));
            m.put("lifetime", Mem.w(a + Defs.ShotT_Lifetime_w));
            m.put("flags", Mem.w(a + Defs.ShotT_Flags_w));
            m.put("worry", Mem.ub(a + Defs.ShotT_Worry_b));
            m.put("upper", Mem.ub(a + Defs.ShotT_InUpperZone_b));
            m.put("enemyFlags", Mem.l(a + Defs.EntT_EnemyFlags_l));
            m.put("height", Mem.w(a + 4));
            pool.add(m);
        }
        after.put("pool", pool);
        after.put("rand", Mem.w(ab3d2.ObjectmoveData.Rand1));

        // Le DEBORDEMENT : la routine ecrit « 2 » a seize octets du point qu elle vient de
        // poser, soit l octet fort du X du point DEUX rangs plus loin. On releve tous les
        // points du pool, plus quatre, pour pouvoir dire ou cela tombe.
        List<Object> dehors = new ArrayList<>();
        int pt0 = Mem.w(base + aiShotSlot * Defs.ObjT_SizeOf_l);
        for (int k = 0; k < Defs.NUM_ALIEN_SHOT_DATA + 4; k++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pt", pt0 + k);
            m.put("x", Mem.l(pts + (pt0 + k) * 8));
            dehors.add(m);
        }
        after.put("points", dehors);
        return after;
    }
}
