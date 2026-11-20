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
 * Oracle de l'ARME EN MAIN : ou le jeu la pose, a chaque image, avant de la dessiner.
 *
 * <p>L'arme du joueur n'est pas un decor plaque sur l'image : c'est un OBJET du monde, qui
 * occupe l'entite situee deux rangs apres celle du joueur, et que le rendu traite comme
 * n'importe quel modele. La queue de {@code Plr1_Use} (hires.s:2487) la repose entierement a
 * chaque image :
 *
 * <ul>
 *   <li>elle regarde le joueur — son angle vaut celui du joueur plus un DEMI-TOUR ;</li>
 *   <li>elle est dans son secteur, et emprunte son point : les deux coordonnees du joueur sont
 *       recopiees telles quelles dans le point de l'arme ;</li>
 *   <li>son TYPE est l'objet que la base associe a l'arme choisie, et son animation est
 *       toujours celle de l'ACTIVATION — c'est elle qui porte le recul du tir ;</li>
 *   <li>sa hauteur se calcule depuis l'oeil : la taille du joueur au quart, plus mille deux
 *       cent quatre-vingt, le tout ramene de sept bits, et le balancement du pas y ajoute une
 *       fois et demie sa valeur.</li>
 * </ul>
 *
 * <p>Le releve couvre les dix armes et un eventail de poses, de hauteurs, de tailles et de
 * balancements, y compris ceux qui font deborder les seize bits du mot de hauteur.
 */
public final class GunHoldExport {

    private static final int GUNS = 10;

    private GunHoldExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        LevelExport.loadLevelForTools(0);

        int joueur = Mem.l(ab3d2.bss.PlayerBss.Plr1_ObjectPtr_l);
        int arme = joueur + Defs.ENT_NEXT_2;
        int points = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);

        int[][] poses = {
            //  x,      z,     angle, oeil,     taille,  balancement, enHaut
            {  1000,  2000,       0,  0x60000,  0x30000,          0, 0},
            {  1000,  2000,    1024,  0x60000,  0x30000,       1024, 0},
            { -5000,  7000,    2048,  0x10000,  0x30000,      -1024, 1},
            { 32000, -32000,   4095,  0x80000,  0x18000,       4096, 0},
            {     0,      0,   6143, -0x20000,  0x30000,      -4096, 1},
            {   777,   -777,   8188,  0x7F0000, 0x7F0000,      32767, 0},
            {  -777,    777,   3333, -0x7F0000, 0x7F0000,     -32768, 1},
            {   100,    200,     512,  0x40000, 0x00000,          1, 0},
            // Celle-ci est choisie pour que la hauteur frole le bord du mot APRES la pose,
            // et que le balancement l'y fasse deborder.
            {   300,    400,    2730,  4135168, 0x30000,      32767, 0},
        };

        List<Object> cas = new ArrayList<>();
        for (int gun = 0; gun < GUNS; gun++) {
            for (int[] p : poses) {
                cas.add(unCas(joueur, arme, points, gun, p));
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("playerEntity", Mem.uw(joueur));
        root.put("gunObjects", gunObjects());
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("gunhold.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[gunhold] %d poses d'arme en main (%d armes x %d poses) -> %s%n",
                cas.size(), GUNS, poses.length, f);
    }

    /** GLFT_GunObjects_l : l'objet que chaque arme fait tenir au joueur. */
    private static List<Integer> gunObjects() {
        int glf = PortReader.glf();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < GUNS; i++) {
            out.add(Mem.uw(glf + Defs.GLFT_GunObjects_l + i * 2));
        }
        return out;
    }

    private static Map<String, Object> unCas(int joueur, int arme, int points, int gun,
            int[] p) {
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_XOff_l, p[0] << 16);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_ZOff_l, p[1] << 16);
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_TmpAngPos_w, p[2]);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_TmpYOff_l, p[3]);
        Mem.wl(ab3d2.bss.PlayerBss.plr1_TmpHeight_l, p[4]);
        Mem.wl(ab3d2.bss.PlayerBss.plr1_BobbleY_l, p[5]);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_StoodInTop_b, p[6] != 0 ? 0xFF : 0);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_TmpGunSelected_b, gun);
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_Health_w, 100);     // vivant : sinon l'arme est liberee
        Mem.wb(joueur + Defs.EntT_DamageTaken_b, 0);        // pas de recul a appliquer

        // L'enregistrement de l'arme, remis a plat : c'est la routine qui doit tout poser.
        Mem.ww(arme + Defs.EntT_CurrentAngle_w, 0);
        Mem.ww(arme + Defs.ObjT_ZoneID_w, 0);
        Mem.ww(arme + Defs.EntT_ZoneID_w, 0);
        Mem.wb(arme + Defs.EntT_Type_b, 0);
        Mem.wb(arme + Defs.ObjT_TypeID_b, 0);
        Mem.wb(arme + Defs.EntT_WhichAnim_b, 0);
        Mem.ww(arme + 4, 0);
        Mem.wb(arme + Defs.ShotT_InUpperZone_b, 0);

        ab3d2.Hires.Plr1_Use();

        int point = Mem.uw(arme);
        Map<String, Object> un = new LinkedHashMap<>();
        un.put("gun", gun);
        un.put("x", p[0]);
        un.put("z", p[1]);
        un.put("angle", p[2]);
        un.put("eye", p[3]);
        un.put("height", p[4]);
        un.put("bobbleY", p[5]);
        un.put("upper", p[6]);
        un.put("outAngle", Mem.uw(arme + Defs.EntT_CurrentAngle_w));
        un.put("outZone", Mem.uw(arme + Defs.ObjT_ZoneID_w));
        un.put("outEntZone", Mem.uw(arme + Defs.EntT_ZoneID_w));
        un.put("outType", Mem.ub(arme + Defs.EntT_Type_b));
        un.put("outTypeId", Mem.ub(arme + Defs.ObjT_TypeID_b));
        un.put("outWhichAnim", Mem.ub(arme + Defs.EntT_WhichAnim_b));
        un.put("outHeight", Mem.w(arme + 4));
        un.put("outUpper", Mem.ub(arme + Defs.ShotT_InUpperZone_b));
        un.put("outPoint", point);
        un.put("outPointX", Mem.l(points + point * 8));
        un.put("outPointZ", Mem.l(points + point * 8 + 4));
        un.put("playerZone", Mem.uw(Mem.l(ab3d2.bss.PlayerBss.Plr1_ZonePtr_l)));
        un.put("anim", animSteps(arme));
        return un;
    }

    /**
     * Ce que {@code GUNHELD} fait ensuite : {@code ACTANIMOBJ}, pour chaque pas ou l'animation
     * peut en etre.
     *
     * <p>L'angle et la hauteur que la pose vient d'ecrire sont repris tels quels : la routine
     * y AJOUTE les siens. On repart donc de l'enregistrement pose, a chaque pas.
     */
    private static List<Object> animSteps(int arme) {
        int glf = PortReader.glf();
        int type = Mem.ub(arme + Defs.EntT_Type_b);
        int def = glf + Defs.GLFT_ObjectDefs + type * Defs.ODefT_SizeOf_l;
        int angle0 = Mem.uw(arme + Defs.EntT_CurrentAngle_w);
        int hauteur0 = Mem.w(arme + 4);

        List<Object> out = new ArrayList<>();
        for (int pas = 0; pas < 20; pas++) {
            Mem.ww(arme + Defs.EntT_CurrentAngle_w, angle0);
            Mem.ww(arme + 4, hauteur0);
            Mem.ww(arme + Defs.EntT_Timer1_w, pas);
            Mem.wl(arme + 8, 0);
            Mem.ww(arme + 6, 0);
            ab3d2.Newaliencontrol.ACTANIMOBJ(arme, def);

            Map<String, Object> un = new LinkedHashMap<>();
            un.put("step", pas);
            un.put("gfxType", Mem.w(def + Defs.ODefT_GFXType_w));
            un.put("sheet", Mem.uw(arme + 8));
            un.put("flags", Mem.ub(arme + 10));
            un.put("frame", Mem.ub(arme + 11));
            un.put("halfWidth", Mem.ub(arme + 6));
            un.put("halfDepth", Mem.ub(arme + 7));
            un.put("angle", Mem.uw(arme + Defs.EntT_CurrentAngle_w));
            un.put("height", Mem.w(arme + 4));
            un.put("next", Mem.w(arme + Defs.EntT_Timer1_w));
            out.add(un);
        }
        // On remet l'enregistrement tel que la pose l'avait laisse.
        Mem.ww(arme + Defs.EntT_CurrentAngle_w, angle0);
        Mem.ww(arme + 4, hauteur0);
        return out;
    }
}
