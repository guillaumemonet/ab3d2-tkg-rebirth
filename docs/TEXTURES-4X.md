# Les textures agrandies

Les textures du jeu font de seize a deux cent cinquante-six pixels de large. Vues de pres,
un mur n'est plus qu'une poignee de carres — et depuis que le relief, la hauteur et la
brillance se tirent de la clarte de la texture, c'est aussi le RELIEF qui s'en trouve grossier.

`tools/textures4x.py` en tire une version quatre fois plus fine, par l'API locale de Stable
Diffusion (Automatic1111). Le jeu la prend avec `-Phires`.

```
gradle -p rebirth run -Plevel=a -Phires
```

## Ce que l'outil fait, et ce qu'il ne fait pas

Il n'emploie que l'AGRANDISSEUR, jamais `img2img` : rien n'est invente. C'est un choix, pas
une facilite. Le relief est tire de la clarte de l'image, si bien qu'un reflet peint dedans
deviendrait une fausse bosse — et c'est exactement ce que fait un passage de diffusion un peu
appuye. Essai fait avec realisticVision V6 : a 0,30 de denoise la pierre vire au bleu et se
couvre de reflets mouilles ; a 0,17 l'apport est a peine visible. L'agrandisseur seul garde le
dessin d'origine et le rend net.

Les originaux ne bougent pas. Les resultats vont dans un dossier PARALLELE — `walls/sub`
devient `walls/sub4x`, `floors` devient `floors4x` — et `Assets.image` les prefere quand
`-Phires` est pose. Ce qui n'a pas de version agrandie se charge comme avant.

## Ce qui n'est pas dans le depot

Les images, justement : quatre-vingt-quinze fichiers, vingt-deux megaoctets, regenerables en
quatre minutes et demie. `.gitignore` les ecarte.

```
# WebUI lance avec --api, puis :
python rebirth/tools/textures4x.py
UPSCALER="4x-UltraSharp" python rebirth/tools/textures4x.py   # un autre agrandisseur
```

L'outil saute ce qui existe deja : pour refaire le lot avec un autre agrandisseur, videz les
dossiers `*4x` d'abord.

## Quel agrandisseur

Compares sur cinq matieres (pierre, coque metallique, porte a chevrons, Gieger, roche) :

| | |
|---|---|
| **4x-UltraSharp** | **le choix retenu** : il rend son GRAIN a la pierre la ou les autres la lissent, et ajoute de la matiere au metal sans emousser les bandes de danger |
| **4x_foolhardy_Remacri** | tres proche, un peu plus doux |
| **DAT x4** | propre et fidele, mais lisse la pierre en plastique |
| **R-ESRGAN 4x+** | joints plus sombres, surfaces plus plates |
| **SwinIR 4x** | le plus flou, a ecarter |
| **ESRGAN_4x** | beaucoup de grain, mais INVENTE : il grave de fausses rayures sur la coque |

UltraSharp et Remacri ne sont pas livres avec le WebUI. Ils se posent dans
`models/ESRGAN/` (67 Mo chacun, depuis `huggingface.co/uwg/upscaler`) et n'apparaissent
qu'au REDEMARRAGE : la liste des agrandisseurs n'est balayee qu'au lancement.

`webui-api.bat`, a cote de `webui-user.bat`, lance le WebUI avec les arguments qu'il faut
sur une carte de 8 Go -- surtout `--medvram-sdxl`, sans lequel un SDXL reclame 11,2 Go et
deborde en RAM systeme.

## Le piege du lissage

Le lissage de la carte de hauteur se compte en TEXELS, mais le relief se juge en metres de
mur. Une texture agrandie quatre fois a quatre fois plus de texels pour le meme mur : garder
le meme rayon revient a lisser quatre fois moins, et chaque grain devient une bosse. Le mur
tourne alors au papier froisse — c'est ce qu'on a vu au premier essai. Le rayon suit donc
desormais la resolution (`Relief.REFERENCE`).

## Le prix

4,87 ms par image en textures d'origine, 7,34 ms avec les agrandies (1280x720, sans synchro
verticale) : cent trente-six images par seconde. Le surcout est celui des textures elles-memes
et des trois cartes qu'on en tire — couleur, relief et brillance, toutes quatre fois plus
grandes.
