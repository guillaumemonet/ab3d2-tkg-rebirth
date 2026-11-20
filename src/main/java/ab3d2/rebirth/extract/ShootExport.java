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
 * Oracle du TIR HITSCAN DU JOUEUR : {@code .fire_hitscanned_bullets} (newplayershoot.s:213) et
 * {@code plr1_HitscanSucceded} (newplayershoot.s:798).
 *
 * <p>C'est l'endroit ou le jeu decide si un coup de fusil porte. Et il n'y a AUCUN rayon : avec
 * une cible alignee, le jeu tire un DE par plomb, et c'est le seul juge.
 *
 * <pre>
 *   jsr GetRand ; and.w #$7fff,d0 ; ext.l d0 ; asl.l #1,d0
 *   d1 = (tx - Plr1_XOff)^2 + (tz - Plr1_ZOff)^2 ; asr.l #6,d1
 *   cmp.l d1,d0 ; bgt .hit
 * </pre>
 *
 * <p>Soit : cent pour cent a bout portant, soixante-quinze pour cent a mille vingt-quatre
 * unites, la moitie a mille quatre cent quarante-huit, et ZERO des deux mille quarante-huit —
 * le plus grand tirage possible vaut {@code $fffe}, et le carre de la distance divise par
 * soixante-quatre le depasse des lors. Au-dela, le fusil ne peut PLUS toucher, quoi qu'on vise.
 *
 * <p>Le meme de est deja porte de l'autre cote : {@code ai_AttackWithHitScan} le roule contre le
 * joueur, avec {@code asl.l #2} au lieu de {@code asl.l #1} — les monstres sont donc DEUX FOIS
 * plus precis que lui a egale distance, et leur portee utile va a deux mille huit cent
 * quatre-vingt-seize unites.
 *
 * <h2>Ce qu'on pose</h2>
 *
 * <p>Le joueur est pose SUR L'AXE de sa cible : on choisit un angle, on prend le sinus et le
 * cosinus du jeu, et on recule le joueur de {@code dist} unites depuis le point de la cible. La
 * visee est alors parfaite par construction, et l'auto-visee de {@code CalcPLR1InLine} comme le
 * rayon du portage designent la MEME entite — c'est la seule facon de comparer les deux.
 *
 * <p>Chaque pose est validee par un balayage de {@code MoveObject} du joueur vers la cible : si
 * un mur s'interpose, le cas n'est pas emis. Le jeu, lui, s'en moque — son de ignore la
 * geometrie — mais le portage trace un rayon, et un cas derriere un mur ne comparerait rien.
 *
 * <p>On varie la distance de part et d'autre des trois seuils qui comptent (1024, 1448, 2048),
 * l'arme (toutes celles que la base dit instantanees), la graine, et la hauteur du joueur
 * au-dessus de sa cible — ce dernier reglage eprouve le filtre vertical de
 * {@code .find_closest_in_line} ({@code |dy| * 93/4096 <= profondeur}).
 */
public final class ShootExport {

    /** Les niveaux releves. */
    private static final int[] NIVEAUX = {0, 2, 6, 13};

    /** Combien de cibles servent d'ancre, au plus, par niveau. */
    private static final int ANCRES = 6;

    /** Les distances essayees, en unites monde. Les seuils du de sont 1024, 1448 et 2048. */
    private static final int[] DISTANCES = {
        1, 40, 100, 256, 512, 800, 1023, 1024, 1025, 1448, 1600,
        2046, 2047, 2048, 2049, 2400, 3000,
    };

    /** Les huit caps essayes : des huitiemes de tour dans la table du jeu. */
    private static final int[] CAPS = {0, 1024, 2048, 3072, 4096, 5120, 6144, 7168};

    /**
     * Les graines essayees : le de en depend entierement.
     *
     * <p>Il en faut BEAUCOUP. {@code GetRand} est un {@code rol.w #3 ; add.w #$2343} : avec
     * cinq graines, les trois mille coups du corpus ne tirent que cinq valeurs, et la falaise
     * des deux mille quarante-huit unites semblait tomber des deux mille quarante-SIX, faute
     * d'un tirage assez haut pour la franchir.
     */
    private static final int[] GRAINES = {
        234, 1, 2, 3, 7, 19, 255, 256, 1000, 4919, 9999, 12345,
        16384, 20000, 23456, 27183, 31337, 32768, 40000, 48000,
        54321, 60000, 65534, 65535,
    };

    /** Les ecarts de hauteur du joueur au-dessus de sa cible, en unites de dessin. */
    private static final int[] HAUTS = {0, 40, 200, 1000};

    private static int base;
    private static int pts;
    private static int plrSlot;
    private static int plrShotSlot;
    private static int slots;
    private static int zptrs;

    /** Le tirage qui decroise les reglages (voir le piege de corpus de HitExport). */
    private static int alea;

    private static int tire(int k) {
        alea = alea * 1103515245 + 12345;
        return ((alea >>> 16) & 0x7FFF) % k;
    }

    private ShootExport() {
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
        Path f = outRoot.resolve("shoot.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[shoot] %d niveaux, %d coups -> %s%n", NIVEAUX.length, cas, f);
    }

    private static int sin(int ang) {
        return Mem.w(ab3d2.data.TablesData.SinCosTable_vw + (ang & 8190));
    }

    private static int cos(int ang) {
        return Mem.w(ab3d2.data.TablesData.SinCosTable_vw + ((ang + 2048) & 8190));
    }

    private static int pointX(int slot) {
        return Mem.w(pts + Mem.w(base + slot * Defs.ObjT_SizeOf_l) * 8);
    }

    private static int pointZ(int slot) {
        return Mem.w(pts + Mem.w(base + slot * Defs.ObjT_SizeOf_l) * 8 + 4);
    }

    /**
     * Le joueur voit-il sa cible ? On fait marcher {@code MoveObject} du joueur vers le point de
     * la cible, par pas de soixante-quatre unites, exactement comme {@code plr1_HitscanFailed}
     * fait marcher son rayon de rate. Un mur en chemin, et le cas est ecarte.
     */
    private static boolean vueDegagee(int px, int pz, int py, int zone, int tx, int tz) {
        int zp = Mem.l(zptrs + zone * 4);
        if (zp == 0) {
            return false;
        }
        int dx = tx - px;
        int dz = tz - pz;
        int len = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        if (len < 2) {
            return true;
        }
        int pas = Math.max(1, 64);
        int sx = dx * pas / len;
        int sz = dz * pas / len;
        if (sx == 0 && sz == 0) {
            return true;
        }
        Mem.ww(ab3d2.ObjectmoveData.oldx, px);
        Mem.ww(ab3d2.ObjectmoveData.oldz, pz);
        Mem.ww(ab3d2.ObjectmoveData.newx, px + sx);
        Mem.ww(ab3d2.ObjectmoveData.newz, pz + sz);
        Mem.wl(ab3d2.ObjectmoveData.oldy, py);
        Mem.wl(ab3d2.ObjectmoveData.newy, py);
        Mem.wb(ab3d2.ObjectmoveData.exitfirst, 0xFF);
        Mem.wb(ab3d2.ObjectmoveData.Obj_WallBounce_b, 0);
        Mem.ww(ab3d2.ObjectmoveData.Obj_ExtLen_w, 0);
        Mem.wb(ab3d2.ObjectmoveData.Obj_AwayFromWall_b, 0xFF);
        Mem.ww(ab3d2.ObjectmoveData.wallflags, 0b0000010000000000);
        Mem.wl(ab3d2.ObjectmoveData.StepUpVal, 0);
        Mem.wl(ab3d2.ObjectmoveData.StepDownVal, 0x1000000);
        Mem.wl(ab3d2.ObjectmoveData.thingheight, 0);
        Mem.wl(ab3d2.ObjectmoveData.Obj_ZonePtr_l, zp);
        for (int i = 0; i < len / pas + 2 && i < 200; i++) {
            ab3d2.Objectmove.MoveObject();
            if (Mem.b(ab3d2.ObjectmoveData.hitwall) != 0) {
                return false;
            }
            int nx = Mem.w(ab3d2.ObjectmoveData.newx);
            int nz = Mem.w(ab3d2.ObjectmoveData.newz);
            if ((long) (nx - tx) * (nx - tx) + (long) (nz - tz) * (nz - tz) <= 80L * 80L) {
                return true;
            }
            Mem.ww(ab3d2.ObjectmoveData.oldx, nx);
            Mem.ww(ab3d2.ObjectmoveData.oldz, nz);
            Mem.ww(ab3d2.ObjectmoveData.newx, nx + sx);
            Mem.ww(ab3d2.ObjectmoveData.newz, nz + sz);
        }
        return false;
    }

    private static Map<String, Object> unNiveau(int lvl) {
        LevelExport.loadLevelForTools(lvl);
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, ab3d2.Hires.PLR_SINGLE);

        base = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
        pts = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
        zptrs = Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l);
        plrSlot = (Mem.l(ab3d2.bss.PlayerBss.Plr1_ObjectPtr_l) - base) / Defs.ObjT_SizeOf_l;
        plrShotSlot = (Mem.l(ab3d2.bss.PlayerBss.Plr_ShotDataPtr_l) - base) / Defs.ObjT_SizeOf_l;
        slots = 0;
        while (Mem.w(base + slots * Defs.ObjT_SizeOf_l) >= 0) {
            slots++;
        }

        // Les armes INSTANTANEES de la base : celles dont la balle porte IsHitScan.
        int glf = Mem.l(ab3d2.HiresData.GLF_DatabasePtr_l);
        List<Integer> fusils = new ArrayList<>();
        List<Object> armes = new ArrayList<>();
        for (int g = 0; g < 10; g++) {
            int sd = glf + Defs.GLFT_ShootDefs_l + g * Defs.ShootT_SizeOf_l;
            int bt = Mem.w(sd + Defs.ShootT_BulType_w);
            if (bt < 0 || bt >= 20) {
                continue;
            }
            int bd = glf + Defs.GLFT_BulletDefs_l + bt * Defs.BulT_SizeOf_l;
            if (Mem.w(bd + Defs.BulT_IsHitScan_l + 2) == 0) {
                continue;
            }
            fusils.add(g);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gun", g);
            m.put("bulletType", bt);
            m.put("pellets", Mem.w(sd + Defs.ShootT_BulCount_w));
            // move.w BulT_HitDamage_l+2(a5),d0 puis add.b d0,... : le MOT BAS du long est lu,
            // mais add.b n'en garde que l'octet bas — soit exactement l'octet que
            // ShotT_Power_w prend pour un projectile. Les deux familles font le meme mal.
            m.put("pelletDamage", Mem.w(bd + Defs.BulT_HitDamage_l + 2) & 0xFF);
            m.put("hitDamageWord", Mem.w(bd + Defs.BulT_HitDamage_l + 2));
            armes.add(m);
        }

        // Les ancres : des MONSTRES vivants, etales dans la table.
        List<Integer> monstres = new ArrayList<>();
        for (int k = 0; k < slots; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            if (Mem.ub(a + Defs.ObjT_TypeID_b) == 0 && Mem.w(a + Defs.ObjT_ZoneID_w) >= 0
                    && Mem.ub(a + Defs.EntT_HitPoints_b) != 0) {
                monstres.add(k);
            }
        }
        List<Integer> ancres = new ArrayList<>();
        int combien = Math.min(ANCRES, monstres.size());
        for (int i = 0; i < combien; i++) {
            ancres.add(monstres.get(i * monstres.size() / combien));
        }
        System.out.printf("[shoot] %c : %d entites, %d monstres, %d ancres, armes instantanees %s%n",
                (char) ('a' + lvl), slots, monstres.size(), ancres.size(), fusils);

        List<Object> cases = new ArrayList<>();
        int ecartes = 0;
        alea = 1234567891;
        for (int ancre : ancres) {
            int tx = pointX(ancre);
            int tz = pointZ(ancre);
            int ta = base + ancre * Defs.ObjT_SizeOf_l;
            int tzone = Mem.w(ta + Defs.ObjT_ZoneID_w);
            int thaut = Mem.w(ta + 4);
            int tupper = Mem.ub(ta + Defs.ShotT_InUpperZone_b);
            for (int cap : CAPS) {
                // Le joueur recule de dist unites le long du cap, et regarde dans ce cap :
                // la cible est alors pile devant lui.
                int sx = sin(cap);
                int cz = cos(cap);
                for (int dist : DISTANCES) {
                    int px = tx - (int) Math.round((double) sx * dist / 32768.0);
                    int pz = tz - (int) Math.round((double) cz * dist / 32768.0);
                    int haut = HAUTS[tire(HAUTS.length)];
                    int py = (thaut - haut) * 128;
                    if (!vueDegagee(px, pz, py, tzone, tx, tz)) {
                        ecartes++;
                        continue;
                    }
                    int gun = fusils.get(tire(fusils.size()));
                    int graine = GRAINES[tire(GRAINES.length)];
                    Map<String, Object> cas = new LinkedHashMap<>();
                    cas.put("anchor", ancre);
                    cas.put("gun", gun);
                    cas.put("seed", graine);
                    cas.put("angle", cap);
                    cas.put("dist", dist);
                    cas.put("playerX", px);
                    cas.put("playerZ", pz);
                    cas.put("playerY", py);
                    cas.put("playerHeight", thaut - haut);
                    cas.put("playerZone", tzone);
                    cas.put("playerUpper", tupper);
                    cas.put("targetX", tx);
                    cas.put("targetZ", tz);
                    cas.put("targetHeight", thaut);
                    cas.put("targetZone", tzone);
                    cas.put("after", unCoup(cas));
                    cases.add(cas);
                }
            }
        }
        // --- LES POSES D'EGALITE ---
        //
        // Le de est « bgt » : il faut que le tirage DEPASSE le seuil, l'egalite est un RATE.
        // Aucun des trois mille coups ci-dessus ne tombe dessus — le tirage vaut deux fois un
        // nombre de quinze bits, le seuil un carre de distance divise par soixante-quatre, et
        // qu'ils coincident tient du hasard. On les fabrique donc : pour chaque graine, on
        // calcule le premier tirage, on en deduit la distance dont le carre donne EXACTEMENT ce
        // seuil, et on pose le joueur pile a cette distance, plein axe.
        //
        // Sans ces poses-la, remplacer « bgt » par « bge » ne se verrait nulle part.
        int egalites = 0;
        for (int ancre : ancres) {
            int tx = pointX(ancre);
            int tz = pointZ(ancre);
            int ta2 = base + ancre * Defs.ObjT_SizeOf_l;
            int tzone = Mem.w(ta2 + Defs.ObjT_ZoneID_w);
            int thaut = Mem.w(ta2 + 4);
            int tupper = Mem.ub(ta2 + Defs.ShotT_InUpperZone_b);
            for (int graine : GRAINES) {
                // GetRand : rol.w #3 ; add.w #$2343.
                int r = graine & 0xFFFF;
                r = ((r << 3) | (r >>> 13)) & 0xFFFF;
                r = (r + 0x2343) & 0xFFFF;
                int d0 = (r & 0x7FFF) << 1;
                // On cherche dz tel que (dz * dz) >> 6 vaille exactement d0.
                int dz = (int) Math.sqrt((double) d0 * 64.0);
                int trouve = -1;
                for (int k = Math.max(0, dz - 2); k <= dz + 2; k++) {
                    if ((k * k) >> 6 == d0) {
                        trouve = k;
                        break;
                    }
                }
                if (trouve <= 0 || trouve > 4000) {
                    continue;
                }
                for (int sens = -1; sens <= 1; sens += 2) {
                    int px = tx;
                    int pz = tz - sens * trouve;
                    // Cap ZERO : sinus nul, cosinus plein — le joueur regarde le long de z, et
                    // l'ecart lateral est nul au point pres.
                    int cap = sens > 0 ? 0 : 4096;
                    int py = thaut * 128;
                    if (!vueDegagee(px, pz, py, tzone, tx, tz)) {
                        continue;
                    }
                    Map<String, Object> cas = new LinkedHashMap<>();
                    cas.put("anchor", ancre);
                    cas.put("gun", fusils.get(0));
                    cas.put("seed", graine);
                    cas.put("angle", cap);
                    cas.put("dist", trouve);
                    cas.put("equality", true);
                    cas.put("playerX", px);
                    cas.put("playerZ", pz);
                    cas.put("playerY", py);
                    cas.put("playerHeight", thaut);
                    cas.put("playerZone", tzone);
                    cas.put("playerUpper", tupper);
                    cas.put("targetX", tx);
                    cas.put("targetZ", tz);
                    cas.put("targetHeight", thaut);
                    cas.put("targetZone", tzone);
                    cas.put("after", unCoup(cas));
                    cases.add(cas);
                    egalites++;
                }
            }
        }

        System.out.printf("[shoot] %c : %d coups retenus (dont %d d'egalite), %d poses "
                + "ecartees (mur en chemin)%n",
                (char) ('a' + lvl), cases.size(), egalites, ecartes);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", String.valueOf((char) ('a' + lvl)));
        out.put("index", lvl);
        out.put("playerSlot", plrSlot);
        out.put("guns", armes);
        out.put("cases", cases);
        return out;
    }

    /** Pose la scene, tire UN coup par Plr1_Shot, et releve tout ce qu'il a laisse. */
    /** Le rang d'une entite en ne comptant que les NON-AUX : l'indice des deux tableaux. */
    private static int rangNonAux(int slot) {
        int r = 0;
        for (int k = 0; k < slot; k++) {
            if (Mem.ub(base + k * Defs.ObjT_SizeOf_l + Defs.ObjT_TypeID_b) != Defs.OBJ_TYPE_AUX) {
                r++;
            }
        }
        return r;
    }

    private static Map<String, Object> unCoup(Map<String, Object> cas) {
        int ancre = (Integer) cas.get("anchor");

        // --- la table remise a plat : degats, recul, et la ligne de vue eteinte PARTOUT ---
        for (int k = 0; k < slots; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            Mem.wb(a + Defs.EntT_DamageTaken_b, 0);
            Mem.ww(a + Defs.EntT_ImpactX_w, 0);
            Mem.ww(a + Defs.EntT_ImpactZ_w, 0);
            Mem.ww(a + Defs.EntT_ImpactY_w, 0);
            Mem.wb(a + Defs.ObjT_SeePlayer_b, 0);
        }
        // --- le pool du joueur vide : chaque plomb y pose sa marque ---
        for (int k = plrShotSlot; k < plrShotSlot + 20 && k < slots; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            Mem.ww(a + Defs.ObjT_ZoneID_w, -1);
            Mem.wb(a + Defs.ObjT_TypeID_b, Defs.OBJ_TYPE_PROJECTILE);
            Mem.wb(a + Defs.ShotT_Status_b, 0);
        }

        // --- la cible : vivante, dans sa zone, et VUE (bit zero de ObjT_SeePlayer_b) ---
        int ta = base + ancre * Defs.ObjT_SizeOf_l;
        if (Mem.ub(ta + Defs.EntT_HitPoints_b) == 0) {
            Mem.wb(ta + Defs.EntT_HitPoints_b, 200);
        }
        Mem.wb(ta + Defs.ObjT_SeePlayer_b, 1);

        // --- le joueur ---
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_XOff_l, (Integer) cas.get("playerX") << 16);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_ZOff_l, (Integer) cas.get("playerZ") << 16);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_YOff_l, (Integer) cas.get("playerY"));
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_Height_l, (Integer) cas.get("playerY"));
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_AngPos_w, (Integer) cas.get("angle"));
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_SinVal_w, sin((Integer) cas.get("angle")));
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_CosVal_w, cos((Integer) cas.get("angle")));
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_StoodInTop_b, (Integer) cas.get("playerUpper"));
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_ZonePtr_l,
                Mem.l(zptrs + (Integer) cas.get("playerZone") * 4));
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_AimSpeed_l, 0);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_Mouse_b, 0);
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_TimeToShoot_w, 0);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_TmpFire_b, 0xFF);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_TmpGunSelected_b, (Integer) cas.get("gun"));
        for (int b = 0; b < 20; b++) {
            Mem.ww(ab3d2.bss.PlayerBss.Plr1_AmmoCounts_vw + b * 2, 999);
        }
        // Le corps du joueur suit sa position : CalcPLR1InLine parcourt la table ENTIERE, et le
        // corps y a son rang. Pose ailleurs, il entrerait dans le cone de visee pour rien.
        int pa = base + plrSlot * Defs.ObjT_SizeOf_l;
        int ppt = Mem.w(pa);
        Mem.wl(pts + ppt * 8, (Integer) cas.get("playerX") << 16);
        Mem.wl(pts + ppt * 8 + 4, (Integer) cas.get("playerZ") << 16);
        Mem.ww(pa + 4, (Integer) cas.get("playerHeight"));
        Mem.ww(pa + Defs.ObjT_ZoneID_w, (Integer) cas.get("playerZone"));

        Mem.ww(ab3d2.bss.AnimBss.Anim_TempFrames_w, 1);
        Mem.ww(ab3d2.ObjectmoveData.Rand1, (Integer) cas.get("seed"));

        ab3d2.modules.Transform.CalcPLR1InLine();
        ab3d2.Newplayershoot.Plr1_Shot();

        // --- ce qu'il en reste ---
        Map<String, Object> after = new LinkedHashMap<>();
        // La seule entite ecrite est celle que l'auto-visee a retenue : la table etait a plat.
        //
        // PIEGE : le balayage s'arrete AVANT le pool. Un emplacement de tir est dans la meme
        // table, et ses champs de tir recouvrent ceux d'une entite — EntT_ImpactZ_w (44) est le
        // mot fort de ShotT_AccYPos_w, et EntT_DamageTaken_b (19) un octet de ShotT_VelocityX_w.
        // Les marques que les plombs viennent d'y poser ressemblent donc toutes a des blessures.
        int blesse = -1;
        for (int k = 0; k < plrShotSlot; k++) {
            int a = base + k * Defs.ObjT_SizeOf_l;
            if (Mem.ub(a + Defs.EntT_DamageTaken_b) != 0 || Mem.w(a + Defs.EntT_ImpactX_w) != 0
                    || Mem.w(a + Defs.EntT_ImpactZ_w) != 0) {
                blesse = k;
                break;
            }
        }
        after.put("slot", blesse);
        after.put("damage", blesse < 0 ? 0
                : Mem.ub(base + blesse * Defs.ObjT_SizeOf_l + Defs.EntT_DamageTaken_b));
        after.put("impactX", blesse < 0 ? 0
                : Mem.w(base + blesse * Defs.ObjT_SizeOf_l + Defs.EntT_ImpactX_w));
        after.put("impactZ", blesse < 0 ? 0
                : Mem.w(base + blesse * Defs.ObjT_SizeOf_l + Defs.EntT_ImpactZ_w));
        // L'ancre elle-meme, relevee sans chercher : c'est elle que le portage vise.
        after.put("anchorDamage", Mem.ub(ta + Defs.EntT_DamageTaken_b));
        after.put("anchorImpactX", Mem.w(ta + Defs.EntT_ImpactX_w));
        after.put("anchorImpactZ", Mem.w(ta + Defs.EntT_ImpactZ_w));
        // La profondeur tournee et l'ecart lateral que CalcPLR1InLine a calcules pour l'ancre :
        // ce sont eux qui decident qu'elle est « alignee », et le filtre vertical s'y compare.
        //
        // QUIRK : l'indice n'est PAS le rang. CalcPLR1InLine saute les AUX par .itaux, qui
        // avance l'entite mais NI le tableau NI le compteur de boucle ; le tableau est donc
        // ecrit dans l'ordre des entites NON-AUX. .find_closest_in_line le relit de meme — son
        // « tst.b (a1)+ » vient APRES le test d'AUX — de sorte que les deux restent d'accord.
        after.put("inLine", Mem.ub(ab3d2.bss.PlayerBss.Plr1_ObsInLine_vb + rangNonAux(ancre)) != 0);
        // La profondeur, elle, se relit par le POINT de l'entite (« move.w (a0),d5 ; move.w
        // (a2,d5.w*2),d6 ») alors qu'elle s'ecrit dans l'ordre non-AUX : les deux ne coincident
        // que lorsque le point vaut le rang non-AUX. On releve les DEUX.
        after.put("depth", Mem.w(ab3d2.bss.PlayerBss.Plr1_ObjectDistances_vw + Mem.w(ta) * 2));
        after.put("depthByRank", Mem.w(ab3d2.bss.PlayerBss.Plr1_ObjectDistances_vw
                + rangNonAux(ancre) * 2));
        // Combien d'emplacements du pool portent une marque : un par plomb quand une cible est
        // alignee (touche ou rate), UN SEUL quand il n'y en a pas — c'est ce compte qui separe
        // « tous les plombs ont rate » de « aucune cible n'etait alignee ».
        int marques = 0;
        for (int k = plrShotSlot; k < plrShotSlot + 20 && k < slots; k++) {
            if (Mem.w(base + k * Defs.ObjT_SizeOf_l + Defs.ObjT_ZoneID_w) >= 0) {
                marques++;
            }
        }
        after.put("marks", marques);
        // targdist vaut $7fff quand .find_closest_in_line n'a RIEN retenu : c'est la valeur de
        // depart de d1, et .out_of_line la recopie telle quelle. Comme l'ancre est la seule
        // entite dont la ligne de vue soit allumee, « une cible retenue » veut dire « l'ancre ».
        int td = Mem.uw(ab3d2.NewplayershootData.targdist);
        after.put("targdist", td);
        after.put("selected", td != 0x7fff);
        after.put("ammo", Mem.w(ab3d2.bss.PlayerBss.Plr1_AmmoCounts_vw
                + Mem.w(ab3d2.NewplayershootData.BULTYPE) * 2));
        after.put("randAfter", Mem.uw(ab3d2.ObjectmoveData.Rand1));
        return after;
    }
}
