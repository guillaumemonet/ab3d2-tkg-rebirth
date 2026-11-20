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
 * Oracle des BORDS d'une face : ce que le trace laisse dans les deux tables de colonnes.
 *
 * <p>Troisieme maillon du dessin d'un objet en relief, apres les points et les faces. Chaque
 * arete d'une face retenue est parcourue de gauche a droite et depose, colonne par colonne,
 * l'ordonnee du bord et les coordonnees de texture qui vont avec. C'est ce que les
 * remplisseurs liront.
 *
 * <p>On seme comme les deux oracles precedents, parce que les trois chaines se suivent.
 *
 * <h2>La fenetre change d'une vue a l'autre</h2>
 *
 * <p>En plein ecran, la fenetre de decoupe va de zero a trois cent vingt, et une borne a zero
 * ne prouve rien : tout ce qui la soustrait pourrait aussi bien ne rien soustraire. En jeu,
 * le decoupage par secteur retrecit la fenetre a chaque portail ; l'oracle fait de meme en
 * changeant de fenetre d'une vue a l'autre, de sorte que les deux bornes prennent des valeurs
 * qui ne sont ni zero ni la largeur.
 */
public final class ModelEdgesExport {

    /** On garde une entite sur quatre : un releve de bords ne pese guere plus qu'un de faces. */
    private static final int UNE_SUR = 4;

    /**
     * Les fenetres de decoupe, prises a tour de role d'une vue a l'autre.
     *
     * <p>La premiere est le plein ecran, les autres valent ce qu'un portail laisse voir.
     */
    private static final int[][] FENETRES = {
        {0, 320}, {37, 281}, {96, 320}, {0, 224},
    };

    private ModelEdgesExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        ab3d2.Hires.Setup_ConstantTable();
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, 0);

        List<Object> cas = new ArrayList<>();
        Map<String, Object> champsParNiveau = new LinkedHashMap<>();
        long faces = 0;
        long tracees = 0;
        long colonnes = 0;
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
            int fenetre = 0;
            for (int[] vue : BitmapExport.pointsDeVue()) {
                if (rang++ % ModelPointsExport.VUE_SUR != 0) {
                    continue;
                }
                int[] f = FENETRES[fenetre++ % FENETRES.length];
                for (int angle : ModelPointsExport.ANGLES) {
                    ModelPointsExport.tourne(vue[0], vue[1], angle);
                    for (int y : ModelPointsExport.HAUTEURS) {
                        Map<String, Object> un = unCas(String.valueOf(c), zone, n,
                                angle, y, vue[0], vue[1], f[0], f[1]);
                        cas.add(un);
                        for (Object o : (List<?>) un.get("models")) {
                            for (Object q : (List<?>) ((Map<?, ?>) o).get("faces")) {
                                List<?> une = (List<?>) q;
                                ici++;
                                int span = ((Number) une.get(5)).intValue();
                                if (span >= 0) {
                                    tracees++;
                                    colonnes += span + 1;
                                }
                            }
                        }
                    }
                }
            }
            faces += ici;
            System.out.printf("  niveau %s : %d entités, secteur %d, %d faces tracées%n",
                    c, n, zone, ici);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("objects", champsParNiveau);
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("modeledges.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[modeledges] %d vues, %d faces dont %d peintes, %d colonnes → %s%n",
                cas.size(), faces, tracees, colonnes, f);
    }

    /** Un cas : la vue posee, la fenetre de decoupe, puis les bords de chaque modele. */
    private static Map<String, Object> unCas(String lettre, int zone, int n,
            int angle, int y, int x, int z, int gauche, int droite) {
        ModelPointsExport.cadre(y);
        Mem.ww(ab3d2.HireswallData.Draw_LeftClip_w, gauche);
        Mem.ww(ab3d2.HireswallData.Draw_RightClip_w, droite);
        List<Object> modeles = new ArrayList<>();
        for (int e = 0; e < n; e += UNE_SUR) {
            ab3d2.tools.ModelEdges.reset();
            ab3d2.tools.ModelEdges.stop = true;
            ab3d2.tools.ModelEdges.capturing = true;
            try {
                ab3d2.Objdrawhires.draw_Object(e);
            } finally {
                ab3d2.tools.ModelEdges.capturing = false;
                ab3d2.tools.ModelEdges.done();
            }
            Map<String, Object> un = new LinkedHashMap<>();
            un.put("entity", e);
            List<Object> fs = new ArrayList<>();
            for (int[] q : ab3d2.tools.ModelEdges.faces) {
                List<Object> une = new ArrayList<>(q.length);
                for (int v : q) {
                    une.add(v);
                }
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
        cas.put("leftClip", gauche);
        cas.put("rightClip", droite);
        cas.put("models", modeles);
        return cas;
    }
}
