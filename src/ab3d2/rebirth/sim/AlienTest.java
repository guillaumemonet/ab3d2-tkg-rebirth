package ab3d2.rebirth.sim;

import ab3d2.rebirth.Assets;
import ab3d2.rebirth.GlfData;
import ab3d2.rebirth.LevelData;

import java.util.List;

/**
 * Validation HEADLESS des monstres (aucune fenetre, aucun rendu).
 *
 * <p>Ce qui est verifie, niveau par niveau :
 * <ul>
 *   <li>les entites sont bien chargees (definition, zone, points de vie, point de controle) ;</li>
 *   <li>elles PATROUILLENT : au bout de 400 frames une part significative a bouge, et personne
 *       ne sort de sa zone valide ni ne traverse un mur (la zone reste coherente) ;</li>
 *   <li>l'animation tourne : la frame dessinee change et reste dans la feuille ;</li>
 *   <li>la ligne de vue fonctionne : place au meme endroit que l'alien, le joueur est vu ;
 *       place dans une zone sans PVS commune, il ne l'est pas ;</li>
 *   <li>reaction : quand le joueur est en vue et a portee, l'alien passe en mode attaque.</li>
 * </ul>
 *
 * <pre>gradle -p rebirth/game alienTest [-Plevel=a|all]</pre>
 */
public final class AlienTest {

    private AlienTest() {
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
        if (lvl == null || glf == null) {
            System.err.println("niveau ou base GLF introuvable : " + letter);
            return false;
        }
        LevelSim sim = LevelSim.of(lvl);
        SinCos sc = SinCos.load();
        Nav nav = new Nav(lvl);
        Los los = new Los(sim, lvl);
        Aliens al = new Aliens(sim, lvl, glf, sc, nav, los);
        PlayerSim p = new PlayerSim(sim, lvl, sc);
        Anims anims = new Anims(sim, lvl);
        anims.run();

        List<Aliens.Alien> all = al.aliens();
        List<Aliens.Alien> list = new java.util.ArrayList<>();
        for (Aliens.Alien a : all) {
            if (a.alive && a.zone >= 0) {              // les emplacements d'engendrement sont vides
                list.add(a);
            }
        }
        if (list.isEmpty()) {
            System.out.printf("=== AlienTest %s : aucun alien (%d points de controle)%n",
                    letter, lvl.numControlPoints);
            return true;
        }

        int[] x0 = new int[list.size()];
        int[] z0 = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            x0[i] = list.get(i).x;
            z0[i] = list.get(i).z;
        }

        // Les aliens DORMENT tant que le joueur n'est pas en vue de leur zone (ShotT_Worry_b).
        // On verifie d'abord ce sommeil, puis on reveille en placant le joueur parmi eux.
        // On prend un alien ELOIGNE : celui d'une zone que la zone du joueur ne voit pas. Si
        // tous sont en vue au depart (ca arrive), le sommeil n'est pas observable ici.
        Aliens.Alien far = null;
        for (Aliens.Alien a : list) {
            if (a.alive && a.zone >= 0 && !al.seesZone(p.zone, a.zone)) {
                far = a;
                break;
            }
        }
        boolean slept = true;
        if (far != null) {
            int sleptX = far.x;
            int sleptZ = far.z;
            for (int f = 0; f < 60; f++) {
                al.run(p);
            }
            slept = far.x == sleptX && far.z == sleptZ;
        } else {
            far = list.get(0);
        }

        // -Pspawn="x,z,zone" : place le joueur a un endroit precis (comparaison avec le port,
        // dont levelTest a -PspawnAt="x z zone").
        String spawn = System.getProperty("rebirth.spawn");
        if (spawn != null) {
            int trace = Integer.getInteger("rebirth.aiTrace", 0);
            String[] sp = spawn.split("[ ,]+");
            p.xOff = p.snapXOff = Integer.parseInt(sp[0].trim()) << 16;
            p.zOff = p.snapZOff = Integer.parseInt(sp[1].trim()) << 16;
            p.zone = Integer.parseInt(sp[2].trim());
            for (int f = 0; f < 400; f++) {
                al.run(p);
                for (int i = 0; i < trace && i < list.size(); i++) {
                    Aliens.Alien a = list.get(i);
                    System.out.printf("AI f=%d i=%d x=%d z=%d zone=%d mode=%d anim=%d cpt=%d"
                                    + " tgt=%d t1=%d t2=%d see=%d hp=%d%n",
                            f, i, a.x, a.z, a.zone, a.mode, a.whichAnim, a.currentCPt,
                            a.targetCPt, a.timer1, a.timer2, a.seePlayer ? 255 : 0,
                            a.hitPoints & 0xFF);
                }
            }
            return true;
        }

        // Reveil : le joueur prend la place du premier alien, donc sa zone les voit.
        p.xOff = p.snapXOff = far.x << 16;
        p.zOff = p.snapZOff = far.z << 16;
        p.zone = far.zone;
        p.stoodInTop = far.upperZone;

        int badZone = 0;
        int animChanges = 0;
        int badFrame = 0;
        int[] lastFrame = new int[list.size()];
        java.util.Arrays.fill(lastFrame, -1);
        // DIAG (-PaiTrace=N) : meme trace que le port (Hires.dbgAiTrace), pour comparer frame a
        // frame le comportement des aliens entre les deux implementations.
        int trace = Integer.getInteger("rebirth.aiTrace", -1);
        for (int f = 0; f < 400; f++) {
            p.keyboardControl();
            p.fall();
            p.control();
            al.run(p);
            for (int i = 0; i < trace && i < list.size(); i++) {
                Aliens.Alien a = list.get(i);
                System.out.printf("AI f=%d i=%d x=%d z=%d zone=%d mode=%d anim=%d cpt=%d tgt=%d"
                                + " t1=%d t2=%d see=%d%n",
                        f, i, a.x, a.z, a.zone, a.mode, a.whichAnim, a.currentCPt, a.targetCPt,
                        a.timer1, a.timer2, a.seePlayer ? 255 : 0);
            }
            for (int i = 0; i < list.size(); i++) {
                Aliens.Alien a = list.get(i);
                if (!a.alive) {
                    continue;
                }
                if (a.zone < 0 || a.zone >= sim.zones.length || sim.zones[a.zone] == null) {
                    badZone++;
                }
                if (a.frame < 0 || a.frame > 63) {
                    badFrame++;
                }
                if (lastFrame[i] != a.frame) {
                    lastFrame[i] = a.frame;
                    animChanges++;
                }
            }
        }
        if (System.getProperty("rebirth.alienLog") != null) {
            for (int i = 0; i < Math.min(6, list.size()); i++) {
                Aliens.Alien a = list.get(i);
                System.out.printf("  alien %d type=%d zone=%d x=%d z=%d h=%d angle=%d "
                        + "(jME %.2f, %.2f, %.2f)%n", i, a.type, a.zone, a.x, a.z, a.height,
                        a.angle, a.x / 64f, -a.height / 64f, -a.z / 64f);
            }
        }
        int moved = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).x != x0[i] || list.get(i).z != z0[i]) {
                moved++;
            }
        }

        // Ligne de vue : depuis l'alien vers lui-meme (meme zone) -> vu.
        Aliens.Alien a0 = list.get(0);
        los.viewerX = a0.x;
        los.viewerZ = a0.z;
        los.viewerY = a0.height;
        los.viewerZone = a0.zone;
        los.viewerTop = a0.upperZone;
        los.targetX = a0.x;
        los.targetZ = a0.z;
        los.targetY = a0.height;
        los.targetZone = a0.zone;
        los.targetTop = a0.upperZone;
        los.canItBeSeen();
        boolean seeSelf = los.canSee;
        // ... et vers une zone qui n'est pas dans sa liste de zones visibles -> pas vu.
        int hidden = -1;
        LevelData.Zone vz = null;
        for (LevelData.Zone z : lvl.zones) {
            if (z.id == a0.zone) {
                vz = z;
            }
        }
        for (LevelData.Zone z : lvl.zones) {
            boolean inPvs = false;
            if (vz != null && vz.pvs != null) {
                for (LevelData.Pvs e : vz.pvs) {
                    inPvs |= e.zone == z.id;
                }
            }
            if (!inPvs) {
                hidden = z.id;
                break;
            }
        }
        boolean seeHidden = false;
        if (hidden >= 0) {
            los.targetZone = hidden;
            los.canItBeSeen();
            seeHidden = los.canSee;
        }

        // Reaction : le joueur pose DEVANT l'alien (a 150 unites dans son axe, sinon le produit
        // scalaire de ai_CheckInFront est nul et il ne reagit jamais) doit le faire attaquer.
        int px = a0.x + (sc.sin(a0.angle) >> 8);
        int pz = a0.z + (sc.cos(a0.angle) >> 8);
        p.xOff = p.snapXOff = px << 16;
        p.zOff = p.snapZOff = pz << 16;
        p.zone = a0.zone;
        p.stoodInTop = a0.upperZone;
        p.yOff = p.snapYOff = sim.zones[a0.zone].floorH - PlayerSim.PLR_STAND_HEIGHT;
        Shots shots = new Shots(sim, glf, sc);
        shots.setTargets(al);
        shots.setPlayer(p);
        al.setFire(shots::fireAtPlayer);
        al.shots = shots;                              // SHOOTPLAYER1 : l'impact des tirs rates
        Inventory inv = new Inventory(glf.maxInventory);
        inv.defaultGame(glf);
        int reacted = 0;
        int alienShots = 0;
        int auxFrames = 0;
        for (int f = 0; f < 300; f++) {
            p.beginFrame(1);
            al.run(p);
            for (Aliens.Alien a : list) {              // graphique auxiliaire (eclats de tir)
                if (a.alive && a.auxFrame >= 0) {
                    auxFrames++;
                }
            }
            shots.update();
            alienShots = Math.max(alienShots, shots.alienShotsActive());
            p.applyDamage(inv, nav);
            for (Aliens.Alien a : list) {
                if (a.alive && a.mode != 0) {
                    reacted++;
                    break;
                }
            }
        }
        int hurt = 200 - inv.health();                 // DEFAULTGAME donne 200 de sante

        // Tir : le joueur, plasma en main, doit pouvoir tuer l'alien qu'il a devant lui.
        al.setFire(null);                              // on isole le tir DU JOUEUR
        inv.setAmmo(0, 500);                           // Plasma Bolt
        // Mort = points de vie a zero (ai_JustDied). L'EMPLACEMENT, lui, n'est rendu qu'a la fin
        // de l'animation de mort, qui dure plus que la fenetre du test pour certaines especes :
        // attendre `alive` ferait passer pour increvable un monstre deja tombe.
        final int startHp = a0.hitPoints & 0xFF;
        boolean killed = false;
        int gore = 0;
        for (int f = 0; f < 400 && !killed; f++) {
            // On se replace SUR lui a chaque frame : c'est le chemin des degats qu'on teste, pas
            // la visee (un alien colle a un mur rendrait le placement « devant lui » impossible).
            p.xOff = p.snapXOff = a0.x << 16;
            p.zOff = p.snapZOff = a0.z << 16;
            p.zone = a0.zone;
            p.stoodInTop = a0.upperZone;
            // Hauteur de tir alignee sur l'alien : la balle part a yOff + 30*128 (fireProjectile)
            // et le test de hauteur du jeu tolere 50 ; un alien volant serait sinon hors de portee.
            p.yOff = p.snapYOff = (a0.height << 7) - 30 * 128;
            p.angPos = (a0.angle + 4096) & SinCos.MASK;
            shots.fire(p, inv, 1, true, 0f);
            shots.update();
            al.run(p);
            gore = Math.max(gore, shots.alienShotsActive());
            killed = !a0.alive || (startHp != 0 && (a0.hitPoints & 0xFF) == 0);
        }
        for (int f = 0; f < 30; f++) {                 // laisse partir la gerbe de morceaux
            shots.update();
            al.run(p);
            gore = Math.max(gore, shots.alienShotsActive());
        }
        // Un alien touche n'a pas forcement moins de points de vie : le jeu accumule les degats
        // dans son workspace et ne compare qu'au quart (ai_TakeDamage). On compte donc les COUPS.
        boolean damaged = killed || al.hits > 0;
        String killDetail = killed ? "" : String.format(" (cible pv=%d cumul=%d mode=%d)",
                a0.hitPoints & 0xFF, al.damageAccum(a0), a0.mode & 0xFF);

        // HITSCAN (Shotgun, arme 0) : le rayon doit toucher le monstre par sa seule geometrie.
        // Ce chemin ne passe PAS par hitEnemies (qui ne suit que les projectiles en vol) ; il
        // est reste sans degats jusqu'a ce qu'un joueur signale ne pas pouvoir tuer un alien.
        // DISPERSION : chaque alien arrive a destination se choisit un point de controle AU
        // HASARD. S'ils convergent tous vers le meme, c'est que le tirage est casse — c'est
        // arrive, le reste de la division etant lu sur un quotient (cf. Aliens.randomCPt).
        java.util.Set<Integer> targets = new java.util.HashSet<>();
        for (Aliens.Alien a : al.aliens()) {
            if (a.alive) {
                targets.add(a.targetCPt);
            }
        }
        int spread = targets.size();

        inv.setAmmo(7, 500);                           // Shotgun Shells : le fusil en consomme
        int hitsBefore = al.hits;
        boolean scanHit = false;
        Aliens.Alien a1 = null;
        for (Aliens.Alien a : al.aliens()) {
            if (a.alive && a.hitPoints != 0) {
                a1 = a;
                break;
            }
        }
        if (a1 != null) {
            for (int f = 0; f < 200 && !scanHit; f++) {
                p.xOff = p.snapXOff = a1.x << 16;
                p.zOff = p.snapZOff = a1.z << 16;
                p.zone = a1.zone;
                p.stoodInTop = a1.upperZone;
                p.yOff = p.snapYOff = (a1.height << 7) - 30 * 128;
                p.angPos = (a1.angle + 4096) & SinCos.MASK;
                shots.fire(p, inv, 0, true, 0f);       // Shotgun : hitScan = 1
                shots.update();
                al.run(p);
                scanHit = al.hits > hitsBefore || !a1.alive;
            }
        }

        // Souffle : une roquette tiree A COTE d'un alien doit le blesser sans le toucher.
        int blastHits = 0;
        Aliens.Alien victim = null;
        for (Aliens.Alien a : list) {
            if (a.alive && a.zone >= 0) {
                victim = a;
                break;
            }
        }
        if (victim != null) {
            int before = al.hits;
            inv.setAmmo(2, 50);                        // Rocket
            for (int f = 0; f < 200; f++) {
                p.xOff = p.snapXOff = (victim.x + 400) << 16;   // a 400 unites sur le cote
                p.zOff = p.snapZOff = victim.z << 16;
                p.zone = victim.zone;
                p.stoodInTop = victim.upperZone;
                p.yOff = p.snapYOff = (victim.height << 7) - 30 * 128;
                p.angPos = 6144;                       // vers -x : le tir passe a cote
                shots.fire(p, inv, 5, true, 0f);        // Rocket Launcher
                shots.update();
                al.run(p);
            }
            blastHits = al.hits - before;
        }

        boolean ok = slept && badZone == 0 && badFrame == 0 && moved > 0 && animChanges > 0
                && seeSelf && !seeHidden && reacted > 0 && damaged
                // le joueur planté devant eux doit finir par prendre quelque chose : un tir parti
                // (riposte a distance) ou des degats (morsure au corps a corps)
                && (alienShots > 0 || hurt > 0)
                // le rayon hitscan doit toucher : sans ca, fusil et mitrailleuse sont inoffensifs
                && (a1 == null || scanHit)
                // et ils ne doivent pas tous viser le meme point de controle — mesurable
                // seulement si assez d'aliens se sont REVEILLES (les dormants gardent leur but).
                && (moved < 8 || lvl.numControlPoints < 4 || spread > 1);
        System.out.printf("=== AlienTest %s : %d aliens, %d ont bouge, %d changements de frame,"
                + " vue (soi %b / cache %b), reaction %d frames, riposte %d tirs / %d degats,"
                + " tir %s, hitscan %s, buts %d, souffle %d, rates traces %d, aux %d,"
                + " sommeil %s%s%n",
                letter, list.size(), moved, animChanges, seeSelf, seeHidden, reacted,
                alienShots, hurt,
                killed ? ("tue, " + gore + " morceaux")
                        : ((damaged ? al.hits + " coups" : "SANS EFFET") + killDetail),
                a1 == null ? "n/a" : (scanHit ? "touche" : "SANS EFFET"), spread,
                blastHits, al.missedShots, auxFrames, slept ? "ok" : "ROMPU",
                ok ? "" : String.format("  [!] ECHEC (zones %d, frames %d)", badZone, badFrame));
        return ok;
    }
}
