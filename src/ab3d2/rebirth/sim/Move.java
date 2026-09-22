package ab3d2.rebirth.sim;

import static ab3d2.rebirth.sim.M68k.divs;
import static ab3d2.rebirth.sim.M68k.muls;
import static ab3d2.rebirth.sim.M68k.s16;

import java.util.ArrayList;
import java.util.List;

/**
 * Port fidele de Objectmove.MoveObject (java/src/ab3d2/Objectmove.java:55-703), SPECIALISE JOUEUR.
 *
 * <p>Specialisation (valeurs posees par Plr1_Control, Hires.java:3591-3596) :
 * Obj_AwayFromWall_b=0 (donc a4/a6 = EdgeT_Byte_12/13 sans decalage), Obj_WallBounce_b=0,
 * exitfirst=0, Obj_ExtLen_w=40 -&gt; on suit toujours la branche {@code calcalong} (glisse le
 * long du mur), jamais le rebond ni la sortie anticipee.
 *
 * <p>Deroule d'un appel : {@code checkwalls} (contour de la zone) -&gt; {@code checkotherwalls}
 * (rayon etendu, tous les groupes) -&gt; {@code CheckMoreFloorLines} (marche de portails, qui met
 * a jour {@link #zone} et {@link #stoodInTop}). Le contact pose le bit {@link #wallFlags}
 * (0x100 = joueur 1) dans {@code edgeFlags} : c'est le handshake qui declenche les portes
 * (cf. {@link Doors}).
 *
 * <p>Unites : coordonnees monde en MOTS signes (partie entiere du 16.16 des joueurs) ;
 * hauteurs en longs virgule fixe.
 */
public final class Move {

    /** move.l #-65536*256 : "pas d'ouverture" (mur plein). */
    private static final int SOLID_H = 0xFF000000;

    private final LevelSim lvl;

    // ---- contexte de mouvement (globals ObjectmoveData) ----
    public int newx, newz, oldx, oldz;
    public int newy, oldy;
    public int zone;                 // Obj_ZonePtr -> index de zone
    public boolean hitwall;
    public boolean stoodInTop;
    public int wallhitheight;
    /** Vrai quand exitfirst a coupe court apres le premier mur touche. */
    private boolean stopped;
    public final List<Integer> roomPath = new ArrayList<>();

    // ---- parametres (joueur) ----
    public int extLen = 40;                  // Obj_ExtLen_w
    public int thingHeight;                  // thingheight
    public int stepUp = 40 * 256;            // StepUpVal (10*256 si accroupi/ecrase)
    public int stepDown = 0x1000000;         // StepDownVal
    public int wallFlags = 0b100000000;      // wallflags : 0x100 joueur 1, 0x800 joueur 2
    /**
     * Obj_AwayFromWall_b : &lt; 0 (0xFF) = pas de rayon, l'arete est prise telle quelle (projectiles) ;
     * 0 = rayon EdgeT_Byte_12/13 sans decalage (joueur) ; &gt; 0 = rayon decale de d3 bits.
     */
    public int awayFromWall;
    /** exitfirst : on s'arrete au PREMIER mur touche, et on calcule le point d'impact exact. */
    public boolean exitFirst;
    /** Obj_WallBounce_b : meme calcul de point d'impact, et on releve les donnees du mur. */
    public boolean wallBounce;

    // Donnees du mur touche (renseignees quand wallBounce) : WallXSize_w / WallZSize_w / WallLength_w.
    public int wallXSize;
    public int wallZSize;
    public int wallLength;

    public Move(LevelSim lvl) {
        this.lvl = lvl;
    }

    /** MoveObject */
    public void moveObject() {
        int zoneBackup = zone;                         // obj_ZoneBackupPtr_l
        int quitLimit = 50;                            // obj_QuitLimit_w
        roomPath.clear();
        hitwall = false;
        stopped = false;
        int xdiff = s16(s16(newx) - s16(oldx));
        int zdiff = s16(s16(newz) - s16(oldz));
        if (xdiff == 0 && zdiff == 0) {
            return;
        }
        wallhitheight = newy;

        while (true) {                                 // gobackanddoitallagain:
            int a5Zone = zone;
            int[] elist = lvl.zones[a5Zone].edgeList;

            // checkwalls : 1er groupe (contour), jusqu'au 1er marqueur negatif
            for (int ei : elist) {
                if (ei < 0) {
                    break;                             // no_more_walls
                }
                checkWall(ei);
                if (stopped) {
                    roomPath.add(-1);                  // stopandleave
                    return;
                }
            }

            // checkotherwalls : rayon etendu, tous les groupes jusqu'a -2
            if (extLen != 0) {
                if (xdiff == 0 && zdiff == 0) {
                    roomPath.add(-1);
                    return;
                }
                for (int ei : elist) {
                    if (ei < 0) {
                        if (ei == -2) {
                            break;
                        }
                        continue;                      // -1 : separateur de groupe
                    }
                    checkOtherWall(ei);
                    if (stopped) {
                        roomPath.add(-1);
                        return;
                    }
                }
            }

            // CheckMoreFloorLines : marche de portails (1er groupe)
            boolean crossed = false;
            for (int ei : elist) {
                if (ei < 0) {
                    break;                             // NoMoreFloorLines
                }
                if (lvl.edgeJoin[ei] < 0) {
                    continue;
                }
                int jz = lvl.edgeJoin[ei];
                // cote du NOUVEAU point vs l'arete
                int d0 = muls(s16(newx) - lvl.edgeX[ei], lvl.edgeDz[ei]);
                int d1 = muls(s16(newz) - lvl.edgeZ[ei], lvl.edgeDx[ei]);
                d0 = d0 - d1;
                if (d0 >= 0) {
                    continue;                          // StillSameSide
                }
                // a-t-il franchi le segment (vs oldx/oldz) ?
                int billy = d0;
                int d6 = s16(newx) - s16(oldx);
                int d7 = s16(newz) - s16(oldz);
                int d4 = lvl.edgeX[ei] - s16(oldx);
                d7 = muls(d7, d4);
                d4 = lvl.edgeZ[ei] - s16(oldz);
                d6 = muls(d6, d4);
                d7 = d7 - d6;
                if (d7 > 0) {
                    continue;                          // StillSameSide
                }
                d6 = s16(newx) - s16(oldx);
                d7 = s16(newz) - s16(oldz);
                d4 = s16(lvl.edgeX[ei] + lvl.edgeDx[ei]) - s16(oldx);
                d7 = muls(d7, d4);
                d4 = s16(lvl.edgeZ[ei] + lvl.edgeDz[ei]) - s16(oldz);
                d6 = muls(d6, d4);
                d7 = d7 - d6;
                if (d7 < 0) {
                    continue;                          // StillSameSide
                }
                // hauteur au point de franchissement -> StoodInTop
                d7 = divs(billy, lvl.edgeW5[ei]);
                d0 = muls(s16(oldx) - lvl.edgeX[ei], lvl.edgeDz[ei]);
                d1 = muls(s16(oldz) - lvl.edgeZ[ei], lvl.edgeDx[ei]);
                d0 = d0 - d1;
                d0 = divs(d0, lvl.edgeW5[ei]);
                d0 = s16(d0 - d7);
                if (d0 <= 0) {
                    d0 = 1;
                }
                d4 = newy - oldy;
                d4 = divs(d4, d0);
                d4 = muls(d4, d7);
                d4 = d4 + newy;
                stoodInTop = d4 < lvl.zones[jz].roofH;
                // entre dans la zone jointe
                roomPath.add(jz);
                zone = jz;
                quitLimit--;
                if (quitLimit == 0) {                  // ERRORINMOVEMENT
                    newx = oldx;
                    newz = oldz;
                    newy = oldy;
                    zone = zoneBackup;
                    hitwall = true;
                    return;
                }
                crossed = true;
                break;
            }

            if (crossed) {
                continue;                              // gobackanddoitallagain (nouvelle zone)
            }
            // NoMoreFloorLines : on reste dans a5Zone
            zone = a5Zone;
            roomPath.add(-1);
            return;
        }
    }

    /** checkwalls : teste/clippe une arete du contour (Objectmove.java:92-370, chemin joueur). */
    private void checkWall(int ei) {
        // hauteurs de l'ouverture (zone jointe) ou mur plein
        int lfh = SOLID_H;
        int lrh = SOLID_H;
        int ufh = SOLID_H;
        int urh = SOLID_H;
        if (lvl.edgeJoin[ei] >= 0) {
            LevelSim.Zone jz = lvl.zones[lvl.edgeJoin[ei]];
            lfh = jz.floorH;
            lrh = jz.roofH;
            ufh = jz.upperFloorH;
            urh = jz.upperRoofH;
        }
        // thisisawall2 : le rayon n'est pris que si Obj_AwayFromWall_b >= 0 (un projectile, a 0xFF,
        // passe au ras de l'arete).
        int a4 = 0;
        int a6 = 0;
        if ((byte) awayFromWall >= 0) {
            a4 = lvl.edgeB12[ei];
            a6 = lvl.edgeB13[ei];
            if (awayFromWall != 0) {
                a4 = s16(a4 << (awayFromWall & 63));
                a6 = s16(a6 << (awayFromWall & 63));
            }
        }

        // produit croise du NOUVEAU point vs l'arete (decalee du rayon a4/a6)
        int d0 = s16(s16(newx) - lvl.edgeX[ei]);
        int d1 = s16(s16(newz) - lvl.edgeZ[ei]);
        d0 = s16(d0 - a4);
        d1 = s16(d1 - a6);
        int d2 = s16(lvl.edgeDx[ei] - a4);
        d2 = s16(d2 - a6);
        d1 = muls(d1, d2);
        int d5 = s16(lvl.edgeDz[ei] + a4);
        d5 = s16(d5 - a6);
        d0 = muls(d0, d5);
        d0 = d0 - d1;
        int d3 = s16(lvl.edgeW5[ei] + extLen);
        if (d0 > 0) {                                  // cote exterieur : pas de collision
            d0 = divs(d0, d3);
            if (s16(d0) < 32) {
                lvl.edgeFlags[ei] |= wallFlags;        // on effleure : arme le flag
            }
            return;                                    // oknothitwall
        }

        // chkhttt : d0 <= 0 -> contact potentiel
        int d7 = divs(d0, d3);
        int d4 = newy - oldy;
        // produit croise de l'ANCIEN point
        int od0 = s16(s16(oldx) - lvl.edgeX[ei]);
        int od1 = s16(s16(oldz) - lvl.edgeZ[ei]);
        od0 = s16(od0 - a4);
        od1 = s16(od1 - a6);
        od1 = muls(od1, d2);
        od0 = muls(od0, d5);
        od0 = od0 - od1;
        od0 = divs(od0, d3);
        od0 = s16(od0 - d7);                           // distance parcourue a travers le mur
        if (od0 <= 0) {
            od0 = 1;
        }
        // hauteur au point de contact
        d1 = d4;
        if (d1 != 0) {
            d1 = divs(d1, od0);
            d1 = muls(d1, d7);
        }
        d1 = d1 + newy;
        int d6 = d1 + thingHeight - stepUp;
        boolean yeshit;
        if (d6 >= lfh) {
            yeshit = true;
        } else if (d1 > lrh) {
            return;                                    // oknothitwall
        } else if (d1 < urh) {
            yeshit = true;
        } else if (d6 < ufh) {
            return;
        } else {
            yeshit = true;
        }
        if (!yeshit) {
            return;
        }

        wallhitheight = d1;

        // Deux facons de placer le point d'impact : « calcalong » (le joueur GLISSE le long du mur)
        // et « calcwherehit » (on s'arrete AU point de contact : projectiles et rebonds).
        boolean whereHit = wallBounce || exitFirst;
        if (wallBounce) {
            wallXSize = d2;
            wallZSize = d5;
            wallLength = d3;
        }
        if (whereHit) {
            // .calcwherehit : remonte la trajectoire jusqu'au mur.
            int hx = s16(s16(newx) - s16(oldx));
            hx = muls(hx, d7);
            hx = divs(hx, od0);
            hx = s16(hx + s16(newx));
            int hz = s16(s16(newz) - s16(oldz));
            hz = muls(hz, d7);
            hz = divs(hz, od0);
            hz = s16(hz + s16(newz));

            // .calcedhit : le segment a-t-il vraiment ete traverse ?
            int e6 = s16(s16(newx) - s16(oldx));
            int e7 = s16(s16(newz) - s16(oldz));
            int e4 = s16(s16(lvl.edgeX[ei] + a4) - s16(oldx));
            int t7 = muls(e7, e4);
            e4 = s16(s16(lvl.edgeZ[ei] + a6) - s16(oldz));
            int t6 = muls(e6, e4);
            if (t7 - t6 > 0) {
                return;                                // oknothitwall
            }
            e6 = s16(s16(newx) - s16(oldx));
            e7 = s16(s16(newz) - s16(oldz));
            e4 = s16(s16(s16(lvl.edgeX[ei] + a4) + d2) - s16(oldx));
            t7 = muls(e7, e4);
            e4 = s16(s16(s16(lvl.edgeZ[ei] + a6) + d5) - s16(oldz));
            t6 = muls(e6, e4);
            if (t7 - t6 < 0) {
                return;                                // oknothitwall
            }
            hitTheWall(ei, hx, hz);
            return;
        }

        // .calcalong : projette le nouveau point sur la ligne du mur (glisse)
        d6 = muls(s16(d7), d5);
        d7 = muls(d7, d2);
        d6 = divs(d6, d3);
        d7 = divs(d7, d3);
        d6 = s16(-s16(d6));
        d6 = s16(d6 + s16(newx));                      // point sur le mur (X)
        d7 = s16(d7 + s16(newz));                      // (Z)
        int px = d6;
        int pz = d7;
        // othercheck : le point projete est-il dans le segment ?
        d6 = s16(d6 - lvl.edgeX[ei]);
        d7 = s16(d7 - lvl.edgeZ[ei]);
        d6 = s16(d6 - a4);
        d7 = s16(d7 - a6);
        int absDx = s16(d2) < 0 ? s16(-s16(d2)) : s16(d2);
        int absDz = s16(d5) < 0 ? s16(-s16(d5)) : s16(d5);
        int t;
        if (absDz > absDx) {                           // UseZ
            if (d7 <= 0) {
                t = s16(d5);
                if (t > 4) {
                    return;
                }
                t = s16(t - 4);
                if (d7 < t) {
                    return;
                }
            } else {
                t = s16(d5);
                if (t < -4) {
                    return;
                }
                t = s16(t + 4);
                if (d7 > t) {
                    return;
                }
            }
        } else {                                       // UseX
            if (d6 <= 0) {
                t = s16(d2);
                if (t > 4) {
                    return;
                }
                t = s16(t - 4);
                if (d6 < t) {
                    return;
                }
            } else {
                t = s16(d2);
                if (t < -4) {
                    return;
                }
                t = s16(t + 4);
                if (d6 > t) {
                    return;
                }
            }
        }

        hitTheWall(ei, px, pz);
    }

    /** hitthewall : pose le point de contact, arme le flag d'arete, et stoppe si exitfirst. */
    private void hitTheWall(int ei, int px, int pz) {
        newx = px;
        newz = pz;
        lvl.edgeFlags[ei] |= wallFlags;
        hitwall = true;
        stopped = exitFirst;                           // stopandleave
    }

    /** checkotherwalls : rayon etendu (Objectmove.java:395-573, chemin joueur). */
    private void checkOtherWall(int ei) {
        // passabilite en hauteur (step up/down) ; sinon c'est un mur
        if (lvl.edgeJoin[ei] >= 0) {
            LevelSim.Zone jz = lvl.zones[lvl.edgeJoin[ei]];
            int gap = jz.floorH - jz.roofH;
            if (gap > thingHeight) {
                int d0h = newy;
                int d1h = d0h + thingHeight - jz.floorH;
                boolean botinside = d1h > 0 ? d1h < stepUp : -d1h < stepDown;
                if (botinside && (d0h - jz.roofH) >= 0) {
                    return;                            // passable (etage bas)
                }
            }
            // thisisawall1 : etage superieur
            gap = jz.upperFloorH - jz.upperRoofH;
            if (gap > thingHeight) {
                int d0h = newy;
                int d1h = d0h + thingHeight - jz.upperFloorH;
                boolean botinside = d1h > 0 ? d1h < stepUp : -d1h < stepDown;
                if (botinside && (d0h - jz.upperRoofH) >= 0) {
                    return;                            // passable (etage haut)
                }
            }
        }

        int a4 = lvl.edgeB12[ei];
        int a6 = lvl.edgeB13[ei];
        int deltax = s16(lvl.edgeDx[ei] - a4);
        deltax = s16(deltax - a6);
        int d5 = s16(lvl.edgeDz[ei] + a4);
        d5 = s16(d5 - a6);
        int d0 = s16(s16(newx) - lvl.edgeX[ei]);
        int d1 = s16(s16(newz) - lvl.edgeZ[ei]);
        d0 = s16(d0 - a4);
        d1 = s16(d1 - a6);
        d1 = muls(d1, deltax);
        d0 = muls(d0, d5);
        d0 = d0 - d1;
        if (d0 >= 0) {
            return;
        }
        int d7 = d0;
        // le segment old->new coupe-t-il l'arete ?
        int g3 = s16(s16(newx) - s16(oldx));
        int g1 = s16(s16(oldx) - lvl.edgeX[ei]);
        g1 = s16(g1 - a4);
        int g2 = s16(lvl.edgeZ[ei] + a6);
        g2 = s16(g2 - s16(oldz));
        int g4 = s16(s16(newz) - s16(oldz));
        g1 = muls(g1, g4);
        g2 = muls(g2, g3);
        g1 = g1 + g2;
        g4 = muls(g4, deltax);
        int g3b = muls(g3, d5);
        g4 = g4 - g3b;
        if (g4 == 0) {
            return;
        }
        if (g4 > 0) {
            if (g1 < 0 || g4 < g1) {
                return;
            }
        } else {
            if (g1 > 0 || g4 > g1) {
                return;
            }
        }
        // mighthit : projette le point sur le mur (recule de 3)
        int d0b = s16(lvl.edgeW5[ei] + extLen);
        d7 = divs(d7, d0b);
        d7 = s16(d7 - 3);
        int d6 = muls(s16(d7), d5);
        d7 = muls(d7, deltax);
        d6 = divs(d6, d0b);
        d7 = divs(d7, d0b);
        d6 = s16(-s16(d6));
        d6 = s16(d6 + s16(newx));
        d7 = s16(d7 + s16(newz));
        int px = d6;
        int pz = d7;
        // verifie que l'ANCIEN point etait du bon cote
        int c0 = s16(s16(oldx) - lvl.edgeX[ei]);
        int c1 = s16(s16(oldz) - lvl.edgeZ[ei]);
        c0 = s16(c0 - a4);
        c1 = s16(c1 - a6);
        c1 = muls(c1, deltax);
        c0 = muls(c0, d5);
        c0 = c0 - c1;
        if (c0 < 0) {
            return;
        }
        hitTheWall(ei, px, pz);
    }
}
