package ab3d2.rebirth;

import ab3d2.rebirth.sim.SinCos;

import java.util.HashMap;
import java.util.Map;

/**
 * Validation headless de l'anneau d'eclairage directionnel des objets ({@link LightRings}).
 *
 * <p>Affiche les deux anneaux finaux (bas/haut) pour une position donnee, dans le meme format
 * que le diagnostic du portage (`gradle -p java levelTest -PdbgRings=N`), pour comparaison
 * chiffre a chiffre.
 *
 * <pre>gradle -p rebirth/game lightTest -Pring=-808,184,3 [-Plevel=a]</pre>
 */
public final class LightTest {

    private LightTest() {
    }

    public static void main(String[] args) {
        String letter = System.getProperty("rebirth.level", "a").toUpperCase();
        String spec = System.getProperty("rebirth.ring", args.length > 0 ? args[0] : "0,0,0");
        String[] p = spec.split(",");
        int x = Integer.parseInt(p[0].trim());
        int z = Integer.parseInt(p[1].trim());
        int zone = Integer.parseInt(p[2].trim());

        LevelData lvl = Assets.json("levels/" + letter + ".json", LevelData.class);
        if (lvl == null) {
            System.err.println("niveau introuvable : " + letter);
            System.exit(1);
        }
        Map<Integer, LevelData.Zone> zones = new HashMap<>();
        for (LevelData.Zone zz : lvl.zones) {
            zones.put(zz.id, zz);
        }
        LightAnim anim = new LightAnim(zones);
        if (System.getProperty("rebirth.anim") != null) {   // diagnostic des lumieres animees
            int shownZone = -1;
            int shownIdx = -1;
            for (LevelData.Zone zz : lvl.zones) {
                if (zz.pointBrightsRaw == null) {
                    continue;
                }
                for (int i = 0; i < zz.pointBrightsRaw.size(); i++) {
                    int w = zz.pointBrightsRaw.get(i);
                    if ((byte) w >= 0 && ((w >> 8) & 0xFF) != 0) {
                        shownZone = zz.id;
                        shownIdx = i;
                        break;
                    }
                }
                if (shownZone >= 0) {
                    break;
                }
            }
            System.out.printf("point anime : zone %d index %d (mot brut 0x%04X)%n",
                    shownZone, shownIdx,
                    shownZone < 0 ? 0 : zones.get(shownZone).pointBrightsRaw.get(shownIdx) & 0xFFFF);
            for (int f = 0; f < 8; f++) {
                System.out.printf("  frame %d : lumieres = %d %d %d %d %d %d %d | luminosite = %d%n",
                        f, anim.light(0), anim.light(1), anim.light(2), anim.light(3),
                        anim.light(4), anim.light(5), anim.light(6),
                        shownZone < 0 ? 0 : anim.pointBright(shownZone, shownIdx));
                anim.advance();
            }
        }
        LightRings r = LightRings.compute(zones, lvl.points, lvl.edges, SinCos.load(),
                anim, x, z, zone);

        String spr = System.getProperty("rebirth.spr");
        if (spr != null) {                             // niveaux d'un sprite eclaire : "vue,brightToAdd"
            String[] q = spr.split(",");
            int viewAngle = Integer.parseInt(q[0].trim());
            int brightToAdd = Integer.parseInt(q[1].trim());
            LightRings zr = LightRings.zoneOnly(zones, lvl.points, lvl.edges, SinCos.load(),
                    anim, x, z, zone);
            StringBuilder rb = new StringBuilder("[sprite-anneau] bas= ");
            for (int i = 0; i < 16; i++) {
                rb.append(zr.bottom(i)).append(' ');
            }
            rb.append("| haut= ");
            for (int i = 0; i < 16; i++) {
                rb.append(zr.top(i)).append(' ');
            }
            System.out.println(rb);
            float[] lv = SpriteLight.levels(zr, viewAngle, brightToAdd, false);
            StringBuilder b = new StringBuilder("[sprite] vue=" + viewAngle
                    + " brightToAdd=" + brightToAdd + " niveaux= ");
            for (float f : lv) {
                b.append((int) f).append(' ');
            }
            System.out.println(b);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[anneau] obj=(").append(x).append(',').append(z).append(") zone=").append(zone)
          .append(" bas=");
        for (int i = 0; i < 16; i++) {
            sb.append(r.bottom(i)).append(' ');
        }
        sb.append("| haut=");
        for (int i = 0; i < 16; i++) {
            sb.append(r.top(i)).append(' ');
        }
        System.out.println(sb);
    }
}
