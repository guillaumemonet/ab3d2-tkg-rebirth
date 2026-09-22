package ab3d2.rebirth;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Quad;

import ab3d2.rebirth.sim.Inventory;

/**
 * Affichage de tête : santé, arme et munitions, messages, éclair de douleur.
 *
 * <p>Le jeu d'origine dessine un bandeau de statut en bas de l'écran (chiffres bitmap du panneau
 * de l'overlay). Le remake étant en plein écran 3D, on rend la même INFORMATION — santé,
 * arme en main, munitions du type de projectile correspondant — avec la police de jME ; le
 * bandeau d'époque pourra le remplacer quand ses graphismes seront extraits.
 */
public final class Hud {

    private final Node node;
    private final BitmapText health;
    private final BitmapText weapon;
    private final BitmapText message;
    private final Geometry flash;
    private final Geometry crosshair;
    private final float width;
    private final float height;

    /** Intensité de l'éclair rouge (0 = rien), décroît toute seule. */
    private float pain;
    /** Voile bleu-vert quand on a la tête (ou le bas de l'écran) sous l'eau. */
    private final Geometry under;
    private int underMode = -1;

    /** Lignes de narration : 4 au plus, la plus ancienne s'efface toutes les 2 secondes. */
    private static final int MSG_LINES = 4;
    private static final float MSG_SCROLL = 2f;        // MSG_SCROLL_PERIOD_MS
    private final BitmapText[] lines = new BitmapText[MSG_LINES];
    private final java.util.ArrayDeque<String> texts = new java.util.ArrayDeque<>();
    private String lastText = "";
    private float lastTextAge;
    private float scroll;

    public Hud(AssetManager assetManager, Node guiNode, float width, float height) {
        this.width = width;
        this.height = height;
        node = new Node("hud");
        guiNode.attachChild(node);
        BitmapFont font = assetManager.loadFont("Interface/Fonts/Default.fnt");

        health = new BitmapText(font);
        health.setSize(font.getCharSet().getRenderedSize() * 1.6f);
        health.setColor(new ColorRGBA(0.85f, 0.95f, 1f, 1f));
        health.setLocalTranslation(24f, health.getLineHeight() + 16f, 0f);
        node.attachChild(health);

        weapon = new BitmapText(font);
        weapon.setSize(font.getCharSet().getRenderedSize() * 1.2f);
        weapon.setColor(new ColorRGBA(0.8f, 0.8f, 0.85f, 1f));
        weapon.setLocalTranslation(24f, health.getLineHeight() + weapon.getLineHeight() + 22f, 0f);
        node.attachChild(weapon);

        message = new BitmapText(font);
        message.setSize(font.getCharSet().getRenderedSize() * 2.2f);
        message.setColor(new ColorRGBA(1f, 0.9f, 0.5f, 1f));
        node.attachChild(message);

        // Eclair de douleur : un voile rouge plein ecran, additif.
        Material red = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        red.setColor("Color", new ColorRGBA(0.7f, 0f, 0f, 0.5f));
        red.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.AlphaAdditive);
        flash = new Geometry("pain", new Quad(width, height));
        flash.setMaterial(red);
        flash.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        node.attachChild(flash);

        for (int i = 0; i < MSG_LINES; i++) {          // la narration, en haut comme dans le jeu
            lines[i] = new BitmapText(font);
            lines[i].setSize(font.getCharSet().getRenderedSize() * 1.1f);
            lines[i].setColor(new ColorRGBA(0.95f, 0.92f, 0.75f, 1f));
            lines[i].setLocalTranslation(24f, height - 20f - i * (lines[i].getLineHeight() + 2f), 0f);
            node.attachChild(lines[i]);
        }

        // Sous l'eau : le jeu repasse l'ecran par un bloc de palette (fillscrnwater) ; faute de
        // post-traitement on superpose la TEINTE MOYENNE de ce bloc, extrait du jeu.
        Material blue = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        blue.setColor("Color", underwaterTint());
        blue.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        under = new Geometry("sous-eau", new Quad(width, height));
        under.setMaterial(blue);
        under.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
        node.attachChild(under);

        // Viseur : deux traits fins au centre (le jeu d'origine dessine l'arme a la place).
        Material white = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        white.setColor("Color", new ColorRGBA(1f, 1f, 1f, 0.35f));
        white.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        crosshair = new Geometry("viseur", new Quad(10f, 2f));
        crosshair.setMaterial(white);
        crosshair.setLocalTranslation(width / 2f - 5f, height / 2f - 1f, 0f);
        node.attachChild(crosshair);
    }

    /** Met à jour les compteurs. {@code gunName} peut être null. */
    /** Le releve chiffre en texte : inutile quand le PANNEAU d'origine est affiche. */
    private boolean textReadout = true;

    /** Detache du graphe : le HUD est rebati quand la taille de fenetre change. */
    public void detach() {
        node.removeFromParent();
    }

    /** Masque tout le HUD (pendant le menu). */
    public void setVisible(boolean on) {
        node.setCullHint(on ? com.jme3.scene.Spatial.CullHint.Inherit
                : com.jme3.scene.Spatial.CullHint.Always);
    }

    public void setTextReadout(boolean on) {
        textReadout = on;
        health.setCullHint(on ? com.jme3.scene.Spatial.CullHint.Inherit
                : com.jme3.scene.Spatial.CullHint.Always);
        weapon.setCullHint(on ? com.jme3.scene.Spatial.CullHint.Inherit
                : com.jme3.scene.Spatial.CullHint.Always);
    }

    public void update(Inventory inv, String gunName, int ammo, float tpf) {
        tickMessages(tpf);
        // Le voile rouge s'efface TOUJOURS : il n'a rien a voir avec le releve en texte, qui est
        // coupe des que le bandeau de statut d'origine est la. Il etait decru apres la sortie
        // anticipee ci-dessous, donc jamais — l'ecran restait rouge pour de bon.
        if (pain > 0f) {
            pain = Math.max(0f, pain - tpf * 2.5f);
            flash.setCullHint(pain > 0f ? com.jme3.scene.Spatial.CullHint.Inherit
                    : com.jme3.scene.Spatial.CullHint.Always);
            flash.getMaterial().setColor("Color", new ColorRGBA(0.7f, 0f, 0f, pain * 0.5f));
        }
        if (!textReadout) {
            return;
        }
        health.setText("SANTE  " + Math.max(0, inv.health())
                + (inv.items[1] != 0 ? "    CARBURANT " + inv.consumables[1] : ""));
        weapon.setText((gunName == null ? "" : gunName.toUpperCase()) + "   MUNITIONS " + ammo);
    }

    /** Teinte moyenne du bloc de palette « sous l'eau » (water_under.lut.png). */
    private static ColorRGBA underwaterTint() {
        java.awt.image.BufferedImage img = Assets.image("textures/water_under.lut.png");
        if (img == null) {
            return new ColorRGBA(0.05f, 0.25f, 0.35f, 0.45f);
        }
        long r = 0;
        long g = 0;
        long b = 0;
        for (int x = 0; x < img.getWidth(); x++) {
            int c = img.getRGB(x, 0);
            r += (c >> 16) & 0xFF;
            g += (c >> 8) & 0xFF;
            b += c & 0xFF;
        }
        int n = img.getWidth() * 255;
        return new ColorRGBA(r / (float) n, g / (float) n, b / (float) n, 0.45f);
    }

    /**
     * Voile sous-marin : 0 = rien, 1 = moitie basse (la surface est a hauteur d'yeux),
     * 2 = plein ecran (on a la tete sous l'eau). Cf. fillscrnwater (Hires.java:3397).
     */
    public void underwater(int mode) {
        if (mode == underMode) {
            return;
        }
        underMode = mode;
        if (mode <= 0) {
            under.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
            return;
        }
        under.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
        under.setMesh(new Quad(width, mode == 1 ? height / 2f : height));
        under.setLocalTranslation(0f, 0f, 0f);
    }

    /**
     * Empile une ligne de narration. Comme {@code Msg_PushLineDedupLast}, une ligne identique
     * a la precedente est ignoree si elle revient dans les 2 secondes.
     */
    public void pushMessage(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String t = text.trim();
        if (t.equals(lastText) && lastTextAge < 2f) {
            return;                                    // MSG_DEDUPLICATION_PERIOD_MS
        }
        lastText = t;
        lastTextAge = 0f;
        texts.addLast(t);
        while (texts.size() > MSG_LINES) {
            texts.removeFirst();
        }
        scroll = 0f;
        refreshLines();
    }

    /** Fait défiler la narration : une ligne toutes les 2 secondes. */
    private void tickMessages(float tpf) {
        lastTextAge += tpf;
        if (texts.isEmpty()) {
            return;
        }
        scroll += tpf;
        if (scroll >= MSG_SCROLL) {
            scroll = 0f;
            texts.removeFirst();
            refreshLines();
        }
    }

    private void refreshLines() {
        int i = 0;
        for (String t : texts) {
            lines[i++].setText(t);
        }
        for (; i < MSG_LINES; i++) {
            lines[i].setText("");
        }
    }

    /** Efface la narration (changement de niveau). */
    public void clearMessages() {
        texts.clear();
        lastText = "";
        refreshLines();
    }

    /** Un coup encaissé : l'écran rougit un instant. */
    public void hurt(int damage) {
        pain = Math.min(1f, pain + damage / 40f);
        flash.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
    }

    /** Message central (null ou vide pour l'effacer). */
    public void message(String text) {
        if (text == null || text.isEmpty()) {
            message.setText("");
            return;
        }
        message.setText(text);
        message.setLocalTranslation((width - message.getLineWidth()) / 2f, height * 0.62f, 0f);
    }
}
