# ModularPacks

ModularPacks is a Paper plugin that adds **physical backpacks** (items you carry and interact with) and **installable upgrades/modules** that extend what a backpack can do.

I originally wrote this because “PlayerVault-style” plugins felt boring: same concept, usually accessed via commands. I wanted a real item with a GUI that you can open, upgrade, and carry around. The Sophisticated Backpacks mod was a heavy inspiration — this is a server-side take so anyone can use it on a vanilla client (a resource pack is only needed for nicer visuals like custom models/textures).

## Requirements

- Paper `1.21.10+` (plugin `api-version: 1.21`)
- Java `21`

## Installation

1. Put the jar in `plugins/`
2. Start the server once to generate:
   - `plugins/ModularPacks/config.yml`
   - `plugins/ModularPacks/lang/en_us.yml`
   - `plugins/ModularPacks/backpacks.db` (SQLite; created on first use)
3. Configure `config.yml` / `lang/en_us.yml`
4. Restart (or run `/backpack reload`)

## How it works

- A backpack item stores two important PersistentDataContainer values:
  - `backpack_id` (UUID): the identity of the backpack
  - `backpack_type` (tier/type id): e.g. `Leather`, `Iron`, `Netherite`
- **All contents and installed modules are persisted in SQLite** (`plugins/ModularPacks/backpacks.db`) under that `backpack_id`.
- Backpacks can have **upgrade slots** (configured per type). Upgrades are items you install into those slots.
- To avoid dupes and “stale views”:
  - **Backpack sessions lock to the current viewer** while the backpack (or a module screen) is open.
  - Sorting-mod click spam is rate-limited/blocked inside ModularPacks menus.
  - Linked backpacks (multiple items pointing at the same `backpack_id`) refresh when changes are saved.

## Using a backpack

- Obtain a backpack (craft it, or `/backpack give type <typeId>`).
- **Right-click** the backpack item to open it.
- Put upgrades into the upgrade slots.
- Module interaction is shown in the module’s lore (see `lang/en_us.yml` keys like `moduleActions*`).

## Upgrades (modules)

Upgrades are defined in `config.yml` under `Upgrades:`. Each upgrade can be:

- `Toggleable: true/false` (Shift + Left-click in the backpack GUI toggles it)
- `SecondaryAction: true/false` (Right-click in the backpack GUI performs a module-specific action)
- `ScreenType:` (opens a module screen like `Dropper`, `Crafting`, `Anvil`, etc.)

Included upgrades (see `config.yml` for exact settings):

- Utility: `Everlasting`, `Magnet`, `Tank`, `Feeding`, `Void`
- Workstations: `Crafting`, `Smelting`, `Blasting`, `Smoking`, `Stonecutter`, `Anvil`, `Smithing`, `Jukebox`

Notes:

- The `Jukebox` upgrade stores **real music discs** (not ghost items). Only discs physically in its module inventory can play.
- The `Void` upgrade is intentionally safer: it logs full item bytes in the DB so accidental voids can be recovered.

## Commands & permissions

Base command: `/backpack`

Permissions (see `src/main/resources/plugin.yml`):

- `modularpacks.command` (required to use `/backpack`; default `op`)
- `modularpacks.admin` (admin tools; default `op`)
- `modularpacks.reload` (reload; default `op`)

Subcommands:

- Give items:
  - `/backpack give type <typeId> [player] [amount]`
  - `/backpack give module <upgradeId> [player] [amount]`
- List backpacks from the DB (works for offline players if the server has their UUID cached):
  - `/backpack list <playerName>`
  - `/backpack list unowned`
- Open a backpack by DB identity (admin):
  - `/backpack open <uuid>`
  - `/backpack open <player> <Type#N>` (example: `Netherite#1`)
- Change the tier/type of the backpack in your main hand (admin):
  - `/backpack settype <typeId> [--force]`
- Reload config/lang/recipes:
  - `/backpack reload`
- Recovery (admin):
  - `/backpack recover backpack <player> <backpackUuid>`
  - `/backpack recover void <player|uuid> list [limit] [all]`
  - `/backpack recover void <player|uuid> <id|latest> [receiver]`

## Configuration

Main config file: `plugins/ModularPacks/config.yml`

### General settings

Under `modularpacks:` you can configure:

- `ResizeGUI`: dynamically size pages vs fixed size
- `AutoCloseOnTeleport`
- `Debug.ClickLog`: logs click/drag events to `plugins/ModularPacks/click-events.log` (useful for debugging sorting mods)
- `AllowShulkerBoxes`, `AllowBundles`: block/allow these container types inside backpacks
- `BackpackInsertBlacklist`: block specific `Material` types from being inserted (the Magnet module respects this)
- GUI materials (nav buttons, borders, etc.)

### Backpack types (tiers)

Backpack tiers are defined under `BackpackTypes:` (example ids: `Leather`, `Copper`, `Iron`, `Gold`, `Diamond`, `Netherite`).

Each type defines:

- `Rows`: storage rows (`Rows * 9` slots)
- `UpgradeSlots`
- `CraftingRecipe` (either `Crafting` or `Smithing`)
- Item appearance (`DisplayName`, `Lore`, `CustomModelData`, `SkullData`, etc.)

`CraftingRecipe` supports multiple alternatives using a list-of-maps format (see the `Iron` example in `config.yml`).

### Upgrade recipes and special ingredient tokens

Recipe ingredient values support vanilla materials *and* ModularPacks tokens:

- `BACKPACK:<TypeId>` (example: `BACKPACK:Leather`)
- `MODULE:<UpgradeId>` / `UPGRADE:<UpgradeId>` (example: `UPGRADE:Magnet`)

## Language & placeholders

Language file: `plugins/ModularPacks/lang/en_us.yml`

You can use placeholders in names/lore with `{placeholderName}`.

Common placeholders:

- Backpack item lore:
  - `{backpackContents}` (uses `backpackContents` / `backpackContentsEmpty`)
  - `{backpackId}`
  - `{installedModules}`
- Module lore:
  - `{moduleActions}` and module-specific variants from `lang/en_us.yml` (Feeding/Tank/Jukebox, etc.)
  - `{toggleState}` (uses `toggleState.enabled` / `toggleState.disabled`)
  - `{magnetRange}`
  - `{containedFluid}` / `{containedExp}` (Tank)
  - `{jukeboxMode}` (Jukebox)

## Data storage & recovery

- Database file: `plugins/ModularPacks/backpacks.db`
- Tables:
  - `backpacks` (metadata + contents bytes)
  - `backpack_modules` (installed modules, per-slot)
  - `voided_items` (audit + full item bytes for recovery)

If you use the `Void` upgrade, the `voided_items` table is what makes “undo” possible via `/backpack recover void ...`.

## Resource pack / visuals (optional)

Backpacks are currently `PLAYER_HEAD` items and upgrades use normal materials by default, but both support `CustomModelData`.

To add custom textures/models later:

- Set `CustomModelData` per backpack type / upgrade in `config.yml`
- Provide the corresponding resource pack models/textures on the client

## Building

This project uses Maven.

- Build: `mvn -DskipTests package`
- Output jar: `target/modularpacks-<version>.jar`

