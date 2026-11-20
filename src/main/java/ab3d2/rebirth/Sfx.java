package ab3d2.rebirth;

import com.jme3.asset.AssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.audio.AudioData;
import com.jme3.audio.AudioNode;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;

import java.util.HashMap;
import java.util.Map;

/**
 * Bruitages : lecture des WAV extraits ({@code assets/sfx/NN.wav}, PCM 8 bits 8006 Hz) par le
 * moteur audio de jME, en 3D quand le son a une position dans le monde.
 *
 * <p>Le jeu d'origine mixe lui-même quatre voies Paula : il calcule un volume et une répartition
 * gauche/droite à partir de la position du bruit relative au joueur ({@code MakeSomeNoise}), et
 * limite le nombre de voix en gardant la plus « importante ». Ici on laisse OpenAL faire
 * l'atténuation et la spatialisation — même information, moyens modernes — mais on garde les
 * DÉCLENCHEURS d'origine : c'est le code porté qui dit quel numéro d'effet jouer et quand.
 */
public final class Sfx {

    /** Portée d'un bruit, en unités jME : au-delà, il est inaudible (cf. Aud_NoiseVol_w). */
    private static final float RANGE = 40f;

    private final AssetManager assetManager;
    private final Node root;
    private final Map<Integer, Boolean> missing = new HashMap<>();
    private boolean enabled = true;

    public Sfx(AssetManager assetManager, Node root) {
        this.assetManager = assetManager;
        this.root = root;
        // Les WAV vivent dans assets/ (hors classpath jME) : on ajoute ce dossier comme locator.
        assetManager.registerLocator(Assets.root().toString(), FileLocator.class);
    }

    public void setEnabled(boolean on) {
        enabled = on;
    }

    /** Joue l'effet `num` sans position (interface, douleur du joueur...). */
    public void play(int num) {
        play(num, null, 1f);
    }

    /** Joue l'effet `num` à une position du monde jME. */
    public void playAt(int num, Vector3f pos) {
        play(num, pos, 1f);
    }

    /** Joue l'effet `num` à la position MONDE du jeu (x/z en unités Amiga, y = hauteur de dessin). */
    public void playAtWorld(int num, int x, int z, int heightWord) {
        playAt(num, new Vector3f(x / LevelBuilder.XZ_SCALE, -heightWord / 64f,
                -z / LevelBuilder.XZ_SCALE));
    }

    private static String pathOf(int num) {
        return String.format("sfx/%02d.wav", num);
    }

    /** Position de l'auditeur (la camera), pour ignorer ce qui est trop loin pour s'entendre. */
    public void setListener(Vector3f p) {
        listener.set(p);
    }

    private final Vector3f listener = new Vector3f();
    /** Le jeu d'origine n'a que quatre voies de bruitage : on borne aussi le nombre de voix. */
    private static final int MAX_VOICES = 12;

    private void play(int num, Vector3f pos, float volume) {
        if (!enabled || num < 0 || Boolean.TRUE.equals(missing.get(num))) {
            return;
        }
        if (pos != null && pos.distance(listener) > RANGE) {
            return;                                    // hors de portee : Aud_NoiseVol_w tomberait a 0
        }
        if (playing.size() >= MAX_VOICES) {
            return;                                    // toutes les voies sont prises
        }
        if (!Assets.exists(pathOf(num))) {
            missing.put(num, true);
            return;
        }
        AudioNode node = new AudioNode(assetManager, pathOf(num), AudioData.DataType.Buffer);
        node.setVolume(volume * soundVolume);
        node.setLooping(false);
        if (pos == null) {
            node.setPositional(false);
        } else {
            node.setPositional(true);
            node.setLocalTranslation(pos);
            node.setRefDistance(4f);
            node.setMaxDistance(RANGE);
        }
        root.attachChild(node);
        node.play();
        playing.add(node);
        if (System.getProperty("rebirth.sfxLog") != null) {
            System.out.printf("[son] %d %s%n", num, pos == null ? "(tete)" : pos.toString());
        }
    }

    private final java.util.List<AudioNode> playing = new java.util.ArrayList<>();

    /** À appeler une fois par frame : détache les sons terminés. */
    public void update() {
        for (int i = playing.size() - 1; i >= 0; i--) {
            AudioNode n = playing.get(i);
            if (n.getStatus() != com.jme3.audio.AudioSource.Status.Playing) {
                n.removeFromParent();
                playing.remove(i);
            }
        }
    }

    /** Coupe tout (changement de niveau). */
    // ------------------------------------------------------------------ musique

    private AudioNode music;
    private String musicPath;
    /** Volumes de SOUND OPTIONS, de 0 a 1 ; ils multiplient les gains ci-dessous. */
    private float musicVolume = 1f;
    private float soundVolume = 1f;
    /** Le mixage Paula sort tres bas (crete ~4096/32767) : on remonte au niveau des bruitages. */
    private static final float MUSIC_GAIN = 4f;

    /**
     * Lance une musique en boucle (ou la laisse jouer si c'est deja la meme).
     * {@code null} arrete la musique.
     *
     * @param name nom du fichier dans {@code assets/music}, sans extension
     * @param loop faux pour les jingles de fin
     */
    public void music(String name, boolean loop) {
        String path = name == null ? null : "music/" + name + ".wav";
        if (java.util.Objects.equals(path, musicPath)) {
            return;
        }
        if (music != null) {
            music.stop();
            music.removeFromParent();
            music = null;
        }
        musicPath = path;
        if (path == null || !enabled || !Assets.exists(path)) {
            return;
        }
        // Flux : ces fichiers font plusieurs megaoctets, inutile de les charger en memoire.
        music = new AudioNode(assetManager, path, AudioData.DataType.Stream);
        music.setPositional(false);
        music.setLooping(loop);
        music.setVolume(MUSIC_GAIN * musicVolume);
        root.attachChild(music);
        music.play();
    }

    /** Regle les deux volumes (SOUND OPTIONS) ; la musique en cours suit immediatement. */
    public void setVolumes(float musicGain, float soundGain) {
        musicVolume = Math.max(0f, Math.min(1f, musicGain));
        soundVolume = Math.max(0f, Math.min(1f, soundGain));
        if (music != null) {
            music.setVolume(MUSIC_GAIN * musicVolume);
        }
    }

    public void stopAll() {
        music(null, false);
        for (AudioNode n : playing) {
            n.stop();
            n.removeFromParent();
        }
        playing.clear();
    }
}
