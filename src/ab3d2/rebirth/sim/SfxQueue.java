package ab3d2.rebirth.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * File des bruitages demandés par la SIMULATION pendant une frame.
 *
 * <p>Le jeu d'origine pose {@code Aud_SampleNum_w}, la position ({@code Aud_NoiseX/Z_w}) et le
 * volume, puis appelle {@code MakeSomeNoise} qui choisit une voie Paula. Ici la simulation se
 * contente d'empiler l'ÉVÉNEMENT — même numéro d'effet, même moment — et la couche de rendu le
 * joue avec le moteur audio. La simulation reste ainsi sans dépendance au son.
 */
public final class SfxQueue {

    /** Un bruitage demandé : numéro d'effet, et position monde s'il en a une. */
    public static final class Event {
        public final int num;
        public final boolean positional;
        public final int x, z, height;

        Event(int num, boolean positional, int x, int z, int height) {
            this.num = num;
            this.positional = positional;
            this.x = x;
            this.z = z;
            this.height = height;
        }
    }

    private final List<Event> events = new ArrayList<>();

    /** Bruit sans position (arme du joueur, douleur, interface). */
    public void play(int num) {
        if (num >= 0) {
            events.add(new Event(num, false, 0, 0, 0));
        }
    }

    /** Bruit situé dans le monde (unités d'origine ; {@code height} = 4(a0)). */
    public void playAt(int num, int x, int z, int height) {
        if (num >= 0) {
            events.add(new Event(num, true, x, z, height));
        }
    }

    /** Récupère et vide la file (appelé une fois par frame par le rendu). */
    public List<Event> drain() {
        if (events.isEmpty()) {
            return List.of();
        }
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }
}
