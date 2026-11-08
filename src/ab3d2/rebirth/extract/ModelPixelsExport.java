package ab3d2.rebirth.extract;

import ab3d2.Mem;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle des PIXELS d'un modele en relief : les quatre remplisseurs de polygones.
 *
 * <p>Dernier maillon de la chaine, apres les points, les faces et les bords. Chaque colonne
 * peinte est relevee telle qu'elle sort du remplisseur : ou elle commence, combien de lignes
 * elle couvre, et les octets qu'elle laisse.
 *
 * <h2>Le motif de depart</h2>
 *
 * <p>L'ecran recoit un motif connu AVANT CHAQUE OBJET, et non une seule fois par vue. Deux
 * raisons : le miroitement lit le pixel deja pose, de sorte qu'un fond uniforme ne
 * l'eprouverait sur rien ; et remettre le motif a chaque objet rend chaque objet independant
 * des precedents, ce qui dispense la verification de reproduire un ordre de dessin.
 *
 * <p>A l'interieur d'un objet, en revanche, les faces se recouvrent sans que rien ne soit
 * remis a zero — c'est exactement ce que le jeu fait, et c'est ce que le miroitement exige.
 *
 * <h2>La fenetre change d'une vue a l'autre</h2>
 *
 * <p>Comme pour les bords, et pour la meme raison, mais cette fois dans les DEUX sens : le
 * decoupage des remplisseurs est vertical.
 *
 * <h2>L'oeil est pose DEVANT chaque modele</h2>
 *
 * <p>Les oracles precedents semaient l'oeil aux points de vue du niveau, et cela suffisait
 * tant qu'on ne relevait que des nombres. Pour des pixels, non : a ces distances-la les
 * modeles tiennent en deux lignes, et un remplisseur qui ne remplit rien ne prouve rien. On
 * place donc l'oeil a quelques pas DEVANT le modele, a trois distances, avec un pas de cote
 * pour qu'il ne soit pas toujours au centre. Un modele par vue, et la vue est faite pour lui.
 */
public final class ModelPixelsExport {

    /** On garde une entite sur dix-sept : un releve de pixels pese bien plus qu'un de bords. */
    private static final int UNE_SUR = 17;

    /** A quelle distance l'oeil se pose devant le modele. */
    private static final int[] DISTANCES = {60, 200, 600};

    /** De combien il s'ecarte de l'axe, pour que le modele ne soit pas toujours au centre. */
    private static final int[] COTES = {0, 90, -140};

    /** De combien l'oeil monte au-dessus du modele. */
    private static final int[] HAUTEURS = {0, 6000};

    /** Les fenetres de decoupe, prises a tour de role d'une vue a l'autre. */
    private static final int[][] FENETRES = {
        {0, 320, 0, 240}, {37, 281, 24, 212}, {96, 320, 0, 168}, {0, 224, 61, 240},
    };

    private ModelPixelsExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        ab3d2.Hires.Setup_ConstantTable();
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, 0);

        Base64.Encoder encodeur = Base64.getEncoder();
        List<Object> cas = new ArrayList<>();
        Map<String, Object> champsParNiveau = new LinkedHashMap<>();
        long colonnes = 0;
        long pixels = 0;
        for (char c = 'a'; c <= 'p'; c++) {
            try {
                LevelExport.loadLevelForTools(c - 'a');
            } catch (Throwable t) {
                System.out.println("  niveau " + c + " : ÉCHEC " + t);
                continue;
            }
            if (Mem.l(ab3d2.bss.VidBss.Vid_FastBufferPtr_l) == 0) {
                throw new IllegalStateException("pas de tampon d'écran : rien à relever");
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
            int fenetre = 0;
            int pts = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
            for (int e = 0; e < n; e += UNE_SUR) {
                int pt = Mem.uw(objets + e * ab3d2.Defs.ObjT_SizeOf_l);
                int objX = Mem.w(pts + pt * 8);
                int objZ = Mem.w(pts + pt * 8 + 4);
                // L'oeil se met a la HAUTEUR du modele : la semaille les a poses tres haut ou
                // tres bas, et de pres cela les sortirait de l'ecran a tout coup.
                int base = Mem.w(objets + e * ab3d2.Defs.ObjT_SizeOf_l + 4) * 128;
                for (int d = 0; d < DISTANCES.length; d++) {
                    int[] f = FENETRES[fenetre++ % FENETRES.length];
                    for (int angle : ModelPointsExport.ANGLES) {
                        int[] oeil = devant(objX, objZ, angle, DISTANCES[d], COTES[d]);
                        ModelPointsExport.tourne(oeil[0], oeil[1], angle);
                        for (int haut : HAUTEURS) {
                            int y = base + haut;
                            Map<String, Object> un = unCas(String.valueOf(c), zone, e,
                                    angle, y, oeil[0], oeil[1], f, encodeur);
                            cas.add(un);
                            for (Object o : (List<?>) un.get("models")) {
                                for (Object q : (List<?>) ((Map<?, ?>) o).get("columns")) {
                                    ici++;
                                    pixels += ((Number) ((List<?>) q).get(2)).intValue();
                                }
                            }
                        }
                    }
                }
            }
            colonnes += ici;
            System.out.printf("  niveau %s : %d entités, secteur %d, %d colonnes peintes%n",
                    c, n, zone, ici);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("objects", champsParNiveau);
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("modelpixels.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[modelpixels] %d vues, %d colonnes, %d pixels → %s%n",
                cas.size(), colonnes, pixels, f);
    }

    /**
     * Ou poser l'oeil pour voir un modele de face, a la distance voulue.
     *
     * <p>On recule le long de l'axe de vue, puis on s'ecarte perpendiculairement. Les deux
     * deplacements se font dans la meme unite que les points du niveau.
     */
    private static int[] devant(int objX, int objZ, int angle, int loin, int cote) {
        int tour = 8192;
        int sin = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + (angle & (tour - 2)));
        int cosAt = (angle + ab3d2.data.TablesData.COSINE_OFS) & (tour - 2);
        int cos = Mem.w(ab3d2.data.TablesData.SinCosTable_vw + cosAt);
        int x = objX - (int) ((long) loin * sin >> 15) + (int) ((long) cote * cos >> 15);
        int z = objZ - (int) ((long) loin * cos >> 15) - (int) ((long) cote * sin >> 15);
        return new int[] {x, z};
    }

    /** Un cas : la vue posee, la fenetre, puis les colonnes du modele vise. */
    private static Map<String, Object> unCas(String lettre, int zone, int vise,
            int angle, int y, int x, int z, int[] f, Base64.Encoder encodeur) {
        ModelPointsExport.cadre(y);
        Mem.ww(ab3d2.HireswallData.Draw_LeftClip_w, f[0]);
        Mem.ww(ab3d2.HireswallData.Draw_RightClip_w, f[1]);
        Mem.ww(ab3d2.HireswallData.draw_TopClip_w, f[2]);
        Mem.ww(ab3d2.HireswallData.draw_BottomClip_w, f[3]);
        int buffer = Mem.l(ab3d2.bss.VidBss.Vid_FastBufferPtr_l);

        List<Object> modeles = new ArrayList<>();
        for (int e = vise; e == vise; e++) {
            motif(buffer);
            ab3d2.tools.ModelPixels.reset();
            ab3d2.tools.ModelPixels.capturing = true;
            try {
                ab3d2.Objdrawhires.draw_Object(e);
            } finally {
                ab3d2.tools.ModelPixels.capturing = false;
            }
            Map<String, Object> un = new LinkedHashMap<>();
            un.put("entity", e);
            List<Object> cols = new ArrayList<>();
            List<Object> octets = new ArrayList<>();
            for (int i = 0; i < ab3d2.tools.ModelPixels.columns.size(); i++) {
                int[] q = ab3d2.tools.ModelPixels.columns.get(i);
                List<Object> une = new ArrayList<>(3);
                une.add(q[0]);
                une.add(q[1]);
                une.add(q[2]);
                cols.add(une);
                octets.add(encodeur.encodeToString(ab3d2.tools.ModelPixels.bytes.get(i)));
            }
            un.put("columns", cols);
            un.put("pixels", octets);
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
        cas.put("leftClip", f[0]);
        cas.put("rightClip", f[1]);
        cas.put("topClip", f[2]);
        cas.put("botClip", f[3]);
        cas.put("models", modeles);
        return cas;
    }

    /** Le motif de depart, fonction des coordonnees absolues de l'ecran. */
    static void motif(int buffer) {
        for (int y = 0; y < ab3d2.Hires.FS_HEIGHT; y++) {
            int ligne = buffer + y * ab3d2.Hires.SCREEN_WIDTH;
            for (int x = 0; x < ab3d2.Hires.SCREEN_WIDTH; x++) {
                Mem.wb(ligne + x, (x * 7 + y * 13) & 0xFF);
            }
        }
    }
}
