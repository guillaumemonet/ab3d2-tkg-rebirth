package ab3d2.rebirth.extract;

import ab3d2.Mem;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle de l'APPARENCE des choses : ce qu'un pas d'animation donne à voir.
 *
 * <p>C'est le chaînon qui manquait entre la simulation et le dessin. Les champs de dessin d'une
 * chose — la feuille, l'image, les drapeaux, les demi-tailles — sont tous à ZÉRO dans le
 * fichier de niveau ; c'est {@code animObj} qui les remplit à chaque image, d'après le pas où
 * l'animation en est. Les relevés des sprites contournaient ce trou en SEMANT ces champs ;
 * celui-ci éprouve la routine qui les pose vraiment.
 *
 * <p>Trente définitions d'objets, deux scripts chacune (le repos et l'activation), vingt pas
 * par script : douze cents appels, et on relève à chaque fois les douze premiers octets de
 * l'enregistrement plus ce que la routine a avancé.
 *
 * <p>On sème la hauteur et l'angle de départ, car la routine les CUMULE : sans valeur de départ
 * connue, on ne saurait pas dire ce qu'elle y a ajouté.
 */
public final class ObjAnimExport {

    /** Combien de définitions d'objets. */
    private static final int DEFS = 30;

    /** Combien de pas par script. */
    private static final int PAS = 20;

    /** La hauteur de départ semée. */
    private static final int HAUTEUR = 1000;

    /** L'angle de départ semé. */
    private static final int ANGLE = 777;

    private ObjAnimExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        LevelExport.loadLevelForTools(0);

        int glf = Mem.l(ab3d2.HiresData.GLF_DatabasePtr_l);
        // Un enregistrement d'objet quelconque sert de brouillon : la routine n'y lit que le
        // type, le pas courant, la hauteur et l'angle, et c'est nous qui les posons.
        int obj = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);

        List<Object> cas = new ArrayList<>();
        for (int type = 0; type < DEFS; type++) {
            int def = glf + ab3d2.Defs.GLFT_ObjectDefs + type * ab3d2.Defs.ODefT_SizeOf_l;
            int gfxType = Mem.w(def + ab3d2.Defs.ODefT_GFXType_w);
            for (int script = 0; script < 2; script++) {
                for (int pas = 0; pas < PAS; pas++) {
                    Map<String, Object> un = unCas(obj, def, type, gfxType, script, pas);
                    cas.add(un);
                }
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("objanim.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[objanim] %d appels (%d definitions x 2 scripts x %d pas) -> %s%n",
                cas.size(), DEFS, PAS, f);
    }

    /** Un appel : on pose l'entrée, on appelle, on relève ce qui a bougé. */
    private static Map<String, Object> unCas(int obj, int def, int type, int gfxType,
            int script, int pas) {
        // L'enregistrement, remis a plat avant chaque appel.
        for (int i = 0; i < 12; i++) {
            Mem.wb(obj + i, 0);
        }
        Mem.wb(obj + ab3d2.Defs.EntT_Type_b, type);
        Mem.ww(obj + ab3d2.Defs.EntT_Timer1_w, pas);
        Mem.ww(obj + 4, HAUTEUR);
        Mem.ww(obj + ab3d2.Defs.EntT_CurrentAngle_w, ANGLE);

        if (script == 0) {
            ab3d2.Newaliencontrol.DEFANIMOBJ(obj, def);
        } else {
            ab3d2.Newaliencontrol.ACTANIMOBJ(obj, def);
        }

        Map<String, Object> un = new LinkedHashMap<>();
        un.put("type", type);
        un.put("gfxType", gfxType);
        un.put("script", script);
        un.put("step", pas);
        un.put("sheet", Mem.uw(obj + 8));
        un.put("flags", Mem.ub(obj + 10));
        un.put("frame", Mem.ub(obj + 11));
        un.put("halfWidth", Mem.ub(obj + 6));
        un.put("halfDepth", Mem.ub(obj + 7));
        un.put("height", Mem.w(obj + 4));
        un.put("next", Mem.w(obj + ab3d2.Defs.EntT_Timer1_w));
        un.put("angle", Mem.uw(obj + ab3d2.Defs.EntT_CurrentAngle_w));
        return un;
    }
}
