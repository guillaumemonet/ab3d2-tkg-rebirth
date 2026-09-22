package ab3d2.rebirth.sim;

import ab3d2.rebirth.Assets;
import ab3d2.rebirth.LevelData;

import java.util.ArrayList;
import java.util.List;

/**
 * Validation HEADLESS de la simulation du joueur (aucune fenetre, aucun rendu) :
 * on lance le joueur dans les 4 directions cardinales et on observe la traversee de zones,
 * l'arret contre les murs et la stabilisation de la hauteur.
 *
 * <p>Equivalent du MoveTest.gd qui avait valide le portage cote Godot.
 *
 * <pre>gradle -p rebirth/game moveTest [-Plevel=a]</pre>
 */
public final class MoveTest {

    private MoveTest() {
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
        if (lvl == null) {
            System.err.println("niveau introuvable : " + letter);
            return false;
        }
        SinCos sinCos = SinCos.load();
        System.out.printf("=== MoveTest niveau %s : %d zones, %d aretes, %d portes, %d lifts ===%n",
                letter, lvl.zones.size(), lvl.edges.size(),
                lvl.doors == null ? 0 : lvl.doors.size(), lvl.lifts == null ? 0 : lvl.lifts.size());

        boolean ok = true;
        for (int quarter = 0; quarter < 4; quarter++) {
            int angle = quarter * 2048;                // 0, 90, 180, 270 degres
            ok &= walk(lvl, sinCos, angle, 200);
        }
        ok &= doors(lvl);
        ok &= pickups(letter, lvl);
        ok &= solids(lvl);
        ok &= abilities(lvl);
        System.out.println(ok ? "  -> PASS" : "  -> ECHEC");
        return ok;
    }

    /**
     * Portes : on simule le contact (Move pose le bit joueur dans edgeFlags) et on verifie que
     * le toit de la zone-porte remonte, puis se referme apres openDuration.
     */
    private static boolean doors(LevelData lvl) {
        if (lvl.doors == null || lvl.doors.isEmpty()) {
            return true;
        }
        LevelSim sim = LevelSim.of(lvl);
        Anims anims = new Anims(sim, lvl);
        anims.run();
        int opened = 0;
        for (int i = 0; i < lvl.doors.size(); i++) {
            LevelData.Liftable dr = lvl.doors.get(i);
            int roofBefore = sim.zones[dr.zone].roofH;
            for (int f = 0; f < 400; f++) {
                for (int wi : dr.walls) {              // contact du joueur sur chaque arete
                    if (wi >= 0 && wi < sim.edgeFlags.length) {
                        sim.edgeFlags[wi] |= 0b100000000;
                    }
                }
                anims.playerZone = -1;
                anims.playerSpaceTap = true;           // porte manuelle : touche action maintenue
                anims.run();
                if (anims.doorOpenRatio[i] > 0.99f) {
                    break;
                }
            }
            if (anims.doorOpenRatio[i] > 0.99f) {
                opened++;
            } else {
                System.out.printf("  porte %d (zone %d, openBits %d) : ratio %.2f (toit %d -> %d)%n",
                        i, dr.zone, dr.openBits, anims.doorOpenRatio[i], roofBefore,
                        sim.zones[dr.zone].roofH);
            }
        }
        System.out.printf("  portes ouvertes au contact : %d/%d%n", opened, lvl.doors.size());
        return opened == lvl.doors.size();
    }

    /**
     * Ramassage : on pose le joueur sur chaque objet ramassable et on verifie qu'il le prend,
     * puis que les verrous de portes (cles restantes) retombent a zero.
     */
    private static boolean pickups(String letter, LevelData lvl) {
        ab3d2.rebirth.GlfData glf = Assets.json("glf.json", ab3d2.rebirth.GlfData.class);
        if (glf == null || glf.objects == null) {
            return true;
        }
        LevelSim sim = LevelSim.of(lvl);
        ObjectRuntime pk = new ObjectRuntime(sim, lvl, glf);
        PlayerSim p = new PlayerSim(sim, lvl, SinCos.load());

        int total = 0;
        int taken = 0;
        int locks0 = pk.run(p, false);
        for (LevelData.Obj o : lvl.objects) {
            if (o.typeId != 1 || o.zone < 0) {
                continue;
            }
            ab3d2.rebirth.GlfData.ObjDef def = glf.object(o.def);
            if (def == null || def.type != 0) {
                continue;                              // ENT_TYPE_COLLECTABLE uniquement
            }
            // NB : un objet qui ne donne RIEN est quand meme ramassable (Game_CheckInventoryLimits
            // renvoie TRUE quand givesAnything == 0) — ce sont les marqueurs narratifs du jeu.
            total++;
            if (def.defAnim != null && !def.defAnim.isEmpty()) {
                o.animDelta = def.defAnim.get(0).delta;   // comme animObj au 1er passage
            }
            // Teleporte le joueur sur l'objet, a la hauteur du sol de sa zone.
            p.xOff = o.x << 16;
            p.zOff = o.z << 16;
            p.zone = o.zone;
            p.stoodInTop = o.upperZone;
            LevelSim.Zone z = sim.zones[o.zone];
            p.yOff = p.snapYOff = (o.upperZone ? z.upperFloorH : z.floorH) - PlayerSim.PLR_STAND_HEIGHT;
            pk.run(p, false);
            if (!pk.collected().isEmpty()) {
                taken++;
            }
        }
        // Les interrupteurs verrouillent aussi tant qu'on ne les actionne pas : on passe sur
        // chacun en appuyant sur la touche action, comme le joueur le ferait.
        int switched = 0;
        for (LevelData.Obj o : lvl.objects) {
            if (o.typeId != 1 || o.zone < 0) {
                continue;
            }
            ab3d2.rebirth.GlfData.ObjDef def = glf.object(o.def);
            if (def == null || def.type != 1) {
                continue;                              // ENT_TYPE_ACTIVATABLE uniquement
            }
            if (def.defAnim != null && !def.defAnim.isEmpty()) {
                o.animDelta = def.defAnim.get(0).delta;
            }
            p.xOff = o.x << 16;
            p.zOff = o.z << 16;
            p.zone = o.zone;
            p.stoodInTop = o.upperZone;
            LevelSim.Zone z = sim.zones[o.zone];
            p.yOff = p.snapYOff = (o.upperZone ? z.upperFloorH : z.floorH) - PlayerSim.PLR_STAND_HEIGHT;
            pk.run(p, true);
            if (o.activated) {
                switched++;
            }
        }
        int locks1 = pk.run(p, false);
        boolean ok = taken == total && locks1 == 0;
        System.out.printf("  ramassage : %d/%d objets pris, %d interrupteurs | verrous 0x%04X -> 0x%04X%s%n",
                taken, total, switched, locks0, locks1, ok ? "" : "  [!] ECHEC");
        return ok;
    }


    /**
     * Les capacites du joueur : accroupissement (plr_KeyboardControl), jetpack et degats de chute
     * (plr_Fall). On part du point de depart du niveau, sur le sol.
     */
    private static boolean abilities(LevelData lvl) {
        ab3d2.rebirth.GlfData glf = Assets.json("glf.json", ab3d2.rebirth.GlfData.class);
        LevelSim sim = LevelSim.of(lvl);
        PlayerSim p = new PlayerSim(sim, lvl, SinCos.load());
        Inventory inv = new Inventory(glf == null ? null : glf.maxInventory);
        p.inventory = inv;
        inv.consumables[0] = 200;

        // --- accroupi : une pression baisse la taille de 12*1024 a 8*1024, par pas de 1024 ---
        int stand = p.height;
        p.duckTap = true;
        for (int i = 0; i < 8; i++) {
            step(p);
            p.duckTap = false;
        }
        int crouch = p.height;
        p.duckTap = true;
        for (int i = 0; i < 8; i++) {
            step(p);
            p.duckTap = false;
        }
        int backUp = p.height;
        boolean duckOk = stand == PlayerSim.PLR_STAND_HEIGHT
                && (crouch == PlayerSim.PLR_CROUCH_HEIGHT || p.squished)
                && (backUp == PlayerSim.PLR_STAND_HEIGHT || p.squished);

        // --- degats de chute : plr_FallDamage_w compte les FRAMES en l'air (le jeu note lui-meme
        // que ca devrait dependre de la distance) et seules celles au-dela de 100 font mal.
        p.ducked = false;
        p.snapYOff = p.yOff = p.snapTYOff - 60 * 1024;  // 60 unites au-dessus du sol
        p.snapYVel = 0;
        p.damageTaken = 0;
        int airFrames = 0;
        for (int i = 0; i < 200 && p.snapYOff < p.snapTYOff; i++) {
            step(p);
            airFrames = Math.max(airFrames, p.fallDamage);
        }
        p.snapYOff = p.yOff = p.snapTYOff;              // pose au sol : c'est la que ca fait mal
        p.snapYVel = 0;
        p.fallDamage = 150;                             // une chute de plus de 100 frames
        p.damageTaken = 0;
        step(p);
        int fallHurt = p.damageTaken;

        // --- jetpack : avec carburant, maintenir le saut fait remonter et consomme ---
        inv.items[1] = 0xFF;                            // InvIT_JetPack_w
        inv.consumables[1] = 100;                       // InvCT_JetpackFuel_w
        p.damageTaken = 0;
        p.snapYOff = p.yOff = p.snapTYOff - 40 * 1024;
        p.snapYVel = 0;
        p.jump = true;
        int fuel0 = inv.consumables[1];
        int highest = p.snapYOff;
        for (int i = 0; i < 60; i++) {
            step(p);
            highest = Math.min(highest, p.snapYOff);
        }
        p.jump = false;
        boolean jetOk = inv.consumables[1] < fuel0 && p.jetpackOn;

        boolean ok = duckOk && airFrames > 0 && fallHurt == 50 && jetOk;
        System.out.printf("  capacites : taille %d -> %d -> %d | chute %d frames en l'air, "
                + "%d degats a 150 | jetpack carburant %d -> %d%s%n",
                stand, crouch, backUp, airFrames, fallHurt, fuel0, inv.consumables[1],
                ok ? "" : "  [!] ECHEC");
        return ok;
    }

    /** Une frame de simulation du joueur, sans monstres ni objets. */
    private static void step(PlayerSim p) {
        p.keyboardControl();
        p.fall();
        p.control();
    }

    /**
     * Collision contre les ENTITES (Obj_DoCollision) : on colle le joueur contre chaque MONSTRE
     * du niveau et on verifie qu'avancer dedans est refuse, mais que s'en eloigner passe — c'est
     * le dernier test de la routine (on ne bute que si le mouvement rapproche).
     *
     * <p>On compte aussi, a titre indicatif, le DECOR (comportement &gt;= 2) qui bloque : presque
     * aucun, a cause du quirk du registre a2 decrit dans {@link Collide}. Les deux extents
     * verticaux d'un objet y sont lus dans {@code AI_WorkT_DamageDone_w} (-1, jamais reecrit pour
     * l'equipe) et {@code AI_WorkT_DamageTaken_w} (0) : le recouvrement n'est alors vrai que pour
     * un objet dont l'animation le pose SOUS le sol. Le chiffre sert de temoin de non-regression.
     */
    private static boolean solids(LevelData lvl) {
        ab3d2.rebirth.GlfData glf = Assets.json("glf.json", ab3d2.rebirth.GlfData.class);
        if (glf == null || lvl.objects == null) {
            return true;
        }
        LevelSim sim = LevelSim.of(lvl);
        Collide col = new Collide(sim, lvl, glf);
        col.aliens = new Aliens(sim, lvl, glf, SinCos.load(), new Nav(lvl), new Los(sim, lvl));

        int decor = 0;
        int decorBlocks = 0;
        for (LevelData.Obj o : lvl.objects) {
            if (o.typeId != 1 || o.zone < 0) {
                continue;
            }
            ab3d2.rebirth.GlfData.ObjDef def = glf.object(o.def);
            if (def == null || def.type < 2) {
                continue;
            }
            if (def.defAnim != null && !def.defAnim.isEmpty()) {
                o.animDelta = def.defAnim.get(0).delta;
            }
            decor++;
            LevelSim.Zone z = sim.zones[o.zone];
            int newy = (o.upperZone ? z.upperFloorH : z.floorH) - PlayerSim.PLR_STAND_HEIGHT;
            if (col.check(o.x - 40, o.z, o.x - 100, o.z, newy, PlayerSim.PLR_STAND_HEIGHT,
                    o.zone, o.upperZone)) {
                decorBlocks++;
            }
        }

        int total = 0;
        int blocked = 0;
        int freed = 0;
        int blockedZ = 0;
        int freedZ = 0;
        java.util.List<Aliens.Alien> all = col.aliens.aliens();
        int[] zones = new int[all.size()];
        for (int i = 0; i < all.size(); i++) {
            zones[i] = all.get(i).zone;
        }
        for (int i = 0; i < all.size(); i++) {
            Aliens.Alien a = all.get(i);
            if (zones[i] < 0 || (a.hitPoints & 0xFF) == 0) {
                continue;
            }
            total++;
            // On isole le monstre teste : un voisin de la meme zone fausserait la mesure.
            for (int j = 0; j < all.size(); j++) {
                all.get(j).zone = j == i ? zones[j] : -1;
            }
            // Le joueur a les PIEDS a la hauteur du monstre (c'est ce que compare .ycol) et
            // avance depuis 100 unites a l'ouest jusqu'a 40 de lui : ca doit coincer.
            int newy = (M68k.s16(a.height) << 7) - PlayerSim.PLR_STAND_HEIGHT;
            if (col.check(a.x - 40, a.z, a.x - 100, a.z, newy, PlayerSim.PLR_STAND_HEIGHT,
                    a.zone, a.upperZone)) {
                blocked++;
            }
            // ... et en repartant (on s'eloigne), plus rien ne bloque.
            if (!col.check(a.x - 100, a.z, a.x - 40, a.z, newy, PlayerSim.PLR_STAND_HEIGHT,
                    a.zone, a.upperZone)) {
                freed++;
            }
            // Le meme aller-retour sur l'axe Z. L'original y bloquait SANS tester si l'on
            // s'eloigne (cf. Collide.hits) : le joueur restait prisonnier du monstre.
            if (col.check(a.x, a.z - 40, a.x, a.z - 100, newy, PlayerSim.PLR_STAND_HEIGHT,
                    a.zone, a.upperZone)) {
                blockedZ++;
            }
            if (!col.check(a.x, a.z - 100, a.x, a.z - 40, newy, PlayerSim.PLR_STAND_HEIGHT,
                    a.zone, a.upperZone)) {
                freedZ++;
            }
        }
        for (int j = 0; j < all.size(); j++) {
            all.get(j).zone = zones[j];
        }
        boolean ok = blocked == total && freed == total
                && blockedZ == total && freedZ == total;
        System.out.printf("  entites : %d/%d monstres bloquent, %d/%d laissent repartir (Z : %d/%d, %d/%d) | "
                + "decor %d dont %d bloquent (quirk a2)%s%n",
                blocked, total, freed, total, blockedZ, total, freedZ, total,
                decor, decorBlocks, ok ? "" : "  [!] ECHEC");
        return ok;
    }

    private static boolean walk(LevelData lvl, SinCos sinCos, int angle, int frames) {
        LevelSim sim = LevelSim.of(lvl);
        PlayerSim p = new PlayerSim(sim, lvl, sinCos);
        Anims anims = new Anims(sim, lvl);
        anims.run();
        p.look(angle);
        p.forward = true;

        List<Integer> zones = new ArrayList<>();
        zones.add(p.zone);
        int startZone = p.zone;
        int lastX = p.xOff;
        int lastZ = p.zOff;
        int stuckFrames = 0;

        for (int i = 0; i < frames; i++) {
            p.keyboardControl();
            p.fall();
            p.control();
            anims.playerZone = p.zone;
            anims.playerSpaceTap = false;
            anims.run();
            p.floorSpd = anims.playerFloorSpd;

            if (zones.get(zones.size() - 1) != p.zone) {
                zones.add(p.zone);
            }
            if (sim.zones[p.zone] == null) {
                System.out.printf("  angle %4d : ZONE INVALIDE %d%n", angle, p.zone);
                return false;
            }
            if (Math.abs(p.xOff - lastX) < 4096 && Math.abs(p.zOff - lastZ) < 4096) {
                stuckFrames++;
            } else {
                stuckFrames = 0;
            }
            lastX = p.xOff;
            lastZ = p.zOff;
        }

        // le joueur doit reposer sur le sol de sa zone (ou etre en train d'y tomber)
        LevelSim.Zone zf = sim.zones[p.zone];
        int floorH = p.stoodInTop ? zf.upperFloorH : zf.floorH;
        int roofH = p.stoodInTop ? zf.upperRoofH : zf.roofH;
        int expected = floorH - p.height;              // la taille varie : accroupi / ecrase
        // Zone ecrasee (plafond au niveau du sol) : la butee de plafond de plr_Fall l'emporte,
        // le joueur y est coince — c'est le comportement d'origine, pas un defaut du portage.
        boolean squashed = roofH + 10 * 256 >= expected;
        boolean grounded = squashed || p.snapYOff <= expected + 8192;   // tolerance 1 m (chute)

        System.out.printf("  angle %4d : zones %s | pos (%d,%d) | y=%d (sol %d) | %s%s%n",
                angle, zones, p.xOff >> 16, p.zOff >> 16, p.snapYOff, expected,
                stuckFrames > 0 ? "arret contre un mur" : "en mouvement",
                squashed ? " [zone ecrasee : plafond = sol dans les donnees]"
                        : (grounded ? "" : " [!] AU-DESSUS DU SOL"));
        return grounded;
    }
}
