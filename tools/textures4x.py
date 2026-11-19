"""Agrandit toutes les textures de decor par quatre, via l'API locale de Stable Diffusion.

Agrandisseur SEUL (DAT x4) : aucune invention. On ne passe pas par img2img parce que le
relief, la hauteur et la brillance sont desormais TIRES de la clarte de la texture -- un
reflet cuit dans la couleur deviendrait une fausse bosse.

Ecrit a cote des originaux, dans un dossier parallele que le jeu prefere quand on le lui
demande (-Phires). Les originaux ne bougent pas.
"""
import os
import base64
import json
import sys
import time
import urllib.request

API = "http://127.0.0.1:7860"
JEU = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "assets", "textures") + os.sep
LOTS = [("walls/sub", "walls/sub4x"), ("floors", "floors4x")]


def agrandir(p):
    charge = {
        "image": base64.b64encode(open(p, "rb").read()).decode(),
        "upscaling_resize": 4,
        "upscaler_1": os.environ.get("UPSCALER", "DAT x4"),
        "resize_mode": 0,
    }
    d = json.dumps(charge).encode()
    r = urllib.request.Request(API + "/sdapi/v1/extra-single-image", data=d,
                               headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(r, timeout=900) as f:
        return json.load(f)["image"]


n = 0
rates = 0
t0 = time.time()
for src, dst in LOTS:
    srcdir = JEU + src
    dstdir = JEU + dst
    os.makedirs(dstdir, exist_ok=True)
    for f in sorted(os.listdir(srcdir)):
        if not f.endswith(".png") or ".idx." in f or ".lut." in f:
            continue
        cible = os.path.join(dstdir, f)
        if os.path.isfile(cible):
            continue                                  # deja fait : on peut relancer
        try:
            b64 = agrandir(os.path.join(srcdir, f))
            open(cible, "wb").write(base64.b64decode(b64))
            n += 1
            print("%3d  %s/%s" % (n, dst, f), flush=True)
        except Exception as e:
            rates += 1
            print("RATE %s/%s : %s" % (src, f, e), flush=True)
print("fini : %d images en %.0f s, %d rates" % (n, time.time() - t0, rates))
