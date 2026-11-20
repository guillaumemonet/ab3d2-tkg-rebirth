package ab3d2.rebirth.menu;

import java.util.List;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;

import ab3d2.rebirth.Assets;

/**
 * Les ECRANS DE TEXTE : l'introduction de chaque niveau et le texte de fin.
 *
 * <p>L'introduction est {@code Game_ShowIntroText} (TWEENTEXT) : seize lignes de la police
 * proportionnelle {@code endfont0}, rendues dans un ecran de 640 pixels de large, fondu sur
 * seize frames puis attente d'une validation. Le portage les rabattait ensuite en 320 ; ici on
 * garde les 640 et on agrandit a la fenetre, ce qui ne perd rien.
 *
 * <p>Le texte de FIN est le meme format ({@code Game_SinglePlayerVictoryText_vb}, 137 lignes).
 * Sa routine d'affichage, {@code ENDGAMESCROLL}, est VIDE dans la source d'origine — son corps a
 * ete retire et remplace par « {@code ; text was here} / {@code rts ; better than blundering} »,
 * et l'{@code include "endscroll.s"} est commente. Le texte et la police, eux, sont livres. On
 * le fait donc defiler, ce que le nom de la routine et son voisinage ({@code Game_ClearIntroText}
 * puis le copper de texte) indiquaient.
 */
public final class StoryText {

    /** La largeur logique dans laquelle le jeu compose le texte. */
    private static final int TEXT_W = 640;
    /** Hauteur d'une ligne, et de la police. */
    private static final int LINE_H = 16;
    /** Hauteur de l'ecran d'origine : seize lignes en remplissent un. */
    private static final int SCREEN_H = 256;
    /** Le fondu d'entree de {@code introPresentLoop} : seize frames. */
    private static final int FADE_FRAMES = 16;
    /** Son attente maximale : 400 frames a 50 Hz, soit huit secondes. */
    private static final int INTRO_FRAMES = 400;
    /** Vitesse du defilement de fin, en pixels logiques par frame. */
    private static final float SCROLL_SPEED = 0.5f;

    /** Modele de {@code menu/story.json}. */
    public static final class Story {
        public List<List<Line>> intro;
        public List<Line> end;
    }

    public static final class Line {
        public boolean centred;
        public String text;
    }

    /** Modele de {@code menu/endfont.json}. */
    public static final class Font {
        public int glyphWidth;
        public int glyphHeight;
        public int columns;
        public int firstChar;
        public List<Integer> widths;
    }

    private final Node node = new Node("texte");
    private final Geometry glyphs;
    private final Material mat;
    private final Story story;
    private final Font font;
    private final float scale;
    private final float screenH;

    private int frames;
    private boolean scrolling;
    private float scrollY;
    private float scrollEnd;
    private boolean done;

    private StoryText(AssetManager assets, Node gui, int width, int height,
                      Story story, Font font, Texture2D atlas) {
        this.story = story;
        this.font = font;
        this.scale = width / (float) TEXT_W;
        this.screenH = height;

        mat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setTexture("ColorMap", atlas);
        mat.setColor("Color", ColorRGBA.White);
        mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        mat.getAdditionalRenderState().setDepthTest(false);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        glyphs = new Geometry("texte_glyphes", new Mesh());
        glyphs.setMaterial(mat);

        // Fond NOIR plein : l'ecran de texte d'origine n'a rien d'autre.
        Material bm = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        bm.setColor("Color", ColorRGBA.Black);
        bm.getAdditionalRenderState().setDepthTest(false);
        // L'ordre des sommets d'un quad 2D n'a pas de sens « avant/arriere » : sans ca le
        // quad part en face arriere et disparait.
        bm.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        Geometry back = new Geometry("texte_fond", quad(0, 0, width, height));
        back.setMaterial(bm);
        // Le bucket GUI trie par Z : sans profondeurs distinctes, l'ordre entre le fond et les
        // glyphes n'est pas garanti.
        back.setLocalTranslation(0f, 0f, -1f);
        glyphs.setLocalTranslation(0f, 0f, 0f);

        node.attachChild(back);
        node.attachChild(glyphs);
        node.setQueueBucket(RenderQueue.Bucket.Gui);
        node.setCullHint(Spatial.CullHint.Always);
        gui.attachChild(node);
    }

    /** {@code null} si l'extraction des textes n'a pas ete faite. */
    public static StoryText create(AssetManager assets, Node gui, int width, int height) {
        Story st = Assets.json("menu/story.json", Story.class);
        Font f = Assets.json("menu/endfont.json", Font.class);
        Texture2D atlas = Assets.texture("menu/endfont.png");
        if (st == null || f == null || atlas == null || f.widths == null) {
            System.out.println("[Story] textes absents (gradle -p rebirth extract -Pwhat=story)");
            return null;
        }
        return new StoryText(assets, gui, width, height, st, f, atlas);
    }

    public boolean visible() {
        return node.getCullHint() != Spatial.CullHint.Always;
    }

    public boolean done() {
        return done;
    }

    public void hide() {
        node.setCullHint(Spatial.CullHint.Always);
        done = true;
    }

    public void detach() {
        node.removeFromParent();
    }

    /** L'introduction d'un niveau (0..15) ; {@code false} si ce niveau n'en a pas. */
    public boolean showIntro(int level) {
        if (story.intro == null || level < 0 || level >= story.intro.size()) {
            return false;
        }
        List<Line> lines = story.intro.get(level);
        if (lines == null || lines.stream().allMatch(l -> l.text == null || l.text.isBlank())) {
            return false;                                  // niveau sans narration
        }
        build(lines, 0);
        scrolling = false;
        frames = 0;
        done = false;
        scrollY = 0f;
        glyphs.setLocalTranslation(0f, 0f, 0f);
        node.setCullHint(Spatial.CullHint.Inherit);
        return true;
    }

    /** Le texte de fin, en defilement. */
    public boolean showEnd() {
        if (story.end == null || story.end.isEmpty()) {
            return false;
        }
        // Il commence sous le bas de l'ecran et monte jusqu'a ce que la derniere ligne sorte.
        build(story.end, SCREEN_H);
        scrolling = true;
        frames = 0;
        done = false;
        scrollY = 0f;
        scrollEnd = (story.end.size() + 1) * LINE_H + SCREEN_H;
        glyphs.setLocalTranslation(0f, 0f, 0f);
        node.setCullHint(Spatial.CullHint.Inherit);
        return true;
    }

    /**
     * Une frame. Renvoie vrai tant que l'ecran doit rester affiche.
     *
     * @param validate le joueur a appuye sur une touche de validation
     */
    public boolean frame(boolean validate) {
        if (done) {
            return false;
        }
        frames++;
        float fade = Math.min(1f, frames / (float) FADE_FRAMES);
        mat.setColor("Color", new ColorRGBA(fade, fade, fade, 1f));
        if (scrolling) {
            scrollY += SCROLL_SPEED;
            // Seuls les GLYPHES defilent : le fond noir couvre l'ecran en permanence.
            glyphs.setLocalTranslation(0f, scrollY * scale, 0f);
            if (scrollY >= scrollEnd || (validate && frames > FADE_FRAMES)) {
                hide();
                return false;
            }
            return true;
        }
        // introPresentLoop : apres le fondu, on attend la validation, avec un delai de garde.
        if (frames > FADE_FRAMES && (validate || frames >= INTRO_FRAMES)) {
            hide();
            return false;
        }
        return true;
    }

    /** Compose les lignes en un maillage de glyphes. {@code topOffset} decale tout vers le bas. */
    private void build(List<Line> lines, int topOffset) {
        int count = 0;
        for (Line l : lines) {
            count += l.text == null ? 0 : l.text.length();
        }
        float[] pos = new float[count * 18];
        float[] uv = new float[count * 12];
        int p = 0;
        int t = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (l.text == null || l.text.isEmpty()) {
                continue;
            }
            int y = topOffset + i * LINE_H;
            int x = l.centred ? (TEXT_W - lineWidth(l.text)) / 2 : 0;
            if (x < 0) {
                x = 0;
            }
            for (int c = 0; c < l.text.length(); c++) {
                int g = glyphIndex(l.text.charAt(c));
                int w = width(g);
                if (l.text.charAt(c) != ' ') {
                    float x0 = x * scale;
                    float x1 = (x + font.glyphWidth) * scale;
                    // Repere GUI : Y monte, et l'origine du texte est le HAUT de l'ecran.
                    float y1 = screenH - y * scale;
                    float y0 = screenH - (y + font.glyphHeight) * scale;
                    float u0 = (g % font.columns) * font.glyphWidth / (float) atlasW();
                    float u1 = u0 + font.glyphWidth / (float) atlasW();
                    float v0 = (g / font.columns) * font.glyphHeight / (float) atlasH();
                    float v1 = v0 + font.glyphHeight / (float) atlasH();
                    p = tri(pos, p, x0, y1, x1, y1, x1, y0);
                    t = uvs(uv, t, u0, v0, u1, v0, u1, v1);
                    p = tri(pos, p, x0, y1, x1, y0, x0, y0);
                    t = uvs(uv, t, u0, v0, u1, v1, u0, v1);
                }
                x += w;
            }
        }
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3,
                BufferUtils.createFloatBuffer(java.util.Arrays.copyOf(pos, p)));
        m.setBuffer(VertexBuffer.Type.TexCoord, 2,
                BufferUtils.createFloatBuffer(java.util.Arrays.copyOf(uv, t)));
        m.updateBound();
        glyphs.setMesh(m);
    }

    /** Largeur d'une ligne, en pixels logiques (les espaces de fin sont deja retires). */
    private int lineWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += width(glyphIndex(s.charAt(i)));
        }
        return w;
    }

    private int glyphIndex(char c) {
        int g = c - font.firstChar;
        return g < 0 || g >= font.widths.size() ? 0 : g;    // hors police -> espace
    }

    private int width(int glyph) {
        Integer w = font.widths.get(glyph);
        return w == null ? 0 : w;
    }

    private int atlasW() {
        return font.columns * font.glyphWidth;
    }

    private int atlasH() {
        int rows = (font.widths.size() + font.columns - 1) / font.columns;
        return rows * font.glyphHeight;
    }

    private static Mesh quad(float x0, float y0, float x1, float y1) {
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(new float[] {
            x0, y1, 0f,  x1, y1, 0f,  x1, y0, 0f,
            x0, y1, 0f,  x1, y0, 0f,  x0, y0, 0f,
        }));
        m.updateBound();
        return m;
    }

    private static int tri(float[] a, int i, float x0, float y0, float x1, float y1,
                           float x2, float y2) {
        a[i++] = x0; a[i++] = y0; a[i++] = 0f;
        a[i++] = x1; a[i++] = y1; a[i++] = 0f;
        a[i++] = x2; a[i++] = y2; a[i++] = 0f;
        return i;
    }

    private static int uvs(float[] a, int i, float u0, float v0, float u1, float v1,
                           float u2, float v2) {
        a[i++] = u0; a[i++] = v0;
        a[i++] = u1; a[i++] = v1;
        a[i++] = u2; a[i++] = v2;
        return i;
    }
}
