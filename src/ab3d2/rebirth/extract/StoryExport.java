package ab3d2.rebirth.extract;

import ab3d2.Mem;
import ab3d2.data.DrawData;
import ab3d2.data.TextData;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * Extraction des TEXTES NARRATIFS et de leur police.
 *
 * <p>Deux blocs, au meme format : des enregistrements de 82 octets — octet 0 = index de police
 * (toujours 0), octet 1 = centrer, puis 80 caracteres.
 * <ul>
 *   <li>{@code INCLUDES/TEXT_FILE} (20992 o) : le texte d'INTRODUCTION, 16 lignes par niveau
 *       pour les 16 niveaux, affiche avant de jouer ({@code Game_ShowIntroText}, TWEENTEXT) ;</li>
 *   <li>{@code Game_SinglePlayerVictoryText_vb} (137 lignes, data/text_data.s) : le texte de
 *       FIN, la sortie du heros puis les credits.</li>
 * </ul>
 *
 * <p>La police est {@code endfont0} : 96 glyphes (caracteres 32 a 127) de 16 lignes, un mot par
 * ligne, le glyphe etant cale a DROITE dans le mot sur sa largeur propre, donnee par
 * {@code charwidths0}. Le jeu la rend dans un ecran de 640 pixels de large.
 *
 * <p>Sorties dans {@code assets/menu/} : {@code endfont.png} (atlas 16x6 glyphes),
 * {@code endfont.json} (les 96 largeurs) et {@code story.json} (les deux blocs de texte).
 */
public final class StoryExport {

    /** Un enregistrement de texte. */
    private static final int RECORD = 82;
    /** Lignes par niveau dans TEXT_FILE. */
    private static final int LINES_PER_LEVEL = 16;
    private static final int LEVELS = 16;
    /** La police : 96 glyphes de 16x16, 32 octets chacun. */
    private static final int GLYPHS = 96;
    private static final int GLYPH_W = 16;
    private static final int GLYPH_H = 16;
    private static final int ATLAS_COLS = 16;

    private StoryExport() {
    }

    public static void extract(Path outRoot) throws IOException {
        loadStoryFile();
        Path dir = outRoot.resolve("menu");
        Files.createDirectories(dir);
        int[] widths = writeFont(dir);
        writeStory(dir, widths);
    }

    /**
     * Charge {@code ab3:includes/TEXT_FILE} comme le fait {@code Game_Start} : c'est lui qui
     * porte le texte d'introduction des seize niveaux.
     */
    private static void loadStoryFile() {
        if (Mem.l(ab3d2.ControlloopData.Lvl_IntroTextPtr_l) != 0) {
            return;
        }
        try {
            long st = ab3d2.modules.FileIo.IO_LoadFile(ab3d2.ControlloopData.Game_StoryFile_vb);
            Mem.wl(ab3d2.ControlloopData.Lvl_IntroTextPtr_l, ab3d2.modules.FileIo.addr(st));
        } catch (Throwable t) {
            System.out.println("[story] TEXT_FILE absent (" + t + ")");
        }
    }

    /** L'atlas de la police de fin, plus la table de largeurs. */
    private static int[] writeFont(Path dir) throws IOException {
        int font = DrawData.draw_EndFont0_vb;
        int cw = DrawData.draw_CharWidths0_vb;
        int[] widths = new int[GLYPHS];
        int rows = (GLYPHS + ATLAS_COLS - 1) / ATLAS_COLS;
        BufferedImage img = new BufferedImage(ATLAS_COLS * GLYPH_W, rows * GLYPH_H,
                BufferedImage.TYPE_INT_ARGB);
        for (int g = 0; g < GLYPHS; g++) {
            int w = Mem.ub(cw + g);
            widths[g] = w;
            int ox = (g % ATLAS_COLS) * GLYPH_W;
            int oy = (g / ATLAS_COLS) * GLYPH_H;
            for (int row = 0; row < GLYPH_H; row++) {
                int word = Mem.uw(font + g * 32 + row * 2);
                for (int px = 0; px < w && px < GLYPH_W; px++) {
                    // Le glyphe est cale a DROITE dans le mot : le pixel px est le bit (w-1-px).
                    boolean on = ((word >> (w - 1 - px)) & 1) != 0;
                    img.setRGB(ox + px, oy + row, on ? 0xFFFFFFFF : 0);
                }
            }
        }
        ImageIO.write(img, "png", dir.resolve("endfont.png").toFile());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("glyphWidth", GLYPH_W);
        meta.put("glyphHeight", GLYPH_H);
        meta.put("columns", ATLAS_COLS);
        meta.put("firstChar", 32);
        meta.put("widths", box(widths));
        Files.writeString(dir.resolve("endfont.json"), json(meta));
        System.out.printf("[story] endfont.png (%dx%d, %d glyphes) + endfont.json%n",
                img.getWidth(), img.getHeight(), GLYPHS);
        return widths;
    }

    /** Les deux blocs de texte. */
    private static void writeStory(Path dir, int[] widths) throws IOException {
        int textBase = Mem.l(ab3d2.ControlloopData.Lvl_IntroTextPtr_l);
        List<Object> levels = new ArrayList<>();
        if (textBase == 0) {
            System.out.println("[story] TEXT_FILE non charge : pas de texte d'introduction");
        } else {
            for (int lvl = 0; lvl < LEVELS; lvl++) {
                List<Object> lines = new ArrayList<>();
                for (int i = 0; i < LINES_PER_LEVEL; i++) {
                    lines.add(record(textBase + (lvl * LINES_PER_LEVEL + i) * RECORD));
                }
                levels.add(lines);
            }
        }

        // Le texte de fin va de Game_SinglePlayerVictoryText_vb jusqu'a ENDENDGAMETEXT.
        int from = TextData.Game_SinglePlayerVictoryText_vb;
        int to = TextData.ENDENDGAMETEXT;
        int count = (to - from) / RECORD;
        List<Object> end = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            end.add(record(from + i * RECORD));
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intro", levels);
        m.put("end", end);
        Files.writeString(dir.resolve("story.json"), json(m));
        System.out.printf("[story] story.json (%d niveaux x %d lignes d'intro, %d lignes de fin)%n",
                levels.size(), LINES_PER_LEVEL, count);
    }

    /** Un enregistrement de 82 octets -&gt; {centre, texte}. */
    private static Map<String, Object> record(int at) {
        boolean centred = Mem.ub(at + 1) != 0;
        StringBuilder b = new StringBuilder(80);
        for (int i = 0; i < 80; i++) {
            int c = Mem.ub(at + 2 + i);
            if (c == 0) {
                break;                                     // fin de ligne
            }
            b.append(c < 32 || c > 127 ? ' ' : (char) c);  // non imprimable -> espace
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("centred", centred);
        r.put("text", stripTrailing(b.toString()));
        return r;
    }

    private static String stripTrailing(String s) {
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == ' ') {
            n--;
        }
        return s.substring(0, n);
    }

    private static List<Integer> box(int[] a) {
        List<Integer> l = new ArrayList<>(a.length);
        for (int v : a) {
            l.add(v);
        }
        return l;
    }

    private static String json(Object o) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .create().toJson(o);
    }
}
