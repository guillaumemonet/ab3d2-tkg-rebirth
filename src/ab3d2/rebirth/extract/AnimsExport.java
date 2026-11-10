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
 * Oracle des PORTES et des ASCENSEURS : {@code DoorRoutine} (newanims.s:1237) et
 * {@code LiftRoutine} (newanims.s:869).
 *
 * <p>Ce sont les deux seules machines a etats du jeu qui bougent le DECOR : une porte est une
 * zone dont le toit descend, un ascenseur une zone dont le sol monte. La collision lit ces
 * memes hauteurs, de sorte qu'un pas de ces routines change le monde sous les pieds de tout ce
 * qui s'y trouve. Rien ne les comparait au mot pres.
 *
 * <p>On ne rejoue pas une image : on rejoue une SUITE d'images, parce que tout y est de la
 * memoire — la position, la vitesse, le compte a rebours de refermeture, l'armement des aretes.
 */
public final class AnimsExport {

    /**
     * Les niveaux releves. On y a AJOUTE e et i : ce sont les seuls, avec m et j, a porter des
     * portes dont le second octet de condition est arme ET qui ont une vitesse de fermeture —
     * c est-a-dire les seules ou le fait qu une telle porte ne se referme JAMAIS se voie.
     */
    private static final int[] NIVEAUX = {0, 2, 4, 6, 8, 12, 13, 14};

    /** Combien d'images par scene. */
    /**
     * Combien d images par scene. Il en faut assez pour que le compte a rebours de
     * refermeture, qui va jusqu a deux cents images, soit DEPASSE.
     */
    private static final int IMAGES = 160;

    /**
     * Les valeurs posees dans le drapeau d'une arete : c'est le handshake du declenchement.
     *
     * <p>{@code $100} est le joueur UN, {@code $800} le joueur DEUX, {@code $8000} le
     * « n'importe quoi » qu'une porte automatique attend, {@code $400} un projectile — que
     * rien n'ouvre. {@code $8900} les reunit, et c'est lui qui fait VOYAGER les panneaux :
     * sans une valeur qui porte a la fois le bit du joueur et celui de l'automatisme, la
     * moitie des portes du jeu ne s'ouvre dans aucune scene.
     */
    private static final int[] ARMES = {0x100, 0x800, 0x900, 0x8000, 0x8900, 0x400, 0};

    private static int doorBase;
    private static int liftBase;
    private static int edges;
    private static int zptrs;

    private AnimsExport() {
    }

    public static void extract(Path outRoot) throws Exception {
        PortReader.bootWithAssets();
        ab3d2.c.SystemC.Sys_Init();

        List<Object> niveaux = new ArrayList<>();
        int scenes = 0;
        for (int lvl : NIVEAUX) {
            Map<String, Object> un = unNiveau(lvl);
            niveaux.add(un);
            scenes += ((List<?>) un.get("cases")).size();
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("frames", IMAGES);
        root.put("levels", niveaux);
        Files.createDirectories(outRoot);
        Path f = outRoot.resolve("anims.json");
        Files.writeString(f, new GsonBuilder().disableHtmlEscaping().create().toJson(root));
        System.out.printf("[anims] %d niveaux, %d scenes de %d images -> %s%n",
                NIVEAUX.length, scenes, IMAGES, f);
    }

    /** Les adresses de depart de chaque enregistrement de porte / ascenseur. */
    private static List<Integer> records(int a0) {
        List<Integer> l = new ArrayList<>();
        if (a0 == 0) {
            return l;
        }
        for (int guard = 0; guard < 500; guard++) {
            if (Mem.w(a0) == 999) {
                return l;
            }
            l.add(a0);
            int p = a0 + 36;
            for (int g2 = 0; g2 < 400; g2++) {
                if (Mem.w(p) < 0) {
                    break;
                }
                p += 10;                               // arete (mot) + deux pointeurs (longs)
            }
            a0 = p + 2;                                // saute le terminateur
        }
        return l;
    }

    private static void poseAdresses() {
        doorBase = Mem.l(ab3d2.bss.LevelBss.Lvl_DoorDataPtr_l);
        liftBase = Mem.l(ab3d2.bss.LevelBss.Lvl_LiftDataPtr_l);
        edges = Mem.l(ab3d2.bss.LevelBss.Lvl_ZoneEdgePtr_l);
        zptrs = Mem.l(ab3d2.bss.LevelBss.Lvl_ZonePtrsPtr_l);
    }

    private static List<Integer> murs(int rec) {
        List<Integer> l = new ArrayList<>();
        int p = rec + 36;
        for (int g = 0; g < 400; g++) {
            int e = Mem.w(p);
            if (e < 0) {
                break;
            }
            l.add(e);
            p += 10;
        }
        return l;
    }

    private static Map<String, Object> unNiveau(int lvl) {
        LevelExport.loadLevelForTools(lvl);
        poseAdresses();
        List<Integer> portes = records(doorBase);
        List<Integer> asc = records(liftBase);

        // Le DESSUS : ce que le rejeu doit retrouver dans ses propres donnees de niveau.
        List<Object> dp = new ArrayList<>();
        List<Object> da = new ArrayList<>();
        for (int i = 0; i < portes.size(); i++) {
            dp.add(dessus(portes.get(i), i));
        }
        for (int i = 0; i < asc.size(); i++) {
            da.add(dessus(asc.get(i), i));
        }

        // Toutes les aretes qu'une porte ou un ascenseur touche : c'est la seule partie du
        // tableau des drapeaux que ces routines lisent ou ecrivent.
        List<Integer> toutesAretes = new ArrayList<>();
        for (int rec : portes) {
            for (int e : murs(rec)) {
                if (!toutesAretes.contains(e)) {
                    toutesAretes.add(e);
                }
            }
        }
        for (int rec : asc) {
            for (int e : murs(rec)) {
                if (!toutesAretes.contains(e)) {
                    toutesAretes.add(e);
                }
            }
        }

        // Les reglages tournent sur des PAS PREMIERS ENTRE EUX. La premiere version les faisait
        // tous tourner sur quatre : l'arete armee etait alors toujours celle de la porte
        // verrouillee, et pas un panneau ne bougeait de toute la campagne.
        prendreEmpreinte(portes, asc);
        List<Object> cases = new ArrayList<>();
        int combien = 36;
        for (int s = 0; s < combien; s++) {
            Map<String, Object> cas = new LinkedHashMap<>();
            int cible = portes.isEmpty() ? 0 : s % Math.max(1, portes.size());
            int cibleA = asc.isEmpty() ? 0 : (s / 2) % Math.max(1, asc.size());
            cas.put("target", cible);
            cas.put("targetLift", cibleA);
            cas.put("tap", (s / 3) % 2);
            // Le secteur du joueur : tantot nulle part, tantot DANS la porte visee (ce qui la
            // fait remonter), tantot SUR l'ascenseur vise (ce qui change son masque).
            int pz;
            int ou = (s / 5) % 3;
            if (ou == 0) {
                pz = -1;
            } else if (ou == 1 && !portes.isEmpty()) {
                pz = Mem.w(portes.get(cible) + 30);
            } else if (!asc.isEmpty()) {
                pz = Mem.w(asc.get(cibleA) + 30);
            } else {
                pz = -1;
            }
            cas.put("playerZone", pz);
            // Le LONG des verrous : mot fort = portes, mot faible = ascenseurs.
            int verrous = switch ((s / 7) % 4) {
                case 0 -> 0;
                // On verrouille la porte VOISINE de la cible, et non la cible elle-meme :
                // verrouiller celle dont on arme justement les aretes ne prouve rien, et
                // cachait la moitie des declenchements.
                case 1 -> (1 << ((cible + 1) & 15)) << 16;
                case 2 -> 1 << ((cibleA + 1) & 15);
                default -> 0xFFFFFFFF;
            };
            cas.put("locks", verrous);
            cas.put("tempFrames", 1 + (s / 8) % 3);
            cas.put("arm", ARMES[s % ARMES.length]);
            cas.put("armWho", (s / 2) % 4);            // 0 rien, 1 porte, 2 ascenseur, 3 tout
            cas.put("armOnce", s % 13 == 2);
            cas.put("images", uneScene(lvl, cas, portes, asc, toutesAretes));
            cases.add(cas);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", String.valueOf((char) (97 + lvl)));
        out.put("index", lvl);
        out.put("doors", dp);
        out.put("lifts", da);
        out.put("edges", toutesAretes);
        out.put("cases", cases);
        return out;
    }

    private static Map<String, Object> dessus(int rec, int i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", i);
        m.put("bottom", Mem.w(rec));
        m.put("top", Mem.w(rec + 2));
        m.put("openSpeed", Mem.w(rec + 4));
        m.put("closeSpeed", Mem.w(rec + 6));
        m.put("openDuration", Mem.w(rec + 8));
        m.put("position", Mem.w(rec + 22));
        m.put("speed", Mem.w(rec + 24));
        m.put("zone", Mem.w(rec + 30));
        m.put("zoneId", Mem.w(Mem.l(zptrs + Mem.w(rec + 30) * 4)));
        m.put("openBits", Mem.ub(rec + 34));
        m.put("closeBits", Mem.ub(rec + 35));
        m.put("walls", murs(rec));
        return m;
    }

    /** L'etat de depart du niveau, repose avant chaque scene. */
    private static byte[] instantanePortes;
    private static byte[] instantaneAsc;
    private static int[] instantaneZones;

    /**
     * Prend l'empreinte de tout ce que les deux routines ECRIVENT et qui survit a une image.
     *
     * <p>On ne recharge pas le niveau entre deux scenes : le chargeur d'origine avance dans la
     * memoire plate et ne rend jamais rien, de sorte que deux cents rechargements en useraient
     * cinquante megaoctets — et, surtout, les adresses des enregistrements CHANGENT a chaque
     * fois. Une empreinte vaut mieux.
     */
    private static void prendreEmpreinte(List<Integer> portes, List<Integer> asc) {
        int finP = portes.isEmpty() ? doorBase : finDuBloc(doorBase);
        int finA = asc.isEmpty() ? liftBase : finDuBloc(liftBase);
        instantanePortes = new byte[finP - doorBase + 2];
        instantaneAsc = new byte[finA - liftBase + 2 + 2048];   // + les eaux, qui suivent
        for (int i = 0; i < instantanePortes.length; i++) {
            instantanePortes[i] = (byte) Mem.ub(doorBase + i);
        }
        for (int i = 0; i < instantaneAsc.length; i++) {
            instantaneAsc[i] = (byte) Mem.ub(liftBase + i);
        }
        instantaneZones = new int[(portes.size() + asc.size()) * 2];
        int k = 0;
        for (int rec : portes) {
            int z = Mem.l(zptrs + Mem.w(rec + 30) * 4);
            instantaneZones[k++] = Mem.l(z + Defs.ZoneT_Floor_l);
            instantaneZones[k++] = Mem.l(z + Defs.ZoneT_Roof_l);
        }
        for (int rec : asc) {
            int z = Mem.l(zptrs + Mem.w(rec + 30) * 4);
            instantaneZones[k++] = Mem.l(z + Defs.ZoneT_Floor_l);
            instantaneZones[k++] = Mem.l(z + Defs.ZoneT_Roof_l);
        }
    }

    private static int finDuBloc(int a0) {
        for (int guard = 0; guard < 500; guard++) {
            if (Mem.w(a0) == 999) {
                return a0;
            }
            int p = a0 + 36;
            for (int g2 = 0; g2 < 400; g2++) {
                if (Mem.w(p) < 0) {
                    break;
                }
                p += 10;
            }
            a0 = p + 2;
        }
        return a0;
    }

    private static void reposerEmpreinte(List<Integer> portes, List<Integer> asc) {
        for (int i = 0; i < instantanePortes.length; i++) {
            Mem.wb(doorBase + i, instantanePortes[i] & 0xFF);
        }
        for (int i = 0; i < instantaneAsc.length; i++) {
            Mem.wb(liftBase + i, instantaneAsc[i] & 0xFF);
        }
        int k = 0;
        for (int rec : portes) {
            int z = Mem.l(zptrs + Mem.w(rec + 30) * 4);
            Mem.wl(z + Defs.ZoneT_Floor_l, instantaneZones[k++]);
            Mem.wl(z + Defs.ZoneT_Roof_l, instantaneZones[k++]);
        }
        for (int rec : asc) {
            int z = Mem.l(zptrs + Mem.w(rec + 30) * 4);
            Mem.wl(z + Defs.ZoneT_Floor_l, instantaneZones[k++]);
            Mem.wl(z + Defs.ZoneT_Roof_l, instantaneZones[k++]);
        }
    }

    /** Une suite d'images, depuis le niveau tel que le fichier le pose. */
    private static List<Object> uneScene(int lvl, Map<String, Object> cas, List<Integer> portes,
                                         List<Integer> asc, List<Integer> toutesAretes) {
        reposerEmpreinte(portes, asc);
        for (int e : toutesAretes) {
            Mem.ww(edges + e * 16 + 14, 0);
        }
        for (int i = 0; i < 40; i++) {
            Mem.ww(ab3d2.bss.TablesBss.anim_DoorOpenTimers_vw + i * 2, 0);
            Mem.ww(ab3d2.bss.TablesBss.anim_LiftHeightTable_vw + i * 2, 0);
        }
        Mem.ww(ab3d2.bss.ZoneBss.Zone_CurrentDoorState_w, 0);
        // Le joueur DEUX n'existe pas en solo : son secteur vaut -5 et son pointeur montre un
        // mot qui vaut -5 lui aussi, de sorte qu'aucun ascenseur ne le croie dessus.
        Mem.ww(ab3d2.bss.PlayerBss.Plr2_Zone_w, -5);
        Mem.wl(ab3d2.bss.PlayerBss.Plr2_ZonePtr_l, ab3d2.bss.PlayerBss.Plr2_Zone_w);
        Mem.wb(ab3d2.bss.PlayerBss.Plr2_TmpSpcTap_b, 0);
        // Le bruit se situe par rapport au joueur ; on fige ces entrees-la.
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_TmpXOff_l, 0);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_TmpZOff_l, 0);

        int pz = (Integer) cas.get("playerZone");
        Mem.ww(ab3d2.bss.PlayerBss.Plr1_Zone_w, pz);
        Mem.wl(ab3d2.bss.PlayerBss.Plr1_ZonePtr_l,
                pz >= 0 ? Mem.l(zptrs + pz * 4) : ab3d2.bss.PlayerBss.Plr1_Zone_w);

        int arm = (Integer) cas.get("arm");
        int armWho = (Integer) cas.get("armWho");
        boolean armOnce = (Boolean) cas.get("armOnce");
        int cible = (Integer) cas.get("target");
        int cibleA = (Integer) cas.get("targetLift");

        List<Object> images = new ArrayList<>();
        for (int f = 0; f < IMAGES; f++) {
            if (!armOnce || f == 0) {
                armer(portes, asc, arm, armWho, cible, cibleA);
            }
            Mem.ww(ab3d2.bss.AnimBss.Anim_TempFrames_w, (Integer) cas.get("tempFrames"));
            Mem.wb(ab3d2.bss.PlayerBss.Plr1_TmpSpcTap_b, (Integer) cas.get("tap") != 0 ? 0xFF : 0);
            // Les verrous sont REPOSES a chaque image : l'original les refait poser par
            // ObjectHandler, et chaque routine efface sa moitie en finissant.
            Mem.wl(ab3d2.bss.AnimBss.Anim_DoorAndLiftLocks_l, (Integer) cas.get("locks"));

            ab3d2.Newanims.DoorRoutine();
            Mem.ww(ab3d2.bss.PlayerBss.Plr1_FloorSpd_w, 0);   // objmoveanim, entre les deux
            Mem.ww(ab3d2.bss.PlayerBss.Plr2_FloorSpd_w, 0);
            ab3d2.Newanims.LiftRoutine();

            images.add(releve(portes, asc, toutesAretes));
        }
        return images;
    }

    private static void armer(List<Integer> portes, List<Integer> asc, int arm, int armWho,
                              int cible, int cibleA) {
        if (armWho == 1 || armWho == 3) {
            for (int rec : armWho == 3 ? portes : List.of(portes.isEmpty() ? -1
                    : portes.get(cible))) {
                if (rec < 0) {
                    continue;
                }
                for (int e : murs(rec)) {
                    Mem.ww(edges + e * 16 + 14, arm);
                }
            }
        }
        if (armWho == 2 || armWho == 3) {
            for (int rec : armWho == 3 ? asc : List.of(asc.isEmpty() ? -1 : asc.get(cibleA))) {
                if (rec < 0) {
                    continue;
                }
                for (int e : murs(rec)) {
                    Mem.ww(edges + e * 16 + 14, arm);
                }
            }
        }
    }

    private static Map<String, Object> releve(List<Integer> portes, List<Integer> asc,
                                              List<Integer> toutesAretes) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Integer> dpos = new ArrayList<>();
        List<Integer> dspd = new ArrayList<>();
        List<Integer> dtim = new ArrayList<>();
        List<Integer> droof = new ArrayList<>();
        for (int i = 0; i < portes.size(); i++) {
            int rec = portes.get(i);
            dpos.add(Mem.w(rec + 22));
            dspd.add(Mem.w(rec + 24));
            dtim.add(Mem.w(ab3d2.bss.TablesBss.anim_DoorOpenTimers_vw + i * 2));
            droof.add(Mem.l(Mem.l(zptrs + Mem.w(rec + 30) * 4) + Defs.ZoneT_Roof_l));
        }
        List<Integer> lpos = new ArrayList<>();
        List<Integer> lspd = new ArrayList<>();
        List<Integer> lfloor = new ArrayList<>();
        for (int rec : asc) {
            lpos.add(Mem.w(rec + 22));
            lspd.add(Mem.w(rec + 24));
            lfloor.add(Mem.l(Mem.l(zptrs + Mem.w(rec + 30) * 4) + Defs.ZoneT_Floor_l));
        }
        List<Integer> fl = new ArrayList<>();
        for (int e : toutesAretes) {
            fl.add(Mem.w(edges + e * 16 + 14));
        }
        m.put("doorPos", dpos);
        m.put("doorSpd", dspd);
        m.put("doorTimer", dtim);
        m.put("doorRoof", droof);
        m.put("liftPos", lpos);
        m.put("liftSpd", lspd);
        m.put("liftFloor", lfloor);
        m.put("state", Mem.w(ab3d2.bss.ZoneBss.Zone_CurrentDoorState_w));
        m.put("floorSpd", Mem.w(ab3d2.bss.PlayerBss.Plr1_FloorSpd_w));
        m.put("stood", Mem.ub(ab3d2.bss.PlayerBss.plr1_StoodOnLift_b));
        m.put("edgeFlags", fl);
        return m;
    }
}
