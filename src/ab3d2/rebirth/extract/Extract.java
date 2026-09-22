package ab3d2.rebirth.extract;

import java.nio.file.Path;

/**
 * Point d'entrée de l'extraction des assets d'origine vers des formats modernes.
 *
 * <pre>
 *   gradle -p rebirth extract                  # tout
 *   gradle -p rebirth extract -Pwhat=textures  # textures | levels | models | tables
 * </pre>
 *
 * Sortie : le dossier designe par {@code -Drebirth.assets} (la tache Gradle le pose),
 * sinon {@code assets/} a cote du projet. Il vit HORS du depot : tout s'y regenere.
 */
public final class Extract {

    private Extract() {
    }

    public static void main(String[] args) throws Exception {
        String what = args.length > 0 ? args[0] : "all";
        String prop = System.getProperty("rebirth.assets");
        Path out = (prop != null && !prop.isBlank())
                ? Path.of(prop)
                : Path.of("assets");

        PortReader.boot();
        System.out.println("[rebirth] extraction '" + what + "' → " + out.toAbsolutePath());

        boolean all = what.equals("all");
        if (all || what.equals("glf")) {
            Glf.extract(out);
        }
        if (all || what.equals("textures")) {
            Textures.extractAll(out);
        }
        if (all || what.equals("sprites")) {
            Sprites.extract(out.resolve("textures").resolve("objects"), PortReader.paletteRGB());
        }
        if (all || what.equals("levels")) {
            LevelExport.extractAll(out);
        }
        if (all || what.equals("models")) {
            VectObjExport.extractAll(out);
        }
        if (all || what.equals("sfx")) {
            Sfx.extract(out);
        }
        if (all || what.equals("tables")) {
            Tables.extract(out);
        }
        if (all || what.equals("shimmer")) {
            Shimmer.extract(out);
        }
        if (all || what.equals("music")) {
            MusicExport.extract(out);
        }
        if (all || what.equals("hud")) {
            HudExport.extract(out, PortReader.paletteRGB());
        }
        if (all || what.equals("menu")) {
            MenuExport.extract(out);
        }
        if (all || what.equals("story")) {
            StoryExport.extract(out);
        }

        System.out.println("[rebirth] terminé.");
    }
}
