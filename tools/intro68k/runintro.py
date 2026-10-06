"""Fait tourner le moteur de rendu de l'intro et sort ses images.

On ne TRANSCRIT pas le moteur, on l'EXECUTE : transcrire a la main une dizaine de routines
de generation de tables serait fragile, executer le code d'origine ne l'est pas. L'interpreteur
68000 est dans m68k.py.

Ce qui n'est pas execute : la prise en main du materiel, la liste Copper et l'affichage, qui
n'ont aucun sens hors d'un Amiga. On appelle les memes routines que le corps ($2A00), dans le
meme ordre, en sautant celles-la. Deux choses doivent alors etre fournies a la main, parce
qu'elles viennent du systeme et non du programme :

  - $15DF bit 0 : le cadre de demarrage le met quand son interruption de balayage est en place.
    Sans lui, $2EC2 rend la main aussitot et rien ne bouge.
  - $2EC2 lui-meme : c'est l'interruption qui fait avancer le temps. Ici on l'appelle une fois
    par image, ce que fait la machine.

Prealable : `gradle -p rebirth intro`, qui depose hunk0.bin et hunk2.bin dans assets/intro/.
Ces hunks sont relocalises a i << 28 : une adresse absolue dit alors d'elle-meme dans quel
hunk elle tombe. L'interpreteur compte la-dessus.

    python3 runintro.py [nb_images]
"""
import sys
import time

from m68k import CPU, Mem

HUNK = [0x00000000, 0x10000000, 0x20000000, 0x30000000]
STACK = 0x40000000
TAILLES = [58360, 1469952, 9912, 321332]

# Les routines du corps, dans l'ordre ou $2A00 les appelle.
INIT = [
    (0x2BCC, None, "tables du moteur (hauteurs, normales, parcours, couleurs)"),
    (0x320E, None, "palette -> deux listes Copper"),
    (0x316A, None, "logos -> plans de bits"),
    (0x49AA, ('a0', 1, 0), "masques sur le debut du hunk 1"),
    (0x32EA, ('a0', 3, 0x8340, 'a1', 1, 0x147A00), "tampons d'ecran"),
]


ASSETS = '../../assets/intro'       # ce que depose `gradle -p rebirth intro`


def boot(dossier=ASSETS):
    mem = Mem()
    donnees = {}
    for i in (0, 2):
        with open('%s/hunk%d.bin' % (dossier, i), 'rb') as f:
            donnees[i] = bytearray(f.read())
    for i, taille in enumerate(TAILLES):
        b = donnees.get(i, bytearray())
        b += bytes(taille + 0x1000 - len(b))      # marge : la machine a une memoire continue
        mem.add(HUNK[i], b)
    mem.add(STACK, bytearray(0x10000))
    cpu = CPU(mem, HUNK[0], bytes(donnees[0]))
    cpu.a[7] = STACK + 0xF000
    return cpu, mem


def init(cpu, mem, bavard=True):
    for addr, args, quoi in INIT:
        if args:
            for k in range(0, len(args), 3):
                reg, hunk, off = args[k], args[k + 1], args[k + 2]
                cpu.a[int(reg[1])] = HUNK[hunk] + off
        n0 = cpu.steps
        cpu.call(addr)
        if bavard:
            print("  $%04X  %-52s %10d instructions" % (addr, quoi, cpu.steps - n0))
    mem.wb(0x15DF, 1)                             # l'interruption de balayage est en place


def palette(mem):
    """La palette d'AFFICHAGE : 128 couleurs, les quatre bancs de 0x6F78.

    Ne pas confondre avec la rampe de 256 couleurs en 0x6B78 : celle-la est la palette SOURCE
    du moteur, que $2C1A etend vers hunk1+0x030000 pour que la boucle de rendu y pioche. Ce que
    le rendu ECRIT, ce sont des index de l'ecran, et l'ecran est decrit par le gabarit Copper :
    BPLCON0 = $7201 en 0x44CA, soit SEPT plans de bits. Donc 128 couleurs, et le bit 7 de
    l'octet de pixel n'est pas affiche.

    Ces 128 couleurs sont les quatre bancs de 32 que $320E envoie au Copper, un banc par passe
    (addi.l #$2000,d6 : le champ BANK de BPLCON3). Chaque couleur part en DEUX ecritures, poids
    forts puis poids faibles, ce qui donne les 8 bits par canal de l'AGA.

    On prend ici le fondu a fond : le facteur de $32E6/$32E8 vaut 0 au demarrage et c'est le
    sequenceur qui le monte, or on ne le fait pas tourner."""
    h0, _ = mem._find(0)
    out = []
    for i in range(128):
        a = 0x6F78 + i * 4
        out += [h0[a + 1], h0[a + 2], h0[a + 3]]
    return out + out                      # bit 7 non affiche : on replie 128..255


def normales(mem, chemin):
    """La carte de normales : 512 x 512 mots, un octet de pente par axe.

    C'est la preuve que toute la chaine est juste : le titre y est grave EN DIAGONALE,
    comme le pas de 0x201 de $310C le prevoit."""
    from PIL import Image
    b, o = mem._find(HUNK[1])
    im = Image.new('RGB', (512, 512))
    px = im.load()
    for y in range(512):
        base = o + 0x080000 + y * 1024
        for x in range(512):
            px[x, y] = (b[base + x * 2], b[base + x * 2 + 1], 128)
    im.save(chemin)


def main():
    from PIL import Image
    images = int(sys.argv[1]) if len(sys.argv) > 1 else 160
    cpu, mem = boot()
    init(cpu, mem)
    normales(mem, 'intro_normales.png')
    print("  carte de normales -> intro_normales.png")

    pal = palette(mem)
    buf = mem.rl(0x44C0)
    b, o = mem._find(buf)
    t0 = time.time()
    for n in range(1, images + 1):
        cpu.call(0x2EC2)          # l'interruption : le temps avance
        cpu.call(0x2B56)          # le rendu : 16 000 mots longs = 320 x 200 octets
        cpu.call(0x2AEA)          # le mouvement : division de perspective
        if n % 20 == 0:
            im = Image.new('P', (320, 200))
            im.putpalette(pal)
            im.putdata(bytes(b[o:o + 64000]))
            im.resize((640, 400), Image.NEAREST).save('intro_f%03d.png' % n)
            print("  image %3d  %6d pixels allumes   %4.0f s, %d Minstr"
                  % (n, sum(1 for v in b[o:o + 64000] if v), time.time() - t0,
                     cpu.steps // 1000000))


if __name__ == '__main__':
    main()
