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
 * Oracle de la VIE DES OBJETS : ce que la boucle fait, a chaque image, de chaque chose posee
 * dans le niveau.
 *
 * <p>Le fichier de niveau ne dit d'un objet que ou il est et de quel genre il est. Tout le
 * reste — l'image qu'il montre, la hauteur a laquelle il flotte, l'angle sous lequel on le
 * voit, les portes qu'il tient fermees — est REFAIT a chaque image par {@code ItsAnObject}
 * (newaliencontrol.s:86), qui range les choses en quatre familles :
 *
 * <ul>
 *   <li>le RAMASSABLE, qui tient ses serrures tant qu'il est au sol ;</li>
 *   <li>l'INTERRUPTEUR, qui les tient tant qu'il n'est pas enclenche ;</li>
 *   <li>le DESTRUCTIBLE, qui les tient tant qu'il n'est pas casse ;</li>
 *   <li>le DECOR, qui ne tient rien et se contente de vivre.</li>
 * </ul>
 *
 * <p>Et le destructible est la seule des quatre a lire les degats qu'elle a pris. Tant qu'ils
 * restent sous {@code ODefT_HitPoints_w}, {@code StillHere} la declare vivante, pose ses
 * serrures, cherche le joueur du regard — puis TOMBE dans {@code Decoration}, sans {@code rts}
 * entre les deux : une chose intacte s'anime donc exactement comme une lampe. Passe le seuil,
 * elle dit son texte une fois, se declare morte, et joue l'animation d'ACTIVATION. C'est tout
 * le mecanisme de l'ordinateur qu'il faut detruire pour que la porte de sortie s'ouvre.
 *
 * <h2>Ce qu'on seme, et pourquoi</h2>
 *
 * <p>Le jeu n'anime que les objets qu'il a VUS : {@code markVisibleAndWorry} (hires.s:2058)
 * arme {@code ShotT_Worry_b} sur les choses des secteurs visibles, et les quatre familles
 * commencent par le tester. Le portage ne calcule pas cette visibilite ; on arme donc le
 * drapeau sur TOUT, a chaque image, comme le ferait un joueur qui verrait le niveau entier.
 *
 * <p>Les degats sont POSES et non ajoutes, pour que le rejeu soit exact — et l'echelle passe
 * par le seuil moins un, le seuil, le retour a zero (une chose cassee REVIT si ses degats
 * retombent : rien ne verrouille), le maximum d'un octet, et le seuil plus un.
 *
 * <h2>Deux series</h2>
 *
 * <p>Dans la premiere, le joueur est pose hors de toute zone ({@code Plr1_Zone_w = -1}) : rien
 * ne se ramasse et rien ne s'enclenche, et le releve ne porte que sur ce que la boucle fait
 * d'elle-meme.
 *
 * <p>Dans la seconde, il est TELEPORTE sur une chose, quatre images durant, et ainsi de suite
 * de chose en chose. Sans elle, un ramassable qui ne disparait jamais ne dirait rien de ce
 * qu'il devient, ni un interrupteur qu'on n'enclenche pas de son compteur. Les quatre temps
 * sont choisis pour FROLER les deux bords de la portee : l'oeil se pose pile sur la chose,
 * puis juste a la tolerance verticale — ou l'egalite passe encore —, puis un cran au-dela ou
 * elle ne passe plus, puis pile dessus a nouveau. La touche d'action tombe au premier et au
 * dernier des quatre, de sorte qu'un interrupteur s'enclenche et se DESENCLENCHE a la main,
 * avant que son delai n'expire.
 *
 * <p>La troisieme est la meme, l'inventaire PLEIN : tout ce qui ne donne qu'un consommable
 * reste alors au sol, et c'est le seul endroit ou l'on voie qu'une CLE, elle, se ramasse
 * toujours.
 *
 * <p>L'etat du joueur et l'inventaire de depart sont EXPORTES, pour que le rejeu n'ait rien a
 * redeviner.
 */
public final class ObjectLifeExport {

    /**
     * Les niveaux releves. Le premier pour son decor ; le deuxieme pour son INTERRUPTEUR, le
     * seul du jeu a tenir une serrure du mot fort et a n'avoir aucun delai ; les quatre autres
     * pour leurs cassables et leurs chargeurs.
     */
    private static final int[] NIVEAUX = {0, 2, 6, 12, 13, 14};

    /** Combien d'images par niveau. */
    private static final int IMAGES = 240;

    /** Le drapeau que markVisibleAndWorry arme : {@code or.b #127,ShotT_Worry_b}. */
    private static final int WORRY = 127;

    private ObjectLifeExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();

        List<Object> niveaux = new ArrayList<>();
        int etats = 0;
        for (int lvl : NIVEAUX) {
            Map<String, Object> un = unNiveau(lvl);
            niveaux.add(un);
            for (String quelle : new String[] {"idle", "contact", "full"}) {
                @SuppressWarnings("unchecked")
                Map<String, Object> serie = (Map<String, Object>) un.get(quelle);
                etats += ((List<?>) serie.get("frames")).size()
                        * ((List<?>) serie.get("entities")).size();
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("frames", IMAGES);
        root.put("levels", niveaux);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("objectlife.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[objectlife] %d niveaux, %d etats d'objet -> %s%n",
                NIVEAUX.length, etats, f);
    }

    private static Map<String, Object> unNiveau(int lvl) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", String.valueOf((char) ('a' + lvl)));
        out.put("index", lvl);
        out.put("idle", uneSerie(lvl, 0));
        out.put("contact", uneSerie(lvl, 1));
        out.put("full", uneSerie(lvl, 2));
        return out;
    }

    /**
     * Une serie : zero, le joueur loin de tout ; un, pose sur chaque chose a tour de role ;
     * deux, la meme chose l'inventaire plein.
     */
    private static Map<String, Object> uneSerie(int lvl, int quelle) {
        LevelExport.loadLevelForTools(lvl);
        boolean contact = quelle != 0;
        if (quelle == 2) {
            remplirInventaire();
        }

        // Le jeu solo : c'est la seule forme ou le texte d'une chose cassee s'affiche, et la
        // seule ou les serrures se posent (AI_NoEnemies_b, arme par Game_Start a un joueur).
        Mem.wb(ab3d2.bss.PlayerBss.Plr_MultiplayerType_b, ab3d2.Hires.PLR_SINGLE);
        Mem.wb(ab3d2.bss.AiBss.AI_NoEnemies_b, 0xFF);
        Mem.ww(ab3d2.bss.AnimBss.Anim_TempFrames_w, 1);

        Map<String, Object> debut = inventaire();       // avant la premiere image
        int objData = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectDataPtr_l);
        List<Integer> rangs = new ArrayList<>();       // les rangs d'entite de type OBJET
        List<Integer> visites = new ArrayList<>();     // l'ordre ou le joueur va les voir
        List<Object> entites = new ArrayList<>();
        int a0 = objData - Defs.ObjT_SizeOf_l;
        for (int k = 0; k < 4000; k++) {
            a0 += Defs.ObjT_SizeOf_l;
            if (Mem.w(a0) < 0) {                       // la liste finit sur -1
                break;
            }
            if (Mem.ub(a0 + Defs.ObjT_TypeID_b) != 1) {
                continue;                              // seuls les OBJETS passent par ItsAnObject
            }
            int def = Mem.ub(a0 + Defs.EntT_Type_b);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("rank", k);
            e.put("def", def);
            e.put("behaviour", Mem.w(defAddr(def) + Defs.ODefT_Behaviour_w));
            e.put("hitPoints", Mem.w(defAddr(def) + Defs.ODefT_HitPoints_w));
            // L'etat de DEPART, tel que le chargement du niveau le laisse : c'est de la que
            // le rejeu doit partir, et rien d'autre ne le dirait.
            e.put("start", etat(a0));
            entites.add(e);
            rangs.add(k);
        }
        // Le joueur ne visite qu'une chose toutes les huit images, et deux cent quarante
        // images ne suffisent pas a faire le tour des cent quarante-quatre choses du niveau o.
        // On va donc voir d'abord UNE chose de chaque definition ET DE CHAQUE ETAGE — une
        // vingtaine au plus, et ainsi aucune famille ne dort, pas meme l'entite du niveau n
        // dont l'octet d'etage ne vaut ni zero ni deux cent cinquante-cinq et qui n'est donc
        // d'AUCUN des deux. Le reste suit dans l'ordre du niveau. Cela ne change rien a la
        // boucle, qui visite toujours toutes les choses dans l'ordre du niveau ; cela ne
        // change que ou le joueur se tient.
        List<Integer> vues = new ArrayList<>();
        for (int rang : rangs) {
            int e = objData + rang * Defs.ObjT_SizeOf_l;
            int quoi = Mem.ub(e + Defs.EntT_Type_b) * 256 + Mem.ub(e + Defs.ShotT_InUpperZone_b);
            if (!vues.contains(quoi)) {
                vues.add(quoi);
                visites.add(rang);
            }
        }
        for (int rang : rangs) {
            if (!visites.contains(rang)) {
                visites.add(rang);
            }
        }

        // L'echelle de degats : posee, pas ajoutee, pour que le rejeu soit exact.
        List<Object> poses = new ArrayList<>();
        List<Object> images = new ArrayList<>();
        for (int f = 0; f < IMAGES; f++) {
            Map<String, Object> joueur = contact && !visites.isEmpty()
                    ? poseJoueur(objData, visites.get((f / TEMPS) % visites.size()), f)
                    : loinDeTout();
            // move.w #0,Anim_DoorAndLiftLocks_l : la routine des portes efface le MOT fort, et
            // la boucle des objets le reconstruit entierement a chaque image.
            Mem.ww(ab3d2.bss.AnimBss.Anim_DoorAndLiftLocks_l, 0);

            for (int rang : rangs) {
                int e = objData + rang * Defs.ObjT_SizeOf_l;
                int def = Mem.ub(e + Defs.EntT_Type_b);
                int seuil = Mem.w(defAddr(def) + Defs.ODefT_HitPoints_w);
                int degats = degats(f, seuil);
                if (degats >= 0) {
                    Mem.wb(e + Defs.EntT_DamageTaken_b, degats);
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("frame", f);
                    p.put("rank", rang);
                    p.put("value", degats);
                    poses.add(p);
                }
                // markVisibleAndWorry : tout est vu.
                Mem.wb(e + Defs.ShotT_Worry_b, Mem.ub(e + Defs.ShotT_Worry_b) | WORRY);
                // La boucle recopie le secteur AVANT de choisir la famille, et saute les
                // retires (newanims.s:1782).
                Mem.ww(e + Defs.EntT_ZoneID_w, Mem.uw(e + Defs.ObjT_ZoneID_w));
                if (Mem.w(e + Defs.ObjT_ZoneID_w) < 0) {
                    continue;
                }
                ab3d2.Newaliencontrol.ItsAnObject(e);
            }

            Map<String, Object> img = new LinkedHashMap<>();
            img.put("frame", f);
            img.put("player", joueur);
            img.put("locks", Mem.uw(ab3d2.bss.AnimBss.Anim_DoorAndLiftLocks_l));
            img.put("inventory", inventaire());
            List<Object> etats = new ArrayList<>();
            for (int rang : rangs) {
                etats.add(etat(objData + rang * Defs.ObjT_SizeOf_l));
            }
            img.put("objects", etats);
            images.add(img);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("startInventory", debut);
        out.put("entities", entites);
        out.put("damage", poses);
        out.put("frames", images);
        return out;
    }

    /** Le joueur hors de toute zone : rien ne se ramasse, rien ne s'enclenche. */
    private static Map<String, Object> loinDeTout() {
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_Zone_w, -1);
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_TmpSpcTap_b, 0);
        return etatJoueur(false);
    }

    /**
     * Le joueur pose SUR une chose : meme secteur, meme etage, memes coordonnees, et une
     * hauteur d'oeil qui tombe pile sur celle que l'animation lui a donnee a l'image
     * precedente.
     *
     * <p>L'etage se pose a zero ou a deux cent cinquante-cinq, comme le jeu le fait : une
     * entite qui porte un autre octet n'est d'AUCUN des deux, et le joueur ne peut donc pas
     * l'atteindre. Le niveau n en a une.
     */
    private static Map<String, Object> poseJoueur(int objData, int rang, int image) {
        int e = objData + rang * Defs.ObjT_SizeOf_l;
        int haut = Mem.ub(e + Defs.ShotT_InUpperZone_b);
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_Zone_w, Mem.uw(e + Defs.ObjT_ZoneID_w));
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_StoodInTop_b, haut == 0 ? 0 : 0xFF);
        int points = Mem.l(ab3d2.bss.LevelBss.Lvl_ObjectPointsPtr_l);
        int pt = Mem.uw(e);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_XOff_l, Mem.w(points + pt * 8) << 16);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_ZOff_l, Mem.w(points + pt * 8 + 4) << 16);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_Height_l, TAILLE);

        // Les huit temps. Le joueur arrive SANS appuyer — sinon rien ne dirait que la touche
        // est requise —, enclenche au deuxieme, frole les deux bords de la tolerance
        // verticale aux troisieme et quatrieme, se desenclenche A LA MAIN au sixieme, puis
        // reenclenche au dernier et s'en va : le delai de l'interrupteur expirera tout seul,
        // bien apres son depart.
        //
        // La tolerance se compare par « plus grand que », donc l'egalite PASSE : on s'y pose
        // exactement, puis un cran plus loin ou elle ne passe plus. La hauteur visee est celle
        // que l'animation a donnee a la chose a l'image precedente, et c'est la seule qu'on
        // puisse connaitre avant de l'appeler.
        int tolerance = Mem.w(defAddr(Mem.ub(e + Defs.EntT_Type_b))
                + Defs.ODefT_CollideHeight_w);
        int sien = Mem.w(e + 4);
        int temps = image % TEMPS;
        int vise = switch (temps) {
            case 2 -> sien + tolerance;                // pile a la tolerance : ca passe
            case 3 -> sien - tolerance - 1;            // un cran au-dela : ca ne passe plus
            default -> sien;                           // pile dessus
        };
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_YOff_l, (vise << 7) - TAILLE / 2);
        boolean action = temps == 1 || temps == 5 || temps == TEMPS - 1;
        Mem.wb(ab3d2.bss.PlayerBss.Plr1_TmpSpcTap_b, action ? 0xFF : 0);
        return etatJoueur(action);
    }

    /** Combien d'images le joueur reste sur une meme chose. */
    private static final int TEMPS = 8;

    /**
     * L'inventaire pousse a ses PLAFONDS ({@code gmp_MaxInventory}) : tout ce qui ne donne
     * qu'un consommable reste alors au sol. Une cle, elle, se ramasse quand meme — c'est la
     * premiere ligne de {@code Plr1_CollectItem}, et rien d'autre ne l'eprouve.
     */
    private static void remplirInventaire() {
        int plafonds = ab3d2.bss.TablesBss.game_ModProps + Defs.GModT_MaxInv
                + Defs.InvCT_Health_w;
        int at = ab3d2.bss.PlayerBss.Plr1_Invetory_vw;
        for (int i = 0; i < 22; i++) {
            Mem.ww(at + i * 2, Mem.uw(plafonds + i * 2));
        }
    }

    /** La taille du joueur debout ({@code Plr1_Height_l}). */
    private static final int TAILLE = 0x3000;

    private static Map<String, Object> etatJoueur(boolean action) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("zone", Mem.w(ab3d2.bss.PlayerBss.Plr1_Zone_w));
        p.put("upper", Mem.ub(ab3d2.bss.PlayerBss.Plr1_StoodInTop_b));
        p.put("x", Mem.l(ab3d2.bss.PlayerBss.Plr1_XOff_l));
        p.put("z", Mem.l(ab3d2.bss.PlayerBss.Plr1_ZOff_l));
        p.put("y", Mem.l(ab3d2.bss.PlayerBss.Plr1_YOff_l));
        p.put("height", Mem.l(ab3d2.bss.PlayerBss.Plr1_Height_l));
        p.put("action", action);
        return p;
    }

    /** Plr1_Invetory_vw : les vingt-deux consommables puis les douze objets. */
    private static Map<String, Object> inventaire() {
        List<Integer> cons = new ArrayList<>();
        List<Integer> objs = new ArrayList<>();
        int at = ab3d2.bss.PlayerBss.Plr1_Invetory_vw;
        for (int i = 0; i < 22; i++) {
            cons.add(Mem.w(at + i * 2));
        }
        for (int i = 0; i < 12; i++) {
            objs.add(Mem.w(at + 44 + i * 2));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("consumables", cons);
        m.put("items", objs);
        return m;
    }

    /**
     * Les degats a POSER a cette image, ou {@code -1} pour n'y pas toucher.
     *
     * <p>L'echelle passe par les valeurs qui comptent : juste sous le seuil, le seuil lui-meme
     * (la comparaison est un {@code blt}, donc l'egalite CASSE), le retour a zero, le maximum
     * d'un octet, et le seuil plus un.
     */
    private static int degats(int image, int seuil) {
        return switch (image) {
            case 0 -> 0;
            case 40 -> Math.max(0, seuil - 1);
            case 80 -> seuil;
            case 120 -> 0;
            case 160 -> 255;
            case 200 -> Math.min(255, seuil + 1);
            default -> -1;
        };
    }

    private static int defAddr(int def) {
        return PortReader.glf() + Defs.GLFT_ObjectDefs + def * Defs.ODefT_SizeOf_l;
    }

    /**
     * Ce qu'une chose est devenue : son etat de simulation, et les quatre champs de dessin que
     * l'animation vient d'y ecrire.
     */
    private static Map<String, Object> etat(int e) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("zone", Mem.w(e + Defs.ObjT_ZoneID_w));
        o.put("entZone", Mem.w(e + Defs.EntT_ZoneID_w));
        o.put("hp", Mem.ub(e + Defs.EntT_HitPoints_b));
        o.put("damage", Mem.ub(e + Defs.EntT_DamageTaken_b));
        o.put("timer1", Mem.uw(e + Defs.EntT_Timer1_w));     // le pas d'animation SUIVANT
        o.put("timer2", Mem.uw(e + Defs.EntT_Timer2_w));     // le compteur d'activation
        o.put("whichAnim", Mem.ub(e + Defs.EntT_WhichAnim_b));
        o.put("worry", Mem.ub(e + Defs.ShotT_Worry_b));
        o.put("height", Mem.uw(e + 4));                      // 4(a0) : la hauteur de dessin
        o.put("angle", Mem.uw(e + Defs.EntT_CurrentAngle_w));
        o.put("size", Mem.uw(e + 6));                        // 6(a0) : les demi-tailles
        o.put("gfx", Mem.l(e + 8));                          // 8..11 : feuille et drapeaux
        return o;
    }
}
