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
        if (what.equals("zoneorder")) {              // oracle de l'ordre de dessin
            ZoneOrderExport.extract(out);
        }
        if (what.equals("xform")) {                  // oracle de la transformation de vue
            XformExport.extract(out);
        }
        if (what.equals("clips")) {                  // oracle de la fenetre de dessin
            ClipExport.extract(out);
        }
        if (what.equals("edgepvs")) {                // oracle du PVS par arete
            EdgePvsExport.extract(out);
        }
        if (what.equals("visedges")) {               // oracle des ouvertures visibles
            VisibleEdgesExport.extract(out);
        }
        if (what.equals("walls")) {                  // oracle de la projection des murs
            WallExport.extract(out);
        }
        if (what.equals("flats")) {                  // oracle de la projection des sols
            FlatExport.extract(out);
        }
        if (what.equals("wallpaint")) {              // oracle du placage des murs
            WallPaintExport.extract(out);
        }
        if (what.equals("gourwallpaint")) {          // oracle du placage des murs degrades
            GourWallPaintExport.extract(out);
        }
        if (what.equals("flatpaint")) {              // oracle du placage des sols
            FlatPaintExport.extract(out);
        }
        if (what.equals("gourflatpaint")) {          // oracle du placage des sols degrades
            GourFlatPaintExport.extract(out);
        }
        if (what.equals("waterpaint")) {             // oracle du placage de l'eau
            WaterPaintExport.extract(out);
        }
        if (what.equals("sky")) {                    // oracle du ciel
            SkyExport.extract(out);
        }
        if (what.equals("objxform")) {               // oracle de la rotation des choses
            ObjXformExport.extract(out);
        }
        if (what.equals("spriteorder")) {            // oracle du tri des choses
            SpriteOrderExport.extract(out);
        }
        if (what.equals("bitmap")) {                 // oracle de la pose du sprite
            BitmapExport.extract(out);
        }
        if (what.equals("bitmappaint")) {            // oracle des pixels du sprite
            BitmapPaintExport.extract(out);
        }
        if (what.equals("bitmaplit")) {              // oracle de l'image eclairee
            BitmapLitExport.extract(out);
        }
        if (what.equals("glare")) {                  // oracle du miroitement
            GlareExport.extract(out);
        }
        if (what.equals("brightrings")) {            // oracle des anneaux de lumiere
            BrightRingsExport.extract(out);
        }
        if (what.equals("modelpoints")) {            // oracle des points d'un modele
            ModelPointsExport.extract(out);
        }
        if (what.equals("modelfaces")) {             // oracle des faces d'un modele
            ModelFacesExport.extract(out);
        }
        if (what.equals("modeledges")) {             // oracle des bords d'une face
            ModelEdgesExport.extract(out);
        }
        if (what.equals("modelpixels")) {            // oracle des pixels d'un modele
            ModelPixelsExport.extract(out);
        }
        if (what.equals("objectlife")) {             // oracle de la vie des objets
            ObjectLifeExport.extract(out);
        }
        if (what.equals("blast")) {                  // oracle du souffle
            BlastExport.extract(out);
        }
        if (what.equals("move")) {                   // oracle de la collision
            MoveExport.extract(out);
        }
        if (what.equals("hit")) {                    // oracle du coup au but
            HitExport.extract(out);
        }
        if (what.equals("menufx")) {                 // oracle du feu et de la police du menu
            MenuFxExport.extract(out);
        }
        if (what.equals("shimmerfx")) {              // oracle de la dematerialisation
            ShimmerExport.extract(out);
        }
        if (what.equals("shoot")) {                  // oracle du tir hitscan du joueur
            ShootExport.extract(out);
        }
        if (what.equals("bits")) {                   // oracle de la gerbe de morceaux
            BitsExport.extract(out);
        }
        if (what.equals("anims")) {                  // oracle des portes et des ascenseurs
            AnimsExport.extract(out);
        }
        if (what.equals("objanim")) {                // oracle de l'apparence des choses
            ObjAnimExport.extract(out);
        }
        if (what.equals("alienanim")) {              // oracle du record de dessin d'un monstre
            AlienAnimExport.extract(out);
        }
        if (what.equals("zonegraph")) {              // oracle de la mise en scene
            ZoneGraphExport.extract(out);
        }
        if (what.equals("lights")) {                 // oracle de l'eclairage
            LightExport.extract(out);
        }
        if (what.equals("hudraster")) {              // oracle du tableau de bord
            HudRasterExport.extract(out);
        }
        if (what.equals("gunhold")) {                // oracle de l'arme en main
            GunHoldExport.extract(out);
        }
        if (what.equals("paula")) {                  // oracle du moteur audio
            PaulaExport.extract(out);
        }
        if (what.equals("musicplay")) {              // oracle du lecteur de musique
            MusicExport2.extract(out);
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
