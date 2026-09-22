package ab3d2.rebirth.sim;

import static ab3d2.rebirth.sim.M68k.divs;
import static ab3d2.rebirth.sim.M68k.divsRem;
import static ab3d2.rebirth.sim.M68k.muls;
import static ab3d2.rebirth.sim.M68k.s16;
import static ab3d2.rebirth.sim.M68k.setw;

import ab3d2.rebirth.GlfData;
import ab3d2.rebirth.LevelData;

import java.util.ArrayList;
import java.util.List;

/**
 * Les ALIENS : entités, animation et intelligence artificielle.
 *
 * <p>Port de {@code ItsAnAlien} (Newaliencontrol.java:67), du pilote d'animation
 * {@code DOALLANIMS}/{@code JUMPALIENANIM} (Hires.java:4180-4285) et du module d'IA
 * {@code modules/Ai.java} (AI_MainRoutine et ses modes).
 *
 * <p><b>Modèle d'origine</b> — chaque alien a trois « comportements » pris dans sa définition
 * GLF : DEFAUT (il patrouille), RÉPONSE (il a vu le joueur) et SUIVI (après son attaque), chacun
 * étant un numéro de routine : charger, charger en biais, tirer, approcher, attendre… Le mode
 * courant est {@code EntT_CurrentMode_b} ; l'animation jouée est {@code EntT_WhichAnim_b}
 * (0 marche, 1 attaque, 2 touché, 3 mort).
 *
 * <p><b>Navigation</b> : pas de pathfinding. Chaque zone porte un POINT DE CONTRÔLE et le niveau
 * embarque la table « prochain saut » ({@link Nav}) ; un alien qui patrouille tire un point au
 * hasard et suit la table. Voir le joueur, c'est {@link Los} (PVS + découpe de portail +
 * hauteur de franchissement).
 *
 * <p>Tout est porté : les dégâts reçus, la gerbe de morceaux et le ré-engendrement d'aliens plus
 * petits à la mort, les sons, le tir (projectile et hitscan, impact des tirs ratés compris),
 * l'éclairage porté ({@code ai_DoTorch}) et le graphique auxiliaire des animations.
 */
public final class Aliens {

    /** Une entité alien (ObjT/EntT de 64 octets côté original). */
    public static final class Alien {
        public int index;                // (a0) : index de son point objet — clé des workspaces
        public int x, z;                 // Lvl_ObjectPoints : position monde (mots)
        public int height;               // 4(a0) : hauteur de dessin/collision
        public int zone;                 // ObjT_ZoneID_w (< 0 : mort, emplacement libre)
        public boolean upperZone;        // ShotT_InUpperZone_b
        public int type;                 // EntT_Type_b : définition d'alien
        public int hitPoints;            // EntT_HitPoints_b
        public int damageTaken;          // EntT_DamageTaken_b
        public int mode;                 // EntT_CurrentMode_b
        public int whichAnim;            // EntT_WhichAnim_b
        public int timer1, timer2;       // EntT_Timer1_w (réaction) / EntT_Timer2_w (frame d'anim)
        public int angle;                // EntT_CurrentAngle_w
        public int currentCPt, targetCPt;
        /** ShotT_Worry_b : l'alien ne pense que si ce drapeau est pose (il se consomme). */
        public int worry;
        public int team;                 // EntT_TeamNumber_b (< 0 = sans équipe)
        public int velocityY;            // EntT_VelocityY_w (vol)
        public boolean seePlayer;        // ObjT_SeePlayer_b
        public int doorsHeld;            // EntT_DoorsAndLiftsHeld_l
        /** EntT_DisplayText_w : message affiche a sa mort (< 0 = aucun). */
        public int displayText = -1;
        /**
         * Objet AUXILIAIRE de la frame courante (ai.s:1938) : un second graphique accroche au
         * monstre — l'eclat de tir des gardes armes (GunGlares). C'est le pas d'animation de
         * la definition {@code AlienT_Auxiliary} a afficher ; &lt; 0 = rien cette frame.
         */
        public int auxFrame = -1;
        /** ShotT_AuxOffsetX_w / AuxOffsetY_w : decalage ECRAN (x2) du graphique auxiliaire. */
        public int auxX, auxY;

        // Workspace d'animation (ObjectWorkspace, 8 octets par entité) :
        int wsAction;                    // (a5)   : action de la frame (mordre / tirer)
        int wsSpecial = -1;              // 1(a5)  : frame forcée ($FF = aucune)
        int wsView;                      // 2(a5)  : option d'animation (0 marche, 8/9/10)
        boolean wsFinished;              // 3(a5)  : l'animation vient de boucler
        int wsCount;                     // 4(a5)  : compteur de la commande « spéciale »

        // Ennui (AI_BoredomSpace) : depuis combien de temps il tourne en rond.
        int borTimer, borX, borZ;

        // --- dessin (posé par ai_DoAttackAnim) ---
        public int sheet, frame;
        public boolean flip;
        public int drawWord2;
        /** Vrai tant que l'entité existe (l'emplacement est rendu quand l'anim de mort finit). */
        public boolean alive = true;
    }

    /** AI_WorkT : ce qu'un alien (ou une équipe) se rappelle du joueur. */
    private static final class Work {
        int lastX, lastY;                // AI_WorkT_LastX_w / LastY_w (= X et Z du joueur)
        int lastZone = -1;
        int lastCPt = -1;
        int seenBy = -1;
        // ai_ResetAliens ecrit move.l #-1,8(a0) : SeenBy ET DamageDone valent -1 au depart.
        int damageDone = -1;
        int damageTaken;
    }

    private final LevelSim lvl;
    /** ZoneT_PotVisibleZoneList_vw, par zone. */
    private final int[][] pvs;
    /** Tampon reutilise par {@link #updateWorry} : les zones visibles cette frame. */
    private boolean[] visible;
    private final GlfData glf;
    private final Nav nav;
    private final Los los;
    private final Move move;
    private final SinCos sinCos;
    private final List<Alien> list = new ArrayList<>();
    private final Work[] work;
    /**
     * AI_Damaged_vw : le CUMUL des degats encaisses, un mot par emplacement d'objet. C'est un
     * tableau A PART du workspace {@link Work} — le jeu n'en efface les mots qu'au chargement du
     * niveau (CLRDAM, Hires.java:2236), alors qu'il remet {@code AI_WorkT_DamageTaken_w} a zero
     * A CHAQUE FRAME pendant la ronde (ai_ProwlFly, Ai.java:536/554). Les confondre rendait les
     * monstres increvables : seuls mouraient ceux qui encaissaient 4 x leurs points de vie dans
     * UNE seule frame (un Red Alien a 2 PV tombait, un Guard a 4 PV jamais).
     */
    private final int[] damageAccum;
    private final Work[] teamWork = new Work[30];
    private final int[] zoneCPt;         // ZoneT_ControlPoint_w (bas)
    private final int[] zoneUpperCPt;
    private final int[][] zoneBrights;   // pointBrights par zone (pour ai_CheckForDark)
    private final int[][] zoneBorder;    // borderPoints par zone

    /** Anim_TempFrames_w : pas fixe, donc 1. */
    public int tempFrames = 1;
    /** File de bruitages (null = muet). */
    public SfxQueue sfx;
    /** Pool de tirs : sert aux morceaux projetes a la mort (Anim_ExplodeIntoBits). */
    public Shots shots;
    /** Lumieres dynamiques : la TORCHE que portent certains gardes (null = aucune). */
    public DynLight dynLight;
    /** File de messages (null = muet) et textes du niveau. */
    public Messages messages;
    private final List<String> levelMessages;
    /** Nombre d'emplacements gardes pour les aliens engendres a la mort d'un gros. */
    private static final int SPAWN_POOL = 10;

    /**
     * Le registre a2 qu'{@code Obj_DoCollision} herite de son appelant sans jamais l'initialiser
     * (cf. {@link Collide}) : dans la boucle de jeu c'est {@code AI_AlienTeamWorkspace_vl}, donc
     * le bloc de 16 octets de l'EQUIPE 0. Renvoie le MOT a l'octet {@code off} de ce bloc.
     */
    public int teamZeroWord(int off) {
        Work w = teamWork[0];
        return switch (off) {
            case 0 -> s16(w.lastX);
            case 2 -> s16(w.lastY);
            case 4 -> s16(w.lastZone);
            case 6 -> s16(w.lastCPt);
            case 8 -> s16(w.seenBy);
            case 10 -> s16(w.damageDone);
            case 12 -> s16(w.damageTaken);
            default -> 0;                              // 14 : le remplissage du bloc, jamais ecrit
        };
    }

    /** Compteur de validation : tirs rates dont l'impact a ete trace (SHOOTPLAYER1). */
    public int missedShots;

    /** thistime : DOALLANIMS n'avance les animations qu'une frame sur cinq. */
    private int thisTime = 5;

    // ---- globaux de l'IA, rechargés par ItsAnAlien pour chaque alien ----
    private int reactionTime, defaultMode, responseMode, retreatMode, followupMode;
    private int prowlSpeed, responseSpeed, retreatSpeed, followupSpeed, followupTimer;
    private int thingHeight, alienBright, vecObj, auxObj;
    private int shotType, shotPower, shotSpeed, shotShift;
    private boolean flyABit, toSide, getOut, finishedAnim;
    private int doAction, animFacing, middleCPt;
    private int awayFromWall, extLen;
    private int stepUp, stepDown;
    /** ai_ToSide_w, mais aussi la sortie de HeadTowardsAng. */
    private int angRet, sinRet, cosRet;
    private boolean gotThere;
    private int newx, newz, oldx, oldz, newy, oldy, speed, range;
    private boolean stoodInTop;
    private boolean hitwall;
    private int zonePtr;                 // Obj_ZonePtr_l -> index de zone
    private boolean okTel;
    private int floorTemp;

    public Aliens(LevelSim lvl, LevelData data, GlfData glf, SinCos sinCos, Nav nav, Los los) {
        this.levelMessages = data.messages;
        // Liste des zones POTENTIELLEMENT VISIBLES depuis chaque zone : c'est elle qui reveille
        // les aliens (cf. updateWorry).
        pvs = new int[lvl.zones.length][];
        if (data.zones != null) {
            for (LevelData.Zone z : data.zones) {
                if (z.id < 0 || z.id >= pvs.length || z.pvs == null) {
                    continue;
                }
                int[] v = new int[z.pvs.size()];
                for (int i = 0; i < v.length; i++) {
                    v[i] = z.pvs.get(i).zone;
                }
                pvs[z.id] = v;
            }
        }
        this.lvl = lvl;
        this.glf = glf;
        this.sinCos = sinCos;
        this.nav = nav;
        this.los = los;
        this.move = new Move(lvl);
        zoneCPt = new int[lvl.zones.length];
        zoneUpperCPt = new int[lvl.zones.length];
        zoneBrights = new int[lvl.zones.length][];
        zoneBorder = new int[lvl.zones.length][];
        for (LevelData.Zone z : data.zones) {
            zoneCPt[z.id] = z.controlPoint;
            zoneUpperCPt[z.id] = z.upperControlPoint;
            zoneBrights[z.id] = toArray(z.pointBrights);
            zoneBorder[z.id] = toArray(z.borderPoints);
        }
        int idx = 0;
        for (LevelData.Obj o : data.objects == null ? List.<LevelData.Obj>of() : data.objects) {
            if (o.typeId != 0) {
                idx++;
                continue;
            }
            Alien a = new Alien();
            a.index = idx++;
            a.x = o.x;
            a.z = o.z;
            a.zone = o.zone;
            a.upperZone = o.upperZone;
            a.type = o.def;
            a.hitPoints = o.hitPoints;
            a.mode = o.mode;
            a.angle = o.angle;
            a.currentCPt = o.controlPoint;
            a.targetCPt = o.targetControlPoint;
            a.team = o.team;
            a.height = o.height;
            a.doorsHeld = o.doorsHeld;
            a.displayText = o.displayText;
            list.add(a);
        }
        // AI_OtherAlienDataPtrs_vl : emplacements libres pour les aliens ENGENDRES a la mort
        // d'un gros (SplatType >= NUM_BULLET_DEFS).
        for (int i = 0; i < SPAWN_POOL; i++) {
            Alien a = new Alien();
            a.index = idx++;
            a.zone = -1;
            a.alive = false;
            list.add(a);
        }
        work = new Work[idx + 1];
        damageAccum = new int[idx + 1];                // CLRDAM : a zero au chargement, et la
        for (int i = 0; i < work.length; i++) {
            work[i] = new Work();
        }
        for (int i = 0; i < teamWork.length; i++) {
            teamWork[i] = new Work();
        }
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l == null ? 0 : l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    public List<Alien> aliens() {
        return list;
    }

    // ================================================================== frame

    /**
     * Une frame : le pilote d'animation ({@code DOALLANIMS}, une fois sur cinq) puis
     * {@code ItsAnAlien} sur chaque alien vivant.
     */
    public void run(PlayerSim player) {
        // L'ordre compte : le drapeau d'inquietude se CONSOMME dans itsAnAlien, donc il faut le
        // poser avant, et DOALLANIMS le teste lui aussi (Objectloop2, hires.s:6029).
        updateWorry(player);
        doAllAnims();
        for (Alien a : list) {
            if (a.alive && a.zone >= 0) {
                itsAnAlien(a, player);
            }
        }
    }

    /**
     * plr1only (hires.s:2015) : REVEILLE les aliens. Un alien ne pense que si sa zone figure
     * dans la liste des zones potentiellement visibles depuis celle du JOUEUR, ou si son EQUIPE
     * a deja repere le joueur ({@code AI_WorkT_SeenBy_w >= 0}) — auquel cas toute l'equipe est
     * alertee. Les autres restent geles.
     *
     * <p>Sans cette porte, les trente-deux aliens d'un niveau reflechissaient des la premiere
     * frame : ils partaient tous vers un point de controle et s'entassaient, au lieu d'attendre
     * qu'on les approche.
     */
    private void updateWorry(PlayerSim player) {
        if (visible == null || visible.length != lvl.zones.length) {
            visible = new boolean[lvl.zones.length];
        }
        java.util.Arrays.fill(visible, false);
        int pz = player == null ? -1 : player.zone;
        if (pz >= 0 && pz < pvs.length && pvs[pz] != null) {
            for (int z : pvs[pz]) {
                if (z >= 0 && z < visible.length) {
                    visible[z] = true;
                }
            }
        }
        for (Alien a : list) {
            if (!a.alive || a.zone < 0 || a.zone >= visible.length) {
                continue;
            }
            boolean wake = visible[a.zone];
            if (!wake && a.team >= 0) {                 // .worryobj par l'equipe alertee
                wake = a.team < teamWork.length && teamWork[a.team].seenBy >= 0;
            }
            if (wake) {
                a.worry |= 127;                        // or.b #127,ShotT_Worry_b(a0)
            }
        }
    }

    /** La zone {@code to} figure-t-elle dans la liste des visibles depuis {@code from} ? */
    public boolean seesZone(int from, int to) {
        if (from < 0 || from >= pvs.length || pvs[from] == null) {
            return false;
        }
        for (int z : pvs[from]) {
            if (z == to) {
                return true;
            }
        }
        return false;
    }

    /** Verrous de portes tenus par les aliens encore en vie (Anim_DoorAndLiftLocks_l). */
    public int doorLocks() {
        long locks = 0;
        for (Alien a : list) {
            if (a.alive && a.zone >= 0 && a.hitPoints != 0) {
                locks |= a.doorsHeld & 0xFFFFFFFFL;
            }
        }
        return (int) ((locks >>> 16) & 0xFFFF);        // DoorRoutine lit le long en MOT
    }

    /**
     * DOALLANIMS + JUMPALIENANIM : avance la frame d'animation de chaque alien une frame sur
     * CINQ, déclenche l'action (morsure/tir) et signale la fin de boucle.
     */
    private void doAllAnims() {
        thisTime--;                                    // subq.b #1,thistime
        if (thisTime > 0) {
            return;
        }
        thisTime = 5;                                  // move.b #5,thistime
        for (Alien a : list) {
            // tst.b ShotT_Worry_b(a0) ; beq.s doneobj2 — un alien qui ne pense pas ne s'anime
            // pas non plus.
            if (!a.alive || a.zone < 0 || a.worry == 0) {
                continue;
            }
            int which = a.whichAnim;
            if (which > 3) {
                continue;
            }
            int opt = switch (which) {                 // 0 marche, 1 attaque, 2 touché, 3 mort
                case 1 -> 8;
                case 2 -> 9;
                case 3 -> 10;
                default -> 0;
            };
            a.wsView = opt;                            // move.b d0,2(a5)
            List<GlfData.AlienFrame> frames = animFrames(a.type, opt);
            if (frames == null || frames.isEmpty()) {
                continue;
            }
            int d1 = a.timer2;
            GlfData.AlienFrame f = frames.get(Math.min(Math.max(d1, 0), frames.size() - 1));
            if (f.sfx != 0 && sfx != null) {       // move.b 5(a6,d1.w),d0 ; subq #1 ; Aud_SampleNum_w
                sfx.playAt(f.sfx - 1, a.x, a.z, a.height);
            }
            if (f.action != 0) {                       // action sur cette frame
                a.wsAction = (a.wsAction + 1) & 0xFF;  // add.b #1,(a5)
                a.wsSpecial = d1 & 0xFF;               // move.b d2,1(a5)
            }
            int d2 = s16(d1 + 1);                      // addq #1,d2
            if (f.special != 0) {                      // commande « spéciale » de la frame
                int d3 = f.special & 63;               // and.w #63,d3
                int cmd = (f.special & 0xFFFF) >>> 6;  // lsr.w #6,d0
                if (cmd < 2) {                         // .storeval
                    a.wsCount = d3 & 0xFF;
                } else if (cmd == 2) {                 // .randval : saute d'un nombre au hasard
                    int r = nav.rand();
                    d3 = d3 == 0 ? 0 : (r % d3) & 0xFFFF;
                    a.wsCount = d3 & 0xFF;
                } else {                               // décompte : reboucle tant qu'il reste
                    a.wsCount = (a.wsCount - 1) & 0xFF;
                    if (a.wsCount != 0) {
                        d2 = d3 & 0xFFFF;
                    }
                }
            }
            // Terminateur : le gfx négatif clôt l'animation, on repart à 0.
            if (d2 >= frames.size() || frames.get(d2).gfx < 0) {
                a.wsFinished = true;                   // st 3(a5)
                d2 = 0;
            }
            a.timer2 = d2;
        }
    }

    private List<GlfData.AlienFrame> animFrames(int type, int opt) {
        GlfData.Alien def = glf == null ? null : glf.alien(type);
        if (def == null || def.anims == null || opt < 0 || opt >= def.anims.size()) {
            return null;
        }
        return def.anims.get(opt);
    }

    // ============================================================ ItsAnAlien

    /** ItsAnAlien : charge les paramètres de la définition puis lance l'IA. */
    private void itsAnAlien(Alien a, PlayerSim player) {
        // ItsAnAlien (newaliencontrol.s:131) : rien a faire tant que l'alien n'est pas INQUIET.
        // Le drapeau se CONSOMME — seuls les bits bas sont effaces — et updateWorry le repose a
        // chaque frame tant que le joueur est en vue de la zone ou que l'equipe est alertee.
        if (a.worry == 0) {                            // tst.b ShotT_Worry_b(a0) ; bne .worry_about
            return;                                    // rts
        }
        a.worry &= 0x80;                               // and.b #$80,ShotT_Worry_b(a0)
        stepUp = 32 * 256;                             // move.l #32*256,StepUpVal
        stepDown = 32 * 256;
        if (a.zone < 0 || a.zone >= lvl.zones.length) {
            return;
        }
        zonePtr = a.zone;                              // Obj_ZonePtr_l
        GlfData.Alien def = glf == null ? null : glf.alien(a.type);
        if (def == null) {
            return;
        }
        // ALIENBRIGHT (newaliencontrol.s:36) : la table est lue puis NEGEE. Seul un resultat
        // strictement negatif (donc un GLFT_AlienBrights positif) allume la torche.
        alienBright = s16(-def.bright);
        GlfData.Gun sh = glf.alienShootDef(a.type);
        shotYOff = sh == null ? 0 : (((sh.bulletType << 16) | (sh.delay & 0xFFFF)) << 7);
        shotOffMult = sh == null ? 0 : s16(s16(-sh.sfx) << 2);
        thingHeight = def.height << 7;                 // asl.l #7 ; thingheight
        auxObj = def.auxiliary;
        vecObj = def.gfxType & 0xFF;              // AI_VecObj_w = octet bas de AlienT_GFXType_w
        reactionTime = def.reactionTime;
        defaultMode = def.defaultBehaviour;
        responseMode = def.responseBehaviour;
        retreatMode = def.retreatBehaviour;
        followupMode = def.followupBehaviour;
        prowlSpeed = def.defaultSpeed;
        responseSpeed = def.responseSpeed;
        retreatSpeed = def.retreatSpeed;
        followupSpeed = def.followupSpeed;
        followupTimer = def.followupTimeout;
        // diststowall (NewaliencontrolData.java:18) : {0,40} {1,80} {2,160} indexé par la carrure.
        int girth = Math.max(0, Math.min(2, def.girth));
        awayFromWall = girth;
        extLen = girth == 0 ? 40 : (girth == 1 ? 80 : 160);
        aiMainRoutine(a, player);
    }

    private int shotYOff, shotOffMult;

    /** AI_MainRoutine : dispatch selon EntT_CurrentMode_b. */
    private void aiMainRoutine(Alien a, PlayerSim p) {
        // move.w #-20,2(a0) : luminosité de dessin propre à l'entité (non utilisée ici)
        int mode = (byte) a.mode;
        if (mode < 1) {
            doDefault(a, p);
        } else if (mode == 1) {
            doResponse(a, p);
        } else if (mode < 3) {
            doFollowup(a, p);
        } else if (mode == 3) {
            // ai_DoRetreat : rts (la retraite n'existe pas dans le jeu final)
        } else if (mode == 5) {
            doDie(a);
        } else {
            doTakeDamage(a, p);
        }
    }

    private void doDefault(Alien a, PlayerSim p) {
        if (defaultMode < 1) {
            prowlRandom(a, p);
        } else if (defaultMode == 1) {
            prowlRandomFlying(a, p);
        }
    }

    private void doResponse(Alien a, PlayerSim p) {
        if (responseMode < 1) {
            charge(a, p, false);
        } else if (responseMode == 1) {
            charge(a, p, true);
        } else if (responseMode < 3) {
            attackWithGun(a, p, false);
        } else if (responseMode == 3) {
            chargeFlying(a, p, false);
        } else if (responseMode < 5) {
            chargeFlying(a, p, true);
        } else if (responseMode == 5) {
            attackWithGun(a, p, true);
        }
    }

    private void doFollowup(Alien a, PlayerSim p) {
        if (followupMode < 1) {
            pauseBriefly(a, p);
        } else if (followupMode == 1) {
            approach(a, p, false, false);
        } else if (followupMode < 3) {
            approach(a, p, true, false);
        } else if (followupMode == 3) {
            approach(a, p, false, true);
        } else if (followupMode < 5) {
            approach(a, p, true, true);
        }
    }

    // ============================================================ patrouille

    private void prowlRandomFlying(Alien a, PlayerSim p) {
        stepDown = 1000 * 256;
        flyABit = true;
        prowlFly(a, p);
    }

    private void prowlRandom(Alien a, PlayerSim p) {
        flyABit = false;
        stepDown = 30 * 256;
        prowlFly(a, p);
    }

    /** ai_ProwlFly (Ai.java:423) : le coeur de la patrouille. */
    private void prowlFly(Alien a, PlayerSim p) {
        stepUp = 20 * 256;
        if (a.damageTaken != 0) {
            takeDamage(a, p);
            if (getOut) {
                return;
            }
        }
        doWalkAnim(a, p);

        // Ennui : si l'alien ne s'éloigne pas de l'endroit mémorisé, il finit par changer de but.
        int d1 = a.borX;
        int d2 = a.borZ;
        int d3 = s16(a.x);
        int d4 = s16(a.z);
        int d5 = d3;
        int d6 = d4;
        d3 = s16(d3 - d1);
        if (s16(d3) < 0) {
            d3 = s16(-s16(d3));
        }
        d4 = s16(d4 - d2);
        if (s16(d4) < 0) {
            d4 = s16(-s16(d4));
        }
        d4 = s16(d4 + d3);
        if (s16(d4) >= 50) {                           // il a bougé : on mémorise
            a.borX = d5;
            a.borZ = d6;
            a.borTimer = 100;
        } else {
            a.borTimer = s16(a.borTimer - 1);
            if (a.borTimer <= 0) {                     // il tourne en rond : nouveau but au hasard
                getRoomCPt(a);
                int t = randomReachableCPt(a);
                a.targetCPt = t;
                a.borTimer = 50;
            }
        }

        // Le bruit du joueur attire : on vise SON point de contrôle.
        if (p.noiseVol != 0) {
            int d1b = playerCPt(p);
            int d0 = nav.nextCPt(a.currentCPt, d1b, flyABit);
            if ((byte) d0 == Nav.NO_WAY) {
                d0 = a.currentCPt;
            }
            a.targetCPt = d0;
        }

        // Souvenir d'équipe, puis souvenir personnel : « le joueur était par là ».
        boolean seen = false;
        if ((byte) a.team >= 0 && a.team < teamWork.length) {
            Work tw = teamWork[a.team];
            if (tw.seenBy >= 0) {
                if (a.index == tw.seenBy) {
                    tw.seenBy = -1;
                } else {
                    Work w = work[a.index];
                    w.damageDone = 0;
                    w.damageTaken = 0;
                    w.lastX = tw.lastX;
                    w.lastY = tw.lastY;
                    w.lastZone = tw.lastZone;
                    w.lastCPt = tw.lastCPt;
                    a.targetCPt = w.lastCPt;
                    w.lastZone = -1;
                    seen = true;
                }
            }
        }
        if (!seen) {
            Work w = work[a.index];
            w.damageDone = 0;
            w.damageTaken = 0;
            if (w.lastZone >= 0) {
                a.targetCPt = w.lastCPt;
                w.lastZone = -1;
            }
        }

        // .not_seen : prochain saut vers le but
        int d0 = nav.nextCPt(a.currentCPt, a.targetCPt, flyABit);
        boolean yesRand;
        if ((byte) d0 == Nav.NO_WAY) {
            yesRand = true;
        } else if (flyABit) {
            yesRand = false;
        } else {
            yesRand = nav.onlySee;
        }
        if (yesRand) {
            d0 = randomReachableCPt(a);
            a.targetCPt = d0;
            d0 = nav.nextCPt(a.currentCPt, a.targetCPt, flyABit);
        }
        middleCPt = d0;

        newx = nav.x(d0);
        newz = nav.z(d0);
        // Frétillement pseudo-aléatoire autour du point (pour ne pas marcher en file indienne).
        int w = setw(0, s16(d0 << 2) + s16(a.index));
        w = muls(w, 0x1347) & 4095;
        int wobX = sinCos.value(w * 2) << 4;
        int wobZ = sinCos.value(w * 2 + 2048 * 2) << 4;
        newx = s16(newx + (wobX >> 16));
        newz = s16(newz + (wobZ >> 16));

        oldx = a.x;
        oldz = a.z;
        speed = 0;
        if (doAction != 0) {
            int d2b = setw(0, doAction & 0xFF);
            d2b = setw(d2b, d2b << 2);
            speed = s16(muls(d2b, prowlSpeed));
        }
        range = 40;
        int y = s16(a.height) << 7;
        y -= thingHeight >> 1;
        newy = y;
        oldy = y;
        stoodInTop = a.upperZone;
        gotThere = false;
        headTowardsAng();
        a.angle = angRet;

        if (gotThere) {
            a.currentCPt = middleCPt;
            if ((short) middleCPt == (short) a.targetCPt) {
                a.targetCPt = randomCPt();             // arrivé : nouveau but au hasard
            }
        }

        moveAlien(a);                                  // MoveObject + zone
        getRoomStats(a);
        if (flyABit) {
            a.height = s16(a.height);                  // la hauteur de vol est gérée à part
            flyToCPtHeight(a);
        }
        doTorch(a);

        a.mode = 0;
        lookForPlayer(a, p);
        a.whichAnim = 0;
        if (a.seePlayer && checkInFront(a, p) != 0) {
            a.timer1 = s16(a.timer1 - tempFrames);
            if (a.timer1 <= 0 && checkForDark(a, p) != 0) {
                boolean attack;
                if (flyABit || responseMode == 2 || responseMode == 5) {
                    attack = true;
                } else {
                    attack = checkAttackOnGround(a, p) != 0;
                }
                if (attack) {
                    a.timer2 = 0;
                    a.mode = 1;
                    a.whichAnim = 1;
                } else {
                    storePlayerPosition(a, p);         // il le voit mais ne peut pas l'atteindre
                    a.timer1 = reactionTime;
                }
            }
            a.angle = s16(a.angle + animFacing);
            return;
        }
        a.timer1 = reactionTime;
        a.angle = s16(a.angle + animFacing);
    }

    /** Tire un point de contrôle au hasard, en cherchant jusqu'à 8 fois un but ATTEIGNABLE. */
    private int randomReachableCPt(Alien a) {
        int d1 = randomCPt();
        for (int d7 = 7; d7 >= 0; d7--) {
            a.targetCPt = d1;
            int d0 = nav.nextCPt(a.currentCPt, d1, flyABit);
            if ((short) d0 != (short) a.currentCPt && (byte) d0 != Nav.NO_WAY) {
                return d1;
            }
            d1 = s16(a.targetCPt + 1);
            if (d1 >= nav.count) {
                d1 = 0;
            }
        }
        return d1;
    }

    /**
     * Un point de controle au hasard (ai.s:590) : {@code GetRand}, puis le RESTE de la division
     * par le nombre de points — l'original le lit par {@code divs.w} + {@code swap}.
     *
     * <p>Ce reste etait pris sur le resultat de {@link M68k#divs}, qui ne rend QUE le quotient :
     * le mot fort valait donc toujours zero et TOUS les aliens visaient le point 0. De la leur
     * regroupement au meme endroit, et les embuscades derriere une porte.
     */
    private int randomCPt() {
        int d0 = nav.rand();
        int d1 = setw(0, d0);                          // moveq #0,d1 ; move.w d0,d1
        return divsRem(d1, nav.count == 0 ? 1 : nav.count) & 0xFFFF;
    }

    private int playerCPt(PlayerSim p) {
        if (p.zone < 0 || p.zone >= zoneCPt.length) {
            return 0;
        }
        return p.stoodInTop ? zoneUpperCPt[p.zone] : zoneCPt[p.zone];
    }

    // =============================================================== réponse

    private void charge(Alien a, PlayerSim p, boolean side) {
        flyABit = false;
        toSide = side;
        stepDown = 30 * 256;
        chargeCommon(a, p, false);
    }

    private void chargeFlying(Alien a, PlayerSim p, boolean side) {
        flyABit = true;
        toSide = side;
        stepDown = 1000 * 256;
        chargeCommon(a, p, true);
    }

    /** ai_ChargeCommon / ai_ChargeFlyingCommon : il fonce sur le joueur et le mord au contact. */
    private void chargeCommon(Alien a, PlayerSim p, boolean flying) {
        if (a.damageTaken != 0) {
            takeDamage(a, p);
            if (getOut) {
                return;
            }
        }
        doAttackAnim(a, p);

        boolean noMunch = checkTeleport(a);
        if (!noMunch) {
            newx = s16(p.xOff >> 16);
            newz = s16(p.zOff >> 16);
            oldx = a.x;
            oldz = a.z;
            if (toSide) {
                runAround(a, p);
            }
            speed = s16(muls(responseSpeed, tempFrames));
            range = 160;
            int y = (s16(a.height) << 7) - (thingHeight >> 1);
            newy = y;
            oldy = y;
            stoodInTop = a.upperZone;
            gotThere = false;
            headTowardsAng();
            // 1er test : a-t-on touché quelque chose (= le joueur) ? 2e : un obstacle ?
            if (doCollision(a, p)) {
                newx = oldx;
                newz = oldz;
                gotThere = true;
            } else if (doCollision(a, p)) {
                newx = oldx;
                newz = oldz;
            } else {
                moveAlien(a);
                a.angle = angRet;
            }
        }

        if (!noMunch && gotThere && doAction != 0) {   // .no_munch : la morsure
            int d0 = setw(0, (doAction & 0xFF) << 1);
            p.damageTaken += d0 & 0xFF;
            int dx = divs(s16(s16(newx) - s16(oldx)), tempFrames);
            p.impactX = s16(p.impactX + dx);
            int dz = divs(s16(s16(newz) - s16(oldz)), tempFrames);
            p.impactZ = s16(p.impactZ + dz);
        }

        storePlayerPosition(a, p);
        if (flying) {
            int saved = a.height;
            getRoomStats(a);
            a.height = saved;
            getRoomCPt(a);
            flyToPlayerHeight(a, p);
        } else {
            getRoomStats(a);
            getRoomCPt(a);
        }
        doTorch(a);
        lookForPlayer(a, p);
        a.mode = 0;
        if (a.seePlayer && checkInFront(a, p) != 0) {
            boolean attack = flyABit || checkAttackOnGround(a, p) != 0;
            if (attack) {
                a.angle = s16(a.angle + animFacing);
                a.mode = 1;
                a.whichAnim = 1;
                return;
            }
        }
        a.whichAnim = 0;
        a.timer2 = 0;
        a.angle = s16(a.angle + animFacing);
    }

    /** RunAround (Newaliencontrol.java:790) : décale la cible sur le côté du joueur. */
    private void runAround(Alien a, PlayerSim p) {
        int d0 = s16(s16(oldx) - s16(newx));
        d0 = M68k.asrw(d0, 1);
        int d1 = s16(s16(oldz) - s16(newz));
        d1 = M68k.asrw(d1, 1);
        int d2 = s16(a.x - s16(p.snapXOff >> 16));
        int d3 = s16(a.z - s16(p.snapZOff >> 16));
        d2 = muls(d2, sinCos.cos(p.angPos));
        d3 = muls(d3, sinCos.sin(p.angPos));
        if (d2 - d3 >= 0) {
            d0 = s16(-s16(d0));
            d1 = s16(-s16(d1));
        }
        newx = s16(newx - s16(d1));
        newz = s16(newz + s16(d0));
    }

    /** ai_AttackWithGun(Flying) + ai_AttackCommon : prépare le tir puis attaque. */
    private void attackWithGun(Alien a, PlayerSim p, boolean flying) {
        flyABit = flying;
        GlfData.Alien def = glf.alien(a.type);
        GlfData.Bullet b = def == null ? null : glf.bullet(def.bulletType);
        shotType = def == null ? 0 : def.bulletType;
        if (b == null) {
            return;
        }
        shotPower = b.hitDamage & 0xFF;
        shotSpeed = 1 << (b.speed & 31);               // bset d0,d1
        shotShift = s16(b.speed - 1);
        if (b.hitScan == 0) {
            attackWithProjectile(a, p, b);
        } else {
            attackWithHitScan(a, p, b);
        }
    }

    /** ai_AttackWithHitScan : il tire « instantanément », avec une chance de toucher. */
    private void attackWithHitScan(Alien a, PlayerSim p, GlfData.Bullet b) {
        if (a.damageTaken != 0) {
            a.mode = 4;
            takeDamage(a, p);
            if (getOut) {
                return;
            }
        }
        doAttackAnim(a, p);

        newx = s16(p.xOff >> 16);
        newz = s16(p.zOff >> 16);
        oldx = a.x;
        oldz = a.z;
        range = -20;
        speed = 20;
        headTowardsAng();
        a.angle = angRet;

        storePlayerPosition(a, p);
        lookForPlayer(a, p);
        a.mode = 0;
        boolean canSee = false;
        if (a.seePlayer && checkInFront(a, p) != 0) {
            a.mode = 1;
            a.whichAnim = 1;
            a.angle = s16(a.angle + animFacing);
            canSee = true;
        }
        if (!canSee) {
            a.whichAnim = 0;
            a.timer2 = 0;
            a.timer1 = followupTimer;
            a.angle = s16(a.angle + animFacing);
            return;
        }

        if (doAction != 0) {
            // Chance de toucher : aléatoire pondéré par le carré de la distance à l'écran.
            int d0 = nav.rand() & 0x7fff;
            int dx = s16(a.x - s16(p.xOff >> 16));
            int dz = s16(a.z - s16(p.zOff >> 16));
            int d1 = muls(dx, dx) + muls(dz, dz);
            d1 = d1 >> 6;
            int d0l = ((short) d0) << 2;
            if (d0l > d1) {                            // touché
                p.damageTaken += shotPower;
                int ddx = s16(a.x - s16(p.snapXOff >> 16));
                int ddz = s16(a.z - s16(p.snapZOff >> 16));
                int d2 = muls(ddx, ddx) + muls(ddz, ddz);
                d2 = sqrt(d2) * 2;
                if (d2 == 0) {
                    d2 = 1;
                }
                p.impactX = s16(p.impactX - divs(muls(ddx, shotPower), d2));
                p.impactZ = s16(p.impactZ - divs(muls(ddz, shotPower), d2));
            } else if (shots != null) {                // .missed_player
                if (shots.shootPlayer1(a, p)) {        // l'impact part sur ce qu'il y a derriere
                    missedShots++;
                }
            }
        }

        newx = a.x;                                    // .no_shooty_thang
        newz = a.z;
        doTorch(a);
        if (finishedAnim) {
            a.whichAnim = 0;
            a.mode = 2;
            a.timer1 = followupTimer;
            a.timer2 = 0;
            a.angle = s16(a.angle + animFacing);
        }
    }

    /** ai_AttackWithProjectile : il crache un projectile (via le pool de tirs). */
    private void attackWithProjectile(Alien a, PlayerSim p, GlfData.Bullet b) {
        if (a.damageTaken != 0) {
            a.mode = 4;
            takeDamage(a, p);
            if (getOut) {
                return;
            }
        }
        doAttackAnim(a, p);

        newx = s16(p.xOff >> 16);
        newz = s16(p.zOff >> 16);
        oldx = a.x;
        oldz = a.z;
        range = -20;
        speed = 20;
        headTowardsAng();
        a.angle = angRet;
        storePlayerPosition(a, p);

        if (doAction != 0 && fire != null) {
            fire.fireAtPlayer(a, p, shotType, shotSpeed, shotShift, shotYOff, shotOffMult);
        }

        newx = a.x;                                    // .no_shooty_thang
        newz = a.z;
        doTorch(a);
        if (finishedAnim) {
            a.whichAnim = 0;
            a.mode = 2;
            a.timer1 = followupTimer;
            a.timer2 = 0;
            a.angle = s16(a.angle + animFacing);
            return;
        }
        lookForPlayer(a, p);
        a.mode = 0;
        if (a.seePlayer && checkInFront(a, p) != 0) {
            a.whichAnim = 1;
            a.mode = 1;
            a.angle = s16(a.angle + animFacing);
            return;
        }
        a.whichAnim = 0;
        a.timer2 = 0;
        a.timer1 = followupTimer;
        a.angle = s16(a.angle + animFacing);
    }

    /** Branchement du tir d'alien (pool de projectiles) — posé par le jeu. */
    public interface AlienFire {
        void fireAtPlayer(Alien a, PlayerSim p, int bulletType, int speed, int shift,
                          int yOff, int offMult);
    }

    private AlienFire fire;

    public void setFire(AlienFire f) {
        this.fire = f;
    }

    // ================================================================= suivi

    /** ai_PauseBriefly : il souffle un instant après son attaque. */
    private void pauseBriefly(Alien a, PlayerSim p) {
        if (a.damageTaken != 0) {
            takeDamage(a, p);
            if (getOut) {
                return;
            }
        }
        a.timer2 = 0;
        doWalkAnim(a, p);
        a.timer1 = s16(a.timer1 - tempFrames);
        if (a.timer1 > 0) {
            a.angle = s16(a.angle + animFacing);
            return;
        }
        newx = a.x;
        newz = a.z;
        doTorch(a);
        lookForPlayer(a, p);
        a.mode = 0;
        if (a.seePlayer && checkInFront(a, p) != 0 && checkForDark(a, p) != 0) {
            a.whichAnim = 1;
            a.mode = 1;
            a.angle = s16(a.angle + animFacing);
            return;
        }
        a.whichAnim = 0;
        a.angle = s16(a.angle + animFacing);
    }

    /** ai_ApproachCommon : il revient vers le joueur sans charger. */
    private void approach(Alien a, PlayerSim p, boolean side, boolean flying) {
        flyABit = flying;
        toSide = side;
        stepDown = flying ? 1000 * 256 : 30 * 256;
        if (a.damageTaken != 0) {
            takeDamage(a, p);
            if (getOut) {
                return;
            }
        }
        doWalkAnim(a, p);

        boolean tel = checkTeleport(a);
        if (!tel) {
            oldx = a.x;
            oldz = a.z;
            newx = s16(p.xOff >> 16);
            newz = s16(p.zOff >> 16);
            if (toSide) {
                runAround(a, p);
            }
            speed = 0;
            if (doAction != 0) {
                int d2 = setw(0, (doAction & 0xFF) << 2);
                speed = s16(muls(d2, followupSpeed));
            }
            range = 160;
            int y = (s16(a.height) << 7) - (thingHeight >> 1);
            newy = y;
            oldy = y;
            stoodInTop = a.upperZone;
            gotThere = false;
            headTowardsAng();
            if (doCollision(a, p)) {
                newx = oldx;
                newz = oldz;
                gotThere = true;
            } else if (doCollision(a, p)) {
                newx = oldx;
                newz = oldz;
            } else {
                moveAlien(a);
                a.angle = angRet;
            }
        }

        storePlayerPosition(a, p);
        if (flyABit) {
            flyToPlayerHeight(a, p);
        }
        int saved = a.height;
        getRoomStats(a);
        if (flyABit) {
            a.height = saved;
        }
        getRoomCPt(a);
        doTorch(a);
        a.mode = 0;
        if (!flyABit && checkAttackOnGround(a, p) == 0) {
            a.whichAnim = 0;
            a.angle = s16(a.angle + animFacing);
            return;
        }
        lookForPlayer(a, p);
        if (a.seePlayer && checkInFront(a, p) != 0) {
            a.mode = 2;
            a.timer1 = s16(a.timer1 - tempFrames);
            if (a.timer1 <= 0 && checkForDark(a, p) != 0) {
                a.mode = 1;
                a.timer2 = 0;
                a.whichAnim = 1;
                a.angle = s16(a.angle + animFacing);
                return;
            }
        }
        a.whichAnim = 0;
        a.angle = s16(a.angle + animFacing);
    }

    // =========================================================== dégâts/mort

    /** ai_DoTakeDamage : il encaisse, tourné vers le joueur, puis repart. */
    private void doTakeDamage(Alien a, PlayerSim p) {
        doWalkAnim(a, p);
        int saved = a.height;
        getRoomStatsStill(a);
        if (defaultMode >= 1) {
            a.height = saved;                          // les volants gardent leur altitude
        }
        if (finishedAnim) {
            a.mode = 0;
            a.whichAnim = 0;
            a.timer2 = 0;
        }
        doTorch(a);                                    // .still_hurting
        newx = s16(p.xOff >> 16);
        newz = s16(p.zOff >> 16);
        oldx = a.x;
        oldz = a.z;
        range = -20;
        speed = 20;
        headTowardsAng();
        a.angle = s16(angRet + animFacing);
    }

    /** ai_DoDie : il joue sa mort, puis l'emplacement est rendu. */
    private void doDie(Alien a) {
        doWalkAnimNoPlayer(a);
        getRoomStatsStill(a);
        if (finishedAnim) {
            a.alive = false;                           // FREE_ENT
            a.zone = -1;
        }
        a.hitPoints = 0;
    }

    /**
     * ai_TakeDamage : les dégâts encaissés depuis la dernière frame. Trois issues : il meurt,
     * il riposte (3 fois sur 4) ou il recule en jouant l'animation « touché ».
     */
    private void takeDamage(Alien a, PlayerSim p) {
        getOut = false;
        int d0 = a.damageTaken & 0xFF;
        // add.w d0,(a2) : a2 = AI_DamagePtr_l, le cumul PERMANENT — surtout pas le workspace
        // de ronde, que ai_ProwlFly efface a chaque frame.
        damageAccum[a.index] = s16(damageAccum[a.index] + d0);
        d0 = M68k.asrw(setw(0, damageAccum[a.index]), 2); // asr.w #2 : divisé par 4
        int d1 = a.hitPoints & 0xFF;
        a.damageTaken = 0;
        if (s16(d1) <= s16(d0)) {
            justDied(a);
            return;
        }
        a.timer1 = 0;
        a.timer2 = 0;
        if ((nav.rand() & 3) != 0) {                   // riposte : il charge le joueur
            a.wsSpecial = 0xFF;
            a.mode = 1;
            a.timer2 = 0;
            a.timer1 = 0;
            a.whichAnim = 1;
            oldx = a.x;
            oldz = a.z;
            newx = s16(p.xOff >> 16);
            newz = s16(p.zOff >> 16);
            speed = 100;
            range = -20;
            headTowardsAng();
            a.angle = angRet;
            getOut = true;
            return;
        }
        a.mode = 4;                                    // il accuse le coup
        a.whichAnim = 2;
        a.wsSpecial = 0xFF;
        getOut = true;
    }

    /**
     * ai_JustDied : l'alien meurt. Son {@code SplatType} dit ce qu'il laisse — soit une GERBE DE
     * MORCEAUX (une definition de projectile « Splutch », qui retombe avec sa gravite), soit,
     * au-dela de NUM_BULLET_DEFS, des aliens PLUS PETITS qui prennent sa place.
     */
    private void justDied(Alien a) {
        a.hitPoints = 0;
        if (messages != null && a.displayText >= 0 && levelMessages != null
                && a.displayText < levelMessages.size()) {
            messages.push(levelMessages.get(a.displayText));   // ai_JustDied : .no_text
        }
        GlfData.Alien def = glf == null ? null : glf.alien(a.type);
        int splat = def == null ? -1 : (def.splatType & 0xFF);
        if (splat >= 0 && splat < 20) {                // cmp.b #NUM_BULLET_DEFS ; blt .go_splutch
            if (shots != null) {
                shots.explodeIntoBits(splat, 8, a.x, a.z, a.height, a.zone, a.upperZone, 0, 0);
            }
        } else if (splat >= 20) {
            spawnSmaller(a, splat - 20);               // sub.b #NUM_BULLET_DEFS
        }
        a.mode = 5;
        a.whichAnim = 3;
        a.timer2 = 0;
        a.wsSpecial = 0xFF;
        getOut = true;
    }

    /** Nombre de coups au but encaisses par les monstres (diagnostic/validation). */
    public int hits;

    /**
     * Les trois aliens plus petits qu'un gros laisse en mourant (ai_JustDied, .spawn_loop) :
     * memes position, zone et angle, points de vie de LEUR definition, sans equipe.
     */
    private void spawnSmaller(Alien parent, int type) {
        GlfData.Alien def = glf == null ? null : glf.alien(type);
        if (def == null) {
            return;
        }
        int spawned = 0;
        for (Alien a : list) {
            if (spawned >= 3) {
                return;                                // move.w #2,d7 : trois au total
            }
            if (a.alive && a.zone >= 0) {
                continue;                              // .find_one_free
            }
            a.alive = true;
            a.type = type;
            a.hitPoints = def.hitPoints & 0xFF;
            a.damageTaken = 0;
            a.x = parent.x;
            a.z = parent.z;
            a.height = parent.height;
            a.zone = parent.zone;
            a.upperZone = parent.upperZone;
            a.currentCPt = parent.currentCPt;
            a.targetCPt = parent.currentCPt;
            a.team = -1;                               // move.b #-1,EntT_TeamNumber_b
            a.angle = parent.angle;
            a.mode = 0;
            a.whichAnim = 0;
            a.timer1 = 0;
            a.timer2 = 0;
            a.doorsHeld = parent.doorsHeld;
            a.seePlayer = false;
            a.velocityY = 0;
            spawned++;
        }
    }

    /** Dégâts infligés par le joueur (appelé par le vol des balles). */
    /** Le cumul permanent d'un monstre (AI_Damaged_vw) : il meurt quand pv &lt;= cumul/4. */
    public int damageAccum(Alien a) {
        return a.index >= 0 && a.index < damageAccum.length ? damageAccum[a.index] : 0;
    }

    public void hit(Alien a, int power) {
        a.damageTaken = (a.damageTaken + power) & 0xFF;
        hits++;
    }

    // ============================================================== services

    /** ai_DoAttackAnim (= ai_DoWalkAnim) : choisit la frame à dessiner et son action. */
    private void doWalkAnim(Alien a, PlayerSim p) {
        doAttackAnim(a, p);
    }

    private void doWalkAnimNoPlayer(Alien a) {
        doAttackAnim(a, null);
    }

    private void doAttackAnim(Alien a, PlayerSim p) {
        int opt = a.wsView;
        if (opt == 0 && vecObj != 1 && p != null) {
            opt = viewpointToDraw(a, p) * 2;           // marche : 4 vues (face, droite, dos, gauche)
        }
        List<GlfData.AlienFrame> frames = animFrames(a.type, opt);
        int d1 = a.timer2;
        if ((byte) a.wsSpecial >= 0) {                 // frame forcée par le pilote d'animation
            d1 = a.wsSpecial & 0xFF;
        }
        a.wsSpecial = 0xFF;                            // st 1(a5)
        doAction = a.wsAction;                         // move.b (a5),ai_DoAction_b
        a.wsAction = 0;
        finishedAnim = a.wsFinished;
        a.wsFinished = false;
        if (frames == null || frames.isEmpty()) {
            return;
        }
        GlfData.AlienFrame f = frames.get(Math.min(Math.max(d1, 0), frames.size() - 1));
        a.sheet = f.gfx;
        int d0 = f.frame;                              // octet signé : négatif = image miroir
        a.flip = d0 <= 0;
        if (d0 <= 0) {
            d0 = -d0;
        }
        a.frame = d0 - 1;
        animFacing = 0;
        if (vecObj == 1) {
            animFacing = f.word2;                      // modèle vectoriel : incrément d'angle
        } else {
            a.drawWord2 = f.word2;
        }
        // FREE_ENT_2 a0,ENT_PREV : l'entite auxiliaire est LIBEREE a chaque passage, et remplie
        // seulement si la definition en a une (AUXOBJ >= 0) et que la frame la demande (octet 8).
        a.auxFrame = -1;
        if (auxObj >= 0 && f.aux >= 0) {               // move.w AUXOBJ,d3 ; blt .noaux
            a.auxFrame = f.aux;
            a.auxX = s16(f.auxX) * 2;                  // add.w d4,d4
            a.auxY = s16(f.auxY) * 2;                  // add.w d5,d5
        }
    }

    /** ViewpointToDraw : quelle vue du monstre présenter à la caméra. */
    private int viewpointToDraw(Alien a, PlayerSim p) {
        int d3 = s16(a.angle - p.angPos) & SinCos.MASK;
        int d2 = sinCos.value(d3);
        d3 = sinCos.value(d3 + SinCos.COSINE_OFS);
        int d0 = -d3;
        int d4 = d2;
        if (d0 > 0) {                                  // face à la caméra
            if (d4 <= 0) {
                d4 = -d4;
                return d4 > d0 ? 3 : 0;
            }
            return d4 > d0 ? 1 : 0;
        }
        if (d4 <= 0) {
            return d0 > d4 ? 3 : 2;
        }
        d0 = -d0;
        return d4 > d0 ? 1 : 2;
    }

    /** AI_LookForPlayer1 : ligne de vue vers le joueur. */
    private void lookForPlayer(Alien a, PlayerSim p) {
        a.seePlayer = false;
        los.viewerTop = a.upperZone;
        los.targetTop = p.stoodInTop;
        los.viewerZone = zonePtr;
        los.targetZone = p.zone;
        los.viewerX = newx;
        los.viewerZ = newz;
        los.targetX = s16(p.xOff >> 16);
        los.targetZ = s16(p.zOff >> 16);
        los.targetY = s16(p.yOff >> 7);
        los.viewerY = s16(a.height);
        los.canItBeSeen();
        a.seePlayer = los.canSee;
    }

    /**
     * ai_DoTorch (ai.s:1867) : le monstre PORTE une lumiere. Elle n'existe que si
     * {@code GLFT_AlienBrights} de sa definition est STRICTEMENT POSITIF — le jeu fait
     * {@code neg.w d1} au chargement puis {@code bge .nobright}, donc un 0 ou un negatif
     * n'eclaire rien. Dans les donnees du jeu, seuls le « Droid » (10) et le « well ard
     * guard » (50) en portent une, et seul le second est pose dans les niveaux.
     *
     * <p>C'est la variante a CONE : la lumiere est orientee par l'angle courant du monstre.
     */
    private void doTorch(Alien a) {
        if (dynLight == null || alienBright >= 0) {    // move.w ALIENBRIGHT,d0 ; bge .nobright
            return;
        }
        dynLight.brightenAngle(alienBright, newx, newz, a.zone, a.angle & SinCos.MASK,
                s16(a.height) << 7);                   // move.w 4(a0),d3 ; ext.l ; asl.l #7
    }

    /** ai_CheckInFront : le joueur est-il dans le demi-plan devant l'alien ? */
    private int checkInFront(Alien a, PlayerSim p) {
        newx = a.x;
        newz = a.z;
        int d0 = s16(s16(p.snapXOff >> 16) - s16(newx));
        int d1 = s16(s16(p.snapZOff >> 16) - s16(newz));
        int d2 = a.angle & SinCos.MASK;
        int d3 = sinCos.value(d2);
        int d4 = sinCos.value(d2 + SinCos.COSINE_OFS);
        return muls(d0, d3) + muls(d1, d4) > 0 ? 0xFF : 0;
    }

    /** ai_CheckForDark : dans une pièce sombre, le joueur peut passer inaperçu. */
    private int checkForDark(Alien a, PlayerSim p) {
        if (p.zone != a.zone) {
            int d0 = nav.rand() & 31;
            if (s16(d0) < s16(roomBright(p))) {
                return 0;                              // il est dans le noir
            }
        }
        return -1;
    }

    /** Plr1_RoomBright_w (Hires.java:2519) : luminosité moyenne de la pièce du joueur - 300. */
    private int roomBright(PlayerSim p) {
        if (p.zone < 0 || p.zone >= zoneBrights.length) {
            return 0;
        }
        int[] br = zoneBrights[p.zone];
        int[] border = zoneBorder[p.zone];
        if (br == null || border == null || border.length == 0) {
            return 0;
        }
        int n = 0;
        int sum = 0;
        for (int i = 0; i < 10 && i < border.length; i++) {
            if (border[i] < 0) {
                break;
            }
            n++;
            int v = i * 4 < br.length ? br[i * 4] : 0;
            if (v < 0) {
                v = -v;
            }
            sum = s16(sum + v);
        }
        if (n == 0) {
            return 0;
        }
        return s16(sum / n - 300);
    }

    /** ai_CheckAttackOnGround : le joueur est-il accessible par le maillage de navigation ? */
    private int checkAttackOnGround(Alien a, PlayerSim p) {
        int d1 = playerCPt(p);
        int d3 = d1;
        int d0 = a.currentCPt;
        if ((short) d1 == (short) d0) {
            return 0xFF;
        }
        d0 = nav.nextCPt(d0, d1, flyABit);
        return (short) d3 == (short) d0 ? 0xFF : 0;
    }

    /** ai_GetRoomCPT : le point de contrôle de la zone où l'alien se trouve. */
    private void getRoomCPt(Alien a) {
        if (zonePtr < 0 || zonePtr >= zoneCPt.length) {
            return;
        }
        a.currentCPt = a.upperZone ? zoneUpperCPt[zonePtr] : zoneCPt[zonePtr];
    }

    /** ai_StorePlayerPosition : mémorise où était le joueur (perso et équipe). */
    private void storePlayerPosition(Alien a, PlayerSim p) {
        Work w = work[a.index];
        w.lastX = s16(p.xOff >> 16);
        w.lastY = s16(p.zOff >> 16);
        w.lastZone = p.zone;
        w.lastCPt = playerCPt(p);
        if ((byte) a.team >= 0 && a.team < teamWork.length) {
            Work tw = teamWork[a.team];
            tw.lastX = w.lastX;
            tw.lastY = w.lastY;
            tw.lastZone = p.zone;
            tw.lastCPt = w.lastCPt;
            tw.seenBy = a.index;
        }
    }

    /** ai_GetRoomStats : commet la position puis recale la hauteur sur le sol. */
    private void getRoomStats(Alien a) {
        a.x = s16(newx);
        a.z = s16(newz);
        getRoomStatsStill(a);
    }

    private void getRoomStatsStill(Alien a) {
        if (zonePtr < 0 || zonePtr >= lvl.zones.length) {
            return;
        }
        a.zone = zonePtr;
        LevelSim.Zone z = lvl.zones[zonePtr];
        int d0 = a.upperZone ? z.upperFloorH : z.floorH;
        d0 -= thingHeight >> 1;
        a.height = s16(d0 >> 7);
    }

    /** ai_FlyToCPTHeight / ai_FlyToPlayerHeight / ai_FlyToHeightCommon. */
    private void flyToCPtHeight(Alien a) {
        flyToHeight(a, nav.y(middleCPt));
    }

    private void flyToPlayerHeight(Alien a, PlayerSim p) {
        flyToHeight(a, p.yOff >> 7);
    }

    private void flyToHeight(Alien a, int target) {
        int d0 = s16(a.height);
        int d2 = s16(a.velocityY);
        if (s16(target) > d0) {                        // descendre
            d2 = s16(d2 + 2);
            if (d2 >= 32) {
                d2 = 32;
            }
        } else {                                       // monter
            d2 = s16(d2 - 2);
            if (d2 <= -32) {
                d2 = -32;
            }
        }
        a.velocityY = d2;
        a.height = s16(a.height + d2);
        checkFloorCeiling(a);
    }

    /** ai_CheckFloorCeiling : coince l'altitude entre sol et plafond de la zone. */
    private void checkFloorCeiling(Alien a) {
        if (zonePtr < 0 || zonePtr >= lvl.zones.length) {
            return;
        }
        int d2 = s16(a.height);
        int d4 = thingHeight >> 8;
        int d3 = s16(d2);
        d2 = s16(d2 - d4);
        d3 = s16(d3 + d4);
        LevelSim.Zone z = lvl.zones[zonePtr];
        int d0 = a.upperZone ? z.upperFloorH : z.floorH;
        int d1 = a.upperZone ? z.upperRoofH : z.roofH;
        d0 >>= 7;
        d1 >>= 7;
        if (s16(d3) >= s16(d0)) {
            d3 = s16(d0);
            d2 = s16(s16(d3) - d4 - d4);
        }
        if (s16(d2) <= s16(d1)) {
            d2 = s16(d1);
            d3 = s16(s16(d2) + d4 + d4);
        }
        a.height = s16(d3 - d4);
    }

    /** CheckTeleport : la zone de départ téléporte-t-elle ? */
    private boolean checkTeleport(Alien a) {
        okTel = false;
        if (a.zone < 0 || a.zone >= lvl.zones.length) {
            return false;
        }
        LevelSim.Zone z = lvl.zones[a.zone];
        if (z.telZone < 0) {
            return false;
        }
        LevelSim.Zone to = lvl.zones[z.telZone];
        floorTemp = to.floorH - z.floorH;
        // Le jeu vérifie qu'il y a la place à l'arrivée (Obj_DoCollision) ; sans les autres
        // entités à l'arrivée on considère que oui.
        okTel = true;
        a.x = z.telX;
        a.z = z.telZ;
        a.zone = z.telZone;
        zonePtr = z.telZone;
        a.height = s16(a.height + (floorTemp >> 7));
        return true;
    }

    /** HeadTowardsAng (Objectmove.java:810) : avance vers (newx,newz) et renvoie l'angle. */
    private void headTowardsAng() {
        int d1 = s16(s16(newx) - s16(oldx));
        int xdiff = d1;
        int d2 = s16(s16(newz) - s16(oldz));
        int zdiff = d2;
        d1 = muls(d1, d1);
        d2 = muls(d2, d2);
        int d0 = 0;
        d2 += d1;
        gotThere = d2 == 0;
        if (d2 != 0) {
            d0 = sqrt(d2);
            gotThere = s16(d0) <= s16(range);
            if (s16(d0) <= s16(range)) {
                newx = oldx;
                newz = oldz;
            } else {
                int d3 = s16(speed + range);
                if (s16(d3) >= s16(d0)) {
                    d3 = s16(d0);
                    gotThere = true;
                }
                d3 = s16(d3 - range);
                d1 = divs(muls(xdiff, d3), d0);
                d2 = divs(muls(zdiff, d3), d0);
                newx = s16(d1 + s16(oldx));
                newz = s16(d2 + s16(oldz));
            }
        }
        if (s16(d0) == 0) {
            return;
        }
        d0 = s16(d0 + 1);
        sinRet = s16(divs((xdiff << 16) >> 1, d0));
        cosRet = s16(divs((zdiff << 16) >> 1, d0));
        // Recherche dichotomique de l'angle dans la table sinus (4 pas).
        int d2a = 0;
        int d6 = SinCos.COSINE_OFS;
        for (int d5 = 3; d5 >= 0; d5--) {
            int d3 = sinCos.value(d2a * 2);
            int d4 = sinCos.value(d2a * 2 + SinCos.COSINE_OFS);
            d4 = muls(d4, sinRet);
            d3 = muls(d3, cosRet);
            if (d4 - d3 >= 0) {
                d2a = s16(d2a + d6);
                d2a = s16(d2a + d6);
            }
            d2a = s16(d2a - d6);
            d2a = d2a & 4095;
            d6 = M68k.asrw(setw(0, d6), 1);
        }
        angRet = s16(d2a + d2a);
    }

    /** ai_CalcSqrt : racine entière par 3 itérations de Newton (identique au jeu). */
    private static int sqrt(int d2) {
        if (d2 == 0) {
            return 0;
        }
        int d0 = 31;
        while (d0 >= 0 && (d2 & (1 << d0)) == 0) {
            d0--;
        }
        d0 = d0 >> 1;
        d0 = 1 << d0;
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

    /**
     * Déplacement effectif : MoveObject avec les réglages de l'alien (rayon selon sa carrure,
     * bit 0x200 dans les drapeaux d'arête — c'est lui qui ouvre les portes sur son passage).
     */
    private void moveAlien(Alien a) {
        move.oldx = s16(oldx);
        move.oldz = s16(oldz);
        move.newx = s16(newx);
        move.newz = s16(newz);
        move.oldy = oldy;
        move.newy = newy;
        move.zone = zonePtr;
        move.stoodInTop = stoodInTop;
        move.awayFromWall = awayFromWall;
        move.exitFirst = false;
        move.wallBounce = false;
        move.extLen = extLen;
        move.stepUp = stepUp;
        move.stepDown = stepDown;
        move.thingHeight = thingHeight;
        move.wallFlags = 0b1000000000;                 // %1000000000 : contact « alien »
        move.moveObject();
        newx = move.newx;
        newz = move.newz;
        zonePtr = move.zone;
        stoodInTop = move.stoodInTop;
        a.upperZone = move.stoodInTop;
        hitwall = move.hitwall;
    }

    /**
     * Obj_DoCollision (Objectmove.java:1246) : y a-t-il une entité là où l'on veut aller ?
     * Le jeu teste les objets de la MÊME zone, au même étage, dans une boîte de 80 unités, et
     * ne retient que ceux dont on se rapproche. Le drapeau Obj_CollideFlags_l n'est pas testé
     * (le {@code btst} est commenté dans l'original) : les deux appels successifs sont donc
     * identiques, le premier servant à détecter la morsure, le second à bloquer.
     */
    private boolean doCollision(Alien self, PlayerSim p) {
        int d4 = newy >> 7;
        int d5 = (newy + thingHeight) >> 7;
        // Le joueur d'abord (il est une entité du tableau d'objets côté jeu).
        if (p.zone == zonePtr && p.stoodInTop == stoodInTop) {
            int py = s16((p.yOff + (p.height >> 1)) >> 7);
            if (near(s16(p.snapXOff >> 16), s16(p.snapZOff >> 16), d4, d5, py)) {
                return true;
            }
        }
        for (Alien o : list) {
            if (o == self || !o.alive || o.zone != zonePtr || o.upperZone != stoodInTop
                    || o.hitPoints == 0) {
                continue;
            }
            if (near(o.x, o.z, d4, d5, o.height)) {
                return true;
            }
        }
        return false;
    }

    /** Test de proximité commun à {@link #doCollision} (boîte 80 puis « on se rapproche »). */
    private boolean near(int ox, int oz, int d4, int d5, int oy) {
        // Extents verticaux : le jeu les lit dans AlienShootDefs (quirk du registre hérité).
        GlfData.Gun sh = glf == null ? null : glf.alienShootDef(0);
        int lo = sh == null ? 0 : s16(sh.delay);
        int hi = sh == null ? 0 : s16(sh.bulletCount);
        int d1 = s16(oy - lo);
        if (s16(d5) < d1) {
            return false;
        }
        d1 = s16(d1 + hi);
        if (s16(d4) > d1) {
            return false;
        }
        int dx = s16(ox - s16(newx));
        if (dx < 0) {
            dx = -dx;
        }
        int dz = s16(oz - s16(newz));
        if (dz < 0) {
            dz = -dz;
        }
        if (dz > dx) {
            return s16(dz - 80) <= 80;
        }
        if (s16(dx - 80) > 80) {
            return false;
        }
        // On ne bloque que si le déplacement RAPPROCHE de l'entité.
        int nd = muls(s16(ox - s16(newx)), s16(ox - s16(newx)))
                + muls(s16(oz - s16(newz)), s16(oz - s16(newz)));
        int od = muls(s16(ox - s16(oldx)), s16(ox - s16(oldx)))
                + muls(s16(oz - s16(oldz)), s16(oz - s16(oldz)));
        return nd <= od;
    }
}
