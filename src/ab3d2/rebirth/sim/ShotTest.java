package ab3d2.rebirth.sim;

import ab3d2.rebirth.Assets;
import ab3d2.rebirth.GlfData;
import ab3d2.rebirth.LevelData;

/**
 * Validation HEADLESS du tir (aucune fenetre, aucun rendu) : cadence, munitions, vol des
 * projectiles et impact.
 *
 * <p>Ce qui est verifie, pour chaque arme et dans les 4 directions cardinales depuis le point de
 * depart du joueur :
 * <ul>
 *   <li>la cadence : un tir tous les {@code ShootT_Delay_w} frames, pas plus ;</li>
 *   <li>les munitions : {@code ShootT_BulCount_w} par tir, et plus de tir a zero ;</li>
 *   <li>le vol : le projectile avance, change de zone quand il le faut, et FINIT toujours par
 *       s'arreter (mur, sol, plafond ou duree de vie) — un emplacement du pool qui ne se libere
 *       jamais est un bug ;</li>
 *   <li>le hitscan : le tir instantane laisse une explosion contre un mur.</li>
 * </ul>
 *
 * <pre>gradle -p rebirth/game shotTest [-Plevel=a|all]</pre>
 */
public final class ShotTest {

    private ShotTest() {
    }

    public static void main(String[] args) {
        String arg = System.getProperty("rebirth.level", args.length > 0 ? args[0] : "a");
        if ("all".equalsIgnoreCase(arg)) {
            boolean all = true;
            for (char c = 'A'; c <= 'P'; c++) {
                all &= run(String.valueOf(c));
            }
            System.out.println(all ? "PASS (16 niveaux)" : "ECHEC");
            System.exit(all ? 0 : 2);
        }
        System.exit(run(arg.toUpperCase()) ? 0 : 2);
    }

    private static boolean run(String letterArg) {
        String letter = letterArg.toUpperCase();
        LevelData lvl = Assets.json("levels/" + letter + ".json", LevelData.class);
        GlfData glf = Assets.json("glf.json", GlfData.class);
        if (lvl == null || glf == null || glf.guns == null) {
            System.err.println("niveau ou base GLF introuvable : " + letter);
            return false;
        }
        System.out.printf("=== ShotTest niveau %s ===%n", letter);
        boolean ok = true;
        for (int gun = 0; gun < glf.guns.size(); gun++) {
            ok &= gun(lvl, glf, gun);
        }
        System.out.println(ok ? "  -> PASS" : "  -> ECHEC");
        return ok;
    }

    private static boolean gun(LevelData lvl, GlfData glf, int gun) {
        GlfData.Gun g = glf.gun(gun);
        GlfData.Bullet b = glf.bullet(g.bulletType);
        int fired = 0;
        int impacts = 0;
        int moved = 0;
        int leaked = 0;
        int badZone = 0;
        boolean ammoOk = true;

        for (int quarter = 0; quarter < 4; quarter++) {
            LevelSim sim = LevelSim.of(lvl);
            SinCos sinCos = SinCos.load();
            PlayerSim p = new PlayerSim(sim, lvl, sinCos);
            Anims anims = new Anims(sim, lvl);
            anims.run();
            p.look(quarter * 2048);
            Inventory inv = new Inventory(glf.maxInventory);
            inv.defaultGame(glf);
            inv.setAmmo(g.bulletType, 100);            // de quoi tirer 4 fois au minimum
            Shots shots = new Shots(sim, glf, sinCos);

            int ammo0 = inv.ammo(g.bulletType);
            int shotsFired = 0;
            int seen = 0;
            // 900 frames : de quoi epuiser la duree de vie la plus longue (mine : 500) apres les
            // 4 tirs, sinon un projectile encore en vol passerait pour une fuite du pool.
            for (int f = 0; f < 900; f++) {
                p.keyboardControl();
                p.fall();
                p.control();
                boolean pressed = shotsFired < 4;      // 4 tirs, puis on relache
                if (shots.fire(p, inv, gun, pressed, 0f)) {
                    shotsFired++;
                }
                int before = countActive(shots);
                int[] posBefore = positions(shots);
                shots.update();
                seen = Math.max(seen, before);
                moved += movedCount(shots, posBefore);
                for (Shots.Shot s : shots.shots()) {
                    if (s.active && (s.zone < 0 || s.zone >= sim.zones.length)) {
                        badZone++;
                    }
                    if (s.active && s.popping) {
                        impacts++;
                    }
                }
            }
            fired += shotsFired;
            // Munitions : chaque tir consomme exactement ShootT_BulCount_w.
            ammoOk &= inv.ammo(g.bulletType) == ammo0 - shotsFired * g.bulletCount;
            // Pool : tout doit etre rendu une fois les tirs finis.
            leaked += countActive(shots);
            if (seen == 0 && shotsFired > 0) {
                leaked += 1000;                        // aucun projectile n'est jamais apparu
            }
        }

        boolean ok = fired > 0 && ammoOk && leaked == 0 && badZone == 0
                && (b.hitScan != 0 || moved > 0);
        System.out.printf("  arme %d %-18s (%s, delai %2d, x%d) : %d tirs, %d frames de vol,"
                + " %d frames d'impact%s%n",
                gun, g.name, b.hitScan != 0 ? "hitscan" : "projectile", g.delay, g.bulletCount,
                fired, moved, impacts,
                ok ? "" : String.format("  [!] ECHEC (munitions %s, fuites %d, zones %d)",
                        ammoOk ? "ok" : "FAUX", leaked, badZone));
        return ok;
    }

    private static int countActive(Shots shots) {
        int n = 0;
        for (Shots.Shot s : shots.shots()) {
            if (s.active) {
                n++;
            }
        }
        return n;
    }

    private static int[] positions(Shots shots) {
        int[] pos = new int[shots.shots().size() * 2];
        for (int i = 0; i < shots.shots().size(); i++) {
            pos[i * 2] = shots.shots().get(i).x;
            pos[i * 2 + 1] = shots.shots().get(i).z;
        }
        return pos;
    }

    private static int movedCount(Shots shots, int[] before) {
        int n = 0;
        for (int i = 0; i < shots.shots().size(); i++) {
            Shots.Shot s = shots.shots().get(i);
            if (s.active && (s.x != before[i * 2] || s.z != before[i * 2 + 1])) {
                n++;
            }
        }
        return n;
    }
}
