package ab3d2.rebirth.extract;

import ab3d2.Defs;
import ab3d2.Mem;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extraction de la base GLF (Game Link File) vers {@code glf.json} : la table de correspondance
 * globale du jeu — niveaux, textures murales, défs d'objets/aliens/armes, noms, fichiers.
 *
 * <p>Sert de référence à tous les autres extracteurs (un objet de niveau référence un index de
 * def objet/alien ; un mur référence un index de texture ; etc.). Champs lus depuis {@code Mem}
 * aux offsets {@code GLFT_*}/structs {@code ODefT_}/{@code AlienT_}/{@code ShootT_} du portage.
 */
public final class Glf {

    private Glf() {
    }

    public static void extract(Path outRoot) throws Exception {
        int glf = PortReader.glf();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("levels", levels(glf));
        root.put("wallTextures", walls(glf));
        root.put("objects", objects(glf));
        // Plafonds d'inventaire du mod (game_ModProps.gmp_MaxInventory), utilisés par
        // Game_CheckInventoryLimits / Game_AddToInventory. Ils sont posés par
        // game_LoadModProperties (santé 10000, carburant 250, munitions 10000) : sans cet appel
        // la table reste à zéro et aucun ramassage ne donnerait quoi que ce soit.
        ab3d2.c.GameC.game_LoadModProperties();
        root.put("maxInventory", words(ab3d2.bss.TablesBss.game_ModProps + Defs.GModT_MaxInv,
                Defs.NUM_INVENTORY_CONSUMABLES));
        root.put("vectorModels", resourceList(glf, Defs.GLFT_VectorNames_l, Defs.NUM_OBJECT_DEFS));
        root.put("spriteSheets", resourceList(glf, Defs.GLFT_ObjGfxNames_l, Defs.NUM_OBJECT_DEFS));
        root.put("aliens", aliens(glf));
        root.put("guns", guns(glf));
        root.put("bullets", bullets(glf));
        root.put("alienShootDefs", alienShootDefs(glf));
        root.put("floorData", floorData(glf));
        root.put("ambientSfx", words(glf + Defs.GLFT_AmbientSFX_l, 16));   // GLFT_AmbientSFX_l
        root.put("sfx", sfx(glf));
        root.put("files", files(glf));
        root.put("playerGraphics", playerGraphics(glf));

        Files.createDirectories(outRoot);
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        Path f = outRoot.resolve("glf.json");
        Files.writeString(f, json);
        System.out.println("[glf] glf.json écrit (" + json.length() + " o) → " + f);
    }

    private static List<Object> levels(int glf) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < Defs.NUM_LEVELS; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("letter", String.valueOf((char) ('a' + i)));
            e.put("name", PortReader.fixedStr(glf + Defs.GLFT_LevelNames_l + i * 40, 40));
            e.put("music", PortReader.fixedStr(glf + Defs.GLFT_LevelMusic_l + i * 64, 64));
            l.add(e);
        }
        return l;
    }

    private static List<Object> walls(int glf) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < Defs.NUM_WALL_TEXTURES; i++) {
            String file = PortReader.fixedStr(glf + Defs.GLFT_WallGFXNames_l + i * 64, 64);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("name", PortReader.baseName(file));
            e.put("file", file);
            e.put("height", Mem.w(glf + Defs.GLFT_WallHeights_l + i * 2));
            l.add(e);
        }
        return l;
    }

    /**
     * Un script d'animation d'objet : 20 pas de 6 octets. Champs lus par animObj :
     * {@code gfx} (feuille de sprite, ou modèle vectoriel), {@code frame}, {@code word2}
     * (bitmap : donnée de dessin ; vecteur : incrément d'angle), {@code delta} (ajouté ×2 à
     * l'offset 4 de l'entité) et {@code next} (index du pas suivant).
     */
    /** n mots consécutifs (non signés). */
    private static List<Object> words(int base, int n) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(Mem.uw(base + i * 2));
        }
        return l;
    }

    private static List<Object> animSteps(int base) {
        List<Object> steps = new ArrayList<>();
        for (int k = 0; k < 20; k++) {
            int at = base + k * 6;
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("gfx", Mem.b(at));
            st.put("frame", Mem.ub(at + 1));
            st.put("word2", Mem.w(at + 2));
            st.put("delta", Mem.b(at + 4));
            st.put("next", Mem.ub(at + 5));
            steps.add(st);
        }
        return steps;
    }

    private static List<Object> objects(int glf) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < Defs.NUM_OBJECT_DEFS; i++) {
            int def = glf + Defs.GLFT_ObjectDefs + i * Defs.ODefT_SizeOf_l;
            int gfx = Mem.w(def + Defs.ODefT_GFXType_w);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("name", PortReader.fixedStr(glf + Defs.GLFT_ObjectNames_l + i * 20, 20));
            e.put("gfxType", gfx);
            e.put("isVector", gfx == 1);
            e.put("type", Mem.w(def));                   // ENT_TYPE_* (mot 0 : ramassable, activable...)
            e.put("behaviour", Mem.w(def + Defs.ODefT_Behaviour_w));
            e.put("activeTimeout", Mem.w(def + Defs.ODefT_ActiveTimeout_w));
            e.put("sfx", Mem.w(def + Defs.ODefT_SFX_w));            // bruit au ramassage (< 0 = aucun)
            e.put("hitPoints", Mem.w(def + Defs.ODefT_HitPoints_w));
            e.put("collideRadius", Mem.w(def + Defs.ODefT_CollideRadius_w));
            e.put("collideHeight", Mem.w(def + Defs.ODefT_CollideHeight_w));
            e.put("floorCeiling", Mem.w(def + Defs.ODefT_FloorCeiling_w));
            e.put("lockToWall", Mem.w(def + Defs.ODefT_LockToWall_w));
            e.put("sfx", Mem.w(def + Defs.ODefT_SFX_w));
            // Scripts d'animation : 20 pas de 6 octets (O_AnimSize = O_FrameStoreSize*20).
            // Chaque pas = {gfx, frame, mot2, delta4, suivant} et pointe le pas SUIVANT : la durée
            // est encodée par répétition. cf. Newaliencontrol.animObj.
            // Ce que l'objet DONNE au ramassage : consommables (santé, munitions par type) et
            // items (armes, jetpack) — indexés comme l'inventaire du joueur (obj_SetInventoryPointers).
            e.put("ammoGive", words(glf + Defs.GLFT_AmmoGive_l + i * Defs.AmmoGiveLen,
                    Defs.NUM_INVENTORY_CONSUMABLES));
            e.put("gunGive", words(glf + Defs.GLFT_GunGive_l + i * Defs.GunGiveLen,
                    Defs.NUM_INVENTORY_ITEMS));
            e.put("defAnim", animSteps(glf + Defs.GLFT_ObjectDefAnims_l + i * Defs.O_AnimSize));
            e.put("actAnim", animSteps(glf + Defs.GLFT_ObjectActAnims_l + i * Defs.O_AnimSize));
            l.add(e);
        }
        return l;
    }

    /**
     * Liste de RESSOURCES (modèles vectoriels ou feuilles de sprites) : fichiers indexés
     * séquentiellement (0..n), chargés tels quels par Res_LoadObjects dans Draw_PolyObjects/
     * Draw_ObjectPtrs. Un objet/alien y référence son graphisme par INDEX (via l'anim), pas par
     * position dans la table des défs — d'où une liste séparée (pas d'appariement 1:1 avec les noms).
     */
    private static List<Object> resourceList(int glf, int baseOffset, int count) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String file = PortReader.fixedStr(glf + baseOffset + i * 64, 64);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("name", PortReader.baseName(file));
            e.put("file", file);
            l.add(e);
        }
        return l;
    }

    private static List<Object> aliens(int glf) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < Defs.NUM_ALIEN_DEFS; i++) {
            int def = glf + Defs.GLFT_AlienDefs_l + i * Defs.AlienT_SizeOf_l;
            int gfx = Mem.w(def + Defs.AlienT_GFXType_w);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("name", PortReader.fixedStr(glf + Defs.GLFT_AlienNames_l + i * 20, 20));
            e.put("gfxType", gfx);
            e.put("isVector", gfx == 1);
            e.put("bright", Mem.w(glf + Defs.GLFT_AlienBrights_l + i * 2));
            e.put("hitPoints", Mem.w(def + Defs.AlienT_HitPoints_w));
            e.put("height", Mem.w(def + Defs.AlienT_Height_w));
            e.put("girth", Mem.w(def + Defs.AlienT_Girth_w));
            e.put("bulletType", Mem.w(def + Defs.AlienT_BulType_w));
            e.put("splatType", Mem.w(def + Defs.AlienT_SplatType_w));
            e.put("auxiliary", Mem.w(def + Defs.AlienT_Auxilliary_w));
            e.put("reactionTime", Mem.w(def + Defs.AlienT_ReactionTime_w));
            e.put("defaultBehaviour", Mem.w(def + Defs.AlienT_DefaultBehaviour_w));
            e.put("defaultSpeed", Mem.w(def + Defs.AlienT_DefaultSpeed_w));
            e.put("responseBehaviour", Mem.w(def + Defs.AlienT_ResponseBehaviour_w));
            e.put("responseSpeed", Mem.w(def + Defs.AlienT_ResponseSpeed_w));
            e.put("responseTimeout", Mem.w(def + Defs.AlienT_ResponseTimeout_w));
            e.put("followupBehaviour", Mem.w(def + Defs.AlienT_FollowupBehaviour_w));
            e.put("followupSpeed", Mem.w(def + Defs.AlienT_FollowupSpeed_w));
            e.put("followupTimeout", Mem.w(def + Defs.AlienT_FollowupTimeout_w));
            e.put("retreatBehaviour", Mem.w(def + Defs.AlienT_RetreatBehaviour_w));
            e.put("retreatSpeed", Mem.w(def + Defs.AlienT_RetreatSpeed_w));
            e.put("retreatTimeout", Mem.w(def + Defs.AlienT_RetreatTimeout_w));
            e.put("damageToRetreat", Mem.w(def + Defs.AlienT_DamageToRetreat_w));
            e.put("damageToFollowup", Mem.w(def + Defs.AlienT_DamageToFollowup_w));
            e.put("anims", alienAnims(glf, i));
            l.add(e);
        }
        return l;
    }

    /**
     * Animations d'un alien : 11 OPTIONS (marche vue de face/droite/dos/gauche…, attaque, touché,
     * mort) de 20 frames de 11 octets (A_AnimLen = 11 * A_OptLen = 11 * 20 * A_FrameLen).
     *
     * <p>Une frame : {@code gfx} (feuille de sprites ; NÉGATIF = fin de l'animation, on reboucle),
     * {@code frame} (numéro + 1, NÉGATIF = miroir horizontal), {@code word2} (donnée de dessin, ou
     * incrément d'angle pour un modèle vectoriel), {@code sfx} (son + 1), {@code action}
     * (mordre/tirer sur cette frame), {@code special} (saut/attente codés), {@code aux}/{@code auxX}
     * /{@code auxY} (objet auxiliaire attaché, ex. la flamme d'un lance-flammes).
     * On s'arrête au terminateur inclus : c'est lui qui dit où l'animation reboucle.
     */
    private static List<Object> alienAnims(int glf, int def) {
        List<Object> opts = new ArrayList<>();
        for (int opt = 0; opt < 11; opt++) {
            int base = glf + Defs.GLFT_AlienAnims_l + def * Defs.A_AnimLen + opt * Defs.A_OptLen;
            List<Object> frames = new ArrayList<>();
            for (int f = 0; f < 20; f++) {
                int at = base + f * Defs.A_FrameLen;
                Map<String, Object> fr = new LinkedHashMap<>();
                fr.put("gfx", Mem.b(at));
                fr.put("frame", Mem.b(at + 1));
                fr.put("word2", Mem.w(at + 2));
                fr.put("sfx", Mem.ub(at + 5));
                fr.put("action", Mem.ub(at + 6));
                fr.put("special", Mem.ub(at + 7));
                fr.put("aux", Mem.b(at + 8));
                fr.put("auxX", Mem.b(at + 9));
                fr.put("auxY", Mem.b(at + 10));
                frames.add(fr);
                if (Mem.b(at) < 0) {
                    break;                               // terminateur inclus
                }
            }
            opts.add(frames);
        }
        return opts;
    }

    private static List<Object> guns(int glf) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < Defs.NUM_GUN_DEFS; i++) {
            int sh = glf + Defs.GLFT_ShootDefs_l + i * Defs.ShootT_SizeOf_l;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("name", PortReader.fixedStr(glf + Defs.GLFT_GunNames_l + i * 20, 20));
            e.put("bulletType", Mem.w(sh + Defs.ShootT_BulType_w));
            e.put("delay", Mem.w(sh + Defs.ShootT_Delay_w));
            e.put("bulletCount", Mem.w(sh + Defs.ShootT_BulCount_w));
            e.put("sfx", Mem.w(sh + Defs.ShootT_SFX_w));
            e.put("gunObject", Mem.w(glf + Defs.GLFT_GunObjects_l + i * 2));
            l.add(e);
        }
        return l;
    }

    /**
     * GLFT_AlienShootDefs : un enregistrement ShootT par définition d'alien. Il sert au tir
     * (hauteur de sortie du projectile, décalage latéral) ET — quirk du registre a2 hérité par
     * {@code Obj_DoCollision} — de table d'extents verticaux pour la collision entre entités.
     */
    /**
     * GLFT_FloorData : 16 types de sol, 4 octets chacun — mot fort = DEGATS infliges au joueur
     * qui s'y tient (sols toxiques), mot faible = bruitage de pas (- 1 a l'usage ; < 0 = aucun).
     * L'index vient de ZoneT_FloorNoise_w / ZoneT_UpperFloorNoise_w.
     */
    private static List<Object> floorData(int glf) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("damage", Mem.w(glf + Defs.GLFT_FloorData_l + i * 4));
            e.put("sfx", Mem.w(glf + Defs.GLFT_FloorData_l + i * 4 + 2));
            l.add(e);
        }
        return l;
    }

    private static List<Object> alienShootDefs(int glf) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < Defs.NUM_ALIEN_DEFS; i++) {
            int sh = glf + Defs.GLFT_AlienShootDefs_l + i * Defs.ShootT_SizeOf_l;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("bulletType", Mem.w(sh + Defs.ShootT_BulType_w));
            e.put("delay", Mem.w(sh + Defs.ShootT_Delay_w));
            e.put("bulletCount", Mem.w(sh + Defs.ShootT_BulCount_w));
            e.put("sfx", Mem.w(sh + Defs.ShootT_SFX_w));
            l.add(e);
        }
        return l;
    }

    private static List<Object> bullets(int glf) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < Defs.NUM_BULLET_DEFS; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("name", PortReader.fixedStr(glf + Defs.GLFT_BulletNames_l + i * 20, 20));
            // Définition complète du projectile (BulT) : ce qu'il faut pour le tirer et le faire
            // voler (cf. Newplayershoot.fireProjectile et Newanims.ItsABullet).
            int b = glf + Defs.GLFT_BulletDefs_l + i * Defs.BulT_SizeOf_l;
            e.put("hitScan", Mem.l(b + Defs.BulT_IsHitScan_l));
            e.put("gravity", Mem.l(b + Defs.BulT_Gravity_l));
            e.put("lifetime", Mem.l(b + Defs.BulT_Lifetime_l));
            e.put("ammoInClip", Mem.l(b + Defs.BulT_AmmoInClip_l));
            e.put("bounceHoriz", Mem.l(b + Defs.BulT_BounceHoriz_l));
            e.put("bounceVert", Mem.l(b + Defs.BulT_BounceVert_l));
            e.put("hitDamage", Mem.l(b + Defs.BulT_HitDamage_l));
            e.put("impactSFX", Mem.l(b + Defs.BulT_ImpactSFX_l));   // bruit d'impact (- 1 a l'usage)
            e.put("explosiveForce", Mem.l(b + Defs.BulT_ExplosiveForce_l));
            e.put("speed", Mem.l(b + Defs.BulT_Speed_l));
            e.put("animFrames", Mem.l(b + Defs.BulT_AnimFrames_l));
            e.put("popFrames", Mem.l(b + Defs.BulT_PopFrames_l));
            e.put("graphicType", Mem.l(b + Defs.BulT_GraphicType_l));
            e.put("impactGraphicType", Mem.l(b + Defs.BulT_ImpactGraphicType_l));
            e.put("animData", animSteps(b + Defs.BulT_AnimData_vb));
            e.put("popData", animSteps(b + Defs.BulT_PopData_vb));
            l.add(e);
        }
        return l;
    }

    private static List<Object> sfx(int glf) {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < Defs.NUM_SFX; i++) {
            // Pas de 64 octets (Res.Res_LoadSoundFx : adda.w #64,a0), pas 60 comme le laisse
            // croire le commentaire de Defs : avec 60 les noms derivent de 4 octets par entree.
            String file = PortReader.fixedStr(glf + Defs.GLFT_SFXFilenames_l + i * 64, 64);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("file", file);
            l.add(e);
        }
        return l;
    }

    private static Map<String, Object> files(int glf) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("floor", PortReader.fixedStr(glf + Defs.GLFT_FloorFilename_l, 64));
        e.put("texture", PortReader.fixedStr(glf + Defs.GLFT_TextureFilename_l, 192));
        e.put("gunGfx", PortReader.fixedStr(glf + Defs.GLFT_GunGFXFilename_l, 64));
        e.put("story", PortReader.fixedStr(glf + Defs.GLFT_StoryFilename_l, 64));
        return e;
    }

    private static Map<String, Object> playerGraphics(int glf) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("player1", Mem.w(glf + Defs.GLFT_Player1Graphic_w));
        e.put("player2", Mem.w(glf + Defs.GLFT_Player2Graphic_w));
        return e;
    }
}
