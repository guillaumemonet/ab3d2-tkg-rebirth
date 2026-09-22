package ab3d2.rebirth;

import java.util.List;

/** Base GLF extraite (rebirth/assets/glf.json) : seules les tables utiles au rendu. */
public final class GlfData {

    public List<Named> wallTextures;    // index -> nom du PNG (textures/walls/<nom>.png)
    public List<Named> spriteSheets;    // index -> dossier (textures/objects/<nom>/frame_NN.png)
    public List<Named> vectorModels;    // index -> dossier (models/<nom>/frame_NNN.obj)
    public List<Named> levels;
    public List<ObjDef> objects;        // definitions d'objets (type, gfx, scripts d'animation)
    public List<Integer> maxInventory;  // plafonds d'inventaire (game_ModProps.gmp_MaxInventory)
    public List<Alien> aliens;          // AlienT : definitions de monstres (IA + animations)
    public List<Gun> alienShootDefs;    // ShootT par alien (tir + extents de collision)
    public List<Floor> floorData;       // GLFT_FloorData : degats + bruit de pas par type de sol
    public List<Integer> ambientSfx;    // GLFT_AmbientSFX_l : 16 bruits d'ambiance
    public List<Gun> guns;              // ShootT : armes (quel projectile, cadence, rafale)
    public List<Bullet> bullets;        // BulT : projectiles (vol, impact, graphismes)

    /** Definition d'objet (ODefT) : ce qu'il faut pour l'afficher et l'animer. */
    public static final class ObjDef {
        public int index;
        public String name;
        public int gfxType;             // 0 = sprite, 1 = modele vectoriel, 2 = glare (additif)
        public int type;                // ENT_TYPE_* : 0 ramassable, 1 activable, 2 destructible...
        public int collideRadius;
        public int collideHeight;
        public int floorCeiling;        // 0 = pose au sol, sinon accroche au plafond
        public int activeTimeout;       // duree d'activation (< 0 = jusqu'a nouvelle action)
        public int sfx;                 // ODefT_SFX_w : bruit au ramassage (< 0 = aucun)
        public List<Integer> ammoGive;  // consommables donnes au ramassage
        public List<Integer> gunGive;   // items donnes au ramassage
        public List<ObjectAnim.Step> defAnim;   // animation par defaut (DEFANIMOBJ)
        public List<ObjectAnim.Step> actAnim;   // animation active (ACTANIMOBJ)
    }

    /** Definition de l'objet `i`, ou null. */
    public ObjDef object(int i) {
        return objects != null && i >= 0 && i < objects.size() ? objects.get(i) : null;
    }

    /** Definition de monstre (AlienT) : statistiques et comportements d'IA. */
    public static final class Alien {
        public int index;
        public String name;
        public int gfxType;             // mot ; son OCTET BAS = AI_VecObj_w (1 = modele vectoriel)
        public boolean isVector;
        public int bright;              // GLFT_AlienBrights : lumiere qu'il porte (torche)
        public int hitPoints;
        public int height;              // AlienT_Height_w (x128 = thingheight)
        public int girth;               // 0..2 : rayon de collision (table diststowall)
        public int bulletType;          // projectile tire
        public int splatType;           // gerbe a la mort (ou alien engendre si >= 20)
        public int auxiliary;           // objet auxiliaire des animations
        public int reactionTime;        // frames avant de reagir a la vue du joueur
        public int defaultBehaviour, defaultSpeed;
        public int responseBehaviour, responseSpeed, responseTimeout;
        public int followupBehaviour, followupSpeed, followupTimeout;
        public int retreatBehaviour, retreatSpeed, retreatTimeout;
        public int damageToRetreat, damageToFollowup;
        /** 11 options (4 vues de marche entrelacees, attaque, touche, mort) x frames. */
        public List<List<AlienFrame>> anims;
    }

    /** Une frame d'animation d'alien (A_FrameLen = 11 octets). */
    public static final class AlienFrame {
        public int gfx;                 // feuille de sprites ; NEGATIF = fin de l'animation
        public int frame;               // numero + 1 ; NEGATIF = image miroir
        public int word2;               // donnee de dessin (ou increment d'angle si vectoriel)
        public int sfx;                 // son + 1
        public int action;              // la frame ou il mord / tire
        public int special;             // commande de saut/attente (2 bits de code + 6 de valeur)
        public int aux, auxX, auxY;     // objet auxiliaire attache
    }

    /** Definition de monstre `i`, ou null. */
    public Alien alien(int i) {
        return aliens != null && i >= 0 && i < aliens.size() ? aliens.get(i) : null;
    }

    /** Table de tir du monstre `i`, ou null. */
    public Gun alienShootDef(int i) {
        return alienShootDefs != null && i >= 0 && i < alienShootDefs.size()
                ? alienShootDefs.get(i) : null;
    }

    /** Type de sol : degats au joueur qui s'y tient, et bruit de pas (+1). */
    public static final class Floor {
        public int index;
        public int damage;
        public int sfx;
    }

    /** Definition d'arme (ShootT, 8 octets). */
    public static final class Gun {
        public int index;
        public String name;
        public int bulletType;          // ShootT_BulType_w : projectile tire
        public int delay;               // ShootT_Delay_w : frames avant le tir suivant
        public int bulletCount;         // ShootT_BulCount_w : projectiles par tir (et munitions consommees)
        public int sfx;                 // ShootT_SFX_w
        public int gunObject;           // GLFT_GunObjects_l : objet « arme en main »
    }

    /** Definition de projectile (BulT). */
    public static final class Bullet {
        public int index;
        public String name;
        public int hitScan;             // BulT_IsHitScan_l : 1 = touche instantanement (fusil)
        public int gravity;             // BulT_Gravity_l : ajoute a la vitesse Y chaque frame
        public int lifetime;            // BulT_Lifetime_l : frames avant explosion (< 0 = infini)
        public int ammoInClip;
        public int bounceHoriz;         // BulT_BounceHoriz_l : rebondit sur les murs
        public int bounceVert;          // BulT_BounceVert_l : rebondit sur sol/plafond
        public int hitDamage;
        public int explosiveForce;      // ComputeBlast
        public int impactSFX;           // BulT_ImpactSFX_l : bruit d'impact (- 1 a l'usage)
        public int speed;               // BulT_Speed_l : decalage applique a sin/cos (vitesse)
        public int animFrames;          // BulT_AnimFrames_l : dernier pas de l'anim de vol
        public int popFrames;           // BulT_PopFrames_l : dernier pas de l'anim d'impact
        public int graphicType;         // 0 = bitmap, 1 = glare, 2+ = bitmap additif
        public int impactGraphicType;
        /** Anim de VOL : pas de 6 octets. Pour une balle l'octet 5 (« next ») est la LUMINOSITE. */
        public List<ObjectAnim.Step> animData;
        /** Anim d'IMPACT (BulT_PopData_vb), meme structure. */
        public List<ObjectAnim.Step> popData;
    }

    /** Definition de l'arme `i`, ou null. */
    public Gun gun(int i) {
        return guns != null && i >= 0 && i < guns.size() ? guns.get(i) : null;
    }

    /** Definition du projectile `i`, ou null. */
    public Bullet bullet(int i) {
        return bullets != null && i >= 0 && i < bullets.size() ? bullets.get(i) : null;
    }

    public static final class Named {
        public int index;
        public String name;
        public String file;
        public int height;              // wallTextures : hauteur de la texture
    }

    public String wallTexture(int i) {
        return name(wallTextures, i);
    }

    public String spriteSheet(int i) {
        return name(spriteSheets, i);
    }

    public String vectorModel(int i) {
        return name(vectorModels, i);
    }

    private static String name(List<Named> list, int i) {
        if (list == null || i < 0 || i >= list.size()) {
            return null;
        }
        String n = list.get(i).name;
        return (n == null || n.isBlank()) ? null : n;
    }
}
