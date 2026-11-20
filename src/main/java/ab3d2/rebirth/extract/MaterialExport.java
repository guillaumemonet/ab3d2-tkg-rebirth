package ab3d2.rebirth.extract;

import ab3d2.rebirth.Assets;
import ab3d2.rebirth.GlfData;
import ab3d2.rebirth.LevelBuilder;
import ab3d2.rebirth.LevelData;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import javax.imageio.ImageIO;

/**
 * Écrit les matériaux du décor en <b>`.j3m`</b>, le format natif de jMonkeyEngine.
 *
 * <p>Jusqu'ici chaque matériau était fabriqué en code ({@code LevelBuilder.wallMaterial},
 * {@code flatMaterial}…), donc invisible depuis le SDK jME et impossible à retoucher sans
 * recompiler. Un `.j3m` est un fichier texte : on peut y changer une texture, une couleur, le
 * mode de mélange ou la face cullée à la main, et le jeu le reprend au lancement.
 *
 * <p>Ce qui est produit, dans {@code assets/materials/} :
 * <ul>
 *   <li>{@code wall_<tex>_<fromTile>_<texW>.j3m} — un par SOUS-TUILE de mur réellement employée
 *       dans les seize niveaux ;</li>
 *   <li>{@code flat_<tile>.j3m} — sols et plafonds ;</li>
 *   <li>{@code water_<tile>_3d.j3m} — surfaces d'eau.</li>
 * </ul>
 *
 * <p>Les murs ne peuvent pas pointer directement sur le PNG de la texture : le moteur d'origine
 * n'affiche qu'une TRANCHE verticale {@code [fromTile, fromTile+texW)} de celle-ci, avec repli
 * horizontal. On découpe donc cette tranche une fois pour toutes (même code que le jeu,
 * {@link LevelBuilder#subTile}) vers {@code textures/walls/sub/}, et le `.j3m` la référence.
 *
 * <p>Les noms de fichiers reprennent EXACTEMENT les clés de matériau de {@code LevelBuilder},
 * qui va chercher le `.j3m` avant de retomber sur sa construction en code.
 *
 * <p>Deux réglages de la texture méritent attention. {@code Repeat} est écrit ici, et il est
 * indispensable : les murs répètent leur motif sur la longueur, sans lui le bord de l'image
 * s'étire en traînées verticales. Le FILTRAGE, lui, ne s'exprime pas en `.j3m` — c'est
 * {@code LevelBuilder.j3m} qui repose le « plus proche voisin » après chargement, faute de quoi
 * l'interpolation lisse les pixels et le jeu perd son grain d'origine.
 *
 * <pre>
 * gradle -p rebirth materials
 * </pre>
 */
public final class MaterialExport {

    /** Matériau éclairé du rendu moteur, décalque de {@code LevelBuilder.litMaterial}. */
    private static final String LIT = """
            Material %s : Common/MatDefs/Light/Lighting.j3md {
                MaterialParameters {
                    DiffuseMap : Repeat %s
                    UseMaterialColors : true
                    Diffuse  : 1.0 1.0 1.0 1.0
                    Ambient  : 1.0 1.0 1.0 1.0
                    Specular : 0.0 0.0 0.0 1.0
                    Shininess : 1.0
                }
                AdditionalRenderState {
                    FaceCull Off
                }
            }
            """;

    private MaterialExport() {
    }

    public static void main(String[] args) throws IOException {
        Path out = Assets.root();
        Path matDir = out.resolve("materials");
        Path subDir = out.resolve("textures").resolve("walls").resolve("sub");
        Files.createDirectories(matDir);
        Files.createDirectories(subDir);

        GlfData glf = Assets.json("glf.json", GlfData.class);

        // Les clés réellement employées, tous niveaux confondus.
        Set<String> walls = new LinkedHashSet<>();     // "tex_fromTile_texW"
        Set<Integer> flats = new TreeSet<>();
        Set<Integer> waters = new TreeSet<>();
        int levels = 0;
        for (char c = 'a'; c <= 'p'; c++) {
            LevelData lvl;
            try {
                lvl = Assets.json("levels/" + c + ".json", LevelData.class);
            } catch (RuntimeException absent) {
                continue;                              // niveau pas encore extrait
            }
            if (lvl == null) {
                continue;
            }
            levels++;
            if (lvl.walls != null) {
                for (LevelData.Wall w : lvl.walls) {
                    walls.add((w.texIndex & 0x7FFF) + "_" + w.fromTile + "_" + (w.widthMask + 1));
                }
            }
            if (lvl.flats != null) {
                for (LevelData.Flat f : lvl.flats) {
                    if ("water".equals(f.kind)) {
                        waters.add(f.tile);
                    } else {
                        flats.add(f.tile);
                    }
                }
            }
        }

        int nWalls = 0;
        for (String key : walls) {
            String[] p = key.split("_");
            int texIndex = Integer.parseInt(p[0]);
            int fromTile = Integer.parseInt(p[1]);
            int texW = Integer.parseInt(p[2]);
            String name = glf == null ? null : glf.wallTexture(texIndex);
            BufferedImage full = name == null ? null : Assets.image("textures/walls/" + name + ".png");
            if (full == null) {
                continue;                              // texture absente : le code gardera son repli
            }
            // La tranche que le moteur d'origine affiche, découpée une fois pour toutes.
            BufferedImage sub = LevelBuilder.subTile(full, fromTile, texW);
            String png = "textures/walls/sub/wall_" + key + ".png";
            ImageIO.write(sub, "png", out.resolve(png).toFile());
            write(matDir.resolve("wall_" + key + ".j3m"), LIT.formatted("wall_" + key, png));
            nWalls++;
        }

        for (int tile : flats) {
            String png = String.format("textures/floors/floor_%02d.png", tile);
            if (!Files.isRegularFile(out.resolve(png))) {
                continue;
            }
            write(matDir.resolve("flat_" + tile + ".j3m"), LIT.formatted("flat_" + tile, png));
        }

        // L'eau garde son materiau dedie (LevelBuilder.modernWaterMaterial, anime) : un .j3m
        // plat le remplacerait par une simple texture eclairee. A reprendre quand on saura
        // decrire cette eau en j3m.

        System.out.printf("[materials] %d niveaux lus -> %d murs, %d sols dans %s"
                + "  (%d eaux laissees a leur materiau dedie)%n",
                levels, nWalls, flats.size(), matDir, waters.size());
    }

    private static void write(Path p, String body) throws IOException {
        Files.writeString(p, body, StandardCharsets.UTF_8);
    }
}
