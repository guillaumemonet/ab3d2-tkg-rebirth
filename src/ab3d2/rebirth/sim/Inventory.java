package ab3d2.rebirth.sim;

import java.util.List;

/**
 * Inventaire du joueur : port de {@code Game_CheckInventoryLimits} et {@code Game_AddToInventory}
 * (c/GameC.java:46-125).
 *
 * <p>Deux tableaux de mots, indexés comme dans le jeu : 22 CONSOMMABLES (santé, carburant du
 * jetpack, puis une munition par type de projectile) et 12 ITEMS (armes, jetpack...). Un objet
 * ramassable porte les mêmes tableaux ({@code ammoGive} / {@code gunGive} de sa définition) ; les
 * plafonds viennent de {@code game_ModProps.gmp_MaxInventory} (santé 10000, carburant 250,
 * munitions 10000).
 */
public final class Inventory {

    public static final int NUM_CONSUMABLES = 22;
    public static final int NUM_ITEMS = 12;
    /** InvIT_JetPack_w : les boucles du jeu démarrent à ce décalage dans les items. */
    private static final int ITEM_START = 1;

    public final int[] consumables = new int[NUM_CONSUMABLES];
    public final int[] items = new int[NUM_ITEMS];
    private final int[] limits = new int[NUM_CONSUMABLES];

    public Inventory(List<Integer> maxInventory) {
        for (int i = 0; i < NUM_CONSUMABLES && maxInventory != null && i < maxInventory.size(); i++) {
            limits[i] = maxInventory.get(i);
        }
    }

    /**
     * Game_CheckInventoryLimits (c/GameC.java) : vrai si l'objet apporte au moins quelque chose.
     * En SOLO, tout item donné suffit ; un consommable ne compte que si le joueur n'est pas déjà
     * au plafond.
     *
     * <p>Et surtout, la DERNIÈRE ligne du C : {@code return givesAnything ? FALSE : TRUE} — un
     * objet qui ne donne RIEN DU TOUT est toujours ramassable. C'est ce qui fait marcher les
     * marqueurs narratifs du jeu (définition 0, sans nom, posés devant les portes scellées) :
     * on les « ramasse » et leur texte s'affiche.
     */
    public boolean canCollect(List<Integer> give, List<Integer> giveItems) {
        int givesAnything = 0;
        for (int n = ITEM_START; n < ITEM_START + NUM_ITEMS; n++) {
            if (at(giveItems, n) != 0) {
                return true;                           // solo : un item donné suffit
            }
            givesAnything |= at(giveItems, n);
        }
        for (int n = 0; n < NUM_CONSUMABLES; n++) {
            givesAnything += at(give, n);
            if (at(give, n) > 0 && consumables[n] < limits[n]) {
                return true;
            }
        }
        return givesAnything == 0;
    }

    /** Game_AddToInventory : items en OU logique, consommables ajoutés puis bornés au plafond. */
    public void add(List<Integer> give, List<Integer> giveItems) {
        for (int n = ITEM_START; n < ITEM_START + NUM_ITEMS; n++) {
            if (n < NUM_ITEMS) {
                items[n] |= at(giveItems, n);
            }
        }
        for (int n = 0; n < NUM_CONSUMABLES; n++) {
            consumables[n] = addSaturated(consumables[n], at(give, n), limits[n]);
        }
    }

    /** addSaturated (GameC) : somme bornée au plafond, sans dépasser. */
    private static int addSaturated(int value, int add, int limit) {
        int sum = value + add;
        return sum > limit ? limit : sum;
    }

    private static int at(List<Integer> l, int i) {
        return l != null && i >= 0 && i < l.size() ? l.get(i) : 0;
    }

    /** Santé courante (consommable 0). */
    public int health() {
        return consumables[0];
    }

    /**
     * InvCT_AmmoCounts_vw : les munitions occupent les consommables 2..21, une par type de
     * projectile (santé et carburant du jetpack occupent 0 et 1).
     */
    public int ammo(int bulletType) {
        int i = AMMO_START + bulletType;
        return i >= AMMO_START && i < NUM_CONSUMABLES ? consumables[i] : 0;
    }

    public void setAmmo(int bulletType, int value) {
        int i = AMMO_START + bulletType;
        if (i >= AMMO_START && i < NUM_CONSUMABLES) {
            consumables[i] = value;
        }
    }

    /** InvCT_AmmoCounts_vw. */
    private static final int AMMO_START = 2;

    /**
     * DEFAULTGAME (Controlloop.java:143) : nouvelle partie — 200 de santé, l'arme 0 et 20
     * munitions de SON type de projectile. C'est tout ce que le jeu donne au départ.
     */
    public void defaultGame(ab3d2.rebirth.GlfData glf) {
        java.util.Arrays.fill(consumables, 0);
        java.util.Arrays.fill(items, 0);
        consumables[0] = 200;                          // move.w #200,Plr_Health_w
        items[WEAPON_START] = 0xFF;                    // move.w #$ff,Plr_Weapons_vw
        ab3d2.rebirth.GlfData.Gun g = glf == null ? null : glf.gun(0);
        if (g != null) {
            setAmmo(g.bulletType, 20);                 // move.w #20,(Plr_AmmoCounts_vw,d0.w*2)
        }
    }

    /** InvIT_Weapons_vw : les armes occupent les items 2..11 (bouclier 0, jetpack 1). */
    public static final int WEAPON_START = 2;

    /** Recopie un inventaire (passage d'un niveau au suivant : on emporte tout). */
    public void copyFrom(Inventory other) {
        System.arraycopy(other.consumables, 0, consumables, 0, NUM_CONSUMABLES);
        System.arraycopy(other.items, 0, items, 0, NUM_ITEMS);
    }

    /** Vrai si le joueur possède l'arme `gun`. */
    public boolean hasGun(int gun) {
        int i = WEAPON_START + gun;
        return i >= 0 && i < NUM_ITEMS && items[i] != 0;
    }
}
