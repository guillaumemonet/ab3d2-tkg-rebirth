package ab3d2.rebirth.sim;

import ab3d2.rebirth.LevelData;

import java.util.List;

/**
 * Port fidele des machines a etats des PORTES et ASCENSEURS
 * (Newanims.DoorRoutine, java/src/ab3d2/Newanims.java:1039-1301 et
 * Newanims.LiftRoutine, :734-1035), appelees une fois par frame comme dans objmoveanim.
 *
 * <p>Principe d'origine : une porte est une ZONE dont le TOIT descend/monte
 * ({@code ZoneT_Roof = (position>>2)*256}) ; un ascenseur est une zone dont le SOL monte/descend
 * ({@code ZoneT_Floor = (position>>2)*256}). La collision ({@link Move}) lit ces memes hauteurs :
 * l'ouverture est donc "gratuite" cote physique.
 *
 * <p>Declenchement = handshake d'aretes : {@link Move} pose le bit du joueur (0x100) dans
 * {@code edgeFlags} au contact ; ici on lit ce flag, on le remet a 0x8000 (desarme), et on
 * applique la vitesse si {@code flags &amp; masque} est non nul. Le masque vient de
 * {@link #doorOpenMask}/{@link #doorCloseMask}/{@link #liftRaiseMask}/{@link #liftLowerMask}.
 *
 * <p>Non porte ici (systemes absents) : effets sonores (Aud_xxx, MakeSomeNoise), bits de visibilite
 * Zone_CurrentDoorState_w, joueur 2. Les verrous par cle ({@code Anim_DoorAndLiftLocks_l},
 * {@code anim_LiftOnlyLocks_w}) sont exposes en champs, a alimenter par le futur ramassage d'objets.
 */
public final class Anims {

    private final LevelSim lvl;
    private final List<LevelData.Liftable> doors;
    private final List<LevelData.Liftable> lifts;

    // etat runtime par porte / ascenseur (champs "position"/"speed" du record d'origine)
    private final int[] doorPos, doorSpd, doorTimer;
    private final int[] liftPos, liftSpd;

    /** Ratio d'ouverture [0..1] par porte (pour l'animation du panneau). */
    public final float[] doorOpenRatio;
    /** Hauteur courante du sol de l'ascenseur, en unites de zone (pour le visuel). */
    public final int[] liftFloorH;
    /**
     * Hauteur courante du PLAFOND de la zone-porte, en unites de zone. C'est le DESSOUS du
     * battant : le port ecrit la position de la porte dans le flat de plafond de sa zone
     * ({@code move.w d3,2(a1)} dans DoorRoutine, Newanims.java:1146) en plus des murs qu'elle
     * deforme — sans cela la porte n'est pas un volume, il n'y a que ses cotes qui montent.
     */
    public final int[] doorRoofH;

    /** Position brute d'une porte (le mot que le jeu ecrit dans les murs qu'elle deforme). */
    public int doorPosition(int i) {
        return i >= 0 && i < doorPos.length ? doorPos[i] : 0;
    }

    /** Position brute d'un ascenseur. */
    public int liftPosition(int i) {
        return i >= 0 && i < liftPos.length ? liftPos[i] : 0;
    }

    // ---- entrees par frame ----
    /** Anim_TempFrames_w : nombre de frames ecoulees (1 a cadence nominale). */
    public int tempFrames = 1;
    /** Zone du joueur 1 (Plr1_Zone_w). */
    public int playerZone = -1;
    /** Plr1_TmpSpcTap_b : touche action tapee cette frame. */
    public boolean playerSpaceTap;
    /** Anim_DoorAndLiftLocks_l : bit i = porte i verrouillee (cles). */
    public int doorLocks;
    /** anim_LiftOnlyLocks_w : bit i = ascenseur i verrouille. */
    public int liftLocks;

    // ---- sorties vers le joueur ----
    /** plr1_StoodOnLift_b. */
    public boolean playerStoodOnLift;
    /** Plr1_FloorSpd_w : vitesse du sol mobile sous le joueur. */
    public int playerFloorSpd;

    // scratch partage par les sous-cas (d7 du 68k)
    private int liftSpeed;
    private int actionSoundFx;      // anim_ActionSoundFX_w : bruit du declenchement
    /** File de bruitages (posee par le jeu ; null = muet). */
    public SfxQueue sfx;
    /** Generateur du jeu, pour les bruits d'ambiance (null = pas d'ambiance). */
    public Nav nav;
    /** GLFT_AmbientSFX_l : 16 bruits d'ambiance possibles. */
    public int[] ambientSfx;
    private int timeToNoise;         // anim_TimeToNoise_w
    private int oddEven;             // anim_OddEven_w : alterne etage bas / haut
    // anim_OpeningSoundFX_w / anim_ClosingSoundFX_w de la porte (ou ascenseur) en cours.
    private int openingSfx, closingSfx;

    public Anims(LevelSim lvl, LevelData data) {
        this.lvl = lvl;
        this.doors = data.doors == null ? List.of() : data.doors;
        this.lifts = data.lifts == null ? List.of() : data.lifts;

        doorPos = new int[doors.size()];
        doorSpd = new int[doors.size()];
        doorTimer = new int[doors.size()];
        doorOpenRatio = new float[doors.size()];
        for (int i = 0; i < doors.size(); i++) {
            doorPos[i] = doors.get(i).position;
            doorSpd[i] = doors.get(i).speed;
        }
        liftPos = new int[lifts.size()];
        liftSpd = new int[lifts.size()];
        liftFloorH = new int[lifts.size()];
        doorRoofH = new int[doors.size()];
        for (int i = 0; i < lifts.size(); i++) {
            liftPos[i] = lifts.get(i).position;
            liftSpd[i] = lifts.get(i).speed;
        }
    }

    /** objmoveanim : BACKSFX, puis LiftRoutine et DoorRoutine, une fois par frame. */
    public void run() {
        playerFloorSpd = 0;
        playerStoodOnLift = false;
        backSfx();
        liftRoutine();
        doorRoutine();
    }

    /**
     * BACKSFX (Newanims.java:525) : toutes les {@code 100 + hasard} frames, un bruit d'ambiance
     * tire au hasard parmi ceux que la ZONE autorise ({@code ZoneT_BackSFXMask_w}, un bit par
     * bruit) — souffle de ventilation, grondements, gouttes. Sans position : c'est l'atmosphere.
     */
    private void backSfx() {
        if (sfx == null || nav == null || ambientSfx == null) {
            return;
        }
        timeToNoise = M68k.s16(timeToNoise - tempFrames); // sub.w Anim_TempFrames_w
        if ((short) timeToNoise > 0) {
            return;
        }
        timeToNoise = (((nav.rand() >>> 3) & 127) + 100); // and.w #127 ; add.w #100
        LevelSim.Zone z = zoneOf(playerZone);
        if (z == null) {
            return;
        }
        int mask = oddEven == 0 ? z.backSfxMask : z.upperBackSfxMask;
        oddEven = 2 - oddEven;                         // move.w #2,d0 ; sub.w anim_OddEven_w,d0
        if ((short) mask == 0) {
            return;
        }
        int d0 = (nav.rand() >>> 3) & 0xFFFF;
        for (int guard = 0; guard < 64; guard++) {     // .notfound : premier bit arme rencontre
            d0 = (d0 + 1) & 15;
            if ((mask & (1 << d0)) != 0) {
                break;
            }
        }
        if (d0 < ambientSfx.length) {
            sfx.play(ambientSfx[d0]);                  // (pas de -1 ici, contrairement aux autres)
        }
    }

    // --------------------------------------------------------------- portes

    /** DoorRoutine (Newanims.java:1039). */
    private void doorRoutine() {
        for (int i = 0; i < doors.size(); i++) {
            LevelData.Liftable dr = doors.get(i);
            int d0 = dr.bottom;                        // bottom of movement
            int d1 = dr.top;                           // top of movement
            int openingSpeed = -dr.openingSpeed;       // neg.w anim_OpeningSpeed_w
            int closingSpeed = dr.closingSpeed;
            // subq.w #1 AU CHARGEMENT (newanims.s:896) : un bruitage a 0 devient -1, et le
            // test plus bas ne joue alors RIEN. C'est ce -1 qui manquait — la porte rouge du
            // niveau A (closingSFX = 0) reclamait l'echantillon -1 a chaque frame.
            openingSfx = dr.openingSFX - 1;
            closingSfx = dr.closingSFX - 1;
            int openDuration = dr.openDuration;

            int d3 = doorPos[i];                       // position courante
            int d2 = doorSpd[i];                       // vitesse courante
            d3 = M68k.setw(d3, d3 + M68k.muls(d2, tempFrames)); // muls Anim_TempFrames_w,d2 ; add.w d2,d3
            d2 = doorSpd[i];                           // re-lecture (move.w 2(a0),d2)

            boolean doorClosed = (short) d0 <= (short) d3;   // sle anim_DoorClosed_b
            if (!((short) d0 > (short) d3)) {
                if (d2 != 0 && sfx != null) {          // tst.w d2 ; beq .nonoise (elle CLAQUE)
                    sfx.playAt(dr.closedSFX - 1, dr.x, dr.z, doorHeight(dr));
                }
                d2 = 0;
                d0 = M68k.setw(d0, d3);                // move.w d3,d0 (quirk d'origine : pas de clamp)
            }
            boolean doorOpen = (short) d1 >= (short) d3;     // sge anim_DoorOpen_b
            if (!((short) d1 < (short) d3)) {
                if (d2 != 0) {                         // elle vient d'arriver en butee haute
                    doorTimer[i] = 0;                  // move.w #0,(a6) : le compte a rebours
                    if (sfx != null) {                 // de refermeture repart de zero
                        sfx.playAt(dr.openedSFX - 1, dr.x, dr.z, doorHeight(dr));
                    }
                }
                d3 = M68k.setw(d3, d1);                // clamp a "ouvert"
                d2 = 0;
            }

            doorPos[i] = (short) d3;
            doorSpd[i] = (short) d2;
            int d7 = d2;                               // vitesse a appliquer si declenche

            // toit de la zone-porte : ZoneT_Roof_l = (position>>2)*256
            int roof = M68k.muls(M68k.asrw(d3, 2), 256);
            LevelSim.Zone zone = zoneOf(dr.zone);
            if (zone != null) {
                zone.roofH = roof;
            }
            doorRoofH[i] = roof;
            int denom = dr.bottom - dr.top;
            doorOpenRatio[i] = denom == 0 ? 0f
                    : Math.clamp((dr.bottom - (float) (short) d3) / denom, 0f, 1f);

            // masque de declenchement
            int mask;
            boolean simple = false;
            if (dr.zone == playerZone && !doorOpen && (short) d2 >= 0) {
                // .gobackup : le joueur est DANS la porte -> elle remonte
                d7 = -16;
                mask = 0x8000;
            } else if ((doorLocks & (1 << (i & 31))) != 0) {
                mask = 0;                              // verrouillee (cle) -> DOOR_SIMPLE
                simple = true;
            } else if (doorOpen) {
                liftSpeed = d7;
                mask = doorCloseMask(i, openDuration, closingSpeed);
                d7 = liftSpeed;
            } else if (doorClosed) {
                liftSpeed = d7;
                mask = doorOpenMask(dr.openBits, openingSpeed);
                d7 = liftSpeed;
            } else {
                mask = 0;
            }

            applyToWalls(dr.walls, mask, simple, i, d7, true);
        }
    }

    /** tstdoortoopen (Newanims.java:1259). */
    private int doorOpenMask(int openBits, int openingSpeed) {
        actionSoundFx = openingSfx;                    // anim_ActionSoundFX = anim_OpeningSoundFX
        if (openBits < 1) {                            // door0 : manuelle (touche action)
            int d1 = 0;
            if (playerSpaceTap) {
                d1 = 0b100000000;                      // bit joueur 1
            }
            liftSpeed = openingSpeed;
            return d1;
        } else if (openBits == 1) {                    // door1
            liftSpeed = openingSpeed;
            return 0b100100000000;
        } else if (openBits < 3) {                     // door2
            liftSpeed = openingSpeed;
            return 0b10000000000;
        } else if (openBits == 3) {                    // door3
            liftSpeed = openingSpeed;
            return 0b1000000000;
        } else if (openBits < 5) {                     // door4
            liftSpeed = openingSpeed;
            return 0x8000;
        }
        return 0;                                      // door5 : ne s'ouvre pas
    }

    /** tstdoortoclose (Newanims.java:1285) : timer d'ouverture. */
    private int doorCloseMask(int i, int openDuration, int closingSpeed) {
        int d1 = M68k.setw(0, doorTimer[i] + tempFrames);
        doorTimer[i] = (short) d1;
        boolean stayOpen = (short) d1 < (short) openDuration;
        // move.w anim_ClosingSoundFX_w,anim_ActionSoundFX_w : le port le fait AVANT de brancher,
        // donc une porte OUVERTE reclame le bruit de FERMETURE, pas celui d'ouverture. Sans ca,
        // une porte qui reste ouverte (grande porte rouge du niveau A : closingSpeed 0,
        // openDuration 0) rejoue son bruit d'ouverture a chaque frame.
        actionSoundFx = closingSfx;
        if (!stayOpen) {                               // dclose0 : la duree est ecoulee
            liftSpeed = closingSpeed;
            return 0x8000;
        }
        return 0;                                      // dclose1 : reste ouverte
    }

    // ----------------------------------------------------------- ascenseurs

    /** LiftRoutine (Newanims.java:734). */
    private void liftRoutine() {
        for (int i = 0; i < lifts.size(); i++) {
            LevelData.Liftable lf = lifts.get(i);
            int d0 = lf.bottom;
            int d1 = lf.top;
            int openingSpeed = -lf.openingSpeed;       // neg.w
            int closingSpeed = lf.closingSpeed;
            openingSfx = lf.openingSFX - 1;            // subq.w #1 (idem portes)
            closingSfx = lf.closingSFX - 1;

            int d3 = liftPos[i];
            int d2 = liftSpd[i];
            int floorMoveSpeed = d2;                   // anim_FloorMoveSpeed_w
            d3 = M68k.setw(d3, d3 + M68k.muls(d2, tempFrames));

            boolean atBottom = (short) d0 <= (short) d3;
            if (!((short) d0 > (short) d3)) {
                // TODO audio
                d2 = 0;
                d3 = M68k.setw(d3, d0);                // clamp en bas
            }
            boolean atTop = (short) d1 >= (short) d3;
            if (!((short) d1 < (short) d3)) {
                // TODO audio
                d2 = 0;
                d3 = M68k.setw(d3, d1);                // clamp en haut
            }

            liftPos[i] = (short) d3;
            liftSpd[i] = (short) d2;
            int d7 = d2;

            // sol de la zone-ascenseur : ZoneT_Floor_l = (position>>2)<<8
            int floor = ((short) M68k.asrw(d3, 2)) << 8;
            LevelSim.Zone zone = zoneOf(lf.zone);
            if (zone != null) {
                zone.floorH = floor;
            }
            liftFloorH[i] = floor;

            // le joueur est-il sur l'ascenseur ? (plr1_StoodOnLift_b / Plr1_FloorSpd_w)
            if (lf.zone == playerZone) {
                playerStoodOnLift = true;
                playerFloorSpd = floorMoveSpeed;
            }

            int mask;
            boolean simple = false;
            if ((liftLocks & (1 << (i & 31))) != 0) {
                mask = 0;                              // verrouille -> LIFT_SIMPLE
                simple = true;
            } else if (atTop) {
                liftSpeed = d7;
                mask = liftLowerMask(lf.closeBits, closingSpeed, lf.zone == playerZone);
                d7 = liftSpeed;
            } else if (atBottom) {
                liftSpeed = d7;
                mask = liftRaiseMask(lf.openBits, openingSpeed, lf.zone == playerZone);
                d7 = liftSpeed;
            } else {
                mask = 0;
            }

            applyToWalls(lf.walls, mask, simple, i, d7, false);
        }
    }

    /** tstliftlower (Newanims.java:947). */
    private int liftLowerMask(int lowerBits, int closingSpeed, boolean stoodOn) {
        actionSoundFx = closingSfx;                    // anim_ActionSoundFX = anim_ClosingSoundFX
        if ((byte) lowerBits < 1) {                    // lift0
            int d1 = 0;
            if (playerSpaceTap) {
                d1 = 0b100000000;
                liftSpeed = closingSpeed;
                if (stoodOn) {
                    return 0x8000;
                }
            }
            return d1;
        } else if (lowerBits == 1) {                   // lift1
            liftSpeed = closingSpeed;
            return stoodOn ? 0x8000 : 0b100100000000;
        } else if (lowerBits < 3) {                    // lift2
            liftSpeed = closingSpeed;
            return 0x8000;
        }
        return 0;                                      // lift3
    }

    /** tstliftraise (Newanims.java:1000). */
    private int liftRaiseMask(int raiseBits, int openingSpeed, boolean stoodOn) {
        actionSoundFx = openingSfx;                    // anim_ActionSoundFX = anim_OpeningSoundFX
        if ((byte) raiseBits < 1) {                    // rlift0
            int d1 = 0;
            if (playerSpaceTap) {
                d1 = 0b100000000;
                liftSpeed = openingSpeed;
                if (stoodOn) {
                    return 0x8000;
                }
            }
            return d1;
        } else if (raiseBits == 1) {                   // rlift1
            liftSpeed = openingSpeed;
            return stoodOn ? 0x8000 : 0b100100000000;
        } else if (raiseBits < 3) {                    // rlift2
            liftSpeed = openingSpeed;
            return 0x8000;
        }
        return 0;                                      // rlift3
    }

    // ------------------------------------------------------------- commun

    /**
     * backfromtst / dothesimplething : parcourt les aretes du panneau, lit puis DESARME leur
     * flag, et applique la vitesse si le masque correspond.
     */
    private void applyToWalls(List<Integer> walls, int mask, boolean simple, int index, int speed,
                              boolean door) {
        if (walls == null) {
            return;
        }
        for (int wi : walls) {
            if (wi < 0 || wi >= lvl.edgeFlags.length) {
                continue;
            }
            if (simple) {
                lvl.edgeFlags[wi] = 0;                 // porte/lift verrouille : flag efface
                continue;
            }
            int flags = lvl.edgeFlags[wi];
            lvl.edgeFlags[wi] = 0x8000;                // desarme (valeur d'origine)
            if ((flags & mask) != 0) {
                if (door) {
                    doorSpd[index] = (short) speed;
                } else {
                    liftSpd[index] = (short) speed;
                }
                // move.w anim_ActionSoundFX_w,Aud_SampleNum_w ; blt.s nothinghit — un bruitage
                // NEGATIF ne joue rien. Le masque de fermeture vaut 0x8000 et les drapeaux
                // d'arete sont rearmes a 0x8000 chaque frame : sans cette garde, une porte sans
                // bruit de fermeture rejouait un son a chaque frame, indefiniment.
                if (sfx != null && actionSoundFx >= 0) {
                    LevelData.Liftable src = door ? doors.get(index) : lifts.get(index);
                    sfx.playAt(actionSoundFx, src.x, src.z, doorHeight(src));
                }
            }
        }
    }

    /** Hauteur de dessin approchee d'une porte/ascenseur, pour situer son bruit. */
    private int doorHeight(LevelData.Liftable l) {
        LevelSim.Zone z = zoneOf(l.zone);
        return z == null ? 0 : (short) (z.floorH >> 7);
    }

    private LevelSim.Zone zoneOf(int id) {
        return id >= 0 && id < lvl.zones.length ? lvl.zones[id] : null;
    }
}
