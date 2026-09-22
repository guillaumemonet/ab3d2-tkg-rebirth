package ab3d2.rebirth.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Messages poussés par la simulation (ramassages, textes narratifs du niveau).
 *
 * <p>Le jeu a une file de 8 lignes qui défile d'une ligne toutes les 2 secondes et refuse un
 * doublon dans ce même délai ({@code MSG_SCROLL_PERIOD_MS}, {@code MSG_DEDUPLICATION_PERIOD_MS}
 * de c/Message.java). La simulation se contente d'empiler le texte ; l'affichage garde la cadence.
 */
public final class Messages {

    private final List<String> pending = new ArrayList<>();

    public void push(String text) {
        if (text != null && !text.isBlank()) {
            pending.add(text.trim());
        }
    }

    public List<String> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }
}
