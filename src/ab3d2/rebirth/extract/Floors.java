package ab3d2.rebirth.extract;

import ab3d2.Mem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extraction des textures de SOL (FLOORTILE) vers PNG — 16 tuiles 64×64 + atlas 4×4.
 *
 * <p>Format (décodé depuis {@code Hires.pastfloorbright}/draw_GoraudFloor) : le fichier FLOORTILE
 * (65536 o) contient <b>16 tuiles</b> = 4 banques × 4 tuiles entrelacées. Le texel d'une tuile est :
 * {@code floorBase[ b*256 + k + (T*256 + S)*4 ]} avec la banque {@code b ∈ 0..3} et l'entrelacement
 * {@code k ∈ 0..3} ; le sélecteur du niveau ({@code whichtile}) vaut {@code b*256 + k}. L'octet lu
 * est directement l'INDEX de palette (la table de shade ne sert qu'à l'éclairage en jeu).
 * Index de tuile exporté = {@code b*4 + k} (0..15).
 */
public final class Floors {

    private static final int TILE = 64;
    private static final int NUM_TILES = 16;                 // 4 banques × 4 entrelacées
    private static final int ATLAS_COLS = 4;                 // atlas 4×4 (banque en ligne, k en colonne)

    private Floors() {
    }

    /**
     * LUT d'éclairage des sols/plafonds ET des modèles vectoriels : 256 (index de texel) × 32
     * (niveau 0..31 ; les sols bornent à 30, les modèles à 31).
     * Le rasterizer fait {@code shade[256*32 + niveau*256 + texel]} (Hires.java:791,
     * draw_GoraudFloor : {@code (a1, bright<<8 | texel)}), où shade = Draw_TexturePalettePtr
     * (NEWTEXTUREMAPS.pal). Niveau 0 = le plus clair.
     */
    static void extractShadeLut(Path dir, int[] pal) throws Exception {
        int shade = Mem.l(ab3d2.bss.DrawBss.Draw_TexturePalettePtr_l);
        if (shade == 0) {
            System.out.println("[textures] sols : table de shade absente");
            return;
        }
        BufferedImage lut = new BufferedImage(256, 32, BufferedImage.TYPE_INT_RGB);
        for (int level = 0; level < 32; level++) {
            for (int texel = 0; texel < 256; texel++) {
                lut.setRGB(texel, level, pal[Mem.ub(shade + 256 * 32 + level * 256 + texel)]);
            }
        }
        ImageIO.write(lut, "png", dir.resolve("floor_shade.lut.png").toFile());
        System.out.println("[textures] sols : floor_shade.lut.png (256x32)");
    }

    static void extract(Path dir, int[] pal) throws Exception {
        PortReader.bootWithAssets();
        int floorBase = Mem.l(ab3d2.bss.DrawBss.Draw_GlobalFloorTexturesPtr_l);
        if (floorBase == 0) {
            System.out.println("[textures] sols : FLOORTILE non chargé");
            return;
        }
        Files.createDirectories(dir);
        BufferedImage atlas = new BufferedImage(ATLAS_COLS * TILE, ATLAS_COLS * TILE, BufferedImage.TYPE_INT_RGB);
        for (int tile = 0; tile < NUM_TILES; tile++) {
            int b = tile >> 2;                               // banque 0..3
            int k = tile & 3;                                // entrelacement 0..3
            int whichtile = b * 256 + k;                     // = footer[3] des flats
            BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
            for (int t = 0; t < TILE; t++) {
                for (int s = 0; s < TILE; s++) {
                    int texel = Mem.ub(floorBase + whichtile + (t * 256 + s) * 4);
                    int rgb = pal[texel];
                    img.setRGB(s, t, rgb);
                    atlas.setRGB(k * TILE + s, b * TILE + t, rgb); // ligne = banque, colonne = k
                }
            }
            ImageIO.write(img, "png", dir.resolve(String.format("floor_%02d.png", tile)).toFile());
            // Carte d'INDICES : le texel EST l'index de palette, que le shader combine avec la LUT.
            BufferedImage idx = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
            for (int t = 0; t < TILE; t++) {
                for (int s = 0; s < TILE; s++) {
                    int texel = Mem.ub(floorBase + whichtile + (t * 256 + s) * 4);
                    idx.setRGB(s, t, (texel << 16) | (texel << 8) | texel);
                }
            }
            ImageIO.write(idx, "png", dir.resolve(String.format("floor_%02d.idx.png", tile)).toFile());
        }
        extractShadeLut(dir, pal);
        ImageIO.write(atlas, "png", dir.resolve("floortile_atlas.png").toFile());
        System.out.println("[textures] sols : " + NUM_TILES + " PNG (64x64) + atlas 4x4 → " + dir);
    }
}
