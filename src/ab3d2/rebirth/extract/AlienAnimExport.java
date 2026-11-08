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
 * Oracle du RECORD DE DESSIN D'UN MONSTRE : la queue de {@code ai_DoAttackAnim}.
 *
 * <p>L'oracle {@code objanim} eprouve deja ce qu'un objet du DECOR donne a voir. Un monstre,
 * lui, ne passe pas par la meme routine : son apparence se pose au bout de son animation, et
 * c'est la que les drapeaux de dessin naissent. Ce sont EUX qui decident de la quatrieme
 * boucle de sprite — celle qui se fabrique une palette a chaque image. Tant que ces drapeaux
 * restent a zero, la voie eclairee est branchee mais jamais prise.
 *
 * <p>Trois choses s'y jouent, et aucune ne se devine :
 *
 * <ul>
 *   <li>les demi-tailles partent de {@code $FFFF} et ne sont REECRITES que si le monstre n'est
 *       pas un modele en trois dimensions — un modele garde donc la marque ;</li>
 *   <li>l'octet de drapeaux re-naît a chaque image ({@code move.l #0,8(a0)} efface les quatre
 *       octets), prend 128 si l'image est retournee, puis se voit OU-er le type de dessin du
 *       monstre lui-meme : c'est ce OU qui allume l'eclairage directionnel ;</li>
 *   <li>l'objet AUXILIAIRE — l'eclat au bout d'un canon — est ecrit dans l'entite PRECEDENTE,
 *       avec sa propre definition, donc ses propres trois facons d'etre vu.</li>
 * </ul>
 *
 * <p>Vingt monstres x quatorze facons de choisir l'option d'animation x vingt images. L'option
 * zero passe par {@code ViewpointToDraw} : on fait tourner le monstre autour du regard pour
 * atteindre les quatre vues, sinon la moitie des options ne serait jamais lue.
 */
public final class AlienAnimExport {

    /** Combien de definitions de monstres. */
    private static final int ALIENS = 20;

    /** Combien d'options d'animation par monstre. */
    private static final int OPTIONS = 11;

    /** Combien d'images par option. */
    private static final int FRAMES = 20;

    /** Les orientations essayees pour l'option zero, qui passe par {@code ViewpointToDraw}. */
    private static final int[] ANGLES = {0, 2048, 4096, 6144};

    /** L'orientation du regard, fixe : c'est le monstre qui tourne. */
    private static final int REGARD = 1024;

    /** Le secteur seme dans l'enregistrement, pour que la copie vers l'auxiliaire se voie. */
    private static final int SECTEUR = 7;

    /** La hauteur semee, meme raison. */
    private static final int HAUTEUR = 1234;

    /**
     * Les definitions d'objet essayees comme AUXILIAIRE, en plus de celle que la donnee designe.
     *
     * <p>Les six monstres qui en portent un designent TOUS la meme — la vingt-et-unieme, un
     * halo. Les deux autres facons d'etre vu, le sprite et le modele, ne seraient donc jamais
     * lues : un oracle qui ne les atteint pas n'eprouve pas la routine, il eprouve la donnee.
     * On impose donc aussi une definition de sprite et une de modele.
     */
    private static final int[] AUXILIAIRES = {0, 3};

    private AlienAnimExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();
        LevelExport.loadLevelForTools(0);

        int glf = Mem.l(ab3d2.HiresData.GLF_DatabasePtr_l);
        // La DEUXIEME entite de la table sert de brouillon : la routine ecrit aussi dans celle
        // qui la precede (l'auxiliaire), et il faut donc qu'elle existe.
        int obj = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l) + ab3d2.Defs.ObjT_SizeOf_l;
        int aux = obj + ab3d2.Defs.ENT_PREV;
        int espace = ab3d2.bss.TablesBss.ObjectWorkspace_vl;
        Mem.wl(ab3d2.bss.TablesBss.WorkspacePtr_l, espace);
        Mem.ww(ab3d2.HiresData.Vis_AngPos_w, REGARD);

        List<Object> cas = new ArrayList<>();
        int n = 0;
        for (int type = 0; type < ALIENS; type++) {
            int def = glf + ab3d2.Defs.GLFT_AlienDefs_l + type * ab3d2.Defs.AlienT_SizeOf_l;
            int vecObj = Mem.ub(def + 1);                  // move.b 1(a1),AI_VecObj_w
            int auxObj = Mem.w(def + ab3d2.Defs.AlienT_Auxilliary_w);
            int anims = glf + ab3d2.Defs.GLFT_AlienAnims_l + type * ab3d2.Defs.A_AnimLen;
            // Ce monstre reclame-t-il un auxiliaire a l'une de ses images ? C'est une donnee du
            // fichier, pas un resultat de la routine : on peut la lire pour choisir le corpus.
            boolean porteUnAux = false;
            for (int i = 0; i < OPTIONS * FRAMES && !porteUnAux; i++) {
                porteUnAux = Mem.b(anims + i * ab3d2.Defs.A_FrameLen + 8) >= 0;
            }
            int[] auxDefs = porteUnAux
                    ? new int[] {auxObj, AUXILIAIRES[0], AUXILIAIRES[1]}
                    : new int[] {auxObj};
            for (int v = 0; v < OPTIONS + ANGLES.length - 1; v++) {
                // v = 0..3 : l'option zero, par quatre orientations ; v >= 4 : les options 1 a 10.
                boolean parLaVue = v < ANGLES.length;
                int vue = parLaVue ? 0 : v - ANGLES.length + 1;
                int angle = parLaVue ? ANGLES[v] : ANGLES[v % ANGLES.length];
                // Ou s'arrete cette option : au-dela du terminateur, l'original lit la suite
                // de la table et le jeu n'y va jamais. On ne releve que ce qui est atteignable.
                Mem.ww(obj + ab3d2.Defs.EntT_CurrentAngle_w, angle);
                Mem.wb(ab3d2.bss.AiBss.AI_VecObj_w, vecObj);
                int fin = terminateur(anims, option(obj, vecObj, vue));
                for (int image = 0; image <= fin; image++) {
                    for (int quelAux : auxDefs) {
                        int force = (n % 5 == 2) ? (image + 3) % (fin + 1) : -1;
                        cas.add(unCas(obj, aux, espace, anims, type, vecObj, quelAux,
                                vue, angle, image, force));
                        n++;
                    }
                }
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("cases", cas);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("alienanim.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[alienanim] %d appels (%d monstres x %d facons x les images"
                + " atteignables, trois auxiliaires pour ceux qui en portent un) -> %s%n",
                cas.size(), ALIENS, OPTIONS + ANGLES.length - 1, f);
    }

    /**
     * L'option d'animation REELLEMENT lue.
     *
     * <p>Zero ne veut pas dire la premiere : il veut dire « demande a {@code ViewpointToDraw} »,
     * et la reponse depend de l'orientation du monstre par rapport au regard. Un modele en
     * relief, lui, ne se retourne pas — il garde l'option zero.
     */
    private static int option(int obj, int vecObj, int vue) {
        if (vue != 0 || (byte) vecObj == 1) {
            return vue;
        }
        return ab3d2.Newaliencontrol.ViewpointToDraw(obj) * 2;
    }

    /**
     * Ou s'arrete une option : son terminateur, ou la vingtieme image faute de terminateur.
     *
     * <p>C'est une donnee du fichier, pas un resultat de la routine : on peut la lire pour
     * borner le releve sans que la verification s'acquitte elle-meme.
     */
    private static int terminateur(int anims, int opt) {
        for (int i = 0; i < FRAMES; i++) {
            if (Mem.b(anims + opt * ab3d2.Defs.A_OptLen + i * ab3d2.Defs.A_FrameLen) < 0) {
                return i;
            }
        }
        return FRAMES - 1;
    }

    /** Un appel : on pose l'entree, on appelle, on releve ce qui a bouge. */
    private static Map<String, Object> unCas(int obj, int aux, int espace, int anims,
            int type, int vecObj, int auxObj, int vue, int angle, int image, int force) {
        // Les deux enregistrements, remis a plat : on veut voir ce que la routine ECRIT.
        for (int i = 0; i < ab3d2.Defs.ObjT_SizeOf_l; i++) {
            Mem.wb(obj + i, 0);
            Mem.wb(aux + i, 0);
        }
        Mem.wb(obj + ab3d2.Defs.EntT_Type_b, type);
        Mem.ww(obj + ab3d2.Defs.EntT_Timer2_w, image);
        Mem.ww(obj + ab3d2.Defs.EntT_CurrentAngle_w, angle);
        Mem.ww(obj + ab3d2.Defs.ObjT_ZoneID_w, SECTEUR);
        Mem.ww(obj + 4, HAUTEUR);
        Mem.wb(obj + ab3d2.Defs.ShotT_InUpperZone_b, 0xFF);

        // Le brouillon d'animation (ObjectWorkspace, huit octets) : action, image forcee, option,
        // et le drapeau de fin de boucle. La routine les CONSOMME, on seme donc de quoi le voir.
        Mem.wb(espace, 0x33);                          // (a5)  : action en attente
        Mem.wb(espace + 1, force < 0 ? 0xFF : force);  // 1(a5) : image forcee ($FF = aucune)
        Mem.wb(espace + 2, vue);                       // 2(a5) : option d'animation
        Mem.wb(espace + 3, 0x55);                      // 3(a5) : l'animation vient de boucler

        // Ce que ItsAnAlien pose avant d'arriver ici.
        Mem.wl(ab3d2.NewaliencontrolData.AlienAnimPtr_l, anims);
        Mem.ww(ab3d2.HiresData.AUXOBJ, auxObj);
        Mem.wb(ab3d2.bss.AiBss.AI_VecObj_w, vecObj);

        int opt = option(obj, vecObj, vue);
        int fin = terminateur(anims, opt);

        // L'image REELLEMENT lue, et ce qu'elle reclame comme auxiliaire : deux entrees de la
        // routine, relevees avant l'appel pour que la verification ne les redecouvre pas seule.
        int lue = force < 0 ? image : force;
        int ligne = anims + opt * ab3d2.Defs.A_OptLen + lue * ab3d2.Defs.A_FrameLen;
        int pasAux = Mem.b(ligne + 8);

        ab3d2.modules.Ai.ai_DoAttackAnim(obj);

        Map<String, Object> un = new LinkedHashMap<>();
        un.put("type", type);
        un.put("vecObj", vecObj);
        un.put("auxObj", auxObj);
        un.put("view", vue);
        un.put("angle", angle);
        un.put("frame", image);
        un.put("forced", force);
        un.put("opt", opt);
        un.put("last", fin);
        un.put("used", lue);
        un.put("auxStep", pasAux);
        un.put("halfWidth", Mem.ub(obj + 6));
        un.put("halfDepth", Mem.ub(obj + 7));
        un.put("sheet", Mem.uw(obj + 8));
        un.put("flags", Mem.ub(obj + 10));
        un.put("image", Mem.ub(obj + 11));
        un.put("animFacing", Mem.uw(ab3d2.bss.AiBss.ai_AnimFacing_w));
        un.put("doAction", Mem.ub(ab3d2.bss.AiBss.ai_DoAction_b));
        un.put("finished", Mem.ub(ab3d2.bss.AiBss.ai_FinishedAnim_b));
        un.put("wsAction", Mem.ub(espace));
        un.put("wsSpecial", Mem.ub(espace + 1));
        un.put("wsFinished", Mem.ub(espace + 3));
        un.put("auxZone", Mem.w(aux + ab3d2.Defs.ObjT_ZoneID_w));
        un.put("auxEntZone", Mem.w(aux + ab3d2.Defs.EntT_ZoneID_w));
        un.put("auxHeight", Mem.w(aux + 4));
        un.put("auxUpper", Mem.ub(aux + ab3d2.Defs.ShotT_InUpperZone_b));
        un.put("auxOffX", Mem.w(aux + ab3d2.Defs.ShotT_AuxOffsetX_w));
        un.put("auxOffY", Mem.w(aux + ab3d2.Defs.ShotT_AuxOffsetY_w));
        un.put("auxHalfWidth", Mem.ub(aux + 6));
        un.put("auxHalfDepth", Mem.ub(aux + 7));
        un.put("auxSheet", Mem.uw(aux + 8));
        un.put("auxFlags", Mem.ub(aux + 10));
        un.put("auxImage", Mem.ub(aux + 11));
        return un;
    }
}
