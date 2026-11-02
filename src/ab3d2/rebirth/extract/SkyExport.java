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
 * Oracle du CIEL : le fond que le rendu pose avant toute chose.
 *
 * <p>Le ciel ne se projette pas. C'est une bande de six cent quarante-huit colonnes sur deux
 * cent quarante lignes, rangée COLONNE par colonne, dont l'orientation du joueur choisit le
 * point de départ ; le rendu en recopie trois cent vingt colonnes à la file, en bouclant sur la
 * fin de l'image. Aucune distance, aucune ombre, aucun découpage : une recopie, et c'est tout.
 *
 * <p>Deux choses décident de ce qu'on voit, et le corpus les fait varier toutes les deux :
 * l'ANGLE, qui donne la colonne de départ — masqué à douze bits, de sorte que l'image fait DEUX
 * tours pendant que le joueur en fait un —, et l'HORIZON, qui donne à la fois la hauteur
 * dessinée et le décalage vertical dans l'image.
 *
 * <p>Il n'y a rien à relever en cours de route : on vide le tampon, on dessine, et on prend
 * les lignes écrites telles quelles. On en prend QUATRE DE PLUS, qui doivent être restées
 * vierges : sans elles, un rendu qui descendrait d'un groupe trop bas passerait inaperçu.
 */
public final class SkyExport {

    /** La largeur de l'image, en colonnes. */
    private static final int SKY_W = ab3d2.Defs.SKY_BACKDROP_W;

    /** La hauteur d'une colonne, en octets. */
    private static final int SKY_H = ab3d2.Defs.SKY_BACKDROP_H;

    /**
     * Les horizons relevés.
     *
     * <p>Les quatre premiers ne dessinent qu'un groupe de quatre lignes, mais ce sont eux qui
     * font varier le reste de la division par quatre — c'est-à-dire de combien d'octets la
     * dernière colonne avant le bouclage déborde de l'image.
     */
    private static final int[] HORIZONS = {0, 1, 2, 3, 40, 119, 120, 200, 239};

    /** Le pas entre deux orientations : premier avec huit mille, donc sans retour. */
    private static final int PAS = 331;

    /** Combien d'orientations. */
    private static final int ORIENTATIONS = 24;

    /** Combien de lignes relevées SOUS le ciel, qui doivent rester vierges. */
    private static final int GARDE = 4;

    private SkyExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        LevelExport.loadLevelForTools(0);

        // Le ciel n'est PAS chargé par bootWithAssets : on le demande ici comme Game_Start le
        // fait, et après le niveau, qui libère ce pointeur en passant.
        ab3d2.modules.FileIo.IO_InitQueue();
        ab3d2.modules.FileIo.IO_QueueFile(ab3d2.data.DrawData.draw_BackdropImageName_vb,
                ab3d2.bss.DrawBss.Draw_BackdropImagePtr_l, 0);
        ab3d2.modules.FileIo.IO_FlushQueue();

        int image = Mem.l(ab3d2.bss.DrawBss.Draw_BackdropImagePtr_l);
        if (image == 0) {
            throw new IllegalStateException("ciel introuvable : ab3:includes/rawbackpacked");
        }
        int buffer = Mem.l(ab3d2.bss.VidBss.Vid_FastBufferPtr_l);
        if (buffer == 0) {
            throw new IllegalStateException("pas de tampon d'écran : rien à relever");
        }

        // La dernière colonne avant le bouclage lit UN A QUATRE octets après la fin de
        // l'image : le pas de quatre lignes ne tombe pas juste sur la hauteur d'une colonne.
        // L'original y prend ce que l'allocateur avait laissé ; on y met zéro, des deux côtés.
        Mem.wl(image + SKY_W * SKY_H, 0);

        // Le ciel peut être éteint secteur par secteur, mais aucun niveau ne porte le fichier
        // qui le dirait : la table reste vide. On s'en assure.
        for (int i = 0; i < ab3d2.bss.ZoneBss.ZONE_BACKDROP_DISABLE_SIZE; i++) {
            Mem.wb(ab3d2.bss.ZoneBss.Zone_BackdropDisable_vb + i, 0);
        }

        Mem.wb(ab3d2.bss.VidBss.Vid_FullScreen_b, 0xFF);

        List<Object> cas = new ArrayList<>();
        Base64.Encoder encodeur = Base64.getEncoder();
        long octets = 0;
        for (int horizon : HORIZONS) {
            for (int i = 0; i < ORIENTATIONS; i++) {
                int angle = (i * PAS) & 8191;
                Mem.ww(ab3d2.HireswallData.Vid_CentreY_w, horizon);
                Mem.ww(ab3d2.NewanimsData.tmpangpos, angle);
                int lignes = 4 * ((horizon >> 2) + 1);
                int releve = lignes + GARDE;
                for (int p = 0; p < releve * ab3d2.Hires.SCREEN_WIDTH; p++) {
                    Mem.wb(buffer + p, 0);
                }

                ab3d2.Newanims.Draw_SkyBackdrop(0);

                byte[] pixels = new byte[releve * ab3d2.Hires.SCREEN_WIDTH];
                for (int p = 0; p < pixels.length; p++) {
                    pixels[p] = (byte) Mem.ub(buffer + p);
                }
                octets += pixels.length;

                Map<String, Object> un = new LinkedHashMap<>();
                un.put("angle", angle);
                un.put("centreY", horizon);
                un.put("lines", lignes);
                un.put("guard", GARDE);
                un.put("column", ((angle & 4095) * SKY_W >> 12));
                un.put("pixels", encodeur.encodeToString(pixels));
                cas.add(un);
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("width", SKY_W);
        root.put("height", SKY_H);
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("sky.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[sky] %d poses, %d octets de pixels → %s%n", cas.size(), octets, f);
    }
}
