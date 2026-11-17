"""Taille d'un volume dans les quatre vues d'un monstre, et rendu a 45 degres pour juger."""
import os
import json
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                    "..", "..", "assets") + os.sep
OUT = os.path.dirname(os.path.abspath(__file__)) + os.sep

glf = json.load(open(ROOT + "glf.json"))
sheets = {s["index"]: s["name"] for s in glf["spriteSheets"]}


def vue(alien, v, pas):
    """L'image (gfx, frame, miroir) de la vue v au pas donne."""
    an = glf["aliens"][alien]["anims"][v * 2]
    f = an[min(pas, len(an) - 1)]
    n = f["frame"]
    miroir = n <= 0
    idx = -n if miroir else n
    nom = sheets.get(f["gfx"])
    if nom is None:
        return None
    p = ROOT + "textures/objects/%s/frame_%02d.png" % (nom, idx - 1)
    if not os.path.isfile(p):
        return None
    im = Image.open(p).convert("RGBA")
    if miroir:
        im = im.transpose(Image.FLIP_LEFT_RIGHT)
    return im


def masque(im):
    px = im.load()
    w, h = im.size
    return [[px[x, y][3] >= 128 for x in range(w)] for y in range(h)]


def couleur(im):
    px = im.load()
    w, h = im.size
    return [[px[x, y][:3] for x in range(w)] for y in range(h)]


ALIEN = int(os.environ.get("ALIEN", "0"))
PAS = int(os.environ.get("PAS", "0"))
vues = [vue(ALIEN, v, PAS) for v in range(4)]
if any(v is None for v in vues):
    raise SystemExit("images manquantes : %s" % [v is None for v in vues])
w, h = vues[0].size
print("alien", ALIEN, glf["aliens"][ALIEN]["name"], "pas", PAS, "images", w, "x", h)
m = [masque(v) for v in vues]
c = [couleur(v) for v in vues]
for i, mi in enumerate(m):
    n = sum(sum(1 for b in r if b) for r in mi)
    print("  vue %d : %d pixels opaques" % (i, n))

# --- distance au bord, dans la vue de face -----------------------------------------------
def distance(mask):
    """Chanfrein en deux passes : la distance de chaque pixel plein au vide le plus proche."""
    INF = 1e9
    d = [[0.0 if not mask[y][x] else INF for x in range(w)] for y in range(h)]
    for y in range(h):
        for x in range(w):
            if d[y][x] == 0:
                continue
            best = d[y][x]
            if y > 0:
                best = min(best, d[y - 1][x] + 1, d[y - 1][x - 1] + 1.414 if x > 0 else INF)
            if x > 0:
                best = min(best, d[y][x - 1] + 1)
            d[y][x] = best
    for y in range(h - 1, -1, -1):
        for x in range(w - 1, -1, -1):
            if d[y][x] == 0:
                continue
            best = d[y][x]
            if y < h - 1:
                best = min(best, d[y + 1][x] + 1, d[y + 1][x + 1] + 1.414 if x < w - 1 else INF)
            if x < w - 1:
                best = min(best, d[y][x + 1] + 1)
            d[y][x] = best
    return d


dface = distance(m[0])
dcote = distance(m[1])

# --- la taille -------------------------------------------------------------------------
# front  : (x, y)            dos : (w-1-x, y)
# droite : (z, y)          gauche : (w-1-z, y)
#
# L'intersection seule donne une section RECTANGULAIRE : un corps vu de face large et vu de
# cote epais devient une brique. On l'arrondit en liant la profondeur d'un point a sa
# DISTANCE AU BORD de la silhouette -- une patte, mince de face, reste mince en profondeur ;
# un torse, loin de tout bord, garde toute son epaisseur.
# UNE SEULE VUE ? On ne peut rien tailler : la face et le cote etant la meme image, leur
# intersection est un prisme a section carree. On REVOLUTIONNE alors la silhouette autour de
# l'axe vertical -- ce qui est juste pour une creature a symetrie de revolution, et c'est
# exactement le cas de celles que le jeu ne montre que d'un bord.
identiques = m[0] == m[1]
print("  vues distinctes :", "non (revolution)" if identiques else "oui (taille)")
ROND = float(os.environ.get("ROND", "1.3"))
plein = [[[False] * w for _ in range(w)] for _ in range(h)]   # [y][z][x]
n = 0
if identiques:
    cx = (w - 1) / 2.0
    for y in range(h):
        for x in range(w):
            for z in range(w):
                # le point tourne autour de l'axe : son rayon decide, pas sa position
                r = ((x - cx) ** 2 + (z - cx) ** 2) ** 0.5
                xx = int(round(cx + r))
                if xx < w and m[0][y][xx]:
                    plein[y][z][x] = True
                    n += 1
    print("  volume : %d voxels sur %d" % (n, w * w * h))
for y in range(h):
    if identiques:
        break
    for x in range(w):
        if not (m[0][y][x] and m[2][y][w - 1 - x]):
            continue
        ep = ROND * dface[y][x]                       # demi-epaisseur permise a ce point
        for z in range(w):
            if not (m[1][y][z] and m[3][y][w - 1 - z]):
                continue
            if dcote[y][z] > ep + 0.5:
                continue                              # trop profond pour un point si mince
            plein[y][z][x] = True
            n += 1
print("  volume : %d voxels sur %d" % (n, w * w * h))

# --- LISSAGE : on fond les marches d'escalier -------------------------------------------
# Un volume taille dans des masques a des coins vifs et des pixels isoles. Un vote de
# voisinage (reste plein si assez de voisins le sont) arrondit le tout sans rien perdre de
# la silhouette.
LISSE = int(os.environ.get("LISSE", "1"))
for _ in range(LISSE):
    ancien = [[row[:] for row in couche] for couche in plein]
    for y in range(h):
        for z in range(w):
            for x in range(w):
                v = 0
                for dy in (-1, 0, 1):
                    for dz in (-1, 0, 1):
                        for dx in (-1, 0, 1):
                            Y, Z, X = y + dy, z + dz, x + dx
                            if 0 <= X < w and 0 <= Y < h and 0 <= Z < w and ancien[Y][Z][X]:
                                v += 1
                plein[y][z][x] = v >= 14                 # 14 sur 27 : la majorite
    n = sum(1 for y in range(h) for z in range(w) for x in range(w) if plein[y][z][x])
    print("  apres lissage : %d voxels" % n)

# --- rendu orthographique a 45 degres, ECLAIRE ------------------------------------------
# La normale d'un voxel de surface est la direction du vide : on la prend sur son voisinage.
def normale(x, y, z):
    nx = ny = nz = 0.0
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dx == dy == dz == 0:
                    continue
                X, Y, Z = x + dx, y + dy, z + dz
                vide = not (0 <= X < w and 0 <= Y < h and 0 <= Z < w and plein[Y][Z][X])
                if vide:
                    nx += dx
                    ny += dy
                    nz += dz
    n = (nx * nx + ny * ny + nz * nz) ** 0.5
    return (nx / n, ny / n, nz / n) if n > 0 else (0.0, 0.0, 1.0)


import math
LARGE = int(w * 1.7)
img = Image.new("RGB", (LARGE, h), (14, 14, 18))
px = img.load()
prof = [[1e9] * LARGE for _ in range(h)]
best = [[None] * LARGE for _ in range(h)]
A = math.radians(40)
vx, vz = math.sin(A), math.cos(A)              # direction de vue dans le plan XZ
for y in range(h):
    for z in range(w):
        for x in range(w):
            if not plein[y][z][x]:
                continue
            u = int((x * math.cos(A) - z * math.sin(A)) + LARGE / 2)
            d = x * vx + z * vz
            if 0 <= u < LARGE and d < prof[y][u]:
                prof[y][u] = d
                best[y][u] = (x, z)
# lumiere venue d'en haut a gauche et un peu vers nous
LX, LY, LZ = -0.5, 0.75, 0.43
for y in range(h):
    for u in range(LARGE):
        if best[y][u] is None:
            continue
        x, z = best[y][u]
        nx, ny, nz = normale(x, y, z)
        nl = max(0.0, nx * LX + ny * LY + nz * LZ)
        col = c[0][y][x] if x >= z else c[1][y][z]
        f = 0.35 + 0.75 * nl
        px[u, y] = tuple(min(255, int(v * f)) for v in col)
img.resize((LARGE * 5, h * 5), Image.NEAREST).save(OUT + "carve_%d_%d.png" % (ALIEN, PAS))
cote = Image.new("RGB", (w * 4, h), (14, 14, 18))
for i in range(4):
    cote.paste(vues[i].convert("RGB"), (i * w, 0))
cote.resize((w * 16, h * 4), Image.NEAREST).save(OUT + "vues_%d_%d.png" % (ALIEN, PAS))
print("  ecrit carve_%d_%d.png" % (ALIEN, PAS))
