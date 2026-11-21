"""Refait les PLANCHES DE QUATRE CARTES du decor, en passant par texture-studio.

Une planche est l'image que `CartesPackees` sait lire :

    couleur (albedo)   |  normales (+ hauteur dans l'alpha)
    lueur (emissivite) |  brillance (speculaire)

Deux lots, qui ne se decoupent pas pareil :

- `sols`  : un fichier par texture, agrandi et cartographie tel quel ;
- `murs`  : ON TRAVAILLE SUR LA TEXTURE ENTIERE, PAS SUR LA SOUS-TUILE. Les murs du jeu ne
  sont pas textures avec un fichier chacun : ils lisent une bande
  [fromTile, fromTile+texW) d'une texture commune, avec repli horizontal quand la bande
  deborde. Agrandir et cartographier chaque bande separement reviendrait a inventer des
  bords la ou il n'y en a pas -- et certaines bandes font seize pixels de large. On
  agrandit donc la texture entiere, on en tire les cartes en mode raccordable, puis on
  decoupe les bandes dans l'agrandi avec exactement le repli du rasterizer
  (LevelBuilder.subTile).

L'AGRANDISSEUR ASSOMBRIT : Real-ESRGAN rend une image dont la moyenne descend de quelques
niveaux, jusqu'a douze sur STONEWALL. On repose donc canal par canal la moyenne de
l'original. La finesse vient de l'agrandissement, la teinte reste celle de 1996.

QUEL AGRANDISSEUR. texture-studio offre Real-ESRGAN (general, general-v3, anime, x2plus).
Mesure du 21 novembre 2026 sur trois sols, ecart moyen a l'original une fois l'agrandi
ramene a sa taille de depart : DAT x4 (par Automatic1111) 4,8 a 5,6 niveaux, Real-ESRGAN
general 9,3 a 13,0, general-v3 9,9 a 13,8 -- DAT est deux fois plus fidele. Les SOLS gardent
donc leur agrandissement DAT, fait quand Automatic1111 tenait le port 7860 ; ce script les
refait moins bien et n'est la pour eux qu'en depannage. Les MURS, eux, n'avaient aucun
agrandissement utilisable : Real-ESRGAN est un net gain sur rien. A refaire en DAT le jour
ou Automatic1111 retourne sur 7860 (texture-studio l'occupe).

NE PAS ENCHAINER DEUX FOIS : passer une planche deja assemblee dans la passe de cartes
produit une planche DANS une planche, dont les cartes exterieures sont calculees sur une
mosaique et non sur la matiere. Soixante-six fichiers de murs etaient dans cet etat le
21 novembre 2026 ; rien ne plantait, le relief etait simplement faux.

Pre-requis : texture-studio tourne sur http://localhost:7860 (D:/IA/texture-studio).
Usage      : python tools/planches.py murs [wall_2_0_64.png ...]
             python tools/planches.py sols [floor_03.png ...]
             sans nom de fichier, le lot entier est refait.
"""
import io
import json
import os
import sys
import time
import urllib.request

import numpy as np
from PIL import Image

STUDIO = "http://localhost:7860"
RACINE = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
TEXTURES = os.path.join(RACINE, "assets", "textures")
MURS = os.path.join(TEXTURES, "walls")
SOLS = os.path.join(TEXTURES, "floors")
ECHELLE = 4

# Les memes reglages que ceux retenus a l'oeil : un seuil de lueur a 0,55 garde les bandes
# lumineuses des murs, un seuil a 0,85 les effacait toutes.
CARTES = {
    "normal_strength": 2.5, "normal_format": "opengl", "smoothing": 1.0, "detail": 2.0,
    "specular_contrast": 1.5, "specular_level": 0.6, "glow_threshold": 0.55,
    "glow_saturation": 0.25, "glow_blur": 2.0, "glow_color": True, "ao_strength": 1.0,
    "tileable": True, "extras": True,
}


def api(chemin):
    with urllib.request.urlopen(STUDIO + chemin, timeout=60) as f:
        return json.load(f)


def lire(url):
    with urllib.request.urlopen(STUDIO + url, timeout=120) as f:
        return Image.open(io.BytesIO(f.read())).copy()


def poster(fichier, options):
    """POST multipart vers /api/jobs, sans dependance a requests."""
    g = chr(34)
    limite = "----planche%d" % time.time_ns()
    corps = b""
    for cle, val in (("type", "upscale"), ("options", json.dumps(options))):
        corps += ("--%s\r\nContent-Disposition: form-data; name=%s%s%s\r\n\r\n%s\r\n"
                  % (limite, g, cle, g, val)).encode()
    corps += ("--%s\r\nContent-Disposition: form-data; name=%sfiles%s; filename=%s%s%s\r\n"
              "Content-Type: image/png\r\n\r\n"
              % (limite, g, g, g, os.path.basename(fichier), g)).encode()
    corps += open(fichier, "rb").read() + ("\r\n--%s--\r\n" % limite).encode()
    r = urllib.request.Request(
        STUDIO + "/api/jobs", data=corps,
        headers={"Content-Type": "multipart/form-data; boundary=" + limite})
    with urllib.request.urlopen(r, timeout=120) as f:
        return json.load(f)[0]["id"]


def attendre():
    """La file de texture-studio n'a qu'un GPU : on attend qu'elle se vide."""
    while True:
        restants = [j for j in api("/api/jobs") if j["status"] in ("queued", "running")]
        if not restants:
            return
        print("   file : %d tache(s)" % len(restants), flush=True)
        time.sleep(10)


def recaler(gros, orig):
    """Repose la moyenne de l'original, canal par canal."""
    g = np.asarray(gros.convert("RGB")).astype(np.float32)
    petit = np.asarray(gros.convert("RGB").resize(orig.size, Image.BOX)).astype(np.float32)
    o = np.asarray(orig.convert("RGB")).astype(np.float32)
    for c in range(3):
        if petit[:, :, c].mean() > 1.0:
            g[:, :, c] *= o[:, :, c].mean() / petit[:, :, c].mean()
    return Image.fromarray(np.clip(g, 0, 255).astype(np.uint8))


def bande(im, de, large):
    """La sous-tuile [de, de+large), avec le repli horizontal du rasterizer."""
    if de == 0 and large == im.width:
        return im.copy()
    return Image.fromarray(np.asarray(im)[:, [(de + x) % im.width for x in range(large)]])


def cartographier(sources):
    """Agrandit et cartographie chaque image source. Rend {nom: (albedo, normales, lueur, brillance)}."""
    print("1. agrandissement x%d + cartes de %d texture(s)" % (ECHELLE, len(sources)))
    taches = {}
    for nom, chemin in sorted(sources.items()):
        if not os.path.isfile(chemin):
            print("   ABSENTE : %s" % chemin)
            continue
        taches[nom] = poster(chemin, {"scale": ECHELLE, "model": "general", "format": "png",
                                      "then": {"type": "maps", "options": CARTES}})
        print("   %-16s -> %s" % (nom, taches[nom]), flush=True)
    attendre()

    print("2. recuperation")
    jobs = api("/api/jobs")
    par_id = {j["id"]: j for j in jobs}
    out = {}
    for nom, tid in taches.items():
        enfant = next((j for j in jobs
                       if j.get("parent_id") == tid and j["type"] == "maps"), None)
        if not enfant or enfant["status"] != "done" or par_id[tid]["status"] != "done":
            print("   RATEE : %s" % nom)
            continue
        urls = {}
        for o in par_id[tid]["outputs"]:
            if o["kind"] == "image":
                urls["albedo"] = o["url"]
                break
        for o in enfant["outputs"]:
            for k in ("normal", "height", "glow", "specular"):
                if o["kind"] == "image" and o["path"].endswith("_%s.png" % k):
                    urls[k] = o["url"]
        if len(urls) != 5:
            print("   INCOMPLETE : %s (%s)" % (nom, sorted(urls)))
            continue
        orig = Image.open(sources[nom])
        alb = recaler(lire(urls["albedo"]), orig)
        if alb.size != (orig.width * ECHELLE, orig.height * ECHELLE):
            print("   ECHELLE INATTENDUE : %s %s" % (nom, alb.size))
            continue
        nrm = Image.merge("RGBA", (*lire(urls["normal"]).convert("RGB").split(),
                                   lire(urls["height"]).convert("L")))
        out[nom] = (alb, nrm, lire(urls["glow"]).convert("RGB"),
                    lire(urls["specular"]).convert("RGB"))
    return out


def assembler(quadrants, cible):
    """Les quatre quadrants (meme taille) dans une planche, ecrite en `cible`."""
    qw, qh = quadrants[0].size
    planche = Image.new("RGBA", (qw * 2, qh * 2))
    planche.paste(quadrants[0].convert("RGB"), (0, 0))
    planche.paste(quadrants[1], (qw, 0))
    planche.paste(quadrants[2].convert("RGB"), (0, qh))
    planche.paste(quadrants[3].convert("RGB"), (qw, qh))
    planche.save(cible)


def faire_murs(voulus):
    sub, sub4x = os.path.join(MURS, "sub"), os.path.join(MURS, "sub4x")
    noms = {w["index"]: w.get("name") for w in
            json.load(open(os.path.join(RACINE, "assets", "tables", "glf.json"),
                           encoding="utf-8"))["wallTextures"]}
    fichiers = voulus or sorted(f for f in os.listdir(sub) if f.endswith(".png"))
    besoin = sorted({noms.get(int(f[:-4].split("_")[1])) for f in fichiers} - {None, ""})
    cartes = cartographier({n: os.path.join(MURS, n + ".png") for n in besoin})

    print("3. decoupe et assemblage")
    os.makedirs(sub4x, exist_ok=True)
    ecrits = 0
    for f in fichiers:
        tex, de, large = (int(x) for x in f[:-4].split("_")[1:4])
        nom = noms.get(tex)
        if nom not in cartes:
            continue
        assembler([bande(im, de * ECHELLE, large * ECHELLE) for im in cartes[nom]],
                  os.path.join(sub4x, f))
        ecrits += 1
    print("fini : %d planches dans %s" % (ecrits, sub4x))


def faire_sols(voulus):
    sols4x = os.path.join(TEXTURES, "floors4x")
    # Les .idx.png sont les index de palette, le .lut.png la table de teinte, et
    # floortile_atlas.png une planche de contact que le jeu ne lit jamais.
    fichiers = voulus or sorted(f for f in os.listdir(SOLS)
                                if f.startswith("floor_") and f.endswith(".png")
                                and ".idx." not in f and ".lut." not in f)
    cartes = cartographier({f[:-4]: os.path.join(SOLS, f) for f in fichiers})

    print("3. assemblage")
    os.makedirs(sols4x, exist_ok=True)
    ecrits = 0
    for f in fichiers:
        if f[:-4] not in cartes:
            continue
        assembler(list(cartes[f[:-4]]), os.path.join(sols4x, f))
        ecrits += 1
    print("fini : %d planches dans %s" % (ecrits, sols4x))


if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in ("murs", "sols"):
        sys.exit(__doc__)
    (faire_murs if sys.argv[1] == "murs" else faire_sols)(sys.argv[2:])
