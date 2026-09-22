package ab3d2.rebirth.extract;

import ab3d2.Defs;
import ab3d2.Mem;
import ab3d2.bss.TablesBss;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Extraction des BRUITAGES en WAV.
 *
 * <p>Les échantillons sont chargés tels quels par {@code Res_LoadSoundFx} : des fichiers
 * {@code .fib} = PCM 8 bits SIGNÉ mono, joués par Paula à la période 443 (hires.s:552), soit
 * {@code 3546895 / 443 = 8006 Hz}. {@code Aud_SampleList_vl} donne, après
 * {@code Res_PatchSoundFx}, un couple {début, fin} par effet.
 *
 * <p>On écrit {@code assets/sfx/NN.wav} (PCM 8 bits non signé, la convention WAV) et
 * {@code assets/sfx/sfx.json} qui donne le nom d'origine et la durée de chacun.
 */
public final class Sfx {

    /** Horloge PAL / période 443 (hires.s:552 « period »). */
    public static final int RATE = 3546895 / 443;

    private Sfx() {
    }

    public static void extract(Path outRoot) throws IOException {
        PortReader.bootWithAssets();                     // Res_LoadSoundFx + Res_PatchSoundFx
        Path dir = outRoot.resolve("sfx");
        Files.createDirectories(dir);
        int glf = PortReader.glf();

        List<Object> index = new ArrayList<>();
        int written = 0;
        for (int i = 0; i < Defs.NUM_SFX; i++) {
            int entry = TablesBss.Aud_SampleList_vl + i * 8;
            int start = Mem.l(entry);
            int end = Mem.l(entry + 4);
            String name = PortReader.fixedStr(glf + Defs.GLFT_SFXFilenames_l + i * 64, 64);
            int len = start != 0 && end > start ? end - start : 0;
            java.util.Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("index", i);
            e.put("name", name);
            e.put("samples", len);
            e.put("seconds", len / (float) RATE);
            index.add(e);
            if (len <= 0) {
                continue;
            }
            byte[] pcm = new byte[len];
            for (int k = 0; k < len; k++) {
                pcm[k] = (byte) (Mem.ub(start + k) ^ 0x80);   // signé -> non signé (WAV 8 bits)
            }
            Files.write(dir.resolve(String.format("%02d.wav", i)), wav(pcm, RATE));
            written++;
        }
        Files.writeString(dir.resolve("sfx.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(index));
        System.out.println("[sfx] " + written + " bruitages -> " + dir + " (" + RATE + " Hz, 8 bits)");
    }

    /** En-tête WAV canonique + données PCM 8 bits mono. */
    private static byte[] wav(byte[] pcm, int rate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt(36 + pcm.length);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);                                    // taille du bloc fmt
        b.putShort((short) 1);                           // PCM
        b.putShort((short) 1);                           // mono
        b.putInt(rate);
        b.putInt(rate);                                  // octets/seconde (8 bits mono)
        b.putShort((short) 1);                           // alignement
        b.putShort((short) 8);                           // bits
        b.put("data".getBytes());
        b.putInt(pcm.length);
        b.put(pcm);
        return b.array();
    }
}
