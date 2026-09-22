package ab3d2.rebirth.extract;

import ab3d2.Defs;
import ab3d2.Mem;
import ab3d2.modules.FileIo;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extraction des textures/images d'origine vers PNG (palette → RGB), noms d'origine conservés.
 *
 * <p>Textures murales : format décodé et vérifié dans le portage — un fichier = table de
 * « shade » 32×32 (2048 o) + texels stride 2, colonne-major. Le pixel final = palette[
 * shade[(VBLK + selecteur_rampe) ]], où VBLK fixe le niveau de luminosité baké. On extrait à un
 * VBLK fixe pour obtenir une texture « à plat » (le futur moteur ré-éclaire lui-même).
 */
public final class Textures {

    private static final int SKY_W = 648, SKY_H = 240;

    private Textures() {
    }

    public static void extractAll(Path outRoot) throws Exception {
        int[] pal = PortReader.paletteRGB();
        extractWalls(outRoot.resolve("textures").resolve("walls"), pal);
        Floors.extract(outRoot.resolve("textures").resolve("floors"), pal);
        Water.extract(outRoot, pal);                     // teinte + vagues de la surface d'eau
        extractSky(outRoot.resolve("textures").resolve("sky"), pal);
        extractTextureMapsAtlas(outRoot.resolve("textures"), pal);
        extractTextureMapsAtlasIndex(outRoot.resolve("textures"));
        writePalette(outRoot, pal);
    }

    /** 16 textures murales → PNG (nom d'origine, ex. STONEWALL.png). */
    static void extractWalls(Path dir, int[] pal) throws Exception {
        Files.createDirectories(dir);
        int glf = PortReader.glf();
        int n = 0;
        for (int i = 0; i < Defs.NUM_WALL_TEXTURES; i++) {
            int nameAddr = glf + Defs.GLFT_WallGFXNames_l + i * 64;
            String full = Mem.cstr(nameAddr);
            if (full.isEmpty()) {
                continue;
            }
            long fr = FileIo.IO_LoadFile(nameAddr);
            int base = FileIo.addr(fr);
            int flen = FileIo.len(fr);
            // Format .256wad : [table shade 2048][chunk : pixels 5 bits, 3 par WORD, colonne-major par
            // groupe][footer 2 o = hauteur, big-endian]. HAUTEUR = footer. Chunk = numGroups*H WORDs ;
            // LARGEUR = numGroups*3 arrondie au multiple de 16 (le format ajoute 0..2 pixels de padding
            // par groupe de 3). H et W VÉRIFIÉS contre l'extraction de référence (ab3d2-tkg-jme).
            int h = (Mem.ub(base + flen - 2) << 8) | Mem.ub(base + flen - 1);
            int chunkBytes = flen - 2048 - 2;
            if (h <= 0 || chunkBytes <= 0 || chunkBytes % (h * 2) != 0) {
                continue;
            }
            int numGroups = chunkBytes / (h * 2);
            int w = snapToAmigaWidth(numGroups * 3);
            BufferedImage img = decodeWall(base, numGroups, w, h, pal);
            String name = PortReader.baseName(full);
            ImageIO.write(img, "png", dir.resolve(name + ".png").toFile());
            // Version « éclairable » : carte d'indices + LUT (cf. writeShadeMaps).
            ImageIO.write(decodeWallIndex(base, numGroups, w, h), "png",
                    dir.resolve(name + ".idx.png").toFile());
            ImageIO.write(shadeLut(base, pal), "png", dir.resolve(name + ".lut.png").toFile());
            System.out.printf("  mur %2d : %-16s %dx%d%n", i, name, w, h);
            n++;
        }
        System.out.println("[textures] murs : " + n + " PNG → " + dir);
    }

    /**
     * Décode une texture murale : chaque WORD du chunk = 3 pixels 5 bits (bits[4:0], [9:5], [14:10]),
     * colonne-major par GROUPE de 3 colonnes (groupe g → colonnes 3g,3g+1,3g+2 ; pour chaque groupe les
     * H rangées se suivent). Chaque index 5 bits passe par la table shade rangée 0 (luminosité max,
     * texture « à plat ») → index palette. Pas de transparence (murs opaques).
     */
    static BufferedImage decodeWall(int base, int numGroups, int w, int h, int[] pal) {
        int shade = base;                                    // table shade (row 0 = max) ; octet à t*2
        int chunk = base + 2048;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int g = 0; g < numGroups; g++) {
            int baseX = g * 3;
            for (int y = 0; y < h; y++) {
                int word = Mem.uw(chunk + (g * h + y) * 2);
                int t0 = word & 0x1F, t1 = (word >> 5) & 0x1F, t2 = (word >> 10) & 0x1F;
                if (baseX < w)     img.setRGB(baseX,     y, pal[Mem.ub(shade + t0 * 2)]);
                if (baseX + 1 < w) img.setRGB(baseX + 1, y, pal[Mem.ub(shade + t1 * 2)]);
                if (baseX + 2 < w) img.setRGB(baseX + 2, y, pal[Mem.ub(shade + t2 * 2)]);
            }
        }
        return img;
    }

    /**
     * Carte d'INDICES de la texture : au lieu d'une couleur, chaque pixel garde son sélecteur de
     * rampe 5 bits (0..31), celui que le rasterizer combine avec le niveau de lumière
     * ({@code sr = (word >> (5*k)) & 31}). Stocké tel quel dans R/G/B (valeur 0..31), à lire au
     * filtre « au plus proche ». Avec la LUT, un shader reproduit EXACTEMENT l'éclairage d'origine.
     */
    static BufferedImage decodeWallIndex(int base, int numGroups, int w, int h) {
        int chunk = base + 2048;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int g = 0; g < numGroups; g++) {
            int baseX = g * 3;
            for (int y = 0; y < h; y++) {
                int word = Mem.uw(chunk + (g * h + y) * 2);
                int[] t = { word & 0x1F, (word >> 5) & 0x1F, (word >> 10) & 0x1F };
                for (int k = 0; k < 3; k++) {
                    if (baseX + k < w) {
                        img.setRGB(baseX + k, y, (t[k] << 16) | (t[k] << 8) | t[k]);
                    }
                }
            }
        }
        return img;
    }

    /**
     * LUT d'éclairage d'une texture : 32 (sélecteur de rampe) × 32 (bloc de luminosité).
     * Le rasterizer fait {@code palette[ shade[bloc*64 + sr*2] ]} — le bloc vient de la table SCALE
     * (hireswall.s) : {@code bloc = min(ceil(niveau/2), 31)} pour un niveau 0..64, où
     * {@code niveau = luminosité de coin + distance>>7}. Bloc 0 = le plus clair.
     */
    static BufferedImage shadeLut(int base, int[] pal) {
        BufferedImage lut = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int b = 0; b < 32; b++) {
            for (int sr = 0; sr < 32; sr++) {
                lut.setRGB(sr, b, pal[Mem.ub(base + b * 64 + sr * 2)]);
            }
        }
        return lut;
    }

    /** Arrondit la largeur brute (numGroups*3) au multiple de 16 inférieur (0..2 px de padding par groupe). */
    static int snapToAmigaWidth(int rawWidth) {
        if (rawWidth % 16 == 0) {
            return rawWidth;
        }
        int snapped = (rawWidth / 16) * 16;
        return (rawWidth - snapped <= 3) ? snapped : rawWidth;
    }

    /**
     * Atlas texture-maps en carte d'INDICES (rawIdx par texel, 0 = transparent) : c'est ce que le
     * rasterizer des modèles combine avec la table de shade
     * ({@code shade[256*32 + niveau*256 + rawIdx]}, Objdrawhires.java:1890-1925), donc avec la même
     * LUT que les sols. Pendant « à plat » de texturemaps_atlas.png.
     */
    static void extractTextureMapsAtlasIndex(Path dir) throws Exception {
        int tex = Mem.l(ab3d2.bss.DrawBss.Draw_TextureMapsPtr_l);
        if (tex == 0) {
            return;
        }
        int h = 8 * 64;
        BufferedImage img = new BufferedImage(256, h, BufferedImage.TYPE_INT_ARGB);
        for (int bank = 0; bank < 2; bank++) {
            for (int slot = 0; slot < 4; slot++) {
                int y0 = (bank * 4 + slot) * 64;
                // MEMES bornes et MEME orientation que l'atlas couleur : U = rangée (pas de
                // 1024, 64 rangées par banque), V = colonne (pas de 4, 256 colonnes), et
                // l'image recoit x = v, y = u. Les inverser fait lire u*1024 jusqu'a 261120,
                // donc tres au-dela des 65536 octets de la banque : l'arme en main se couvrait
                // de pixels aleatoires des qu'elle passait par le chemin ECLAIRE.
                for (int u = 0; u < 64; u++) {
                    for (int v = 0; v < 256; v++) {
                        int rawIdx = Mem.ub(tex + bank * 65536 + u * 1024 + v * 4 + slot);
                        int a = rawIdx == 0 ? 0 : 255;   // 0 = transparent
                        img.setRGB(v, y0 + u, (a << 24) | (rawIdx << 16) | (rawIdx << 8) | rawIdx);
                    }
                }
            }
        }
        ImageIO.write(img, "png", dir.resolve("texturemaps_atlas.idx.png").toFile());
        System.out.println("[textures] atlas texture-maps : carte d'indices 256x" + h);
    }

    /** Backdrop ciel (rawbackpacked, colonne-major, palette globale) → backdrop.png. */
    static void extractSky(Path dir, int[] pal) throws Exception {
        byte[] data = ab3d2.Assets.bytes("includes/rawbackpacked");
        if (data == null) {
            System.out.println("[textures] ciel : rawbackpacked absent");
            return;
        }
        Files.createDirectories(dir);
        BufferedImage img = new BufferedImage(SKY_W, SKY_H, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < SKY_W; x++) {
            for (int y = 0; y < SKY_H; y++) {
                int ci = x * SKY_H + y;                       // colonne-major (comme le moteur)
                img.setRGB(x, y, pal[ci < data.length ? (data[ci] & 0xFF) : 0]);
            }
        }
        ImageIO.write(img, "png", dir.resolve("backdrop.png").toFile());
        System.out.println("[textures] ciel : backdrop.png (" + SKY_W + "x" + SKY_H + ") → " + dir);
    }

    /**
     * Atlas texture-maps (NEWTEXTUREMAPS) → texturemaps_atlas.png (256×[banks*4*64], 8 strips par 2 banques).
     * Une strip = (bank,slot) à y=(bank*4+slot)*64. Pixel : {@code rawIdx = tex[bank*65536 + U*1024 + V*4 + slot]} ;
     * 0=transparent ; sinon {@code palette[ shade[32*256 + rawIdx] ]}. Sert de texture aux modèles vectoriels (UV).
     */
    static void extractTextureMapsAtlas(Path dir, int[] pal) throws Exception {
        PortReader.bootWithAssets();                         // peuple Draw_TexturePalettePtr (shade)
        int glf = PortReader.glf();
        long fr = FileIo.IO_LoadFile(glf + Defs.GLFT_TextureFilename_l); // NEWTEXTUREMAPS
        int tex = FileIo.addr(fr);
        int texLen = FileIo.len(fr);
        int shade = Mem.l(ab3d2.bss.DrawBss.Draw_TexturePalettePtr_l);
        int numBanks = texLen / 65536;
        if (numBanks <= 0 || shade == 0) {
            System.out.println("[textures] atlas : texture-maps non disponibles");
            return;
        }
        Files.createDirectories(dir);
        int h = numBanks * 4 * 64;
        BufferedImage img = new BufferedImage(256, h, BufferedImage.TYPE_INT_ARGB);
        for (int bank = 0; bank < numBanks; bank++) {
            for (int slot = 0; slot < 4; slot++) {
                int stripY = (bank * 4 + slot) * 64;
                int bankBase = tex + bank * 65536;
                for (int u = 0; u < 64; u++) {               // U = rangée
                    for (int v = 0; v < 256; v++) {          // V = colonne
                        int rawIdx = Mem.ub(bankBase + u * 1024 + v * 4 + slot);
                        int argb = (rawIdx == 0) ? 0 : pal[Mem.ub(shade + 32 * 256 + rawIdx)];
                        img.setRGB(v, stripY + u, argb);
                    }
                }
            }
        }
        ImageIO.write(img, "png", dir.resolve("texturemaps_atlas.png").toFile());
        System.out.println("[textures] atlas : texturemaps_atlas.png (256x" + h + ", " + numBanks + " banques)");
    }

    /** Palette de référence → palette.png (bande 256×1). */
    static void writePalette(Path root, int[] pal) throws Exception {
        Files.createDirectories(root);
        BufferedImage img = new BufferedImage(256, 1, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 256; i++) {
            img.setRGB(i, 0, pal[i]);
        }
        ImageIO.write(img, "png", root.resolve("palette.png").toFile());
        System.out.println("[textures] palette.png (256x1)");
    }
}
