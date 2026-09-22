package ab3d2.rebirth.menu;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import ab3d2.rebirth.sim.Inventory;

/**
 * Les POSITIONS sauvegardees. Cinq emplacements, comme le jeu ({@code game_SavePosition} /
 * {@code game_LoadPosition}, controlloop.s) : chacun retient le NIVEAU atteint et l'inventaire
 * complet du joueur — sante, munitions, bouclier, armes.
 *
 * <p>Le jeu ecrivait un bloc binaire de 17 longs par emplacement, a plat, a l'adresse de
 * {@code Plr_Health_w}. Ici c'est un JSON : le contenu est le meme, le format est celui du
 * remake, qui n'a pas de raison de figer une disposition memoire 68k dans un fichier.
 */
public final class SaveGame {

    /** {@code SAVE_SLOTS} : le jeu affiche cinq emplacements. */
    public static final int SLOTS = 5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Un emplacement. {@code level} &lt; 0 = vide. */
    public static final class Slot {
        public int level = -1;
        public List<Integer> consumables;
        public List<Integer> items;

        public boolean used() {
            return level >= 0;
        }
    }

    public List<Slot> slots = new ArrayList<>();

    private SaveGame() {
    }

    private static Path file() {
        String prop = System.getProperty("rebirth.saves");
        if (prop != null && !prop.isBlank()) {
            return Paths.get(prop);
        }
        return ab3d2.rebirth.AppDirs.runFile("savegames.json");
    }

    /** Lit le fichier ; un fichier absent ou illisible donne cinq emplacements vides. */
    public static SaveGame load() {
        SaveGame sg = null;
        Path p = file();
        if (Files.isRegularFile(p)) {
            try (var r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                sg = GSON.fromJson(r, SaveGame.class);
            } catch (IOException | RuntimeException e) {
                System.err.println("[Save] " + p + " illisible (" + e.getMessage() + ")");
            }
        }
        if (sg == null) {
            sg = new SaveGame();
        }
        if (sg.slots == null) {
            sg.slots = new ArrayList<>();
        }
        while (sg.slots.size() < SLOTS) {
            sg.slots.add(new Slot());
        }
        return sg;
    }

    public void save() {
        Path p = file();
        try {
            Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Ecriture " + p, e);
        }
    }

    /** Ecrit l'etat courant dans l'emplacement {@code n} (0..4) et enregistre le fichier. */
    public void store(int n, int level, Inventory inv) {
        Slot s = slots.get(n);
        s.level = level;
        s.consumables = toList(inv.consumables);
        s.items = toList(inv.items);
        save();
    }

    /**
     * Recopie l'emplacement {@code n} dans {@code inv}. Renvoie le niveau, ou -1 si
     * l'emplacement est vide (l'inventaire n'est alors pas touche).
     */
    public int restore(int n, Inventory inv) {
        Slot s = slots.get(n);
        if (!s.used()) {
            return -1;
        }
        fill(inv.consumables, s.consumables);
        fill(inv.items, s.items);
        return s.level;
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> l = new ArrayList<>(a.length);
        for (int v : a) {
            l.add(v);
        }
        return l;
    }

    private static void fill(int[] dst, List<Integer> src) {
        if (src == null) {
            return;
        }
        for (int i = 0; i < dst.length && i < src.size(); i++) {
            dst[i] = src.get(i) == null ? 0 : src.get(i);
        }
    }
}
