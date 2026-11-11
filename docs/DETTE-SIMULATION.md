# La dette de simulation — constat et plan

*Relevé du 11 novembre 2026. À traiter après l'achèvement de
[ab3d2-tkg-full-java](https://github.com/guillaumemonet/ab3d2-tkg-full-java).*

Ce dépôt héberge les **extracteurs** qui produisent le corpus de référence : les sondes posées
dans le portage littéral, les relevés en JSON, les cas aux bornes. Une vingtaine de campagnes en
sont sorties, et chacune a corrigé quelque chose.

Toutes ces corrections sont allées dans `full`. Aucune n'est revenue ici.

> **`rebirth` a fabriqué les instruments de mesure et ne s'est jamais mesuré.**

`src/ab3d2/rebirth/sim/` est un fork de la simulation, pris **avant** ces campagnes. `full` est
aujourd'hui vérifié contre le portage par 47 tâches ; ce paquet ne l'est par aucune. Toute
divergence entre les deux est donc une **erreur d'ici**, pas un choix d'ici.

---

## Ce que dit la mesure

6 628 lignes contre 11 107. En neutralisant le nom de paquet, lignes différentes :

| fichier | rebirth | full | différentes |
| --- | ---: | ---: | ---: |
| `Shots` | 1 180 | 1 771 | **1 009** |
| `ObjectRuntime` | 210 | 599 | **635** |
| `Anims` | 447 | 655 | 358 |
| `Aliens` | 1 762 | 1 856 | 332 |
| `MoveTest` | 409 | 442 | 179 |
| `SfxQueue` | 56 | 150 | 134 |
| `Collide` | 182 | 185 | 65 |
| `PlayerSim` | 591 | 620 | 63 |
| `Move` | 499 | 530 | 61 |
| `LevelSim` | 91 | 91 | 58 |
| `Los` | 232 | 222 | 54 |
| `Nav` | 96 | 108 | 46 |

Trois classes n'existent pas ici : **`World`** (l'ordre d'une image), **`Weapon`** (l'arme en
main comme entité), **`ZoneLight`**.

---

## Les manques, vérifiés

### 1. On ne peut tirer sur rien d'autre qu'un monstre

`ObjectRuntime` n'a que deux familles — `collectables` et `activatables`. `full` en a **quatre** :
s'y ajoutent les **destructibles** et le **décor animé**, plus `markVisible`, `seen`, `worryAll`
et `stillHere`.

Et le rayon de tir s'appelle ici `firstAlienOnSegment` ; chez `full`, `firstOnSegment`. Il ne
voit que les monstres. Les barils ne sautent pas, le décor ne bouge pas.

Vérifié par `objectLifeCheck`.

### 2. Les armes hitscan

Manquent `lesPlombs`, `reculX`, `reculZ`, `targetX`, `targetZ`, `rankOf` — tout le chapitre du
fusil : le dé de précision tiré **par plomb**, et le recul.

Vérifié par `shootCheck` : 3 367 coups, 2 580 comparés, 0 écart, état du générateur compris.

### 3. L'arme en main n'est pas une entité

Ici, un `stepWeaponAnim()` maison. `full` a trouvé que le jeu donne à l'arme l'entité située
**deux rangs après celle du joueur** et la repose entièrement à chaque image (`Weapon.place`,
puis `Weapon.animate`). C'est de là que vient le recul — et c'est ce qui la fait passer par le
même éclairage et le même dessin de modèle que tout ce qui l'entoure.

Pour un remake en vraie 3D, c'est exactement le comportement qu'on veut.

Vérifié par `gunHoldCheck`.

### 4. Un bug jouable — le verrou des portes et des ascenseurs

`Main.java:1233` :

```java
int locks = objects.run(player, tap) | monsters.doorLocks();
anims.doorLocks = locks;                                   // le LONG ENTIER
```

`Anim_DoorAndLiftLocks_l` est **un seul long**, et les deux routines n'en lisent pas la même
moitié : `DoorRoutine` fait `move.w Anim_DoorAndLiftLocks_l,d5` — le mot **fort** — et
`LiftRoutine` `move.w anim_LiftOnlyLocks_w,d5` — le mot **faible** qui le suit.

Donner le long entier aux portes revient à **les verrouiller avec les clés des ascenseurs**, et
à **ne verrouiller aucun ascenseur**. Le symptôme : une porte qui refuse de s'ouvrir, clé en
main.

Corrigé dans `full`, `World.frame` :

```java
int verrous = objects.run(player, tap) | aliens.doorLocks();
anims.doorLocks = (verrous >>> 16) & 0xFFFF;
anims.liftLocks = verrous & 0xFFFF;
```

### 5. Le souffle des explosions

`blastSqrt` et `damage` manquent. Vérifié par `blastCheck`.

### 6. La file de bruitages ignore la priorité

56 lignes contre 150 : pas d'`IDNUM`, pas de `notIfPlaying`, pas de volume. Les déclencheurs
sont donc approximatifs — même si laisser OpenAL faire le mixage, plutôt que de refaire Paula,
reste un **choix assumé** de ce dépôt et le restera.

### 7. Le réveil des monstres n'a pas le même retard

Chez `full`, `markVisibleAndWorry` est la **dernière** chose de l'image : c'est l'image
**suivante** qui la consomme, comme dans le jeu. Ici, `visible` est recalculé dans l'image même.

### À vérifier, pas encore tranché

- La cadence de `brightanim`. Le commentaire d'ici dit « une frame sur **six** », celui de `full`
  « une image sur **cinq** ». Le code semble faire cinq des deux côtés : les commentaires se
  contredisent, pas forcément les programmes. À mesurer.
- La tolérance d'une balle sur le joueur : 80 pour les types 0 et 1, 40 au-delà
  (`newanims.s:2616`).

---

## Ce que ce dépôt a et que `full` n'a pas

Le menu complet (8 fichiers, 2 316 lignes), le texte narratif, les sauvegardes, les options,
l'enchaînement des niveaux, la fin de niveau et l'écran de mort.

La dette n'est pas à sens unique.

---

## Le plan : consommer `full/sim` au lieu de le recopier

Rétroporter quelque 2 600 lignes recréerait la dette à la campagne suivante. L'inverse la ferme
pour de bon : **ce dépôt abandonne son paquet `sim` et compile celui de `full`**, comme il
compile déjà `../java`.

C'est possible parce que **le point de soudure est unique** : `LevelSim.of()`. Les deux versions
sont identiques à la **source** près — `LevelData` (POJO JSON) ici, `ab3d2.full.level.Level` (lu
des fichiers d'origine) là-bas. Ce dépôt garde son JSON pour la **géométrie 3D** et lit le niveau
avec le lecteur de `full` pour la **simulation**.

Bonne nouvelle au passage : `full.Shots.fire(...)` prend déjà une **pente** de visée — héritée
d'ici. Les deux visées sont compatibles.

| phase | ce qu'on fait |
| --- | --- |
| **0** | **Mesurer avant de toucher.** Les trois harnais des deux côtés, seize niveaux, comparaison des traces. La liste exhaustive, pas la liste déduite. |
| **1** | La soudure : `srcDirs`, un `LevelSim` qui accepte les deux sources, suppression de `rebirth/sim`. |
| **2** | Rebrancher la vue : `LevelBuilder`, `Hud`, `ObjectAnim`, `DynLights3D` passent de `LevelData.Obj` à `ObjectRuntime.Thing`. C'est là que les destructibles et le décor animé apparaissent. |
| **3** | L'arme devient une entité du monde. |
| **4** | Le `SfxQueue` de `full` ; `Sfx.java` continue de jouer avec OpenAL. |
| **5** | *(séparé)* Dans l'autre sens : donner à `full` le menu, le texte narratif, l'enchaînement et les sauvegardes. |
| **6** | Garde-fou : une tâche `simParity` qui fait tourner les deux et compare, pour que la dette ne se reforme pas. |

### Ce qu'il ne faut pas écraser

Les divergences **voulues** du remake : la visée par pente, la souris, la caméra libre, le pas de
simulation à accumulateur. Elles passent par une fine couche d'adaptation — pas par une fourche
du `sim`.
