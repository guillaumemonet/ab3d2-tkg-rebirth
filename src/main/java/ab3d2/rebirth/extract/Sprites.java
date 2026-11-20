package ab3d2.rebirth.extract;

import ab3d2.Defs;
import ab3d2.Mem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extraction des SPRITES (feuilles objets/aliens) vers PNG avec transparence.
 *
 * <p>Format (décodé depuis {@code Objdrawhires} ~548+). Par feuille {@code sh} (0..29) :
 * <ul>
 *   <li>Entrée {@code Draw_ObjectPtrs[sh]} (16 o) : [0]=WAD (pixels), [4]=PTR (table colonnes),
 *       [12]=palette de shade propre à la feuille.</li>
 *   <li>Table de frames {@code GLF + GLFT_FrameData + sh*256 + frame*8} (record 8 o) :
 *       [0..1]=offset table colonnes (×4 dans PTR), [2..3]=downStrip (rangée de départ),
 *       [4..5]=largeur (colonnes), [6..7]=hauteur (rangées).</li>
 *   <li>Colonne = long dans PTR : octet de poids fort = format (0/1/2), 24 bits bas = offset WAD.
 *       Long nul = colonne transparente.</li>
 *   <li>Pixel : {@code tx} = index de rampe 5 bits extrait du WAD selon le format ;
 *       {@code tx==0} = transparent ; sinon index palette globale = {@code palShade[palOff + tx*2]}.</li>
 * </ul>
 */
public final class Sprites {

    /** Décalage de luminosité dans la palette de shade de la feuille (réglable via -PsprPal). */
    static final int PAL_OFFSET = Integer.getInteger("rebirth.sprPal", 0);

    /** Sprites au format HQN (1 octet/pixel, index direct) au lieu du 5 bits packé. */
    private static final java.util.Set<String> HQN = java.util.Set.of(
            "guard", "priest", "insect", "triclaw", "ashnarg", "robotright", "worm", "globe");

    private Sprites() {
    }

    static void extract(Path dir, int[] pal) throws Exception {
        PortReader.bootWithAssets();
        int glf = PortReader.glf();
        int objPtrs = ab3d2.bss.DrawBss.Draw_ObjectPtrs_vl;
        java.util.Set<Integer> additive = additiveSheets(glf);
        int sheets = 0, frames = 0;
        for (int sh = 0; sh < Defs.NUM_OBJECT_DEFS; sh++) {
            String name = PortReader.fixedStr(glf + Defs.GLFT_ObjGfxNames_l + sh * 64, 64);
            if (name.isEmpty()) {
                continue;
            }
            int entry = objPtrs + sh * 16;
            int wad = Mem.l(entry);
            int ptr = Mem.l(entry + 4);
            int palShade = Mem.l(entry + 12);
            if (wad == 0 || ptr == 0 || palShade == 0) {
                continue;
            }
            String sheet = PortReader.baseName(name);
            boolean hqn = HQN.contains(sheet.toLowerCase());
            boolean add = additive.contains(sh);
            // HQN : monstres éclairés → 4 variantes de palette (blocs de 256 : palShade+p*256).
            // p sélectionné en jeu par WhichLightPal = vec-2 (ex. GUARD : p0 vert, p1 bleu, p2 rouge).
            int nPal = hqn ? 4 : 1;
            int wrote = 0;
            for (int fr = 0; fr < 32; fr++) {
                int rec = glf + Defs.GLFT_FrameData_l + sh * 256 + fr * 8;
                int w = Mem.uw(rec + 4) * 2;                 // LW/LH stockés en DEMI-taille (×2 = pixels)
                int h = Mem.uw(rec + 6) * 2;
                if (w <= 0 || h <= 0 || w > 1024 || h > 1024) {
                    continue;
                }
                // NB : une frame peut etre VIDE (toutes ses colonnes transparentes dans le champ
                // que designe leur format) — PICKUPS/28, celle des marqueurs narratifs, est dans
                // ce cas. Le jeu ne dessine rien non plus : decodeFrame renvoie null et on saute.
                int colTable = ptr + Mem.uw(rec) * 4;
                int downStrip = Mem.uw(rec + 2);
                Path sdir = dir.resolve(sheet);
                Files.createDirectories(sdir);
                boolean wroteBase = false;
                for (int p = 0; p < nPal; p++) {
                    BufferedImage img = hqn
                            ? decodeFrameHqn(wad, colTable, palShade, downStrip, w, h, pal, p * 256)
                            : add
                                ? decodeFrameAdditive(wad, colTable, palShade, downStrip, w, h, pal)
                                : decodeFrame(wad, colTable, palShade, downStrip, w, h, pal);
                    if (img == null) {
                        continue;
                    }
                    if (hqn && p == 0) {
                        // Version « éclairable » : l'index de couleur brut, que le shader combine
                        // avec la palette de la feuille selon la lumière (cf. drawBitmapLighted).
                        BufferedImage idx = decodeFrameHqnIndex(wad, colTable, downStrip, w, h);
                        if (idx != null) {
                            ImageIO.write(idx, "png",
                                    sdir.resolve(String.format("frame_%02d.idx.png", fr)).toFile());
                        }
                    }
                    String fn = (p == 0) ? String.format("frame_%02d.png", fr)
                            : String.format("frame_%02d_p%d.png", fr, p);
                    ImageIO.write(img, "png", sdir.resolve(fn).toFile());
                    wroteBase = wroteBase || (p == 0);
                }
                if (wroteBase) {
                    wrote++;
                    frames++;
                }
            }
            if (wrote > 0) {
                if (hqn) {
                    // Palette de lumière de la feuille : 4 variantes × 256 entrées. Le jeu y lit
                    // `variante*256 + niveau*8 + (idx & 7)` (le niveau vient de l'éclairage).
                    BufferedImage lut = new BufferedImage(256, 4, BufferedImage.TYPE_INT_RGB);
                    for (int p = 0; p < 4; p++) {
                        for (int i = 0; i < 256; i++) {
                            lut.setRGB(i, p, pal[Mem.ub(palShade + p * 256 + i)]);
                        }
                    }
                    ImageIO.write(lut, "png", dir.resolve(sheet).resolve("pal.png").toFile());
                }
                System.out.printf("  sprite %-14s %d frame(s)%s%n", sheet, wrote, hqn ? " (+ indices/palette)" : "");
                sheets++;
            }
        }
        System.out.println("[sprites] " + sheets + " feuilles, " + frames + " frames → " + dir);
    }

    /**
     * Frame d'une feuille ADDITIVE (tirs, explosions, halos).
     *
     * <p>Pour ces sprites, le pointeur @12 de la feuille n'est PAS une palette : c'est une table
     * de MELANGE, « 32 sets of 256 blend values » dit l'original (objdrawhires.s:1020). Le pixel
     * ecrit vaut {@code blend[index*256 + couleur_deja_a_l_ecran]} — un sprite additif n'a donc
     * aucune couleur propre, elle depend du fond.
     *
     * <p>On en extrait la couleur melangee sur du NOIR ({@code blend[index*256]}), c'est-a-dire
     * ce que le sprite AJOUTE ; le rendu additif du remake refait le reste. Les lire comme une
     * palette donnait des couleurs absurdes (du vert et du rouge pur sur un tir de plasma).
     */
    /**
     * Les feuilles dessinees en ADDITIF. Ce n'est pas une propriete de la feuille mais du
     * projectile qui s'en sert : {@code BulT_GraphicType} (ou {@code ImpactGraphicType}) valant 2
     * met {@code draw_Additive_b}. On releve donc les feuilles citees par les pas d'animation de
     * ces balles-la.
     */
    private static java.util.Set<Integer> additiveSheets(int glf) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (int b = 0; b < Defs.NUM_BULLET_DEFS; b++) {
            int def = glf + Defs.GLFT_BulletDefs_l + b * Defs.BulT_SizeOf_l;
            collectSheets(out, Mem.l(def + Defs.BulT_GraphicType_l) == 2,
                    def + Defs.BulT_AnimData_vb, Mem.w(def + Defs.BulT_AnimFrames_l + 2));
            collectSheets(out, Mem.l(def + Defs.BulT_ImpactGraphicType_l) == 2,
                    def + Defs.BulT_PopData_vb, Mem.w(def + Defs.BulT_PopFrames_l + 2));
        }
        return out;
    }

    /** Les feuilles citees par les pas d'un script d'animation (octet 0 de chaque pas de 6). */
    private static void collectSheets(java.util.Set<Integer> out, boolean yes, int data, int n) {
        if (!yes || data == 0) {
            return;
        }
        for (int i = 0; i <= n; i++) {
            int gfx = Mem.b(data + i * 6);
            if (gfx >= 0) {
                out.add(gfx);
            }
        }
    }

    private static BufferedImage decodeFrameAdditive(int wad, int colTable, int blend,
                                                     int downStrip, int w, int h, int[] pal) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        boolean any = false;
        for (int c = 0; c < w; c++) {
            int colEntry = Mem.l(colTable + c * 4);
            if (colEntry == 0) {
                continue;
            }
            int fmt = (colEntry >>> 24) & 0xFF;
            int colData = wad + (colEntry & 0xFFFFFF);
            for (int r = 0; r < h; r++) {
                int word = Mem.uw(colData + (downStrip + r) * 2);
                int tx = (word >> (fmt * 5)) & 0x1F;
                if (tx == 0) {
                    continue;                            // n'ajoute rien : transparent
                }
                int idx = Mem.ub(blend + tx * 256);      // melange sur le noir
                if (idx == 0) {
                    continue;
                }
                img.setRGB(c, r, pal[idx]);
                any = true;
            }
        }
        return any ? img : null;
    }

    private static BufferedImage decodeFrame(int wad, int colTable, int palShade, int downStrip,
                                             int w, int h, int[] pal) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        boolean any = false;
        for (int c = 0; c < w; c++) {
            int colEntry = Mem.l(colTable + c * 4);
            if (colEntry == 0) {
                continue;                                    // colonne transparente
            }
            int fmt = (colEntry >>> 24) & 0xFF;
            int colData = wad + (colEntry & 0xFFFFFF);
            for (int r = 0; r < h; r++) {
                // idx 5 bits = 3 pixels par WORD, ss (=fmt) choisit le champ : (word >> ss*5) & 31.
                int word = Mem.uw(colData + (downStrip + r) * 2);
                int tx = (word >> (fmt * 5)) & 0x1F;
                if (tx != 0) {                               // idx 0 = transparent
                    int idx = Mem.ub(palShade + PAL_OFFSET + tx * 2);
                    img.setRGB(c, r, pal[idx]);
                    any = true;
                }
            }
        }
        return any ? img : null;
    }

    /**
     * Carte d'INDICES d'une frame HQN : l'octet brut du sprite (0 = transparent), sans passer par
     * la palette. Le jeu s'en sert avec une palette choisie par groupe de 8 couleurs selon
     * l'éclairage : {@code couleur = palette[variante][niveau*8 + (idx & 7)]} où le niveau vient
     * de la grille 7×7 (drawBitmapLighted) et le groupe est {@code idx >> 3}.
     */
    private static BufferedImage decodeFrameHqnIndex(int wad, int colTable, int downStrip,
                                                     int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        boolean any = false;
        for (int c = 0; c < w; c++) {
            int colEntry = Mem.l(colTable + c * 4);
            if (colEntry == 0) {
                continue;
            }
            int colData = wad + (colEntry & 0xFFFFFF);
            for (int r = 0; r < h; r++) {
                int colorIdx = Mem.ub(colData + downStrip + r);
                if (colorIdx != 0) {
                    img.setRGB(c, r, 0xFF000000 | (colorIdx << 16) | (colorIdx << 8) | colorIdx);
                    any = true;
                }
            }
        }
        return any ? img : null;
    }

    /** Décodage HQN : 1 octet/pixel (stride 1) → index dans la palette de shade de la feuille
     *  (colorIdx → screenIdx global) ; 0 = transparent. {@code palBlock} = variante (p*256,
     *  sélectionnée en jeu par WhichLightPal ; ex. GUARD p1 = bleu). */
    private static BufferedImage decodeFrameHqn(int wad, int colTable, int palShade, int downStrip,
                                                int w, int h, int[] pal, int palBlock) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        boolean any = false;
        for (int c = 0; c < w; c++) {
            int colEntry = Mem.l(colTable + c * 4);
            if (colEntry == 0) {
                continue;
            }
            int colData = wad + (colEntry & 0xFFFFFF);
            for (int r = 0; r < h; r++) {
                int colorIdx = Mem.ub(colData + downStrip + r);  // stride 1 octet
                if (colorIdx != 0) {
                    int screenIdx = Mem.ub(palShade + palBlock + colorIdx); // bloc variante + colorIdx
                    img.setRGB(c, r, pal[screenIdx]);
                    any = true;
                }
            }
        }
        return any ? img : null;
    }
}
