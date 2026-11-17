package ab3d2.rebirth;

/**
 * LES DEUX LECTURES D'UN MEME NIVEAU, mises face a face.
 *
 * <p>Le remake decrit ses niveaux en JSON — c'est de la que vient sa geometrie 3D. La
 * simulation verifiee, elle, lit les fichiers d'origine. Avant de confier la simulation du
 * remake a celle de {@code full}, il faut s'assurer que les deux descriptions parlent du meme
 * niveau : les memes secteurs, dans le meme ordre, aux memes hauteurs, avec les memes aretes
 * et les memes entites. Un decalage d'un seul rang d'entite suffirait a faire tirer un
 * monstre a la place d'un autre.
 *
 * <p>Ce n'est pas un test de regle de jeu : c'est le controle du JOINT. Il tombe donc dans le
 * remake et non dans {@code full}, et il reste utile apres la soudure — c'est lui qui dira,
 * le jour ou l'extraction changera, que les deux lectures ont diverge.
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

        ab3d2.rebirth.sim.LevelSim a = ab3d2.rebirth.sim.LevelSim.of(json);
        ab3d2.full.sim.LevelSim b = ab3d2.full.sim.LevelSim.of(lu);

        int avant = ecarts;
        cmp(lettre, "nombre de secteurs", a.zones.length, b.zones.length);
        for (int z = 0; z < Math.min(a.zones.length, b.zones.length); z++) {
            ab3d2.rebirth.sim.LevelSim.Zone x = a.zones[z];
            ab3d2.full.sim.LevelSim.Zone y = b.zones[z];
            if ((x == null) != (y == null)) {
                System.out.printf("  %c secteur %d : present d'un seul cote%n", lettre, z);
                ecarts++;
                continue;
            }
            if (x == null) {
                continue;
            }
            String ou = lettre + " secteur " + z;
            cmp(ou, "plancher", x.floorH, y.floorH);
            cmp(ou, "plafond", x.roofH, y.roofH);
            cmp(ou, "plancher haut", x.upperFloorH, y.upperFloorH);
            cmp(ou, "plafond haut", x.upperRoofH, y.upperRoofH);
            cmp(ou, "eau", x.water, y.water);
            cmp(ou, "bruit de sol", x.floorNoise, y.floorNoise);
            cmp(ou, "bruit de sol haut", x.upperFloorNoise, y.upperFloorNoise);
            cmp(ou, "ambiance", x.backSfxMask, y.backSfxMask);
            cmp(ou, "ambiance haute", x.upperBackSfxMask, y.upperBackSfxMask);
            cmp(ou, "teleporteur", x.telZone, y.telZone);
            cmp(ou, "teleporteur x", x.telX, y.telX);
            cmp(ou, "teleporteur z", x.telZ, y.telZ);
            cmp(ou, "longueur de la liste d'aretes", x.edgeList.length, y.edgeList.length);
            for (int k = 0; k < Math.min(x.edgeList.length, y.edgeList.length); k++) {
                cmp(ou, "arete " + k, x.edgeList[k], y.edgeList[k]);
            }
        }

        cmp(lettre, "nombre d'aretes", a.edgeX.length, b.edgeX.length);
        for (int e = 0; e < Math.min(a.edgeX.length, b.edgeX.length); e++) {
            String ou = lettre + " arete " + e;
            cmp(ou, "x", a.edgeX[e], b.edgeX[e]);
            cmp(ou, "z", a.edgeZ[e], b.edgeZ[e]);
            cmp(ou, "dx", a.edgeDx[e], b.edgeDx[e]);
            cmp(ou, "dz", a.edgeDz[e], b.edgeDz[e]);
            cmp(ou, "secteur joint", a.edgeJoin[e], b.edgeJoin[e]);
            cmp(ou, "separateur", a.edgeW5[e], b.edgeW5[e]);
            cmp(ou, "normale x", a.edgeB12[e], b.edgeB12[e]);
            cmp(ou, "normale z", a.edgeB13[e], b.edgeB13[e]);
        }

        // Les ENTITES : c'est par leur rang que tout se tient — un monstre, un objet, une
        // balle et l'arme en main occupent des rangs de la meme table.
        cmp(lettre, "nombre d'entites", json.objects == null ? 0 : json.objects.size(),
                lu.entities().size());

        System.out.printf("  niveau %c : %d secteurs, %d aretes, %d entites%s%n",
                lettre, b.zones.length, b.edgeX.length, lu.entities().size(),
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
