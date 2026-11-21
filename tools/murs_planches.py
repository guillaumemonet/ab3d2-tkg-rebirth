"""Refait les PLANCHES DE QUATRE CARTES des murs, en passant par texture-studio.

Une planche est l'image que `CartesPackees` sait lire :

    couleur (albedo)   |  normales (+ hauteur dans l'alpha)
    lueur (emissivite) |  brillance (speculaire)

ON TRAVAILLE SUR LA TEXTURE ENTIERE, PAS SUR LA SOUS-TUILE. Les murs du jeu ne sont pas
textures avec un fichier chacun : ils lisent une bande [fromTile, fromTile+texW) d'une
texture commune, avec repli horizontal quand la bande deborde. Agrandir et cartographier
chaque bande separement reviendrait a inventer des bords la ou il n'y en a pas -- et
certaines bandes font seize pixels de large. On agrandit donc la texture ENTIERE, on en
tire les cartes en mode raccordable, puis on decoupe les bandes dans l'agrandi avec
exactement le repli du rasterizer (LevelBuilder.subTile).

L'AGRANDISSEUR ASSOMBRIT : Real-ESRGAN rend une image dont la moyenne descend de quelques
niveaux, jusqu'a douze sur STONEWALL. On repose donc canal par canal la moyenne de
l'original. La finesse vient de l'agrandissement, la teinte reste celle de 1996.

NE PAS ENCHAINER DEUX FOIS : passer une planche deja assemblee dans la passe de cartes
produit une planche DANS une planche, dont les cartes exterieures sont calculees sur une
mosaique et non sur la matiere. Soixante-six fichiers etaient dans cet etat le
21 novembre 2026 ; rien ne plantait, le relief etait simplement faux.

Pre-requis : texture-studio tourne sur http://localhost:7860 (D:/IA/texture-studio).
Usage      : python tools/murs_planches.py [wall_2_0_64.png ...]
             sans argument, refait toutes les sous-tuiles de assets/textures/walls/sub.
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
MURS = os.path.join(RACINE, "assets", "textures", "walls")
SUB = os.path.join(MURS, "sub")
SUB4X = os.path.join(MURS, "sub4x")
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
    limite = "----planche%d" % time.time_ns()
    corps = b""
    for cle, val in (("type", "upscale"), ("options", json.dumps(options))):
        corps += ("--%s\r\nContent-Disposition: form-data; name=%s%s%s\r\n\r\n%s\r\n"
                  % (limite, chr(34), cle, chr(34), val)).encode()
    corps += ("--%s\r\nContent-Disposition: form-data; name=%sfiles%s; filename=%s%s%s\r\n"
              "Content-Type: image/png\r\n\r\n"
              % (limite, chr(34), chr(34), chr(34), os.path.basename(fichier), chr(34))).encode()
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


def main(voulus):
    table = json.load(open(os.path.join(RACINE, "assets", "tables", "glf.json"),
                           encoding="utf-8"))["wallTextures"]
    noms = {w["index"]: w.get("name") for w in table}
    fichiers = voulus or sorted(f for f in os.listdir(SUB) if f.endswith(".png"))
    besoin = sorted({noms.get(int(f[:-4].split("_")[1])) for f in fichiers} - {None, ""})

    print("1. agrandissement x%d + cartes de %d textures entieres" % (ECHELLE, len(besoin)))
    taches = {}
    for nom in besoin:
        src = os.path.join(MURS, nom + ".png")
        if not os.path.isfile(src):
            print("   ABSENTE : %s" % src)
            continue
        taches[nom] = poster(src, {"scale": ECHELLE, "model": "general", "format": "png",
                                   "then": {"type": "maps", "options": CARTES}})
        print("   %-16s -> %s" % (nom, taches[nom]), flush=True)
    attendre()

    print("2. recuperation")
    jobs = api("/api/jobs")
    par_id = {j["id"]: j for j in jobs}
    fini = {}
    for nom, tid in taches.items():
        enfant = next((j for j in jobs
                       if j.get("parent_id") == tid and j["type"] == "maps"), None)
        if not enfant or enfant["status"] != "done" or par_id[tid]["status"] != "done":
            print("   RATEE : %s" % nom)
            continue
        d = {}
        for o in par_id[tid]["outputs"]:
            if o["kind"] == "image":
                d["albedo"] = o["url"]
                break
        for o in enfant["outputs"]:
            for k in ("normal", "height", "glow", "specular"):
                if o["kind"] == "image" and o["path"].endswith("_%s.png" % k):
                    d[k] = o["url"]
        if len(d) == 5:
            fini[nom] = d
        else:
            print("   INCOMPLETE : %s (%s)" % (nom, sorted(d)))

    print("3. decoupe et assemblage")
    cache = {}
    ecrits = 0
    os.makedirs(SUB4X, exist_ok=True)
    for f in fichiers:
        tex, de, large = (int(x) for x in f[:-4].split("_")[1:4])
        nom = noms.get(tex)
        if nom not in fini:
            continue
        if nom not in cache:
            d = fini[nom]
            orig = Image.open(os.path.join(MURS, nom + ".png"))
            alb = recaler(lire(d["albedo"]), orig)
            if alb.size != (orig.width * ECHELLE, orig.height * ECHELLE):
                print("   ECHELLE INATTENDUE : %s %s" % (nom, alb.size))
                continue
            nrm = Image.merge("RGBA", (*lire(d["normal"]).convert("RGB").split(),
                                       lire(d["height"]).convert("L")))
            cache[nom] = (alb, nrm, lire(d["glow"]).convert("RGB"),
                          lire(d["specular"]).convert("RGB"))
        alb, nrm, lue, bri = cache[nom]
        q = [bande(im, de * ECHELLE, large * ECHELLE) for im in (alb, nrm, lue, bri)]
        qw, qh = q[0].size
        planche = Image.new("RGBA", (qw * 2, qh * 2))
        planche.paste(q[0].convert("RGB"), (0, 0))
        planche.paste(q[1], (qw, 0))
        planche.paste(q[2], (0, qh))
        planche.paste(q[3], (qw, qh))
        planche.save(os.path.join(SUB4X, f))
        ecrits += 1
    print("fini : %d planches dans %s" % (ecrits, SUB4X))


if __name__ == "__main__":
    main(sys.argv[1:])
