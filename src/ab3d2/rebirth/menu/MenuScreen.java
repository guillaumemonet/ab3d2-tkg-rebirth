package ab3d2.rebirth.menu;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;

import ab3d2.rebirth.Assets;

/**
 * L'ECRAN du menu : le fond qui defile, le feu, et le texte.
 *
 * <p>Le jeu compose huit plans de bits en un index de palette et affiche le tout en 320x256
 * ({@code Vid_PresentMenu}). Ici on garde ce calcul a l'identique — c'est lui qui donne au feu
 * sa couleur, puisque la teinte d'une flamme depend AUSSI du fond qu'elle recouvre — mais on le
 * separe en deux couches a l'affichage :
 * <ul>
 *   <li>le fond et le feu (bits 0 a 4) partent dans une texture de 320x256 etiree a la fenetre
 *       en filtrage LINEAIRE : ce sont des degrades, ils n'ont rien a gagner a rester crenele ;</li>
 *   <li>le texte (bits 5 a 7) est redessine par-dessus, glyphe par glyphe, depuis l'atlas de
 *       police en filtrage AU PLUS PROCHE : il reste net a n'importe quelle taille.</li>
 * </ul>
 * Les deux couches montrent la meme chose que l'original : le texte l'emporte sur le feu dans la
 * palette composite, donc le redessiner par-dessus est equivalent. Et comme le feu lit les plans
 * de POLICE comme source, les lettres brulent exactement comme avant.
 */
public final class MenuScreen {

    private static final int W = MenuFire.SCREEN_W;
    private static final int H = MenuFire.SCREEN_H;
    /** L'atlas de police : 20 glyphes par rangee, 16x16 chacun, a partir du caractere 32. */
    private static final int GLYPH = 16;
    private static final int GLYPHS_PER_ROW = 20;
    private static final int ATLAS_W = 320;
    private static final int ATLAS_H = 176;
    /** Hauteur d'une ligne de texte ({@code mnu_printxy} : saut de ligne = 20 pixels). */
    public static final int LINE_HEIGHT = 20;

    private final Node node = new Node("menu");
    private final MenuFire fire;
    private final byte[] fontPlanes;
    private final byte[] creditPlanes;
    private final int[] palette;
    private final byte[] indexBuf = new byte[W * H];
    private final ByteBuffer pixels;
    private final Texture2D surface;
    private final Geometry back;
    private final Geometry text;
    private final Material textMat;
    private final float scaleX;
    private final float scaleY;
    /** Glyphes poses, pour redessiner la couche nette : {glyphe, x en octets, y en pixels}. */
    private final List<int[]> placed = new ArrayList<>();
    private boolean textDirty = true;
    private float fade = 1f;
    private int cursorGlyph = -1;
    private int cursorX = -1;
    private int cursorY;

    private MenuScreen(AssetManager assets, Node gui, int width, int height,
                       byte[] backPlanes, byte[] fontPlanes, byte[] creditPlanes, int[] palette) {
        this.fontPlanes = fontPlanes;
        this.creditPlanes = creditPlanes;
        this.palette = palette;
        this.fire = new MenuFire(backPlanes);
        this.scaleX = width / (float) W;
        this.scaleY = height / (float) H;

        pixels = BufferUtils.createByteBuffer(W * H * 4);
        Image img = new Image(Image.Format.RGBA8, W, H, pixels, com.jme3.texture.image.ColorSpace.sRGB);
        surface = new Texture2D(img);
        // Le fond et le feu sont des degrades : le filtrage lineaire les agrandit sans creneler.
        surface.setMagFilter(Texture.MagFilter.Bilinear);
        surface.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        surface.setWrap(Texture.WrapMode.EdgeClamp);

        Material bm = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        bm.setTexture("ColorMap", surface);
        bm.setColor("Color", ColorRGBA.White);
        bm.getAdditionalRenderState().setDepthTest(false);
        bm.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        back = new Geometry("menu_fond", fullQuad(width, height));
        back.setMaterial(bm);

        Texture2D atlas = Assets.texture("menu/font_rgba.png");
        textMat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        textMat.setTexture("ColorMap", atlas);
        textMat.setColor("Color", ColorRGBA.White);
        textMat.setFloat("AlphaDiscardThreshold", 0.4f);
        textMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        textMat.getAdditionalRenderState().setDepthTest(false);
        textMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        text = new Geometry("menu_texte", new Mesh());
        text.setMaterial(textMat);

        node.attachChild(back);
        node.attachChild(text);
        node.setQueueBucket(RenderQueue.Bucket.Gui);
        node.setCullHint(Spatial.CullHint.Always);
        gui.attachChild(node);
    }

    /** Charge les images du menu ; {@code null} si l'extraction n'a pas ete faite. */
    public static MenuScreen create(AssetManager assets, Node gui, int width, int height) {
        BufferedImage bg = Assets.image("menu/background.png");
        BufferedImage ft = Assets.image("menu/font.png");
        BufferedImage cr = Assets.image("menu/credits.png");
        BufferedImage pl = Assets.image("menu/palette.png");
        if (bg == null || ft == null || cr == null || pl == null) {
            System.out.println("[Menu] images absentes (gradle -p rebirth extract -Pwhat=menu)");
            return null;
        }
        int[] pal = new int[256];
        for (int i = 0; i < 256; i++) {
            pal[i] = pl.getRGB(i, 0) & 0xFFFFFF;
        }
        return new MenuScreen(assets, gui, width, height,
                toPlanes(bg, 2), toPlanes(ft, 3), toPlanes(cr, 3), pal);
    }

    /**
     * Index -> plans de bits. L'extraction ecrit l'index dans le canal ROUGE, un octet par
     * pixel ; le moteur du feu, lui, travaille sur des plans comme le materiel d'origine.
     */
    private static byte[] toPlanes(BufferedImage img, int planes) {
        int w = img.getWidth();
        int h = img.getHeight();
        int rowBytes = w / 8;
        int planeSize = rowBytes * h;
        byte[] out = new byte[planes * planeSize];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = (img.getRGB(x, y) >> 16) & 0xFF;
                if (idx == 0) {
                    continue;
                }
                int at = y * rowBytes + (x >> 3);
                int bit = 1 << (7 - (x & 7));
                for (int p = 0; p < planes; p++) {
                    if ((idx & (1 << p)) != 0) {
                        out[p * planeSize + at] |= (byte) bit;
                    }
                }
            }
        }
        return out;
    }

    public void setVisible(boolean on) {
        node.setCullHint(on ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
    }

    public void detach() {
        node.removeFromParent();
    }

    /** Facteur de fondu, 0 (noir) a 1 ({@code mnu_fadefactor} / 256). */
    public void setFade(float f) {
        fade = Math.max(0f, Math.min(1f, f));
    }

    public float fade() {
        return fade;
    }

    /** {@code mnu_cls} : efface le texte, plans ET couche nette. */
    public void clearText() {
        fire.clearText();
        placed.clear();
        cursorGlyph = -1;
        cursorX = -1;
        textDirty = true;
    }

    /**
     * {@code mnu_printxy} sans la machine a ecrire : pose une chaine a {@code xByte} octets
     * (1 octet = 8 pixels) et {@code y} pixels. Un caractere de code &lt; 32 passe a la ligne.
     */
    public void print(String s, int xByte, int y) {
        int x = xByte;
        int lineY = y;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i) & 0xFF;
            int g = c - 32;
            if (g < 0) {                                   // saut de ligne
                lineY += LINE_HEIGHT;
                x = xByte;
                continue;
            }
            fire.putGlyph(fontPlanes, g, x, lineY);
            placed.add(new int[] { g, x, lineY });
            x += 2;                                        // un glyphe = 16 pixels = 2 octets
        }
        textDirty = true;
    }

    /** Pose un seul glyphe par son code brut (curseur anime, glyphes de touches). */
    public void putRaw(int code, int xByte, int y) {
        int g = (code & 0xFF) - 32;
        if (g < 0) {
            return;
        }
        fire.putGlyph(fontPlanes, g, xByte, y);
        placed.add(new int[] { g, xByte, y });
        textDirty = true;
    }

    /**
     * Pose le CURSEUR ({@code mnu_docursor}) : le jeu reimprime le glyphe de la fleche a chaque
     * frame a la ligne courante, et un espace a l'ancienne des qu'elle change. On fait pareil
     * dans les plans — le curseur brule comme le reste — mais la couche nette le garde a part,
     * pour ne pas accumuler un glyphe par frame.
     *
     * @param code code de caractere du glyphe (0 = pas de curseur)
     */
    public void setCursor(int code, int xByte, int y) {
        if (cursorX >= 0 && (cursorX != xByte || cursorY != y)) {
            fire.putGlyph(fontPlanes, ' ' - 32, cursorX, cursorY);   // mnu_cleararrow
        }
        if (code == 0) {
            cursorGlyph = -1;
            cursorX = -1;
            textDirty = true;
            return;
        }
        int g = (code & 0xFF) - 32;
        fire.putGlyph(fontPlanes, g, xByte, y);
        if (g != cursorGlyph || xByte != cursorX || y != cursorY) {
            textDirty = true;
        }
        cursorGlyph = g;
        cursorX = xByte;
        cursorY = y;
    }

    /** {@code mnu_copycredz} : l'image des credits, posee dans les plans de police. */
    public void credits() {
        fire.putCredits(creditPlanes);
        textDirty = true;                                  // elle n'a pas de couche nette : elle brule
    }

    /** Une frame : le feu avance, la texture est refaite, le texte redessine s'il a change. */
    public void frame() {
        fire.frame();
        fire.composite(indexBuf);
        pixels.clear();
        for (int i = 0; i < indexBuf.length; i++) {
            // Le texte (bits 5 a 7) est redessine net par-dessus : on le RETIRE de la couche
            // du fond, sans quoi sa version etiree transparaitrait derriere.
            int rgb = palette[indexBuf[i] & 0x1F];
            pixels.put((byte) (rgb >> 16));
            pixels.put((byte) (rgb >> 8));
            pixels.put((byte) rgb);
            pixels.put((byte) 0xFF);
        }
        pixels.flip();
        surface.getImage().setUpdateNeeded();

        ColorRGBA tint = new ColorRGBA(fade, fade, fade, 1f);
        back.getMaterial().setColor("Color", tint);
        textMat.setColor("Color", tint);
        if (textDirty) {
            rebuildText();
            textDirty = false;
        }
    }

    /** Reconstruit la couche de texte nette a partir des glyphes poses. */
    private void rebuildText() {
        List<int[]> all = placed;
        if (cursorGlyph >= 0) {
            all = new ArrayList<>(placed);
            all.add(new int[] { cursorGlyph, cursorX, cursorY });
        }
        int n = all.size();
        float[] pos = new float[n * 18];
        float[] uv = new float[n * 12];
        int p = 0;
        int t = 0;
        for (int[] g : all) {
            float x0 = g[1] * 8 * scaleX;
            float x1 = (g[1] * 8 + GLYPH) * scaleX;
            float y1 = (H - g[2]) * scaleY;                // haut du glyphe (repere GUI, Y vers le haut)
            float y0 = (H - g[2] - GLYPH) * scaleY;
            float u0 = (g[0] % GLYPHS_PER_ROW) * GLYPH / (float) ATLAS_W;
            float u1 = u0 + GLYPH / (float) ATLAS_W;
            float v0 = (g[0] / GLYPHS_PER_ROW) * GLYPH / (float) ATLAS_H;
            float v1 = v0 + GLYPH / (float) ATLAS_H;
            // deux triangles ; v0 est le HAUT de l'image (textures chargees sans retournement)
            p = tri(pos, p, x0, y1, x1, y1, x1, y0);
            t = uvs(uv, t, u0, v0, u1, v0, u1, v1);
            p = tri(pos, p, x0, y1, x1, y0, x0, y0);
            t = uvs(uv, t, u0, v0, u1, v1, u0, v1);
        }
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(pos));
        m.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(uv));
        m.updateBound();
        text.setMesh(m);
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

    private static Mesh fullQuad(int width, int height) {
        Mesh m = new Mesh();
        m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(new float[] {
            0f, height, 0f,  width, height, 0f,  width, 0f, 0f,
            0f, height, 0f,  width, 0f, 0f,      0f, 0f, 0f,
        }));
        m.setBuffer(VertexBuffer.Type.TexCoord, 2, BufferUtils.createFloatBuffer(new float[] {
            0f, 0f,  1f, 0f,  1f, 1f,
            0f, 0f,  1f, 1f,  0f, 1f,
        }));
        m.updateBound();
        return m;
    }

    /** Le compteur de frames du menu ({@code main_counter}), pour les animations. */
    public long counter() {
        return fire.counter();
    }
}
