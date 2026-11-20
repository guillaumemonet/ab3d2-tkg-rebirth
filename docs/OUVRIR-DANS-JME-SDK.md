# Ouvrir le projet dans le SDK jMonkeyEngine / NetBeans

**Le projet s'ouvre en GRADLE, et c'est par la que les assets apparaissent.**

## Le noeud Assets : d'ou il vient

Le dossier portait deux descripteurs : un projet Ant (`nbproject/`, `build.xml`) et un projet
Gradle (`build.gradle`, `settings.gradle`). NetBeans n'en retient qu'UN, et il retient le
Gradle. La preuve est dans le dossier lui-meme : `nbproject/build-impl.xml` est REGENERE des
qu'un projet Ant s'ouvre, et il n'a jamais ete ecrit. Le descripteur Ant -- avec son extension
« assets », son `assets.folder.name` et son `assets.jmp` -- n'a donc jamais servi : il etait
complet et ignore. D'ou des assets qui « ne s'ouvrent pas », sans rien de casse nulle part.

Cote Gradle, le wiki du SDK est explicite : `assets` **correspond au noeud Project Assets, et
il est necessaire a l'assetManager** ; et c'est `settings.gradle` qui, pour un projet Gradle,
**porte les proprietes du projet, dont le dossier d'assets**. Concretement, le gabarit officiel
-- celui de `GradleGame` -- declare les assets en **sous-projet** :

```gradle
// settings.gradle
rootProject.name = 'ab3d2-rebirth'
include 'assets'
```

```gradle
// assets/build.gradle  -- et NON le build.gradle de la racine : cf. plus bas
apply plugin: 'java'
buildDir = rootProject.file('build/assets')
sourceSets { main { resources { srcDir '.'
    exclude '**/sub4x/**', '**/floors4x/**', 'build/**', 'build.gradle' } } }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
```

## Et pourquoi le sous-projet ne suffit pas

Le noeud `assets` est apparu, mais aucun `.j3o` ne s'ouvrait. La raison est dans le SDK
lui-meme (`com.jme3.gde.core.assets.AssetsLookupProvider`), qui s'y prend en DEUX temps pour
un projet Gradle :

1. il cherche, dans le modele Gradle, un sous-projet nomme `assets` (ou `:assets`) ;
2. il demande ensuite a NetBeans d'ouvrir ce dossier **comme un projet a part entiere**
   (`ProjectManager.findProject`), et n'attache son `ProjectAssetManager` que si ca marche.

Un dossier sans script de construction n'est pas un projet NetBeans. La seconde etape echouait
donc en silence : l'arbre montrait `assets` -- c'est le sous-projet Gradle --, mais il n'y
avait aucun `ProjectAssetManager`, et sans lui rien ne s'ouvre. D'ou **`assets/build.gradle`**,
qui ne sert qu'a exister. La configuration du sous-projet y a donc demenage depuis la racine.

L'assetManager du SDK prend alors `assets/` pour racine -- le SDK appelle
`new ProjectAssetManager(projetAssets, "")` --, exactement comme le `FileLocator` du jeu, pose
sur `Assets.root()`. Les chemins ecrits dans les `.j3o` (`materials/flat_1.j3m`,
`textures/walls/sub/wall_2_0_128.png`, `Common/MatDefs/Light/Lighting.j3md`) sont donc les
memes des deux cotes.

Le format, lui, n'etait pas en cause : le SDK embarque jME **3.8.0** et le projet construit
avec **3.9.0**, mais les deux ecrivent et lisent la meme version 3 de `.j3o`.

Apres un **Reload Project** (clic droit sur le projet), l'arbre montre `assets`, et les `.j3o`,
`.j3m` et `.j3md` s'ouvrent dans leurs editeurs.

## Deux ecarts assumes par rapport au gabarit

**Les dossiers `*4x` sont ecartes du paquet.** Vingt-huit megaoctets de textures agrandies,
que `tools/textures4x.py` regenere et que `.gitignore` ecarte deja. Les mettre dans le jar
reviendrait a les versionner par la bande.

**Le jar des assets n'est PAS au classpath d'execution.** Le gabarit ajoute
`runtimeOnly project(':assets')` ; ici, non. Le jeu lit ses assets sur le DISQUE, par un
`FileLocator` pose sur `Assets.root()` (cf. `Main`), et c'est ce qui permet de deposer une
texture dans `assets/` et de la voir au lancement suivant sans rien reconstruire -- le
fonctionnement qu'on utilise pour les textures agrandies et pour `-Phires`. Au classpath, le
`ClasspathLocator` de jME, enregistre avant, servirait la copie FIGEE du jar. Le sous-projet
existe donc pour le SDK et pour l'empaquetage, pas pour l'execution.

Le jar se fabrique quand meme a la demande :

```
gradle -p rebirth :assets:jar      # build/assets/libs/assets.jar
```

## Ce qu'il faut savoir sur les sources

**TROIS racines, pas une.** Le remake n'est pas autonome :

- `src/main/java` — le remake lui-meme ;
- `../full/src` — **la SIMULATION**. Celle de `full`, verifiee contre le portage. Le remake
  n'en a pas d'autre : tout ce qui touche au gameplay, aux portes et aux ascenseurs vient de
  la, et ne doit pas etre modifie ici ;
- `../java/src` — le portage, qui sert de LECTEUR des formats d'origine (niveaux, textures,
  sons) a l'extraction.

**La classe principale est `ab3d2.rebirth.Main`.**

**Les assets ne sont pas dans le depot** (`.gitignore` : `/assets/*`, sauf `Shaders/`). Un
clone neuf les fabrique :

```
gradle -p rebirth extract        # assets d'origine -> PNG/JSON/OBJ/WAV
gradle -p rebirth materials      # -> assets/materials/*.j3m
gradle -p rebirth scenes         # -> assets/Scenes/*.j3o
gradle -p rebirth models         # -> assets/models/*/*.j3o
```

`models` vient APRES `materials` : chaque `.j3o` de modele porte
`materials/model_shaded.j3m`, et c'est ce qui le rend regardable dans le SDK.

Tant qu'`extract` n'a pas tourne, le noeud `assets` est presque vide : c'est normal.

## Ce qu'il y a dans `assets/`

| | |
|---|---|
| `Scenes/` | les niveaux en `.j3o` |
| `Shaders/` | les nuanceurs a nous -- les SEULS assets versionnes |
| `materials/` | les `.j3m` du decor et des modeles |
| `models/` | les modeles vectoriels en `.j3o`, et le couple `.obj` + `.lvl` qui leur sert de source |
| `textures/` | murs, sols, objets, ciel, et la palette de reference |
| `levels/` | la geometrie des seize niveaux |
| `tables/` | `glf.json` (la base du jeu) et les tables d'origine |
| `sfx/`, `music/` | les sons |
| `hud/`, `menu/`, `intro/`, `fx/` | ce qui se dessine en pixels |
| `releves/` | **les ORACLES**, et rien d'autre |

`releves/` merite un mot. Ce sont les relectures du PORTAGE que les harnais comparent au
remake -- trois cent quarante megaoctets. Le jeu n'en lit aucun. Ils trainaient a la racine
des assets, melanges aux donnees de jeu, et ils sont exclus du paquet.

## Le descripteur Ant

`nbproject/` et `build.xml` restent dans le depot, inertes. Si un jour vous voulez ouvrir le
projet en Ant, il faut deplacer `build.gradle` et `settings.gradle` hors du dossier le temps
de l'ouverture -- mais les taches de verification et d'extraction sont definies en Gradle et
n'existent pas cote Ant :

```
gradle -p rebirth moveTest -Plevel=a
gradle -p rebirth run -Plevel=a -Phires
```
