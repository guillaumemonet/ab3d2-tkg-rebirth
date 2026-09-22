package ab3d2.rebirth.sim;

import ab3d2.rebirth.LevelData;

import static ab3d2.rebirth.sim.M68k.asrw;
import static ab3d2.rebirth.sim.M68k.muls;
import static ab3d2.rebirth.sim.M68k.s16;
import static ab3d2.rebirth.sim.M68k.setw;

/**
 * Joueur : port fidele de la chaine d'origine
 * plr_KeyboardControl (modules/Player.java:520-700, partie deplacement)
 * -&gt; plr_Fall (Player.java:742-909) -&gt; Plr1_Control (Hires.java:3456-3620).
 *
 * <p>Deux couches comme dans le port : les entrees produisent une INTENTION ({@code snap*}),
 * puis {@link #control()} la fait passer par {@link Move} (collision + marche de portails) et
 * COMMET la position ({@code xOff/zOff/zone}).
 *
 * <p>Unites d'origine conservees : {@code xOff/zOff} en virgule fixe 16.16 (la partie entiere
 * est la coordonnee monde vue par la collision), {@code yOff} en virgule fixe (8192 = 1 m),
 * {@code angPos} en unites de la table sinus (8192 = tour complet, toujours pair, cf. AMOD_A).
 *
 * <p>Non porte (systemes absents) : joueur 2.
 */
public final class PlayerSim {

    /** PLR_STAND_HEIGHT (Hires.java:212). */
    public static final int PLR_STAND_HEIGHT = 12 * 1024;
    /** PLR_CROUCH_HEIGHT (Hires.java:213). */
    public static final int PLR_CROUCH_HEIGHT = 8 * 1024;
    /** InvIT_JetPack_w / InvCT_JetpackFuel_w : le jetpack et son carburant, dans l'inventaire. */
    private static final int JETPACK_ITEM = 1;
    private static final int JETPACK_FUEL = 1;

    private final LevelSim lvl;
    private final Move move;
    private final SinCos sinCos;

    // ---- etat (PlrT_*) ----
    public int xOff, zOff;              // Plr1_XOff_l / ZOff_l : 16.16, partie entiere = monde
    public int snapXOff, snapZOff;      // intention
    public int yOff;                    // Plr1_YOff_l (vue = SnapYOff + bobbleY)
    public int snapYOff;                // hauteur "physique"
    public int snapTYOff;               // hauteur cible (sol de la zone - taille)
    public int snapYVel;                // vitesse verticale
    public int xSpdVal, zSpdVal;        // PlrT_SnapXSpdVal_l / ZSpdVal
    public int angPos;                  // PlrT_SnapAngPos_w (unites table sinus)
    /** PlrT_SnapAngSpd_w : vitesse de rotation. Un coup recu fait tourner la vue par elle. */
    public int angSpd;
    public int height = PLR_STAND_HEIGHT;
    public int bobble, walkSfxTime, addToBobble;
    public int bobbleY;
    public int zone;
    public boolean stoodInTop;
    public boolean decelerate = true;   // Plr_Decelerate_b (controle possible)
    public int fallDamage;              // plr_FallDamage_w
    public int floorSpd;                // PlrT_FloorSpd_w (sol mobile : ascenseur)
    public boolean teleported;          // plr1_Teleported_b (frame ou l'on vient de sauter)
    /** PlrT_NoiseVol_w : le bruit qu'il vient de faire (tir, pas) — il ATTIRE les aliens. */
    public int noiseVol;
    /** File de bruitages (null = muet). */
    public SfxQueue sfx;
    /** Collision contre les ENTITES (monstres, decor solide) ; null = rien ne bloque. */
    public Collide collide;
    /** PlrT_Ducked_b : accroupi volontairement (la touche BASCULE l'etat). */
    public boolean ducked;
    /** PlrT_Squished_b : ECRASE — le plafond est trop bas pour se tenir debout. */
    public boolean squished;
    /** PlrT_SnapTargHeight_l : taille visee ; SnapSquishedHeight_l : celle imposee par le toit. */
    public int snapTargHeight = PLR_STAND_HEIGHT;
    public int snapSquishedHeight = PLR_STAND_HEIGHT;
    /** duck_key : le jeu EFFACE la touche apres l'avoir lue — c'est donc un front montant. */
    public boolean duckTap;
    /**
     * L'inventaire : le jetpack et son carburant y vivent (PlrT_Jetpack_w est le MEME mot que
     * InvIT_JetPack_w, PlrT_JetpackFuel_w que InvCT_JetpackFuel_w). null = pas de jetpack.
     */
    public Inventory inventory;
    /** Vrai la frame ou le jetpack pousse : sert au bruit et au HUD. */
    public boolean jetpackOn;
    /**
     * plr_OldHeight_l : la hauteur d'eau de la frame precedente... sauf que l'original ne
     * l'ECRIT JAMAIS (fall.s). Elle reste a 0, et le PLOUF ne joue donc que pour une eau de
     * hauteur >= 0. On garde le champ pour que la comparaison reste lisible.
     */
    private final int oldHeight = 0;
    /** GLFT_FloorData : par type de sol, degats infliges et bruit de pas (+1). */
    public int[] floorDamage = new int[16];
    public int[] floorSfx = new int[16];
    /** timetodamage : les sols toxiques ne frappent qu'une fois sur cent frames. */
    private int timeToDamage = 100;
    /** EntT_DamageTaken_b de l'objet joueur : degats encaisses cette frame. */
    public int damageTaken;
    /** EntT_ImpactX_w / ImpactZ_w : recul applique par les coups recus. */
    public int impactX, impactZ;
    /**
     * XDiff_w / ZDiff_w (Hires.java:2686) : deplacement du joueur pendant la frame, MULTIPLIE PAR
     * 16. Les aliens s'en servent pour ANTICIPER sa position quand ils tirent.
     */
    public int xDiff, zDiff;
    private int oldXWord, oldZWord;

    /**
     * A appeler au DEBUT de la frame de simulation : memorise la position (plr1_OldX_l) pour que
     * {@link #xDiff}/{@link #zDiff} soient disponibles apres le deplacement.
     */
    public void beginFrame(int tempFrames) {
        int d1 = tempFrames > 0 ? tempFrames : 1;
        xDiff = (short) ((((short) (xOff >> 16) - oldXWord) << 4) / d1);
        zDiff = (short) ((((short) (zOff >> 16) - oldZWord) << 4) / d1);
        oldXWord = (short) (xOff >> 16);
        oldZWord = (short) (zOff >> 16);
    }

    /** Entrees de la frame. */
    public boolean forward, backward, stepLeft, stepRight, run, jump;

    public PlayerSim(LevelSim lvl, LevelData data, SinCos sinCos) {
        this.lvl = lvl;
        this.sinCos = sinCos;
        this.move = new Move(lvl);
        this.move.wallFlags = 0b100000000;             // bit joueur 1
        this.move.extLen = 40;
        this.move.stepUp = 40 * 256;
        this.move.stepDown = 0x1000000;

        LevelData.Start s = data.playerStarts != null && !data.playerStarts.isEmpty()
                ? data.playerStarts.get(0) : null;
        int sx = s == null ? 0 : s.x;
        int sz = s == null ? 0 : s.z;
        zone = s == null ? 0 : s.zone;
        xOff = snapXOff = sx << 16;
        zOff = snapZOff = sz << 16;
        // SETPLAYERS : YOff = SnapYOff = SnapTYOff = ZoneT_Floor - PLR_STAND_HEIGHT
        int floorH = lvl.zones[zone] != null ? lvl.zones[zone].floorH : 0;
        yOff = snapYOff = snapTYOff = floorH - PLR_STAND_HEIGHT;
    }

    /**
     * plr_MouseControl : la visee souris est la source unique de l'angle
     * (Vis_AngPos_w -&gt; PlrT_SnapAngPos_w). {@code delta} est en unites d'angle.
     */
    public void look(int delta) {
        angPos = (angPos + delta) & 8190;              // AMOD_A (SINTAB_MASK_ADR)
    }

    /**
     * plr_KeyboardControl, bloc accroupi (Player.java:282-325) : la touche BASCULE la taille
     * visee, et un plafond a moins de PLR_STAND_HEIGHT+3*1024 du sol l'impose de toute facon
     * (ECRASE). La taille reelle rejoint la cible par pas de 1024.
     */
    private void duckControl() {
        if (duckTap) {                                 // clr.b (a5,d7.w) : un seul front
            snapTargHeight = PLR_STAND_HEIGHT;
            ducked = !ducked;                          // not.b PlrT_Ducked_b(a0)
            if (ducked) {
                snapTargHeight = PLR_CROUCH_HEIGHT;
            }
        }
        LevelSim.Zone z = lvl.zones[zone];
        int d0 = stoodInTop ? z.upperFloorH - z.upperRoofH : z.floorH - z.roofH;
        squished = false;
        snapSquishedHeight = PLR_STAND_HEIGHT;
        if (d0 <= PLR_STAND_HEIGHT + 3 * 1024) {       // bgt.s .oktostand
            squished = true;
            snapSquishedHeight = PLR_CROUCH_HEIGHT;
        }
        // .oktostand : la plus PETITE des deux tailles gagne, et on l'approche de 1024 par frame.
        int d1 = Math.min(snapTargHeight, snapSquishedHeight);
        if (height != d1) {
            height += height > d1 ? -1024 : 1024;      // .crouch / add.l #1024,d0
        }
    }

    /** plr_KeyboardControl, partie deplacement (Player.java:520-700). */
    public void keyboardControl() {
        duckControl();
        // d1 (vitesse angulaire max : 35 marche / 60 course) n'existe que pour la rotation
        // clavier, remplacee ici par la souris (cf. look()).
        // Rotation : les touches de virage sont remplacees par la souris (cf. look()), mais la
        // FRICTION ANGULAIRE et `angPos += 2*angSpd` restent — c'est par la que le recul des coups
        // recus fait tourner la vue (Player.java:556-606).
        int d3a = angSpd;
        if (decelerate) {
            int d5a = setw(0, d3a + d3a);
            d3a = setw(d3a, d3a + d5a);
            d3a = asrw(d3a, 2);                        // 3/4 de la vitesse
            if ((short) d3a < 0) {
                d3a = setw(d3a, d3a + 1);
            }
        }
        angPos = (angPos + d3a + d3a) & 8190;          // add.w d3,d0 (x2) ; AMOD_A
        angSpd = d3a;

        int d2 = 2;                                    // vitesse de deplacement (marche)
        if (run) {
            d2 = 3;
        }
        if (squished || ducked) {                      // .crouch_2 : accroupi, deux fois moins vite
            d2 = asrw(d2, 1);
        }

        int d6 = xSpdVal;
        int d7 = zSpdVal;
        if (decelerate) {                              // frottement : v -= v/8 (avec le quirk d'arrondi)
            d6 = -d6;
            if (d6 > 0) {
                d6 >>= 3;
                d6 += 1;
            } else {
                d6 >>= 3;
            }
            d7 = -d7;
            if (d7 > 0) {
                d7 >>= 3;
                d7 += 1;
            } else {
                d7 >>= 3;
            }
        }

        int d3 = 0;
        if (forward) {
            d3 = setw(d3, -d2);                        // neg.w d2 ; move.w d2,d3
        }
        if (backward) {
            d3 = setw(d3, d2);
        }
        int d4 = 0;
        if (stepLeft) {
            d4 = setw(d4, d4 + d2);
            d4 = setw(d4, d4 + d2);
            d4 = asrw(d4, 1);
        }
        if (stepRight) {
            d4 = setw(d4, d4 + d2);
            d4 = setw(d4, d4 + d2);
            d4 = asrw(d4, 1);
            d4 = setw(d4, -(short) d4);
        }
        addToBobble = setw(0, ((short) d3) << 6);      // Plr_AddToBobble_w

        int sin = sinCos.sin(angPos);
        int cos = sinCos.cos(angPos);
        d6 -= muls(sin, d3);
        d7 -= muls(cos, d3);
        d6 -= muls(cos, d4);
        d7 += muls(sin, d4);

        if (decelerate) {                              // .no_control_possible
            xSpdVal += d6;
            zSpdVal += d7;
        }
        snapXOff += xSpdVal;
        snapZOff += zSpdVal;
    }

    /** plr_Fall (Player.java:742-909) : gravite, saut, sol, plafond. */
    public void fall() {
        int d0 = snapTYOff;
        int d1 = snapYOff;
        int d2 = snapYVel;

        if (d0 < d1) {
            // sous le sol : on est repousse vers le haut
            decelerate = true;
            d0 -= d1;
            if (d0 < -512) {
                d0 = -512;
            }
            d1 += d0;
            proceed(d1, d2);
            return;
        }
        if (d0 == d1) {
            // .on_ground
            d2 = ((short) floorSpd) << 6;              // sol mobile (ascenseur)
            int dmg = setw(fallDamage, fallDamage - 100);
            if ((short) dmg > 0) {                     // add.b d3,EntT_DamageTaken_b(a4)
                damageTaken = (damageTaken + dmg) & 0xFF;
            }
            decelerate = true;
            fallDamage = 0;
            int d3 = addToBobble;
            int d4 = setw(0, d3);
            d3 = setw(d3, d3 + bobble);
            d3 = setw(d3, d3 & 8190);                  // AMOD_A
            bobble = (short) d3;
            d4 = setw(d4, d4 + walkSfxTime);
            d3 = setw(d3, d4);
            d4 = setw(d4, d4 & 4095);
            walkSfxTime = (short) d4;
            d3 = setw(d3, d3 & -4096);
            if ((short) d3 != 0) {
                footstepFx();                          // bsr plr_DoFootstepFX
            }
            int jumpSpeed = -1024;
            if (d1 >= lvl.zones[zone].water) {         // dans l'eau : poussee reduite
                jumpSpeed = -512;
            }
            if (jump && (inventory == null || inventory.health() > 0)) {
                d2 = jumpSpeed;                        // dead dudes don't jump
            }
            if (d2 > 0) {
                d2 = 0;
            }
            d1 += d2;
            proceed(d1, d2);
            return;
        }

        // .above_ground
        decelerate = false;
        jetpackOn = false;
        if (inventory != null && inventory.items[JETPACK_ITEM] != 0
                && inventory.consumables[JETPACK_FUEL] != 0) {  // .not_flying s'il manque l'un des deux
            if (inventory.consumables[JETPACK_FUEL] > 250) {
                inventory.consumables[JETPACK_FUEL] = 250;      // plafond du carburant actif
            }
            // .have_jetpack_fuel : en vol on garde le controle, et la poussee coute une unite.
            decelerate = true;
            if (jump) {
                inventory.consumables[JETPACK_FUEL]--;
                d2 += -128;                            // plr_JumpSpeed_l
                fallDamage = 0;                        // on ne se fait plus mal en retombant
                int b = setw(40, 40 + bobble);
                bobble = (short) (b & 8190);           // AMOD_A
                jetpackOn = true;
            }
        }
        int d3 = d0 - d1;
        if (d3 <= 16 * 64) {
            decelerate = true;                         // assez pres du sol : on garde le controle
        }
        d1 += d2;
        if (d0 <= d1) {
            // on a atterri
            int dmg = setw(fallDamage, fallDamage - 100);
            if ((short) dmg > 0) {                     // .skip_damage_2
                damageTaken = (damageTaken + dmg) & 0xFF;
            }
            fallDamage = 0;
            d2 = ((short) floorSpd) << 6;
            proceed(d1, d2);
            return;
        }
        // .still_above
        d2 += 64;                                      // gravite
        fallDamage = setw(fallDamage, fallDamage + 1);
        int water = lvl.zones[zone].water;
        if (d1 >= water) {                             // on est dans l'eau
            if (water >= oldHeight && sfx != null) {   // cmp.l plr_OldHeight_l,d0 ; blt .no_splash_fx
                sfx.play(6);                           // move.w #6,Aud_SampleNum_w : PLOUF
            }
            decelerate = true;                         // on garde le controle en nageant
            fallDamage = 0;
            if (d2 >= 512) {
                d2 = 512;                              // vitesse terminale
            }
        }
        proceed(d1, d2);
    }

    /** .proceed (fin de plr_Fall) : butee de plafond. */
    private void proceed(int d1, int d2) {
        LevelSim.Zone z = lvl.zones[zone];
        int d3 = stoodInTop ? z.upperRoofH : z.roofH;
        d3 += 10 * 256;
        if (d3 >= d1) {
            d1 = d3;
            if (d2 < 0) {
                d2 = 0;
            }
        }
        snapYVel = d2;
        snapYOff = d1;
    }

    /** Plr1_Control (Hires.java:3456-3620) : collision, zone, hauteur cible. */
    public void control() {
        teleported = false;
        // instantane Tmp -> courant
        int oldx = xOff;
        int oldz = zOff;
        xOff = snapXOff;
        zOff = snapZOff;

        // bobble (mouvement de tete) : Plr1_Control calcule bobbleY et thingheight
        int d1 = sinCos.value(bobble);                 // (d3 = xwobble : balancement lateral, non porte)
        if (d1 > 0) {
            d1 = -d1;
        }
        d1 = setw(d1, d1 + 16384);
        d1 = asrw(d1, 4);
        if (!ducked && !squished) {                    // .notdouble : accroupi, la tete bouge moins
            d1 = setw(d1, d1 + d1);
        }
        bobbleY = (short) d1;
        int thingHeight = height - bobbleY;
        yOff = snapYOff + bobbleY;

        int oldxw = s16(oldx >> 16);
        int oldzw = s16(oldz >> 16);
        int newxw = s16(xOff >> 16);
        int newzw = s16(zOff >> 16);
        boolean cantmove = false;

        // Teleporteur de la zone (ZoneT_TelZone_w) : on saute avant tout deplacement, sauf si une
        // ENTITE occupe deja le point d'arrivee.
        LevelSim.Zone here = lvl.zones[zone];
        if (here.telZone >= 0) {
            if (blocked(here.telX, here.telZ, oldxw, oldzw, thingHeight)) {
                newxw = s16(xOff >> 16);               // move.w Plr1_XOff_l,newx : on ne saute pas
                newzw = s16(zOff >> 16);
            } else {
                // .teleport
                int dy = yOff - here.floorH;
                xOff = (here.telX << 16) | (xOff & 0xFFFF);
                zOff = (here.telZ << 16) | (zOff & 0xFFFF);
                zone = here.telZone;
                int y = dy + lvl.zones[zone].floorH;
                snapYOff = yOff = snapTYOff = y;
                snapXOff = xOff;
                snapZOff = zOff;
                teleported = true;
                if (sfx != null) {
                    sfx.play(26);                      // move.w #26,Aud_SampleNum_w
                }
                cantmove = true;                       // bra .cantmove : pas de deplacement
            }
        }

        if (!cantmove && blocked(newxw, newzw, oldxw, oldzw, thingHeight)) {
            // On rentre dans un monstre ou dans le decor : la frame ne bouge pas.
            xOff = (oldxw << 16) | (xOff & 0xFFFF);    // move.w oldx,Plr1_XOff_l
            zOff = (oldzw << 16) | (zOff & 0xFFFF);
            snapXOff = xOff;
            snapZOff = zOff;
            cantmove = true;
        }

        if (!cantmove) {
            // .nothitanything
            move.oldx = oldxw;
            move.oldz = oldzw;
            move.newx = newxw;
            move.newz = newzw;
            move.newy = yOff;
            move.oldy = yOff;
            move.zone = zone;
            move.stoodInTop = stoodInTop;
            move.thingHeight = thingHeight;
            move.stepUp = ducked || squished ? 10 * 256 : 40 * 256;   // .smallstep / .okbigstep
            move.stepDown = 0x1000000;
            move.moveObject();

            stoodInTop = move.stoodInTop;
            zone = move.zone;
            xOff = (move.newx << 16) | (xOff & 0xFFFF);  // move.w newx,Plr1_XOff_l (mot fort)
            zOff = (move.newz << 16) | (zOff & 0xFFFF);
            snapXOff = xOff;
            snapZOff = zOff;
        }

        // .cantmove
        LevelSim.Zone z = lvl.zones[zone];
        int floorH = stoodInTop ? z.upperFloorH : z.floorH;
        snapTYOff = floorH - height;
    }

    /** Obj_DoCollision : vrai si une entite occupe la position visee. */
    private boolean blocked(int newx, int newz, int oldx, int oldz, int thingHeight) {
        return collide != null
                && collide.check(newx, newz, oldx, oldz, yOff, thingHeight, zone, stoodInTop);
    }

    public boolean hitWall() {
        return move.hitwall;
    }

    // ------------------------------------------------ conversions pour la vue

    /** X monde en unites jME (X/64). */
    public float worldX() {
        return (xOff / 65536f) / 64f;
    }

    /** Z monde en unites jME (Z inverse). */
    public float worldZ() {
        return -(zOff / 65536f) / 64f;
    }

    /**
     * Plr1_Use (Hires.java:3813) : encaisse les degats accumules pendant la frame — recul dans les
     * vitesses, secousse de la vue, et sante en moins. {@code rand} sert au twist aleatoire.
     *
     * @return les degats appliques (0 si rien)
     */
    public int applyDamage(Inventory inv, Nav rand) {
        int d2 = damageTaken & 0xFF;
        if (d2 == 0) {
            return 0;
        }
        int d4 = 0;                                    // moveq #0,d4
        int d3 = s16(impactX);
        if (d3 != 0) {                                 // beq .notwist
            d4 = d2;
        }
        // Les vitesses sont en 16.16 : l'original n'ecrit QUE le mot fort (move.w).
        xSpdVal = (s16((xSpdVal >> 16) + d3) << 16) | (xSpdVal & 0xFFFF);
        d3 = s16(impactZ);
        if (d3 != 0) {                                 // beq .notwist2
            d4 = d2;
        }
        zSpdVal = (s16((zSpdVal >> 16) + d3) << 16) | (zSpdVal & 0xFFFF);
        // (EntT_ImpactY_w non porte : aucun coup vertical pour l'instant)
        impactX = 0;
        impactZ = 0;
        int d0 = rand.rand();                          // jsr GetRand
        d0 = muls(d0, d4);
        d0 = d0 >> 8;
        d0 = d0 >> 4;
        angSpd = s16(angSpd + d0);                     // add.w d0,Plr1_SnapAngSpd_w
        inv.consumables[0] = s16(inv.consumables[0] - d2); // sub.w d2,Plr1_Health_w
        damageTaken = 0;
        return d2;
    }

    /**
     * plr_DoFootstepFX (modules/Player.java:916) : le bruit depend du TYPE DE SOL de la zone
     * (ZoneT_FloorNoise_w -> GLFT_FloorData), et devient un « plouf » (effet 6) si le joueur
     * patauge — l'eau de la zone est au-dessus de ses yeux et il est a l'etage du bas.
     */
    private void footstepFx() {
        if (sfx == null || zone < 0 || zone >= lvl.zones.length) {
            return;
        }
        LevelSim.Zone z = lvl.zones[zone];
        int d0 = z.floorNoise;
        if (z.water < z.floorH && z.water >= yOff && !stoodInTop) {
            sfx.play(6);                               // .have_water : splash
            return;
        }
        if (stoodInTop) {
            d0 = z.upperFloorNoise;
        }
        int num = (d0 >= 0 && d0 < floorSfx.length ? floorSfx[d0] : 0) - 1; // subq #1
        if (num >= 0) {
            sfx.play(num);                             // le pas est « sur » le joueur : sans position
        }
    }

    /**
     * floorDamage (Hires.java:4283) : un sol toxique (ou un liquide) ronge le joueur une frame
     * sur cent, tant qu'il y a les pieds dedans. Renvoie les degats infliges.
     */
    public int floorDamage() {
        if (--timeToDamage > 0) {                      // sub.w #1,timetodamage ; bgt .skip_damage
            return 0;
        }
        timeToDamage = 100;                            // move.w #100,timetodamage
        if (zone < 0 || zone >= lvl.zones.length) {
            return 0;
        }
        LevelSim.Zone z = lvl.zones[zone];
        int d0 = stoodInTop ? z.upperFloorNoise : z.floorNoise;
        boolean apply = z.water < snapYOff || snapTYOff <= snapYOff; // liquide, ou pose au sol
        if (!apply) {
            return 0;
        }
        int dmg = d0 >= 0 && d0 < floorDamage.length ? floorDamage[d0] : 0;
        damageTaken += dmg;                            // add.b d0,EntT_DamageTaken_b
        return dmg;
    }

    /** Y de l'oeil en unites jME (8192 unites = 1 m, axe inverse). */
    public float worldY() {
        return -yOff / 8192f;
    }

    /**
     * Lacet camera. L'angle du jeu donne la direction MONDE {@code (sin a, cos a)} : c'est celle
     * ou le joueur avance (plr_KeyboardControl ajoute {@code +sin, +cos} en marche avant). En jME
     * le Z est inverse, donc cette direction vaut {@code (sin a, 0, -cos a)} ; comme une rotation
     * de lacet {@code t} envoie l'axe de visee (0,0,1) sur {@code (sin t, 0, cos t)}, il faut
     * {@code t = PI - a}. Sans le PI la vue regardait a l'oppose de la marche.
     */
    public float yawRadians() {
        return (float) (Math.PI - angPos / 8192.0 * Math.PI * 2.0);
    }
}
