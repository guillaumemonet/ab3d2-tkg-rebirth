package ab3d2.rebirth;

import java.util.List;

/**
 * LES DEUX LECTURES D'UN MEME NIVEAU, mises face a face.
 *
 * <p>Le remake decrit ses niveaux en JSON — c'est de la que vient sa geometrie 3D. La
 * simulation, elle, lit les fichiers d'origine. Les deux descriptions doivent parler du meme
 * niveau : les memes secteurs, dans le meme ordre, aux memes hauteurs, avec les memes aretes
 * et les memes entites. Un decalage d'un seul rang d'entite suffirait a faire tirer un monstre
 * a la place d'un autre, et rien ne le dirait avant la partie.
 *
 * <p>Ce n'est pas un test de regle de jeu : c'est le controle du JOINT. Il garde sa valeur
 * apres la soudure — c'est lui qui dira, le jour ou l'extraction changera, que les deux
 * lectures ont diverge.
 *
 * <pre>
 * gradle -p rebirth simParity
 * gradle -p rebirth simParity -Plevel=c
 * </pre>
 */
public final class SimParity {

    private SimParity() {
    }

    private static int ecarts;
    private static int compares;

    public static void main(String[] args) {
        String un = System.getProperty("rebirth.level", "");
        String lettres = un.isEmpty() ? "abcdefghijklmnop" : un.toLowerCase();
        for (int i = 0; i < lettres.length(); i++) {
            char c = lettres.charAt(i);
            try {
                niveau(c);
            } catch (RuntimeException e) {
                System.out.printf("  niveau %c : ECHEC %s%n", c, e);
                ecarts++;
            }
        }
        System.out.printf("%d valeurs comparees, %d ecart(s) -> %s%n",
                compares, ecarts, ecarts == 0 ? "PASS" : "FAIL");
        if (ecarts != 0) {
            System.exit(1);
        }
    }

    private static void niveau(char lettre) {
        LevelData json = Assets.json("levels/" + lettre + ".json", LevelData.class);
        ab3d2.full.level.Level lu = ab3d2.full.Data.level(lettre);
        int avant = ecarts;

        // Les SECTEURS, ranges par identite : c'est par elle que la collision les retrouve.
        int hautJson = 0;
        for (LevelData.Zone z : json.zones) {
            hautJson = Math.max(hautJson, z.id);
        }
        int hautLu = 0;
        for (ab3d2.full.level.Level.Zone z : lu.zones()) {
            hautLu = Math.max(hautLu, z.id());
        }
        cmp(lettre, "plus grand identifiant de secteur", hautJson, hautLu);
        cmp(lettre, "nombre de secteurs", json.zones.size(), lu.zones().size());

        LevelData.Zone[] parId = new LevelData.Zone[hautJson + 1];
        for (LevelData.Zone z : json.zones) {
            parId[z.id] = z;
        }
        for (ab3d2.full.level.Level.Zone y : lu.zones()) {
            if (y.id() < 0 || y.id() >= parId.length || parId[y.id()] == null) {
                System.out.printf("  ECART %c : le secteur %d manque au json%n", lettre, y.id());
                ecarts++;
                continue;
            }
            LevelData.Zone x = parId[y.id()];
            String ou = lettre + " secteur " + y.id();
            cmp(ou, "plancher", x.floorH, y.floor());
            cmp(ou, "plafond", x.roofH, y.ceiling());
            cmp(ou, "plancher haut", x.upperFloorH, y.upperFloor());
            cmp(ou, "plafond haut", x.upperRoofH, y.upperCeiling());
            cmp(ou, "eau", x.water, y.water());
            cmp(ou, "bruit de sol", x.floorNoise, y.floorSound());
            cmp(ou, "bruit de sol haut", x.upperFloorNoise, y.upperFloorSound());
            cmp(ou, "ambiance", x.backSfxMask, y.ambientSoundMask());
            cmp(ou, "ambiance haute", x.upperBackSfxMask, y.upperAmbientSoundMask());
            cmp(ou, "teleporteur", x.telZone, y.teleportZone());
            cmp(ou, "teleporteur x", x.telX, y.teleportX());
            cmp(ou, "teleporteur z", x.telZ, y.teleportZ());
            cmp(ou, "luminosite", x.brightness, y.brightness());
            cmp(ou, "luminosite haute", x.upperBrightness, y.upperBrightness());
            cmp(ou, "point de controle", x.controlPoint, y.controlPoint());
            List<Integer> liste = x.edgeList == null ? List.of() : x.edgeList;
            cmp(ou, "longueur de la liste d'aretes", liste.size(), y.edgeList().size());
            for (int k = 0; k < Math.min(liste.size(), y.edgeList().size()); k++) {
                cmp(ou, "arete " + k, liste.get(k), y.edgeList().get(k));
            }
        }

        // Les ARETES, par rang global : la collision les designe ainsi.
        List<LevelData.Edge> a = json.edges == null ? List.of() : json.edges;
        List<ab3d2.full.level.Level.Edge> b = lu.edges();
        cmp(lettre, "nombre d'aretes", a.size(), b.size());
        for (int e = 0; e < Math.min(a.size(), b.size()); e++) {
            String ou = lettre + " arete " + e;
            cmp(ou, "x", a.get(e).x, b.get(e).x());
            cmp(ou, "z", a.get(e).z, b.get(e).z());
            cmp(ou, "dx", a.get(e).dx, b.get(e).dx());
            cmp(ou, "dz", a.get(e).dz, b.get(e).dz());
            cmp(ou, "secteur joint", a.get(e).join, b.get(e).joinZone());
            cmp(ou, "separateur", a.get(e).w5, b.get(e).divider());
            cmp(ou, "normale x", a.get(e).b12, b.get(e).normalX());
            cmp(ou, "normale z", a.get(e).b13, b.get(e).normalZ());
        }

        // Les ENTITES : c'est par leur RANG que tout se tient — un monstre, un objet, une
        // balle et l'arme en main occupent des rangs de la meme table. Le remake retire du
        // decor l'objet ramasse en se servant de ce rang : s'il glissait d'un cran, on
        // ramasserait une chose et une autre disparaitrait.
        cmp(lettre, "nombre d'entites", json.objects == null ? 0 : json.objects.size(),
                lu.entities().size());

        System.out.printf("  niveau %c : %d secteurs, %d aretes, %d entites%s%n",
                lettre, lu.zones().size(), b.size(), lu.entities().size(),
                ecarts == avant ? "" : "   <<< " + (ecarts - avant) + " ecart(s)");
    }

    private static void cmp(Object ou, String quoi, int a, int b) {
        compares++;
        if (a != b) {
            if (ecarts < 40) {
                System.out.printf("  ECART %s, %s : json %d, fichiers %d%n", ou, quoi, a, b);
            }
            ecarts++;
        }
    }
}
