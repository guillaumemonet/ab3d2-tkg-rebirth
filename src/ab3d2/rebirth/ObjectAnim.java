package ab3d2.rebirth;

import java.util.List;

/**
 * Animation des objets : port de {@code animObj} / {@code DEFANIMOBJ}
 * (Newaliencontrol.java:683-742).
 *
 * <p>Chaque type d'objet a un SCRIPT de 20 pas de 6 octets. Un pas donne le graphique à afficher
 * ({@code gfx} = feuille de sprite ou modèle vectoriel, {@code frame}) et l'index du pas SUIVANT :
 * la durée n'est pas un compteur, elle est encodée par répétition, et un pas qui pointe sur
 * lui-même fige l'objet. Le pas courant est {@code EntT_Timer1_w} de l'entité.
 *
 * <p>Le jeu n'avance l'animation que pour les objets « inquiétés » (visibles, cf.
 * {@code markVisibleAndWorry}) : ici on les anime tous, faute de calcul de visibilité — seule la
 * PHASE des animations peut donc différer, pas leur contenu.
 */
public final class ObjectAnim {

    /** Un pas du script (6 octets). */
    public static final class Step {
        public int gfx;      // feuille de sprite, ou index de modèle vectoriel
        public int frame;
        public int word2;    // bitmap : donnée de dessin ; vecteur : incrément d'angle
        public int delta;    // ajouté (×2) à l'offset 4 de l'entité (hauteur)
        public int next;     // pas suivant
    }

    private List<Step> script;
    private int current;

    private ObjectAnim(List<Step> script, int start) {
        this.script = script;
        this.current = start;
    }

    /**
     * Bascule sur un autre script (DEFANIMOBJ <-> ACTANIMOBJ) en repartant du pas 0, comme le
     * jeu qui remet {@code EntT_Timer1_w} a zero au changement d'etat.
     */
    public void retarget(List<Step> other) {
        if (other == null || other.isEmpty() || other == script) {
            return;
        }
        script = other;
        current = 0;
    }

    /**
     * Crée l'animation d'un objet, ou null s'il n'en a pas (script absent, ou pas 0 qui boucle
     * sur lui-même = objet figé).
     */
    public static ObjectAnim of(List<Step> script) {
        if (script == null || script.isEmpty()) {
            return null;
        }
        Step first = script.get(0);
        if (first.next == 0) {
            return null;                               // pas unique : rien à animer
        }
        return new ObjectAnim(script, 0);
    }

    /**
     * Crée l'animation même si le script est figé : nécessaire pour les objets ACTIVABLES, dont
     * le script actif peut s'animer alors que celui au repos est statique.
     */
    public static ObjectAnim always(List<Step> script) {
        return script == null || script.isEmpty() ? null : new ObjectAnim(script, 0);
    }

    /** Force le pas courant (EntT_Timer1_w : le jeu y ecrit 1 quand l'arme tire). */
    public void setStep(int i) {
        if (i >= 0 && i < script.size()) {
            current = i;
        }
    }

    /** Pas courant. */
    public Step step() {
        return script.get(current);
    }

    /** Avance d'un pas (une frame de jeu). Renvoie true si le graphique affiché change. */
    public boolean advance() {
        Step before = step();
        int next = before.next;
        if (next < 0 || next >= script.size()) {
            next = 0;
        }
        current = next;
        Step after = step();
        return after.gfx != before.gfx || after.frame != before.frame;
    }
}
