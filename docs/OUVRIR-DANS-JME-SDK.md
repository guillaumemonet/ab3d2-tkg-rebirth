# Ouvrir le projet dans le SDK jMonkeyEngine / NetBeans

Le projet s'ouvre **des deux facons** : par Gradle en ligne de commande, et comme projet
NetBeans dans le SDK jME. Les deux lisent les memes sources et les memes assets ; ils ne
different que par la facon dont les bibliotheques jME sont trouvees.

| | Gradle | SDK jME |
|---|---|---|
| sources | `sourceSets.main.srcDirs` dans `build.gradle` | `<source-roots>` dans `nbproject/project.xml` |
| bibliotheques | depot Maven (`org.jmonkeyengine:...`) | bibliotheques globales du SDK (`${libs.jme3-*.classpath}`) |
| assets | `-Drebirth.assets=<dossier>` | `assets/`, deja dans `run.classpath` |

## Ce qu'il faut savoir

**TROIS racines de sources, pas une.** Le remake n'est pas autonome :

- `src` — le remake lui-meme ;
- `../full/src` — **la SIMULATION**. Celle de `full`, verifiee contre le portage. Le remake
  n'en a pas d'autre : tout ce qui touche au gameplay, aux portes et aux ascenseurs vient de
  la, et ne doit pas etre modifie ici ;
- `../java/src` — le portage, qui sert de LECTEUR des formats d'origine (niveaux, textures,
  sons) a l'extraction.

**La classe principale est `ab3d2.rebirth.Main`.** Le descripteur etait reste sur
`mygame.Main`, le gabarit du SDK.

**LWJGL 3, pas LWJGL 2.** Le classpath NetBeans demandait `jme3-lwjgl`, qui est la version 2.
Le code appelle GLFW directement -- pour tenir la capture du curseur, cf.
`Main.tenirLeCurseur` --, ce qui exige `jme3-lwjgl3`. Avec l'ancienne, la compilation echoue
sur `org.lwjgl.glfw.GLFW`.

**Les assets sont dans `assets/`**, a la racine du projet, la ou le SDK les attend. Ils y sont
deja declares (`assets.jmp`, `assets.folder.name=assets`), et `run.classpath` les inclut. Le
SDK sait donc ouvrir les `.j3o` et les `.j3m` du jeu, et les editer.

**`nbproject/build-impl.xml` n'est pas dans le depot** : NetBeans le regenere a l'ouverture du
projet. C'est normal, ne pas s'en inquieter.

## Ce que le SDK ne fera pas

Les taches de verification (`moveTest`, `shotTest`, `simParity`) et l'extraction des assets
sont definies dans `build.gradle` et n'existent pas cote Ant. Elles restent en ligne de
commande :

```
gradle -p rebirth moveTest -Plevel=a
gradle -p rebirth run -Plevel=a -Phires
```
