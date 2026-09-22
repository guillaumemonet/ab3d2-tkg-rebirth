package ab3d2.rebirth.sim;

import static ab3d2.rebirth.sim.M68k.asrw;
import static ab3d2.rebirth.sim.M68k.divs;
import static ab3d2.rebirth.sim.M68k.muls;
import static ab3d2.rebirth.sim.M68k.s16;
import static ab3d2.rebirth.sim.M68k.setw;

import ab3d2.rebirth.GlfData;
import ab3d2.rebirth.ObjectAnim;

import java.util.ArrayList;
import java.util.List;

/**
 * Tir du joueur et vol des projectiles.
 *
 * <p>Port de {@code plrShot} / {@code nothingToShoot} / {@code fireProjectile}
 * (Newplayershoot.java:57-421) et de {@code ItsABullet} (Newanims.java:1650-1925).
 *
 * <p>Le jeu garde un POOL de 20 emplacements de tir ({@code NUM_PLR_SHOT_DATA}) : tirer, c'est
 * trouver le premier libre ({@code ObjT_ZoneID_w < 0}) et le remplir ; quand l'animation d'impact
 * est finie, l'emplacement est rendu ({@code FREE_ENT}). On reproduit ce pool tel quel — c'est lui
 * qui limite le nombre de projectiles en vol, donc la cadence reelle des armes rapides.
 *
 * <p>Pas encore porte (le remake n'a pas d'ennemis) : la recherche de cible et la visee
 * automatique de {@code plrShot} — on prend toujours la branche {@code .nothing_to_shoot}, celle
 * du tir droit devant ; les degats, {@code ComputeBlast} et l'illumination des points au passage
 * ({@code anim_BrightenPoints}) ; les sons.
 */
public final class Shots {

    /** NUM_PLR_SHOT_DATA. */
    public static final int PLR_POOL = 20;
    /**
     * De combien on recule un impact de mur, en unites de position (64 = une unite monde). La
     * moitie d'un panneau d'explosion, en gros : de quoi le decoller de la surface sans qu'il
     * ait l'air de flotter.
     */
    private static final int IMPACT_BACKOFF = 24;

    /** NUM_ALIEN_SHOT_DATA : le jeu donne aux aliens leur PROPRE pool de projectiles. */
    public static final int ALIEN_POOL = 20;
    /** Total des emplacements rendus. */
    public static final int POOL = PLR_POOL + ALIEN_POOL;

    /** Un emplacement du pool (champs ShotT de l'entite d'origine). */
    public static final class Shot {
        /** ObjT_ZoneID_w &gt;= 0 : emplacement occupe. */
        public boolean active;
        public int x, z;                 // Lvl_ObjectPoints : 16.16 (MoveObject ne touche que le mot fort)
        public int accY;                 // ShotT_AccYPos_w (long)
        public int heightWord;           // 4(a0) : hauteur de dessin = AccYPos >> 7
        public int velX, velZ;           // ShotT_VelocityX/Z_w (16.16 par frame)
        public int velY;                 // ShotT_VelocityY_w (mot)
        public int zone;
        public boolean upperZone;        // ShotT_InUpperZone_b
        public int type;                 // ShotT_Size_b : index de definition de balle
        public int lifetime;             // ShotT_Lifetime_w
        public int anim;                 // ShotT_Anim_b : pas d'animation courant
        public boolean popping;          // ShotT_Status_b : animation d'impact en cours
        public int gravity;              // ShotT_Gravity_w
        public int power;                // ShotT_Power_w : degats a l'impact
        public int enemyFlags;           // EntT_EnemyFlags_l : quels types d'entites il touche
        public boolean bounceHoriz;      // ShotT_Flags_w
        public boolean bounceVert;       // ShotT_Flags_w+1

        // --- donnees de DESSIN posees par ItsABullet (9/11/10/8/6 (a0)) ---
        public int sheet;                // 9(a0) : feuille de sprites
        public int frame;                // 11(a0) : frame
        public boolean additive;         // 10(a0) = 6 : melange additif
        public int glareSize;            // 8(a0) : taille du halo (graphicType == 1)
        public int brightness;           // anim_Brightness_w : lumiere emise (non portee)
    }

    private final LevelSim lvl;
    private final GlfData glf;
    private final Move move;
    private final SinCos sinCos;
    private final List<Shot> pool = new ArrayList<>();
    /** Entites que les balles peuvent toucher (posees par le jeu). */
    private Aliens targets;
    /** File de bruitages (null = muet). */
    public SfxQueue sfx;
    /** Lumieres dynamiques : un projectile lumineux eclaire la piece (null = aucune). */
    public DynLight dynLight;
    /** Ligne de vue et generateur du jeu : le souffle ne traverse pas les murs. */
    public Los los;
    public Nav nav;

    /** PlrT_TimeToShoot_w : frames restantes avant le prochain tir. */
    public int timeToShoot;
    /** Anim_TempFrames_w : frames ecoulees. La simulation tourne a pas fixe, donc toujours 1. */
    public int tempFrames = 1;
    /** Vrai la frame ou un tir est parti (pour le son et l'animation de l'arme). */
    public boolean fired;
    /** Vrai la frame ou le tir a echoue faute de munitions (SFX 12 dans le jeu). */
    public boolean dryFire;

    public Shots(LevelSim lvl, GlfData glf, SinCos sinCos) {
        this.lvl = lvl;
        this.glf = glf;
        this.sinCos = sinCos;
        this.move = new Move(lvl);
        for (int i = 0; i < POOL; i++) {
            pool.add(new Shot());
        }
    }

    public List<Shot> shots() {
        return pool;
    }

    /** Branche les monstres : sans eux une balle ne peut toucher que les murs. */
    public void setTargets(Aliens a) {
        this.targets = a;
    }

    /** Nombre d'emplacements ALIEN occupes (validation). */
    public int alienShotsActive() {
        int n = 0;
        for (int i = PLR_POOL; i < pool.size(); i++) {
            if (pool.get(i).active) {
                n++;
            }
        }
        return n;
    }

    /** Branche le joueur : c'est la cible des tirs d'alien. */
    public void setPlayer(PlayerSim p) {
        this.player = p;
    }

    private PlayerSim player;

    // ------------------------------------------------------------------ tir

    /**
     * plrShot : cadence, munitions, puis tir. {@code fire} = touche de tir enfoncee
     * (PlrT_TmpFire_b), {@code aimSpeed} = visee verticale (PlrT_AimSpeed_l).
     *
     * @return vrai si un tir est parti
     */
    public boolean fire(PlayerSim player, Inventory inv, int gun, boolean fire,
                        float aimSlope) {
        fired = false;
        dryFire = false;
        if (timeToShoot != 0) {                        // tst.w PlrX_TimeToShoot_w ; beq .can_fire
            timeToShoot = s16(timeToShoot - tempFrames); // sub.w Anim_TempFrames_w
            if ((short) timeToShoot < 0) {
                timeToShoot = 0;
            }
            return false;                              // .no_fire
        }
        // .can_fire
        GlfData.Gun g = glf == null ? null : glf.gun(gun);
        if (g == null) {
            return false;
        }
        GlfData.Bullet b = glf.bullet(g.bulletType);
        if (b == null) {
            return false;
        }
        int bulType = g.bulletType;                    // BULTYPE
        int ammoInMyGun = inv.ammo(bulType);           // AmmoInMyGun
        int bulletSpd = b.speed;                       // BulletSpd (mot bas de BulT_Speed_l)
        if (!fire) {                                   // tst.b PlrX_TmpFire_b ; beq .no_fire
            return false;
        }

        // La recherche de cible du jeu (.fire_hitscanned_bullets, avec son auto-visee) n'est pas
        // portee : on part donc toujours en .nothing_to_shoot. C'est sans consequence depuis que
        // le tir hitscan suit le rayon de visee, puisque c'est ce que l'auto-visee cherchait a
        // obtenir — mais il n'y a pas d'aide a la visee.
        if (ammoInMyGun < g.bulletCount) {             // cmp.w d1,d2 ; bge .okcanshoot
            dryFire = true;
            if (sfx != null) {
                sfx.play(12);                          // move.w #12,Aud_SampleNum_w (noammo)
            }
            return false;
        }
        // .okcanshoot
        timeToShoot = g.delay;
        if (sfx != null) {
            sfx.play(g.sfx);                           // move.w ShootT_SFX_w(a6),Aud_SampleNum_w
        }                         // move.w ShootT_Delay_w(a6),PlrX_TimeToShoot_w
        inv.setAmmo(bulType, s16(ammoInMyGun - g.bulletCount));
        fired = true;

        // ECART ASSUME. Le jeu derive la vitesse verticale de PlrX_AimSpeed_l, exprimee en
        // LIGNES D'ECRAN (« asr.w #8-BulletSpd »). Elle depend donc de l'ouverture de la vue et
        // SATURE des que le tangage depasse la demi-ouverture — sur Amiga c'etait sans objet,
        // la visee ne pouvant pas depasser le bord de l'ecran. En 3D le tir doit suivre le
        // rayon de visee a n'importe quelle inclinaison : on fait donc TOURNER le vecteur
        // vitesse du tangage, ce qui en conserve la norme et ne sature rien.
        if (b.hitScan == 0) {                          // tst.w BulT_IsHitScan_l+2(a5) ; beq FireProjectile
            fireProjectile(player, g, b, bulType, bulletSpd, aimSlope);
        } else {
            hitscanNoTarget(player, bulType, aimSlope, g.bulletCount);
        }
        return true;
    }

    /** plrX_FireProjectile + firefive : {@code bulletCount} projectiles en eventail. */
    private void fireProjectile(PlayerSim player, GlfData.Gun g, GlfData.Bullet b,
                                int bulType, int bulletSpd, float aimSlope) {
        // Le tangage decompose en cosinus (ce qui reste a l'horizontale) et sinus (la montee).
        double t = aimSlope;
        double invLen = 1.0 / Math.sqrt(1.0 + t * t);
        double cosPitch = invLen;
        double sinPitch = t * invLen;
        // Une unite monde vaut 64 unites horizontales et 8192 de hauteur ; la vitesse
        // horizontale par frame vaut (amplitude << bulletSpd) / 65536, soit 2^bulletSpd / 2.
        // La composante verticale qui donne la meme pente vaut donc 64 * 2^bulletSpd * sin.
        // La hauteur croit vers le BAS, d'ou le signe.
        int bulYSpd = (int) Math.round(-64.0 * (1 << (bulletSpd & 15)) * sinPitch);
        int d5 = setw(0, g.bulletCount);               // move.w ShootT_BulCount_w(a6),d5
        int d6 = setw(0, d5);                          // move.w d5,d6
        d6 = setw(d6, d6 - 1);                         // subq #1,d6
        d6 = setw(d6, d6 << 7);                        // asl.w #7,d6
        d6 = setw(d6, -s16(d6));                       // neg.w d6
        d6 = setw(d6, d6 + player.angPos);             // add.w tempangpos,d6
        d6 = setw(d6, d6 & SinCos.MASK);               // AMOD_A d6

        int tempXOff = s16(player.xOff >> 16);         // tempxoff (mot fort de PlrX_XOff_l)
        int tempZOff = s16(player.zOff >> 16);
        int tempYOff = player.yOff + 10 * 128;         // tempyoff

        while (true) {                                 // firefive
            Shot s = free();
            if (s == null) {
                return;                                // plus d'emplacement : le jeu abandonne aussi
            }
            s.active = true;
            s.popping = false;                         // (emplacement libere avec Status = 0)
            s.gravity = b.gravity;                     // move.w BulT_Gravity_l+2(a5),ShotT_Gravity_w(a0)
            s.bounceHoriz = b.bounceHoriz != 0;        // ShotT_Flags_w
            s.bounceVert = b.bounceVert != 0;          // ShotT_Flags_w+1
            // Le jeu borne ici bulyspd a +/-20*128. Cette borne etait un filet : avec son
            // aimSpeed plafonne, la valeur ne pouvait de toute facon pas la depasser. Depuis
            // que la vitesse vient de la pente reelle, elle mordrait des 39 degres de tangage
            // et rabattrait le tir sous le reticule — on ne la reprend donc pas.
            s.type = bulType;                          // move.b BULTYPE+1,ShotT_Size_b(a0)
            s.power = b.hitDamage & 0xFF;              // move.b BulT_HitDamage_l+3(a5),ShotT_Power_w(a0)
            s.enemyFlags = 0b100011;                   // move.l #%100011,EntT_EnemyFlags_l (joueur 1)
            s.x = tempXOff << 16;                      // move.w tempxoff,(a1)
            s.z = tempZOff << 16;                      // move.w tempzoff,4(a1)
            int sin = s16(sinCos.value(d6));           // move.w (a1,d6.w),d0 ; ext.l d0
            int cos = s16(sinCos.value(d6 + SinCos.COSINE_OFS)); // move.w COSINE_OFS(a1,d6.w),d2
            d6 = setw(d6, d6 + 256);                   // add.w #256,d6
            d6 = setw(d6, d6 & SinCos.MASK);           // AMOD_A d6
            // L'horizontale est reduite du cosinus du tangage : le vecteur tourne, il ne
            // s'allonge pas.
            s.velX = (int) Math.round((double) (sin << (bulletSpd & 31)) * cosPitch);
            s.velZ = (int) Math.round((double) (cos << (bulletSpd & 31)) * cosPitch);
            s.velY = s16(bulYSpd);                     // move.w bulyspd,ShotT_VelocityY_w(a0)
            s.upperZone = player.stoodInTop;
            s.lifetime = 0;
            s.zone = player.zone;                      // move.w (a2),ObjT_ZoneID_w(a0)
            s.anim = 0;
            s.accY = tempYOff + 20 * 128;              // move.l tempyoff,d0 ; add.l #20*128,d0
            s.heightWord = s16(s.accY >> 7);           // asr.l #7,d0 ; move.w d0,4(a0)
            d5 = setw(d5, d5 - 1);                     // sub.w #1,d5
            if ((short) d5 <= 0) {                     // bgt firefive
                return;
            }
        }
    }

    /**
     * {@code .nothing_to_shoot}, branche HITSCAN (fusil, mitrailleuse) : un rayon part droit
     * devant, avance par pas doubles jusqu'a toucher un mur, et l'impact y est marque par un tir
     * deja en animation d'explosion ({@code Status = 1}).
     */
    private void hitscanNoTarget(PlayerSim player, int bulType, float aimSlope, int bulletCount) {
        int oldX = s16(player.xOff >> 16);             // move.w PlrX_XOff_l,oldx
        int oldZ = s16(player.zOff >> 16);
        int d0 = setw(0, sinCos.sin(player.angPos));   // move.w PlrX_SinVal_w,d0
        d0 = asrw(d0, 7);                              // asr.w #7,d0
        int newX = setw(0, d0 + oldX);                 // add.w oldx,d0 ; move.w d0,newx
        d0 = setw(0, sinCos.cos(player.angPos));       // move.w PlrX_CosVal_w,d0
        d0 = asrw(d0, 7);
        int newZ = setw(0, d0 + oldZ);
        int oldY = player.yOff + 10 * 128;             // move.l PlrX_YOff_l,d0 ; add.l #10*128,d0
        // ECART ASSUME. Ici le jeu fait « move.w #0,bulyspd » : le tir hitscan sans cible part a
        // PLAT, et c'est son auto-visee (non portee) qui rattrapait la hauteur. Sur Amiga cela ne
        // se voyait pas — regarder en haut y decale l'image sans incliner la vue. En 3D avec une
        // vraie visee souris, un rayon horizontal ne pourrait toucher que ce qui est pile a
        // hauteur d'oeil : on lui donne donc la pente de la visee.
        //
        // Conversion : une unite monde vaut 64 unites de position horizontale et 8192 unites de
        // hauteur, d'ou le facteur 128. La hauteur croit vers le BAS, d'ou le signe.
        int stepDist = (int) Math.round(Math.sqrt(
                (double) s16(newX - oldX) * s16(newX - oldX)
                        + (double) s16(newZ - oldZ) * s16(newZ - oldZ)));
        int newY = oldY - Math.round(aimSlope * 128f * stepDist);
        // Le jeu disperse aussi le tir verticalement (GetRand & $fff - $800) ; sans generateur
        // commun on ne reproduit pas cette dispersion.

        move.oldx = s16(oldX);
        move.oldz = s16(oldZ);
        move.newx = s16(newX);
        move.newz = s16(newZ);
        move.oldy = oldY;
        move.newy = newY;
        move.zone = player.zone;
        move.stoodInTop = player.stoodInTop;
        setupBulletMove();
        move.thingHeight = 0;                          // move.l #0,thingheight (vs 10*128 en vol)
        int guard = 0;
        Aliens.Alien struck = null;
        while (true) {                                 // .again
            // La zone AVANT le pas : c'est celle d'ou part le segment qu'on va tester. Prendre
            // celle d'apres laisserait passer un monstre des que le rayon change de zone.
            int segZone = move.zone;
            move.moveObject();
            // ECART ASSUME. Le jeu decide AVANT de tirer quelle entite est alignee
            // (.fire_hitscanned_bullets et son auto-visee) ; ce chemin-la n'est pas porte, et le
            // joueur ne veut pas d'aide a la visee. On teste donc les monstres le long du RAYON,
            // segment par segment : c'est la geometrie seule qui decide. Sans ce test, les armes
            // hitscan ne faisaient aucun degat.
            struck = firstAlienOnSegment(s16(move.oldx), s16(move.oldz), s16(move.newx),
                    s16(move.newz), s16(move.newy >> 7), segZone, move.zone,
                    player.stoodInTop);
            if (struck != null || move.hitwall || ++guard > 64) { // tst.b hitwall ; bne .nofurther
                break;
            }
            advanceMove();                             // (new - old) * 2 sur x/z/y
        }
        // .nofurther : marque l'impact
        GlfData.Bullet bul = glf == null ? null : glf.bullet(bulType);
        if (struck != null && targets != null) {
            // Une arme hitscan tire ShootT_BulCount_w PROJECTILES par coup — deux plombs pour le
            // fusil — et le jeu les trace un a un (.fire_hitscanned_bullets). Notre rayon est
            // unique et sans dispersion : les N plombs toucheraient donc la meme cible, ce qui
            // revient a lui appliquer N fois les degats. Sans ca le fusil ne faisait que la
            // MOITIE du mal qu'il doit faire.
            int pellets = Math.max(1, bulletCount);
            targets.hit(struck, (bul == null ? 0 : (bul.hitDamage & 0xFF)) * pellets);
        }
        Shot s = free();
        if (s == null) {
            return;
        }
        s.active = true;
        // L'impact se pose sur le monstre touche, sinon la ou le rayon a rencontre le mur.
        //
        // Sur un mur on le RECULE le long du rayon : le panneau d'explosion est centre sur le
        // point d'impact, donc pose pile sur la surface il s'enfonce a moitie dedans et le test
        // de profondeur en mange la moitie.
        int ix = s16(move.newx);
        int iz = s16(move.newz);
        if (struck == null) {
            int dx = s16(ix - s16(move.oldx));
            int dz = s16(iz - s16(move.oldz));
            int len = sqrt(muls(dx, dx) + muls(dz, dz));
            if (len > 0) {
                ix = s16(ix - divs(muls(dx, IMPACT_BACKOFF), len));
                iz = s16(iz - divs(muls(dz, IMPACT_BACKOFF), len));
            }
        }
        s.x = (struck != null ? struck.x : ix) << 16;  // move.w newx,(a1,d2.w*8)
        s.z = (struck != null ? struck.z : iz) << 16;
        s.popping = true;                              // move.b #1,ShotT_Status_b(a0)
        s.gravity = 0;
        s.velX = 0;
        s.velZ = 0;
        s.velY = 0;
        s.type = bulType;                              // move.b BULTYPE+1,ShotT_Size_b(a0)
        s.anim = 0;
        s.lifetime = 0;
        s.zone = move.zone;                            // move.w (a1),ObjT_ZoneID_w(a0)
        s.upperZone = move.stoodInTop;
        s.accY = move.wallhitheight;                   // move.l wallhitheight,ShotT_AccYPos_w(a0)
        s.heightWord = s16(s.accY >> 7);
    }

    /** Bloc commun d'init avant MoveObject (exitfirst..Obj_ZonePtr_l). */
    private void setupBulletMove() {
        move.exitFirst = true;                         // st exitfirst
        move.wallBounce = false;                       // clr.b Obj_WallBounce_b
        move.extLen = 0;                               // move.w #0,Obj_ExtLen_w
        move.awayFromWall = 0xFF;                      // move.b #$ff,Obj_AwayFromWall_b
        move.wallFlags = 0b0000010000000000;           // move.w #%0000010000000000,wallflags
        move.stepUp = 0;                               // move.l #0,StepUpVal
        move.stepDown = 0x1000000;                     // move.l #$1000000,StepDownVal
        move.thingHeight = 10 * 128;                   // move.l #10*128,thingheight
    }

    /** Avance (new - old) * 2 sur x/z (mots) et y (long) entre deux passes de MoveObject. */
    private void advanceMove() {
        int d0 = s16(s16(move.newx) - s16(move.oldx));
        move.oldx = s16(move.oldx + d0);
        move.newx = s16(move.newx + d0);
        d0 = s16(s16(move.newz) - s16(move.oldz));
        move.oldz = s16(move.oldz + d0);
        move.newz = s16(move.newz + d0);
        int d0l = move.newy - move.oldy;
        move.oldy = move.oldy + d0l;
        move.newy = move.newy + d0l;
    }

    /**
     * SHOOTPLAYER1 (newaliencontrol.s:1039) : le monstre a tire au juge sur le joueur et l'a
     * RATE. On trace quand meme le rayon pour marquer l'impact sur ce qu'il y a derriere.
     *
     * <p>La direction part du monstre vers la position VISEE du joueur, puis un tirage
     * {@code GetRand >> 4} la fait devier perpendiculairement (et decale aussi la hauteur
     * d'arrivee) — c'est ce qui donne l'impression de balles qui frolent. Le rayon avance par
     * pas doubles jusqu'au premier mur, et l'impact y est pose comme un tir du pool JOUEUR deja
     * en animation d'explosion, de type de balle 0 (le jeu ecrit {@code move.b #0,ShotT_Size_b}).
     *
     * @return true si un impact a pu etre pose
     */
    public boolean shootPlayer1(Aliens.Alien a, PlayerSim p) {
        int oldX = s16(a.x);                           // move.w (a1),oldx
        int oldZ = s16(a.z);                           // move.w 4(a1),oldz
        int newX = s16(p.snapXOff >> 16);              // move.w Plr1_TmpXOff_l,newx
        int newZ = s16(p.snapZOff >> 16);
        int d1 = s16(newX - oldX);
        int d2 = s16(newZ - oldZ);
        int d0 = nav == null ? 0 : asrw(setw(0, nav.rand()), 4);   // GetRand ; asr.w #4,d0
        d0 = s16(d0);
        int devZ = s16(muls(d0, d1) >> 16);            // muls d0,d1 ; swap d1
        int devX = s16(muls(d0, d2) >> 16);            // muls d0,d2 ; swap d2
        newZ = s16(newZ + devZ);                       // add.w d1,newz
        newX = s16(newX - devX);                       // sub.w d2,newx

        int y = (p.snapYOff + 15 * 128) >> 7;          // add.l #15*128 ; asr.l #7
        int yw = setw(0, y);
        int dy = s16(muls(d0, s16(yw)) >> 16);         // move.w d1,d2 ; muls d0,d2 ; swap d2
        yw = setw(yw, yw + dy);                        // add.w d2,d1
        int newY = s16(yw) << 7;                       // ext.l d1 ; asl.l #7,d1
        int oldY = s16(a.height) << 7;                 // move.w 4(a0),d1 ; ext.l ; asl.l #7

        move.oldx = oldX;
        move.oldz = oldZ;
        move.newx = newX;
        move.newz = newZ;
        move.oldy = oldY;
        move.newy = newY;
        move.zone = a.zone;
        move.stoodInTop = a.upperZone;
        setupBulletMove();
        move.thingHeight = 0;                          // move.l #0,thingheight
        int guard = 0;
        while (true) {                                 // .again
            move.moveObject();
            if (move.hitwall || ++guard > 64) {        // tst.b hitwall ; bne .nofurther
                break;
            }
            advanceMove();
        }
        // .foundonefree2 : l'impact, dans le pool du JOUEUR
        Shot s = free();
        if (s == null) {
            return false;                              // .findonefree2 echoue : le jeu abandonne
        }
        s.active = true;
        s.x = s16(move.newx) << 16;
        s.z = s16(move.newz) << 16;
        s.popping = true;                              // move.b #1,ShotT_Status_b(a0)
        s.gravity = 0;                                 // move.w #0,ShotT_Gravity_w(a0)
        s.type = 0;                                    // move.b #0,ShotT_Size_b(a0)
        s.anim = 0;                                    // move.b #0,ShotT_Anim_b(a0)
        s.lifetime = 0;
        s.power = 0;                                   // (l'original laisse la valeur precedente :
        s.enemyFlags = 0;                              //  sans effet, un tir en Status 1 ne touche rien)
        s.velX = 0;
        s.velZ = 0;
        s.velY = 0;
        s.zone = move.zone;                            // move.w (a1),ObjT_ZoneID_w(a0) : zone d'impact
        s.upperZone = move.stoodInTop;
        s.accY = move.wallhitheight;                   // move.l wallhitheight,ShotT_AccYPos_w(a0)
        s.heightWord = s16(s.accY >> 7);
        return true;
    }

    private Shot free() {                              // .findonefree (pool du joueur)
        for (int i = 0; i < PLR_POOL; i++) {
            if (!pool.get(i).active) {
                return pool.get(i);
            }
        }
        return null;
    }

    /** .findonefree du pool ALIEN (AI_AlienShotDataPtr_l). */
    private Shot freeAlien() {
        for (int i = PLR_POOL; i < pool.size(); i++) {
            if (!pool.get(i).active) {
                return pool.get(i);
            }
        }
        return null;
    }

    /**
     * FireAtPlayer1 (Newaliencontrol.java:924) : l'alien crache un projectile vers le joueur.
     *
     * <p>Il ne vise pas ou il est, mais ou il SERA : la position est avancee de
     * {@code XDiff * distance / vitesse / 16}. {@code offMult} decale ensuite le depart sur le
     * cote (les gros monstres tirent depuis une epaule), et la vitesse verticale est calculee pour
     * arriver a la tete du joueur ({@code 4(a2) - 20}) au bout du vol.
     */
    public void fireAtPlayer(Aliens.Alien a, PlayerSim p, int bulletType, int shotSpeed,
                             int shotShift, int yOff, int offMult) {
        Shot s = freeAlien();
        if (s == null) {
            return;                                    // .cantshoot
        }
        GlfData.Bullet b = glf == null ? null : glf.bullet(bulletType);
        if (b == null) {
            return;
        }
        int oldX = a.x;
        int oldZ = a.z;
        int newX = s16(p.xOff >> 16);
        int newZ = s16(p.zOff >> 16);

        int dist = calcDist(oldX, oldZ, newX, newZ);   // CalcDist -> distaway
        int d6 = muls(p.xDiff, dist);                  // anticipation en X
        d6 = shotSpeed == 0 ? 0 : divs(d6, shotSpeed);
        d6 = asrw(setw(0, d6), 4);
        newX = s16(newX + s16(d6));
        d6 = muls(p.zDiff, dist);                      // anticipation en Z
        d6 = shotSpeed == 0 ? 0 : divs(d6, shotSpeed);
        d6 = asrw(setw(0, d6), 4);
        newZ = s16(newZ + s16(d6));
        int futureX = newX;
        int futureZ = newZ;

        int[] h = headTowards(oldX, oldZ, newX, newZ, shotSpeed, 0);
        newX = h[0];
        newZ = h[1];
        int d0 = s16(newX - oldX);
        int d1 = s16(newZ - oldZ);
        if (s16(offMult) != 0) {                       // .nooffset : depart decale sur le cote
            d0 = muls(d0, offMult) >> 8;
            d1 = muls(d1, offMult) >> 8;
            oldX = s16(oldX + s16(d1));
            oldZ = s16(oldZ - s16(d0));
            h = headTowards(oldX, oldZ, futureX, futureZ, shotSpeed, 0);
            newX = h[0];
            newZ = h[1];
        }

        s.active = true;
        s.popping = false;
        s.anim = 0;
        s.lifetime = 0;
        s.type = bulletType;
        s.power = b.hitDamage & 0xFF;                  // move.b SHOTPOWER,ShotT_Power_w(a5)
        s.gravity = b.gravity;
        s.bounceHoriz = b.bounceHoriz != 0;
        s.bounceVert = b.bounceVert != 0;
        s.x = newX << 16;                              // move.w d0,(a2)
        s.z = newZ << 16;
        s.velX = s16(newX - oldX) << 16;               // move.w d0,ShotT_VelocityX_w(a5)
        s.velZ = s16(newZ - oldZ) << 16;
        s.enemyFlags = 0b110010;                       // move.l #%110010 : il ne vise PAS les aliens
        s.zone = a.zone;
        s.upperZone = a.upperZone;
        int accY = (s16(a.height) << 7) + yOff;        // 4(a0) << 7 + SHOTYOFF
        s.accY = accY;
        s.heightWord = s16(a.height);
        // Vitesse verticale : viser la tete du joueur au bout du vol.
        int d1v = s16(s16((p.yOff + (p.height >> 1)) >> 7) - 20);
        d1v = (d1v << 7) - accY;
        d1v += d1v;
        int d0v = asrw(setw(0, dist), shotShift & 63);
        if (s16(d0v) <= 0) {
            d0v = 1;
        }
        s.velY = s16(divs(d1v, d0v));
    }

    /** CalcDist (Objectmove.java:790) : distance entiere entre deux points. */
    private static int calcDist(int oldX, int oldZ, int newX, int newZ) {
        int d1 = s16(newX - oldX);
        int d2 = s16(newZ - oldZ);
        int d = muls(d1, d1) + muls(d2, d2);
        return d == 0 ? 0 : sqrt(d);
    }

    /**
     * HeadTowards (Objectmove.java:733) : avance de {@code speed} vers la cible en s'arretant a
     * {@code range}. Renvoie {x, z}.
     */
    private static int[] headTowards(int oldX, int oldZ, int newX, int newZ, int speed, int range) {
        int xdiff = s16(newX - oldX);
        int zdiff = s16(newZ - oldZ);
        int d2 = muls(xdiff, xdiff) + muls(zdiff, zdiff);
        if (d2 == 0) {
            return new int[] { newX, newZ };
        }
        int d0 = sqrt(d2);
        if (s16(d0) <= s16(range)) {
            int d1 = divs(muls(xdiff, range), d0);
            int dz = divs(muls(zdiff, range), d0);
            return new int[] { s16(newX - s16(d1)), s16(newZ - s16(dz)) };
        }
        int d3 = s16(speed + range);
        if (s16(d3) >= s16(d0)) {
            d3 = s16(d0);
        }
        d3 = s16(d3 - range);
        int d1 = divs(muls(xdiff, d3), d0);
        int dz = divs(muls(zdiff, d3), d0);
        return new int[] { s16(d1 + oldX), s16(dz + oldZ) };
    }

    // ------------------------------------------------------------------ vol

    /** ObjectHandler, branche JUMPBULLET : {@code ItsABullet} sur chaque emplacement occupe. */
    public void update() {
        for (Shot s : pool) {
            if (s.active) {
                itsABullet(s);
            }
        }
    }

    /** ItsABullet (Newanims.java:1650) : duree de vie, graphisme, sol/plafond, deplacement. */
    private void itsABullet(Shot s) {
        boolean timeout = false;                       // move.b #0,timeout
        if (s.zone < 0 || s.zone >= lvl.zones.length) { // tst.w ObjT_ZoneID_w(a0) ; blt doneshot
            s.active = false;
            return;
        }
        GlfData.Bullet b = glf == null ? null : glf.bullet(s.type);
        if (b == null) {
            s.active = false;
            return;
        }

        if (!s.popping) {                              // tst.b ShotT_Status_b(a0) ; bne noworrylife
            int d2 = s16(s.lifetime);                  // move.w ShotT_Lifetime_w(a0),d2
            if (d2 >= 0 && b.lifetime >= 0) {          // blt.s infinite (x2)
                if (s16(b.lifetime) >= d2) {           // cmp.w d2,d1 ; bge.s notdone
                    s.lifetime = s16(s.lifetime + tempFrames); // add.w Anim_TempFrames_w
                } else {
                    timeout = true;                    // st timeout
                }
            }
        }

        if (s.popping) {
            // ---- popping : animation d'impact ----
            int d2 = s.anim;                           // move.w d1,d2 (anim NON double ici)
            drawStep(s, b.popData, d2, b.impactGraphicType);
            d2 = s16(d2 + 1);                          // addq #1,d2
            if (d2 > s16(b.popFrames)) {               // cmp.w BulT_PopFrames_l+2(a6),d2 ; ble notdonepopping
                s.active = false;                      // FREE_ENT a0
                s.popping = false;
                s.anim = 0;
                return;
            }
            s.anim = d2 & 0xFF;                        // move.b d2,ShotT_Anim_b(a0)
            if (dynLight != null && s.brightness != 0) {   // tst anim_Brightness_w ; beq .nobright
                dynLight.brighten(s16(-(s.brightness & 0xFF)), s16(s.x >> 16), s16(s.z >> 16),
                        s.zone, s16(s.heightWord) << 7);
            }
            return;
        }

        // ---- notpopping : vol ----
        // QUIRK d'origine : en vol l'index d'animation suivant est 2*anim+1 (d2 = anim*2 avant
        // le addq), alors que le graphisme lu l'est a l'index anim*6. On le preserve.
        int d2 = s16(s.anim * 2);
        drawStep(s, b.animData, s.anim, b.graphicType);
        d2 = s16(d2 + 1);                              // addq #1,d2
        if (d2 > s16(b.animFrames)) {                  // cmp.w BulT_AnimFrames_l+2(a6) ; ble notdoneanim
            d2 = 0;
        }
        s.anim = d2 & 0xFF;

        LevelSim.Zone z = lvl.zones[s.zone];
        int roof = s.upperZone ? z.upperRoofH : z.roofH;   // 6(a3), +8 si InUpperZone
        int floor = s.upperZone ? z.upperFloorH : z.floorH; // 2(a3)

        // .notintop : plafond
        if (roof - s.accY >= 10 * 128) {               // cmp.l #10*128,d0 ; blt .nohitroof
            if (s.bounceVert) {                        // btst #0,ShotT_Flags_w+1(a0)
                s.velY = s16(-s16(s.velY));            // neg.w ShotT_VelocityY_w(a0)
                s.accY = roof + 10 * 128;
                if (b.gravity != 0) {
                    s.velX = s.velX >> 1;
                    s.velZ = s.velZ >> 1;
                }
            } else {
                s.anim = 0;                            // .nobounce : impact
                s.popping = true;
            }
        }
        // .nohitroof : sol
        if (floor - s.accY <= 10 * 128) {              // cmp.l #10*128,d0 ; bgt .nohitfloor
            boolean skip = false;
            if (b.bounceVert != 0) {                   // tst.l BulT_BounceVert_l(a6) ; beq .nobounceup
                skip = true;
                if (s16(s.velY) >= 0) {                // tst.w ShotT_VelocityY_w(a0) ; blt .nohitfloor
                    int d0 = setw(0, s.velY);
                    d0 = asrw(d0, 1);                  // asr.w #1,d0
                    d0 = setw(d0, -s16(d0));           // neg.w d0
                    s.velY = s16(d0);
                    s.accY = floor - 10 * 128;
                    if (b.gravity != 0) {
                        s.velX = s.velX >> 1;
                        s.velZ = s.velZ >> 1;
                    }
                }
            }
            if (!skip) {
                s.anim = 0;                            // .nobounceup : impact
                s.popping = true;
            }
        }

        // .nohitfloor : deplacement (les positions sont des longs 16.16)
        int oldX = s.x;
        int oldZ = s.z;
        int newX = oldX + mulFrames(s.velX);           // velocite * Anim_TempFrames_w
        int newZ = oldZ + mulFrames(s.velZ);
        int oldY = s.accY;
        int d3 = muls(s16(s.velY), tempFrames);        // move.w VelocityY,d3 ; muls Anim_TempFrames_w,d3
        if (b.gravity != 0) {                          // tst.l BulT_Gravity_l(a6) ; beq.s nograv
            int d5 = muls(b.gravity, tempFrames);
            d3 = d3 + d5;
            int d6 = s16(s.velY) + d5;                 // ext.l d6 ; add.l d5,d6
            if (d6 >= 10 * 256) {                      // cmp.l #10*256,d6 ; blt okgrav
                d6 = 10 * 256;
            }
            s.velY = s16(d6);
        }
        // nograv
        int d4 = s.accY + d3;
        s.accY = d4;
        int newY = d4 - 5 * 128;                       // move.l d4,newy (apres sub.l #5*128)
        s.heightWord = s16(d4 >> 7);                   // move.w d4,4(a0)

        move.oldx = s16(oldX >> 16);
        move.oldz = s16(oldZ >> 16);
        move.newx = s16(newX >> 16);
        move.newz = s16(newZ >> 16);
        move.oldy = oldY;
        move.newy = newY;
        move.zone = s.zone;
        move.stoodInTop = s.upperZone;
        setupBulletMove();
        move.wallBounce = b.bounceHoriz != 0;          // sne Obj_WallBounce_b
        move.exitFirst = b.bounceHoriz == 0;           // seq exitfirst
        move.hitwall = false;
        if (move.oldx != move.newx || move.oldz != move.newz) { // nomovebul si les deux mots sont egaux
            if (move.oldx == move.newx) {
                move.wallLength = 1;                   // move.w #1,WallLength_w
            }
            move.moveObject();
            if (dynLight != null && s.brightness != 0) {   // lalal : juste apres MoveObject
                dynLight.brighten(s16(-(s.brightness & 0xFF)), s16(move.newx), s16(move.newz),
                        move.zone, move.newy);
            }
            // MoveObject ne modifie que le MOT FORT (move.w newx) : la fraction est conservee.
            newX = (s16(move.newx) << 16) | (newX & 0xFFFF);
            newZ = (s16(move.newz) << 16) | (newZ & 0xFFFF);
        }
        // nomovebul
        s.upperZone = move.stoodInTop;
        boolean hit = false;
        if (move.wallBounce) {
            if (move.hitwall) {
                bounceOffWall(s, b);
            }
            hit = timeout;                             // .nothitwall
        } else if (move.hitwall) {
            s.accY = move.wallhitheight;               // move.l wallhitheight,ShotT_AccYPos_w(a0)
            s.heightWord = s16(s.accY >> 7);
            hit = true;                                // .hitsomething
        } else {
            hit = timeout;
        }
        if (hit) {
            s.anim = 0;
            s.popping = true;                          // move.b #1,ShotT_Status_b(a0)
            impactSound(s, b);
        }
        // lab : la position et la zone sont posees meme apres un impact
        s.zone = move.zone;
        s.x = newX;
        s.z = newZ;
        if (s.enemyFlags != 0 && !s.popping) {
            hitEnemies(s, s16(oldX >> 16), s16(oldZ >> 16));
        }
    }

    /**
     * {@code notasplut} (Newanims.java:2150-2110) : la balle a-t-elle touche une entite ?
     *
     * <p>Le test n'est pas un simple contact : le jeu prend le SEGMENT parcouru dans la frame,
     * calcule la distance perpendiculaire de chaque entite a ce segment (80 unites de tolerance,
     * 40 pour les petites), verifie que ses deux extremites sont dans un rayon de
     * {@code (longueur + 80)} et que l'ecart de hauteur est inferieur a 50. C'est ce qui permet
     * de toucher malgre une balle rapide qui « saute » par-dessus la cible.
     */
    private void hitEnemies(Shot s, int oldXw, int oldZw) {
        // Bit 4 des EnemyFlags = le JOUEUR (un tir d'alien vaut %110010, un tir du joueur %100011).
        if (player != null && (s.enemyFlags & (1 << 4)) != 0
                && player.zone == s.zone && player.stoodInTop == s.upperZone
                && hitsEntity(s, oldXw, oldZw, s16(player.snapXOff >> 16),
                        s16(player.snapZOff >> 16),
                        s16((player.yOff + (player.height >> 1)) >> 7), 80)) {
            player.damageTaken += s.power;
            player.impactX = s16(player.impactX + s16(s.velX >> 16));
            player.impactZ = s16(player.impactZ + s16(s.velZ >> 16));
            s.anim = 0;
            s.popping = true;
            impactSound(s, glf == null ? null : glf.bullet(s.type));
            return;
        }
        if (targets == null || (s.enemyFlags & 1) == 0) {  // btst typeId,EnemyFlags (alien = 0)
            return;
        }
        for (Aliens.Alien a : targets.aliens()) {
            if (!a.alive || a.zone < 0 || a.upperZone != s.upperZone || a.hitPoints == 0) {
                continue;
            }
            if (hitsEntity(s, oldXw, oldZw, a.x, a.z, s16(a.height), 80)) {
                targets.hit(a, s.power);
                s.anim = 0;
                s.popping = true;
                impactSound(s, glf == null ? null : glf.bullet(s.type));
                return;                                // .hitnasty
            }
        }
    }

    /** Le test geometrique commun : l'entite est-elle sur le segment parcouru cette frame ? */
    private boolean hitsEntity(Shot s, int oldXw, int oldZw, int ex, int ez, int eh, int tol) {
        return hitsSegment(oldXw, oldZw, s16(s.x >> 16), s16(s.z >> 16), s16(s.heightWord),
                ex, ez, eh, tol);
    }

    /**
     * Le meme test, mais sur un segment donne explicitement — le rayon hitscan s'en sert, lui
     * qui n'a pas de projectile a promener.
     */
    private boolean hitsSegment(int oldXw, int oldZw, int newXw, int newZw, int rayHeight,
                                int ex, int ez, int eh, int tol) {
        int xdiff = s16(newXw - oldXw);
        int zdiff = s16(newZw - oldZw);
        int range = sqrt(muls(xdiff, xdiff) + muls(zdiff, zdiff));
        int d0 = s16(range + 80);
        int sqrnum = muls(d0, d0);
        boolean moving = xdiff != 0 || zdiff != 0;
        int dh = s16(eh - rayHeight);
        if (dh < 0) {
            dh = -dh;
        }
        if (moving && dh > 50) {                       // .ignoreheight
            return false;
        }
        int d2 = s16(ex - oldXw);
        int d3 = s16(ez - oldZw);
        int d4 = s16(ex - newXw);
        int d5 = s16(ez - newZw);
        int d6 = muls(d2, zdiff) - muls(d3, xdiff);    // produit vectoriel = aire du triangle
        if (d6 <= 0) {
            d6 = -d6;
        }
        d6 = range == 0 ? d6 : divs(d6, range);        // distance perpendiculaire au segment
        if (s16(d6) > tol) {
            return false;
        }
        if (muls(d2, d2) + muls(d3, d3) > sqrnum) {
            return false;
        }
        return muls(d4, d4) + muls(d5, d5) <= sqrnum;
    }

    /**
     * Le premier monstre rencontre sur un segment du rayon, ou {@code null}. Meme tolerance que
     * pour les projectiles, pour que les deux familles d'armes touchent pareil.
     */
    private Aliens.Alien firstAlienOnSegment(int x0, int z0, int x1, int z1, int rayHeight,
                                             int zoneFrom, int zoneTo, boolean upper) {
        if (targets == null) {
            return null;
        }
        for (Aliens.Alien a : targets.aliens()) {
            // Le segment peut enjamber deux zones : on accepte les monstres de l'une ou l'autre.
            if (!a.alive || (a.zone != zoneFrom && a.zone != zoneTo)
                    || a.upperZone != upper || a.hitPoints == 0) {
                continue;
            }
            if (hitsSegment(x0, z0, x1, z1, rayHeight, a.x, a.z, s16(a.height), 80)) {
                return a;
            }
        }
        return null;
    }

    /** bulletImpactFX : bruit d'impact (BulT_ImpactSFX_l - 1) puis SOUFFLE s'il explose. */
    private void impactSound(Shot s, GlfData.Bullet b) {
        if (b == null) {
            return;
        }
        if (sfx != null) {
            sfx.playAt(b.impactSFX - 1, s16(s.x >> 16), s16(s.z >> 16), s.heightWord);
        }
        if (b.explosiveForce != 0) {                   // tst.l BulT_ExplosiveForce_l ; beq .noexplosion
            computeBlast(b.explosiveForce, s16(s.x >> 16), s16(s.z >> 16), s.heightWord,
                    s.zone, s.upperZone, s.type);
        }
    }

    /**
     * ComputeBlast (Newanims.java:1387) : degats de SOUFFLE autour d'un impact.
     *
     * <p>Chaque entite visible depuis le point d'explosion (ligne de vue : le souffle ne traverse
     * pas les murs) prend des degats qui decroissent avec la distance —
     * {@code d = (force * (64 - (dist>>3 - 4))) >> 5}, borne par la force — et un recul dirige.
     * Puis six « flammes » (l'animation d'impact de la meme balle) sont semees autour du point,
     * en trois couronnes de plus en plus larges.
     */
    public void computeBlast(int force, int x, int z, int heightWord, int zone, boolean upper,
                             int bulletType) {
        int d6 = s16(force);
        // --- degats aux entites ---
        if (targets != null) {
            for (Aliens.Alien a : targets.aliens()) {
                if (!a.alive || a.zone < 0 || a.hitPoints == 0) {
                    continue;                          // tst.b EntT_HitPoints_b ; beq HitObjLoop
                }
                int[] hit = blastAt(force, x, z, heightWord, zone, upper,
                        a.x, a.z, s16(a.height), a.zone, a.upperZone);
                if (hit == null) {
                    continue;
                }
                targets.hit(a, hit[0]);
                a.velocityY = s16(a.velocityY + hit[3]);   // EntT_ImpactY_w
            }
        }
        if (player != null && player.zone >= 0) {
            int[] hit = blastAt(force, x, z, heightWord, zone, upper,
                    s16(player.snapXOff >> 16), s16(player.snapZOff >> 16),
                    s16((player.yOff + (player.height >> 1)) >> 7),
                    player.zone, player.stoodInTop);
            if (hit != null) {
                player.damageTaken += hit[0];
                player.impactX = s16(hit[1]);
                player.impactZ = s16(hit[2]);
            }
        }
        // --- flammes : 3 couronnes x 2, l'animation d'impact de la meme balle ---
        if (nav == null) {
            return;
        }
        int d5 = 2;
        for (int ring = 0; ring < 3; ring++) {
            for (int i = 0; i < 2; i++) {
                Shot f = free();
                if (f == null) {
                    return;                            // .nomore
                }
                int dx = s16(muls((byte) nav.rand(), d5)) >> 1;
                if (dx == 0) {
                    dx = 2;
                }
                int dz = s16(muls((byte) nav.rand(), d5)) >> 1;
                if (dz == 0) {
                    dz = 2;
                }
                f.active = true;
                f.popping = true;                      // st ShotT_Status_b : l'anim d'explosion
                f.anim = 0;
                f.lifetime = 0;
                f.type = bulletType;
                f.power = 0;
                f.enemyFlags = 0;
                f.velX = 0;
                f.velZ = 0;
                f.velY = 0;
                f.gravity = 0;
                f.zone = zone;
                f.upperZone = upper;
                f.x = s16(x + dx) << 16;
                f.z = s16(z + dz) << 16;
                int y = (s16(heightWord) << 7) + (muls(nav.rand() & 0xFF, d5) >> 3);
                if (zone >= 0 && zone < lvl.zones.length) {   // borne au sol et au plafond
                    LevelSim.Zone zz = lvl.zones[zone];
                    int floor = upper ? zz.upperFloorH : zz.floorH;
                    int roof = upper ? zz.upperRoofH : zz.roofH;
                    y = Math.min(Math.max(y, roof), floor);
                }
                f.accY = y;
                f.heightWord = s16(y >> 7);
            }
            d5 += 2;
        }
    }

    /**
     * Un objet dans le souffle : renvoie {degats, reculX, reculZ, reculY} ou null s'il est hors
     * de portee ou hors de vue.
     */
    private int[] blastAt(int force, int x, int z, int h, int zone, boolean upper,
                          int tx, int tz, int th, int tzone, boolean tupper) {
        if (los != null) {
            los.viewerX = x;
            los.viewerZ = z;
            los.viewerY = h;
            los.viewerZone = zone;
            los.viewerTop = upper;
            los.targetX = tx;
            los.targetZ = tz;
            los.targetY = th;
            los.targetZone = tzone;
            los.targetTop = tupper;
            los.canItBeSeen();
            if (!los.canSee) {
                return null;                           // tst.b CanSee ; beq HitObjLoop
            }
        }
        int d0 = s16(tx - x);
        int d1 = s16(tz - z);
        int dist = sqrt(muls(d0, d0) + muls(d1, d1));
        int d7 = Math.max(s16(dist), 256);             // cmp.w #256,d7 ; bge .okd
        int d3 = s16(M68k.asrw(setw(0, dist), 3) - 4); // asr.w #3 ; sub.w #4
        if (d3 < 0) {
            d3 = 0;
        }
        if (d3 > 64) {
            return null;                               // cmp.w #64,d3 ; bgt HitObjLoop
        }
        d3 = s16(64 - d3);
        int d5 = muls(s16(force), d3) >> 5;            // asr.l #5
        if (d5 >= s16(force)) {
            d5 = s16(force);                           // borne a anim_MaxDamage_w
        }
        int kx = divs(d0 * force, d7);
        int kz = divs(d1 * force, d7);
        int ky = divs(force << 4, d7);
        ky = s16(-ky);
        if (ky < -8) {
            ky = -8;                                   // cmp.w #-8,d1 ; bge .okbl2
        }
        return new int[] { d5 & 0xFF, kx, kz, ky };
    }

    /**
     * Anim_ExplodeIntoBits (Newanims.java:412) : la gerbe de morceaux d'une entite qui meurt.
     * Chaque debris part dans une direction tiree au hasard, a une vitesse decalee de 1 a 4 bits,
     * plus la moitie du recul de l'entite, et monte de 2 a 10 unites — la gravite de sa definition
     * (les « Splutch ») le fait ensuite retomber.
     */
    public void explodeIntoBits(int splatType, int count, int x, int z, int heightWord, int zone,
                                boolean upper, int impactX, int impactZ) {
        if (nav == null || glf == null || glf.bullet(splatType) == null) {
            return;
        }
        GlfData.Bullet b = glf.bullet(splatType);
        int n = Math.min(count, 7) + 1;                // cmp.w #7,d2 ; ble .oksplut
        for (int i = 0; i < n; i++) {
            Shot s = freeAlien();                      // le jeu prend le pool ALIEN
            if (s == null) {
                return;
            }
            int ang = nav.rand() & SinCos.MASK;        // AMOD_A
            int d3 = s16(sinCos.value(ang));
            int d4 = s16(sinCos.value(ang + SinCos.COSINE_OFS));
            int shift = (nav.rand() & 3) + 1;          // and.w #3 ; add.w #1
            d3 = d3 << shift;
            d4 = d4 << shift;
            s.active = true;
            s.popping = false;                         // clr.b ShotT_Status_b : il VOLE
            s.anim = 0;
            s.lifetime = 0;
            s.type = splatType;
            s.power = 0;                               // move.b #0,ShotT_Power_w
            s.enemyFlags = 0;                          // ne touche personne
            s.gravity = b.gravity;
            s.bounceHoriz = b.bounceHoriz != 0;
            s.bounceVert = b.bounceVert != 0;
            s.x = s16(x) << 16;
            s.z = s16(z) << 16;
            s.velX = ((d3 >> 16) + (s16(impactX) >> 1)) << 16;
            s.velZ = ((d4 >> 16) + (s16(impactZ) >> 1)) << 16;
            s.velY = s16(-((nav.rand() & 1023) + 2 * 128));  // il part vers le HAUT
            s.zone = zone;
            s.upperZone = upper;
            s.accY = (s16(heightWord) + 6) << 7;
            s.heightWord = s16(s.accY >> 7);
        }
    }

    /** ai_CalcSqrt (3 iterations de Newton), comme le jeu. */
    private static int sqrt(int d2) {
        if (d2 == 0) {
            return 0;
        }
        int d0 = 31;
        while (d0 >= 0 && (d2 & (1 << d0)) == 0) {
            d0--;
        }
        d0 = 1 << (d0 >> 1);
        for (int i = 0; i < 3; i++) {
            int d1 = s16(d0);
            d1 = muls(d1, d1);
            d1 -= d2;
            d1 >>= 1;
            d1 = divs(d1, d0);
            d0 = s16(d0 - d1);
            if (s16(d0) <= 0) {
                d0 = 1;
            }
        }
        return s16(d0);
    }

    /** Reflexion de la vitesse sur le mur touche (ItsABullet, branche Obj_WallBounce_b). */
    private void bounceOffWall(Shot s, GlfData.Bullet b) {
        int d0 = muls(s16(s.velZ), s16(move.wallXSize)); // move.w VelZ,d0 ; muls WallXSize_w,d0
        int d1 = muls(s16(s.velX), s16(move.wallZSize)); // move.w VelX,d1 ; muls WallZSize_w,d1
        d0 = d0 - d1;
        d0 = divs(d0, s16(move.wallLength));           // divs WallLength_w,d0
        d1 = setw(0, s.velX);
        int d2 = setw(0, move.wallZSize);
        d2 = setw(d2, d2 + d2);                        // add.w d2,d2
        d2 = muls(s16(d2), d0);
        d2 = divs(d2, s16(move.wallLength));
        d1 = setw(d1, d1 + d2);
        s.velX = s16(d1);                              // move.w d1,ShotT_VelocityX_w(a0)
        d1 = setw(0, s.velZ);
        d2 = setw(0, move.wallXSize);
        d2 = setw(d2, d2 + d2);
        d2 = muls(s16(d2), d0);
        d2 = divs(d2, s16(move.wallLength));
        d1 = setw(d1, d1 - d2);
        s.velZ = s16(d1);
        if (b.gravity != 0) {
            s.velX = s.velX >> 1;
            s.velZ = s.velZ >> 1;
        }
    }

    /**
     * Pose les donnees de dessin d'un pas d'animation : bitmap, halo (« glare ») ou bitmap
     * additif selon {@code BulT_GraphicType_l} / {@code BulT_ImpactGraphicType_l}.
     */
    private static void drawStep(Shot s, List<ObjectAnim.Step> data, int index, int graphicType) {
        if (data == null || index < 0 || index >= data.size()) {
            return;
        }
        ObjectAnim.Step st = data.get(index);
        s.glareSize = 0;                               // move.l #0,8(a0)
        s.additive = false;
        if (graphicType < 1) {                         // .bitmapgraph
            s.sheet = st.gfx;
            s.frame = st.frame;
        } else if (graphicType == 1) {                 // .glaregraph
            s.glareSize = -(byte) st.gfx;              // ext.w d0 ; neg.w d0
            s.frame = st.frame;
        } else {                                       // .additivegraph
            s.sheet = st.gfx;
            s.frame = st.frame;
            s.additive = true;                         // move.b #6,10(a0)
        }
        s.brightness = st.next;                        // anim_Brightness_w (octet 5 du pas)
    }

    /** {@code velocite * Anim_TempFrames_w} en 16.16 (swap/muls/mulu de l'original). */
    private int mulFrames(int velocity) {
        int d4 = velocity & 0xFFFF;                    // move.w d3,d4 (fraction, non signee)
        int d3 = velocity >> 16;                       // swap d3
        d3 = muls(s16(d3), tempFrames);
        d4 = d4 * tempFrames;                          // mulu d5,d4
        d3 = (d3 << 16) + d4;                          // swap d3 ; clr.w d3 ; add.l d4,d3
        return d3;
    }
}
