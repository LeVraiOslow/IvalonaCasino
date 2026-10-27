# SlotMachine (Paper 26.2 / Java 25)

Machine à sous de casino ouverte avec `/roulette` (alias `/slots`, `/slot`, `/machine`).

## Compilation
```
mvn clean package
```
Le jar est généré dans `target/SlotMachine-1.0.0.jar` (JDK 25 requis). Dépose-le dans `plugins/`.

## Dépendances
- **Vault** (obligatoire) + un plugin d'économie (Essentials, etc.)
- **Nexo** (optionnel) : icônes `nexo:<id>` dans `gui.yml`

## Fichiers de configuration (séparés)
| Fichier | Contenu |
|---|---|
| `config.yml` | mises, lignes de paiement, animations, sons, effets, format monétaire |
| `rewards.yml` | symboles (poids, paire, triple), free spins, roue bonus, cagnottes Mini/Major/Giant/Jackpot |
| `messages.yml` | tous les textes (chat, titres, broadcast, table des gains) en MiniMessage |
| `gui.yml` | titre, emplacements, icônes (Material ou Nexo), symboles |
| `data.yml` | généré : montants des cagnottes + free spins en attente |

## Fonctionnement
- **Mise** : un bouton par valeur de `bets`, puis clic sur le bouton **LANCER**. Coût = mise × nombre de lignes actives.
- **Grille 3×3**, lignes configurables (milieu, haut, bas, 2 diagonales).
- **Paire** (2 symboles identiques à gauche), **triple** (3), **WILD** remplace les autres symboles.
- **SCATTER** (n'importe où) : free spins automatiques, multiplicateur de gains, relance possible.
- **BONUS** (3+ n'importe où) : ouvre un menu de **21 cartes face cachée**. Le joueur les retourne, **3 cartes identiques** (Mini, Major, Giant ou Jackpot) = gain de la cagnotte et retour à la machine. Les petits paliers sortent bien plus souvent (poids `wheel-weight`). L'ancienne roue reste disponible avec `bonus.mode: WHEEL`.
- **Cagnottes progressives** : un % de chaque mise payante alimente chaque cagnotte ; remise à la valeur `seed` quand elle est gagnée. `min-bet` par palier pour inciter aux grosses mises.
- **Animations** : défilement rouleau par rouleau, suspense sur le dernier rouleau, clignotement des cases gagnantes, cadre arc-en-ciel, feux d'artifice, titres, sons par résultat.
- Fermer le menu en plein tour ne fait **jamais** perdre un gain (le tour est payé à l'arrêt des rouleaux).

## Commandes / permissions
| Commande | Permission |
|---|---|
| `/roulette` | `slotmachine.use` (défaut : tous) |
| `/roulette pools` | `slotmachine.use` |
| `/roulette reload` | `slotmachine.admin` |
| `/roulette setpool <palier> <montant>` | `slotmachine.admin` |
| `/roulette simulate <tours>` | `slotmachine.admin` |
| `/roulette resetpools` | `slotmachine.admin` (remet les cagnottes à leur `seed`) |
| `/roulette checkitems` | `slotmachine.admin` (liste les items vanilla/Nexo introuvables) |

## Équilibrage
Après toute modification des poids ou gains, lance `/roulette simulate 1000000` pour voir le RTP estimé
(lignes + free spins + cagnottes). Vise typiquement 85-95 % si tu veux que la machine reste un puits d'argent pour l'économie.

## Nexo
- Symbole / bouton / cagnotte : `item: "nexo:mon_item"`.
- Fond de machine : colle ton glyphe Nexo dans `title` de `gui.yml`.
- Sons du resource pack : utilise la clé du son directement dans `config.yml` (ex: `nexo:slot.spin;1;1`).

## Couleurs
Dans tous les textes (messages, titre, noms et lores) : MiniMessage (`<red>`, `<#ff8800>`, `<gradient:..>`),
codes Minecraft (`&c`, `&l`, `&r`, `&&` pour un `&`) et hexadécimal (`&#ff8800` ou `#ff8800`).
Couleurs nommées personnalisées dans `config.yml` -> `colors` (ex: `<orange>`).
Les balises Nexo `<glyph:...>` / `<shift:...>` ne sont pas modifiées.
