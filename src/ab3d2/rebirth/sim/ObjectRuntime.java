package ab3d2.rebirth.sim;

import ab3d2.rebirth.GlfData;
import ab3d2.rebirth.LevelData;

import java.util.ArrayList;
import java.util.List;

/**
 * Interaction avec les objets : port de {@code Collectable} et {@code Activatable}/{@code ACTIVATED}
 * (Newaliencontrol.java:197-357), de {@code Plr1_CheckObjectCollide} / {@code Plr1_CollectItem}
 * et de {@code CheckHit} (Objectmove.java).
 *
 * <p>Un objet ramassable n'est pris que si le joueur est dans SA zone, au même étage, à portée
 * verticale ({@code ODefT_CollideHeight_w}) et horizontale ({@code ODefT_CollideRadius_w}) ; il
 * disparaît alors du niveau ({@code ObjT_ZoneID_w = -1}).
 *
 * <p>Les CLÉS fonctionnent à l'envers de ce qu'on croit : {@code Anim_DoorAndLiftLocks_l} est
 * reconstruit à chaque frame et un objet-clé encore présent y ajoute ses bits — la porte est donc
 * verrouillée TANT QUE la clé n'est pas ramassée. Un objet qui tient une clé est toujours
 * ramassable, quel que soit l'état de l'inventaire.
 */
public final class ObjectRuntime {

    /** ENT_TYPE_* (Defs) : 0 = ramassable, 1 = activable (interrupteurs, chargeurs). */
    private static final int ENT_TYPE_COLLECTABLE = 0;
    private static final int ENT_TYPE_ACTIVATABLE = 1;

    private final LevelSim lvl;
    private final GlfData glf;
    private final List<LevelData.Obj> collectables = new ArrayList<>();
    private final List<LevelData.Obj> activatables = new ArrayList<>();
    private final List<LevelData.Obj> collectedThisFrame = new ArrayList<>();

    public final Inventory inventory;
    /** File de bruitages (null = muet). */
    public SfxQueue sfx;
    /** File de messages (null = muet). */
    public Messages messages;
    /** Les 10 textes du niveau, pour les objets qui portent un EntT_DisplayText_w. */
    private final java.util.List<String> levelMessages;

    public ObjectRuntime(LevelSim lvl, LevelData data, GlfData glf) {
        this.lvl = lvl;
        this.glf = glf;
        this.levelMessages = data.messages;
        this.inventory = new Inventory(glf == null ? null : glf.maxInventory);
        for (LevelData.Obj o : data.objects == null ? List.<LevelData.Obj>of() : data.objects) {
            if (o.typeId != 1) {
                continue;                              // seuls les OBJETS ont une définition ODefT
            }
            GlfData.ObjDef def = glf == null ? null : glf.object(o.def);
            if (def == null) {
                continue;
            }
            if (def.type == ENT_TYPE_COLLECTABLE) {
                collectables.add(o);
            } else if (def.type == ENT_TYPE_ACTIVATABLE) {
                activatables.add(o);
            }
        }
    }

    /** Objets ramassés à la dernière frame (à retirer de la scène). */
    public List<LevelData.Obj> collected() {
        return collectedThisFrame;
    }

    /**
     * Une frame : verrous des portes portés par les clés encore au sol, puis tentative de
     * ramassage. Renvoie le masque de verrous à donner à {@link Anims#doorLocks}.
     */
    public int run(PlayerSim player, boolean actionTap) {
        collectedThisFrame.clear();
        long locks = 0;
        locks |= activatables(player, actionTap);
        for (LevelData.Obj o : collectables) {
            if (o.zone < 0) {
                continue;                              // déjà ramassé
            }
            // Collectable : un objet encore présent verrouille les portes dont il tient la clé.
            // (Le jeu conditionne ça à AI_NoEnemies_b ; sans aliens, c'est toujours le cas ici.)
            locks |= o.doorsHeld & 0xFFFFFFFFL;
            if (collide(player, o) && collect(o)) {
                o.zone = -1;                           // ObjT_ZoneID_w = -1 : retiré du niveau
                collectedThisFrame.add(o);
            }
        }
        // DoorRoutine lit Anim_DoorAndLiftLocks_l en MOT : c'est le mot FORT du long qui porte
        // les bits de portes (clé 0x04000000 -> bit 10 -> porte 10).
        return (int) ((locks >>> 16) & 0xFFFF);
    }

    /**
     * Activatable / ACTIVATED : un interrupteur au repos VERROUILLE ses portes ; une fois
     * actionné (joueur à portée + touche action), il joue son animation « active » et relâche
     * ses verrous, jusqu'à expiration de {@code ODefT_ActiveTimeout_w} (négatif = jamais) ou
     * nouvelle pression du joueur.
     */
    private long activatables(PlayerSim player, boolean actionTap) {
        long locks = 0;
        for (LevelData.Obj o : activatables) {
            if (o.zone < 0) {
                continue;
            }
            GlfData.ObjDef def = glf.object(o.def);
            if (def == null) {
                continue;
            }
            boolean inRange = collide(player, o);
            if (!o.activated) {
                // Au repos : il tient ses verrous, et une pression le déclenche.
                locks |= o.doorsHeld & 0xFFFFFFFFL;
                if (inRange && actionTap) {
                    collect(o);                        // Plr1_CollectItem (ce qu'il donne)
                    o.activated = true;
                    o.activeTimer = 0;
                    o.animStep = 0;
                }
                continue;
            }
            // Actif : le compteur avance ; timeout négatif = jamais.
            o.activeTimer = (short) (o.activeTimer + 1);   // Anim_TempFrames_w = 1
            if (def.activeTimeout >= 0 && def.activeTimeout <= o.activeTimer) {
                o.activated = false;
                o.animStep = 0;
            } else if (inRange && actionTap) {
                o.activated = false;
                o.animStep = 0;
            }
        }
        return locks;
    }

    /** Plr1_CheckObjectCollide. */
    private boolean collide(PlayerSim player, LevelData.Obj o) {
        if (player.stoodInTop != o.upperZone) {
            return false;                              // pas au même étage
        }
        if (player.zone != o.zone) {
            return false;
        }
        GlfData.ObjDef def = glf.object(o.def);
        if (def == null) {
            return false;
        }
        int d7 = player.yOff + (player.height >> 1);
        d7 = d7 >> 7;
        d7 = (short) (d7 - objectHeight(lvl, o, def));
        if (d7 <= 0) {
            d7 = (short) -d7;
        }
        if (d7 > def.collideHeight) {
            return false;
        }
        // CheckHit : distance au carré < rayon au carré (mots signés).
        int dx = (short) (o.x - (short) (player.xOff >> 16));
        int dz = (short) (o.z - (short) (player.zOff >> 16));
        int dist = M68k.muls(dx, dx) + M68k.muls(dz, dz);
        int radius = M68k.muls(def.collideRadius, def.collideRadius);
        return dist < radius;
    }

    /**
     * Hauteur de dessin d'un objet : {@code worry_about} pose le sol (ou le plafond) de sa zone
     * décalé de 7 bits, PUIS l'animation ajoute deux fois le delta de son pas courant
     * (Newaliencontrol.animObj). Sans ce delta, des ramassages deviennent impossibles : les
     * cartouches de fusil ont une tolérance verticale de 40 alors que le joueur est à 48 du sol.
     */
    public static int objectHeight(LevelSim lvl, LevelData.Obj o, GlfData.ObjDef def) {
        LevelSim.Zone z = o.zone >= 0 && o.zone < lvl.zones.length ? lvl.zones[o.zone] : null;
        if (z == null) {
            return 0;
        }
        int h;
        if (def != null && def.floorCeiling != 0) {
            h = o.upperZone ? z.upperRoofH : z.roofH;
        } else {
            h = o.upperZone ? z.upperFloorH : z.floorH;
        }
        return (short) ((h >> 7) + 2 * o.animDelta);
    }

    /** Plr1_CollectItem (sans les messages a l'ecran). */
    private boolean collect(LevelData.Obj o) {
        GlfData.ObjDef def = glf.object(o.def);
        if (def == null) {
            return false;
        }
        if (o.doorsHeld == 0 && !inventory.canCollect(def.ammoGive, def.gunGive)) {
            if (messages != null) {                    // Game_CantCollectItemText_vb
                messages.push("I can't carry any more of these just now.");
            }
            return false;                              // plein : l'objet reste au sol
        }
        inventory.add(def.ammoGive, def.gunGive);
        if (sfx != null) {                             // ODefT_SFX_w (Newaliencontrol.java:538)
            sfx.playAt(def.sfx, o.x, o.z, objectHeight(lvl, o, def));
        }
        if (messages != null) {                        // Plr1_CollectItem : texte du niveau, sinon NOM
            if (o.displayText >= 0 && levelMessages != null
                    && o.displayText < levelMessages.size()) {
                messages.push(levelMessages.get(o.displayText));
            } else {
                messages.push(def.name);
            }
        }
        return true;
    }
}
