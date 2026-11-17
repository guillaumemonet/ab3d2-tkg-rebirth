# Les monstres en volume, tailles dans leurs propres vues

**Etat : essai concluant, PARQUE.** Le 17 novembre 2026, apres avoir vu le resultat, la
decision a ete de ne pas le mettre en jeu pour le moment. Rien n'est perdu : le prototype
complet est dans `prototypes/carve.py` ; il refait ses images en une commande (voir plus bas).

## Ce que les donnees offrent

Chaque monstre porte **quatre vues** par pas d'animation. Dans `glf.json`, pour un alien
d'index `T`, ce sont les listes `aliens[T].anims[0]`, `[2]`, `[4]`, `[6]` — face, droite, dos,
gauche. La simulation les choisit par `Aliens.viewpointToDraw()`, qui rend 0..3, et appelle
`animFrames(type, vue * 2)`.

Exemple du Red Alien (index 0), marche :

| vue | images |
|-----|--------|
| face | 1, 2, 3, … |
| droite | 5, 6, 7, … |
| dos | 9, 10, 11, … |
| gauche | −5, −6, −7, … (negatif = **la droite en miroir**) |

Le pas d'animation est le MEME indice dans les quatre listes : l'image `k` de chaque vue est
donc le meme instant vu d'un autre bord. C'est ce qui rend la reconstruction possible.

Deux choses qui ont rendu l'essai facile :

- **Toutes les images d'une planche partagent le meme canevas** (80×80 pour GUARD, 64×64 pour
  ALIEN2…). Elles ne sont pas recadrees au plus juste, donc elles sont deja alignees entre
  elles — sans quoi il aurait fallu retrouver le decalage de chacune.
- La gauche n'etant que la droite en miroir, il n'y a en verite que **trois** silhouettes
  independantes. C'est le minimum utile, et c'est suffisant.

## La methode, et les trois pieges

Un point `(x, y, z)` n'existe que s'il est opaque dans toutes les vues a la fois :

```
plein(x,y,z) = face(x,y) ET dos(w-1-x,y) ET droite(z,y) ET gauche(w-1-z,y)
```

**Piege 1 — l'intersection brute donne une brique.** Un corps large de face et epais de cote
remplit tout le rectangle entre les deux : la section est un rectangle, et le monstre a un
torse de parpaing. Remede : lier la profondeur d'un point a sa **distance au bord** de la
silhouette (transformee de distance en deux passes). Une patte, mince de face, reste mince en
profondeur ; un torse, loin de tout bord, garde son epaisseur. Le volume tombe de 22 303 a
13 671 voxels sur le Red Alien, et la forme devient organique.

**Piege 2 — la surface sort en escalier.** Un volume taille dans des masques a des coins vifs
et des voxels isoles. Un vote de voisinage (reste plein si 14 de ses 27 voisins le sont) fond
les marches sans rien perdre de la silhouette.

**Piege 3 — certains monstres n'ont qu'une vue.** Le Droid (index 3) montre la meme image sous
tous les angles : `m[0] == m[1]`, et l'intersection en fait un prisme a section CARREE. Pour
ceux-la il faut **revolutionner** la silhouette autour de l'axe vertical — un point y existe
si la silhouette est opaque a son RAYON. C'est geometriquement juste pour une creature a
symetrie de revolution, et c'est exactement le cas de celles que le jeu ne montre que d'un
bord.

## Ce que la mise en jeu demanderait

Non fait. Les quatre points durs, dans l'ordre :

1. **Retrouver la pose.** La simulation donne au rendu l'image DEJA choisie selon l'angle
   (`a.sheet`, `a.frame`, `a.flip`). Il faut une table inverse (planche, image) → (vue, pas)
   pour remonter aux quatre vues du meme instant.
2. **Une texture, quatre vues.** Un maillage ne porte qu'une texture : coller les quatre vues
   en atlas et envoyer chaque face vers le bon quartier selon sa normale.
3. **Un volume par pose**, taille a la demande et garde (9 a 20 poses par monstre). Maillage
   par bandes comme `SpriteExtrude`, sans quoi le compte de triangles explose.
4. **La rotation remplace le billboard.** Le monstre tournerait selon son angle reel au lieu
   de pivoter vers le joueur. C'est la que se joue le rendu, et le risque : ces graphismes ont
   ete dessines pour etre vus de face.

## Faire tourner le prototype

```
cd rebirth/docs/prototypes
ALIEN=7 PAS=0 ROND=1.3 LISSE=1 python carve.py
```

Il ecrit, a cote de lui, les quatre vues d'origine et le volume rendu a 40 degres, eclaire
par une normale calculee sur le voisinage de chaque voxel. `ALIEN` est l'index dans
`glf.json`, `PAS` le pas d'animation, `ROND` la force de l'arrondi, `LISSE` le nombre de
passes de lissage.
