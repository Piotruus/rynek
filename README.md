# AuctionHouse — Paper 1.21.4

Plugin source project for Paper 1.21.4 / Java 21.

## Requirements
- Paper 1.21.4
- Java 21
- Vault
- Economy provider registered through Vault (for example EssentialsX)

## Build
Run `mvn clean package`. GitHub Actions is configured in `.github/workflows/build.yml`; the JAR is uploaded as the `AuctionHouse-Paper-1.21.4` artifact.

## Commands
- `/ah` — help (first `/ah open`, second `/ah search`)
- `/ah open` — open market
- `/ah search <name>` — search from chat
- `/ah sell <price>` — list the held item
- `/ah my` — your auctions; click an item to withdraw it
- `/ah collect` — collect returned/expired items from mailbox

Clicking **WYSZUKAJ** in the GUI currently prompts for the search text in chat. `/ah search <name>` is also supported. Sign input is not implemented in this source build.

## GUI layout
- Categories on the left: WSZYSTKO, UZBROJENIE, PREMIUM, NARZĘDZIA
- Black stained glass separator immediately to their right
- Black stained glass horizontal divider above the bottom action row
- 28 listing slots in the middle
- Bottom row: TWOJE AUKCJE, WYSZUKAJ, empty separator, FILTROWANIE, SORTOWANIE, two empty slots, page arrows
- Sort/filter cycle in the same GUI (no extra selection inventory)
- Sort order: highest price, lowest price, oldest, newest
- Filter: all items / items the player can afford

`PREMIUM` is currently a heuristic category (diamond/netherite, elytra, totem, enchanted/custom-model items); configure this rule in code if your server uses custom premium items.

## Economy and auction rules
- Default auction lifetime: 24 hours
- Commission: 3%
- Limits: default 3, VIP 5, SVIP 8, MVIP 10, FIRE 15, GOAT 15
- SQLite persistence and mailbox for returned items

## Important visual note
The included resource pack is a reference/branding pack containing the supplied market artwork. It does not replace the vanilla chest GUI texture. Replacing the vanilla 54-slot chest texture would also change other large chest inventories for players using the pack; a truly unique per-inventory background needs a custom client-side GUI approach.
