package ab3d2.rebirth.sim;

import java.util.ArrayList;
import java.util.List;

import ab3d2.rebirth.GlfData;
import ab3d2.rebirth.LevelData;

import static ab3d2.rebirth.sim.M68k.muls;
import static ab3d2.rebirth.sim.M68k.s16;
import static ab3d2.rebirth.sim.M68k.setw;

/**
 * Obj_DoCollision (Objectmove.java:1246, objectmove.s:1665) : le joueur BUTE sur les ENTITES de
 * sa zone — les monstres, et EN THEORIE le decor solide (cf. le quirk plus bas).
 *
 * <p>Ne bloquent que les entites de la MEME zone, au MEME etage, vivantes, dont la hauteur
 * recouvre le corps du joueur et qui sont a moins de 80 unites sur les deux axes. Et le dernier
 * test du jeu est malin : on ne bute que si le mouvement RAPPROCHE ({@code d(new) <= d(old)}) —
 * on peut donc toujours se degager d'une entite dans laquelle on est deja encastre.
 *
 * <p>Le decor : seules les definitions d'objet de comportement &gt;= 2
 * ({@code ODefT_Behaviour_w}, qui est le MEME mot que {@code ENT_TYPE_*}) sont solides, soit
 * DESTRUCTABLE (2, tant qu'il lui reste des points de vie) et DECORATION (3). Ni les
 * ramassables ni les interrupteurs n'arretent le joueur.
 *
 * <p><b>Quirk fidelement reproduit</b> : l'original lit les deux extents verticaux dans
 * {@code 2(a2,d3.w*8)} et {@code 4(a2,d3.w*8)} avec <b>a2 jamais initialise</b> dans la routine.
 * Dans la boucle de jeu a2 vaut {@code AI_AlienTeamWorkspace_vl} (hires.s, game_main_loop), donc
 * ces mots sont des champs du workspace de l'EQUIPE 0 :
 * <ul>
 *   <li>monstre (type 0) : {@code 2(a2)} = {@code AI_WorkT_LastY_w}, {@code 4(a2)} =
 *       {@code AI_WorkT_LastZone_w} ;</li>
 *   <li>objet (type 1) : {@code 10(a2)} = {@code AI_WorkT_DamageDone_w}, {@code 12(a2)} =
 *       {@code AI_WorkT_DamageTaken_w}.</li>
 * </ul>
 * Au reset ils valent 0 / -1 / -1 / 0 ({@code ai_ResetAliens}). Consequences, verifiees par
 * {@code moveTest} :
 * <ul>
 *   <li>pour les MONSTRES le hasard tombe juste — {@code 0} et {@code -1} donnent exactement
 *       « la hauteur du monstre est comprise dans le corps du joueur » — et ils bloquent ;</li>
 *   <li>pour le DECOR, {@code DamageDone} vaut -1 et n'est JAMAIS reecrit sur le bloc d'equipe
 *       (ai.s n'ecrit que celui de chaque monstre), donc le recouvrement n'est vrai que pour un
 *       objet que son animation pose SOUS le sol : en pratique presque rien ne bloque. Le jeu
 *       d'origine laisse bel et bien traverser tonneaux et caisses.</li>
 * </ul>
 * Et des qu'un monstre d'equipe 0 a vu le joueur, {@code LastY} devient sa coordonnee Z : le test
 * ne passe plus et les monstres cessent de faire obstacle. C'est le comportement d'origine.
 */
public final class Collide {

    private final LevelSim lvl;
    private final GlfData glf;
    /** Le decor solide du niveau (ODefT_Behaviour_w >= 2). */
    private final List<LevelData.Obj> solids = new ArrayList<>();
    /** Les monstres (ObjT_TypeID_b = 0) ; null = niveau sans IA. */
    public Aliens aliens;

    public Collide(LevelSim lvl, LevelData data, GlfData glf) {
        this.lvl = lvl;
        this.glf = glf;
        for (LevelData.Obj o : data.objects == null ? List.<LevelData.Obj>of() : data.objects) {
            if (o.typeId != 1) {
                continue;
            }
            GlfData.ObjDef def = glf == null ? null : glf.object(o.def);
            if (def != null && def.type >= 2) {        // cmp.w #2,ODefT_Behaviour_w ; blt skip
                solids.add(o);
            }
        }
    }

    /**
     * Vrai si aller en ({@code newx},{@code newz}) rentre dans une entite.
     *
     * @param newy        le HAUT du corps (Plr1_YOff_l), {@code thingHeight} sa taille
     */
    public boolean check(int newx, int newz, int oldx, int oldz, int newy, int thingHeight,
                         int zone, boolean stoodInTop) {
        int d6 = stoodInTop ? 0xFF : 0;                // move.b StoodInTop,d6
        int d4 = newy >> 7;                            // asr.l #7,d4 : le haut
        int d5 = (newy + thingHeight) >> 7;            // asr.l #7,d5 : les pieds
        if (aliens != null) {
            for (Aliens.Alien a : aliens.aliens()) {
                if (a.zone < 0 || a.zone != zone) {
                    continue;                          // tst.w ObjT_ZoneID_w ; cmp .tmp_zone_id_w
                }
                if ((a.hitPoints & 0xFF) == 0) {
                    continue;                          // tst.b EntT_HitPoints_b ; beq
                }
                if ((((a.upperZone ? 0xFF : 0) ^ d6) & 0xFF) != 0) {
                    continue;                          // eor.b d6,d1 ; bne
                }
                if (hits(0, s16(a.height), a.x, a.z, newx, newz, oldx, oldz, d4, d5)) {
                    return true;
                }
            }
        }
        for (LevelData.Obj o : solids) {
            if (o.zone < 0 || o.zone != zone) {
                continue;
            }
            GlfData.ObjDef def = glf.object(o.def);
            if (def == null) {
                continue;
            }
            if ((((o.upperZone ? 0xFF : 0) ^ d6) & 0xFF) != 0) {
                continue;                              // eor.b d6,d1 ; bne
            }
            // Le second test de points de vie (comportement 2, DESTRUCTABLE) porte sur
            // EntT_HitPoints_b, pas sur la definition : StillHere le remet a 1 a chaque frame tant
            // que l'objet est dans le niveau, et Destructable le met a 0 en le detruisant — donc
            // « encore la » (zone >= 0, deja teste) suffit.
            int h = ObjectRuntime.objectHeight(lvl, o, def);
            if (hits(1, h, o.x, o.z, newx, newz, oldx, oldz, d4, d5)) {
                return true;
            }
        }
        return false;
    }

    /** .ycol : recouvrement vertical, puis les deux tests horizontaux. */
    private boolean hits(int typeId, int entHeight, int ex, int ez,
                         int newx, int newz, int oldx, int oldz, int d4, int d5) {
        int d1 = setw(0, entHeight - extent(typeId, 2));  // sub.w 2(a2,d3.w*8),d1
        if (s16(d5) < s16(d1)) {
            return false;                              // cmp.w d1,d5 ; blt
        }
        d1 = setw(d1, d1 + extent(typeId, 4));         // add.w 4(a2,d3.w*8),d1
        if (s16(d4) > s16(d1)) {
            return false;                              // cmp.w d1,d4 ; bgt
        }
        int dx = s16(ex - newx);
        if (dx < 0) {
            dx = s16(-dx);                             // neg.w d1
        }
        int dz = s16(ez - newz);
        if (dz < 0) {
            dz = s16(-dz);
        }
        // ECART ASSUME. L'original (objectmove.s:1753) teste les deux axes DIFFEREMMENT : quand
        // l'ecart en Z domine, il pose hitwall sans plus de test, alors que la branche X ajoute
        // « le mouvement rapproche-t-il ? ». Cette asymetrie ressemble a un oubli : elle rend le
        // joueur PRISONNIER d'un monstre place surtout en Z, puisque meme reculer est refuse.
        // Sur Amiga on s'en sortait au fusil ; en 3D, colle a un alien qui mord, c'est une mort
        // certaine. On applique donc le test de rapprochement aux DEUX axes.
        // Pour revenir au comportement d'origine : rendre vrai des ici quand dz > dx.
        if (dz > dx) {                                 // cmp.w d1,d2 ; ble .checkx
            if (s16(dz - 80) > 80) {                   // sub.w #80 ; cmp.w #80 ; bgt skip
                return false;
            }
        } else if (s16(dx - 80) > 80) {                // .checkx
            return false;
        }
        // On ne bute que si le mouvement RAPPROCHE de l'entite.
        int nx = s16(ex - newx);
        int nz = s16(ez - newz);
        int d7 = muls(nx, nx) + muls(nz, nz);
        int ox = s16(ex - oldx);
        int oz = s16(ez - oldz);
        int d2 = muls(ox, ox) + muls(oz, oz);
        return d7 <= d2;                               // cmp.l d2,d7 ; bgt skip
    }

    /**
     * Les deux extents verticaux lus par le quirk du registre a2 : le workspace de l'equipe 0,
     * {@code 2 + typeId*8} et {@code 4 + typeId*8}.
     */
    private int extent(int typeId, int field) {
        int off = typeId * 8 + field;
        if (aliens != null) {
            return aliens.teamZeroWord(off);
        }
        return switch (off) {                          // ai_ResetAliens : 0 / -1 / -1 / 0
            case 4, 10 -> -1;
            default -> 0;
        };
    }
}
