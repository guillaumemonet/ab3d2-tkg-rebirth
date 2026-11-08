package ab3d2.rebirth.extract;

import ab3d2.Mem;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle des FACES d'un modele : qui se peint, et de quelle couleur.
 *
 * <p>C'est la seconde moitie du dessin d'un objet en relief. Les points sont tournes et
 * projetes ; reste a decider, face par face, si elle est a l'ecran, si elle tourne le dos, et
 * dans quelle teinte la peindre.
 *
 * <p>On seme exactement comme l'oracle des points — meme eclairage, memes entites changees en
 * modeles, memes points de vue —, parce que les deux chaines se suivent : sans les points, les
 * faces n'auraient rien a lire.
 *
 * <h2>Ce qui est releve, et ce qui ne l'est pas</h2>
 *
 * <p>Le sort de CHAQUE face l'est, dans l'ordre ou le jeu les visite. La texture et la palette
 * ne le sont que pour les faces qui ont passe le decoupage d'ecran, parce que c'est seulement
 * la que le jeu les calcule ; les autres n'ont pas de teinte a comparer. La verification ne
 * decide donc jamais elle-meme quelles faces teinter : elle teinte celles que l'oracle nomme.
 */
public final class ModelFacesExport {

    /**
     * On garde une entite sur quatre, la ou l'oracle des points en garde une sur dix-sept.
     *
     * <p>Un releve de faces pese bien moins qu'un releve de points — quatre nombres par face
     * contre six par point —, et il faut cette finesse-la : les faces a TROUS sont sept dans
     * tout le jeu et les faces en degrade que le modeleur a voulues sombres sont deux. Au pas
     * de dix-sept, le degrade sombre n'etait jamais atteint.
     */
    private static final int UNE_SUR = 4;

    private ModelFacesExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        ab3d2.Hires.Setup_ConstantTable();
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, 0);

        List<Object> cas = new ArrayList<>();
        Map<String, Object> champsParNiveau = new LinkedHashMap<>();
        long faces = 0;
        long teintes = 0;
        for (char c = 'a'; c <= 'p'; c++) {
            try {
                LevelExport.loadLevelForTools(c - 'a');
            } catch (Throwable t) {
                System.out.println("  niveau " + c + " : ÉCHEC " + t);
                continue;
            }
            int objets = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
            int n = 0;
            while (Mem.w(objets + n * ab3d2.Defs.ObjT_SizeOf_l) >= 0) {
                n++;
            }
            int zone = ModelPointsExport.eclaire(c);
            ModelPointsExport.seme(objets, n, UNE_SUR);
            champsParNiveau.put(String.valueOf(c), ModelPointsExport.champs(objets, n));

            long ici = 0;
            int rang = 0;
            for (int[] vue : BitmapExport.pointsDeVue()) {
                if (rang++ % ModelPointsExport.VUE_SUR != 0) {
                    continue;
                }
                for (int angle : ModelPointsExport.ANGLES) {
                    ModelPointsExport.tourne(vue[0], vue[1], angle);
                    for (int y : ModelPointsExport.HAUTEURS) {
                        Map<String, Object> un = unCas(String.valueOf(c), zone, n,
                                angle, y, vue[0], vue[1]);
                        cas.add(un);
                        for (Object o : (List<?>) un.get("models")) {
                            Object fs = ((Map<?, ?>) o).get("faces");
                            if (fs != null) {
                                for (Object f : (List<?>) fs) {
                                    ici++;
                                    if (((Number) ((List<?>) f).get(2)).intValue() >= 0) {
                                        teintes++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            faces += ici;
            System.out.printf("  niveau %s : %d entités, secteur %d, %d faces relevées%n",
                    c, n, zone, ici);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("objects", champsParNiveau);
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("modelfaces.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[modelfaces] %d vues, %d faces dont %d teintées → %s%n",
                cas.size(), faces, teintes, f);
    }

    /** Un cas : la vue posee, puis les faces de chaque modele. */
    private static Map<String, Object> unCas(String lettre, int zone, int n,
            int angle, int y, int x, int z) {
        ModelPointsExport.cadre(y);
        List<Object> modeles = new ArrayList<>();
        for (int e = 0; e < n; e += UNE_SUR) {
            ab3d2.tools.ModelFaces.reset();
            ab3d2.tools.ModelFaces.stop = true;
            ab3d2.tools.ModelFaces.capturing = true;
            try {
                ab3d2.Objdrawhires.draw_Object(e);
            } finally {
                ab3d2.tools.ModelFaces.capturing = false;
                ab3d2.tools.ModelFaces.done();
            }
            Map<String, Object> un = new LinkedHashMap<>();
            un.put("entity", e);
            List<Object> fs = new ArrayList<>();
            for (int[] q : ab3d2.tools.ModelFaces.faces) {
                List<Object> une = new ArrayList<>();
                une.add(q[0]);
                une.add(q[1]);
                une.add(q[2]);
                une.add(q[3]);
                fs.add(une);
            }
            un.put("faces", fs);
            modeles.add(un);
        }

        Map<String, Object> cas = new LinkedHashMap<>();
        cas.put("level", lettre);
        cas.put("zone", zone);
        cas.put("frames", ModelPointsExport.IMAGES);
        cas.put("lights", new ArrayList<>(ModelPointsExport.LUMIERES));
        cas.put("x", x);
        cas.put("z", z);
        cas.put("angle", angle);
        cas.put("eyeY", y);
        cas.put("centreX", ab3d2.Hires.FS_WIDTH / 2);
        cas.put("centreY", ab3d2.Hires.FS_HEIGHT / 2);
        cas.put("models", modeles);
        return cas;
    }
}
