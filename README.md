# BedWars1058-MapLoader

Fixes slow startups and freezes on BedWars1058 servers with hundreds of arena worlds in the server root.

* **Map library** – all BedWars maps live in ONE folder (default `bedwars_worlds/`, set `storage.path`). A map is a world folder or a `.zip`.
* **Just-in-time loading** (`loading.mode: LAZY`) – arenas are parked; only `warm-arenas-per-group` joinable arenas per group are loaded. More wake up as arenas fill/start, or when a player runs `/bw join <arena|group|random>` (the player is joined automatically once ready).
* **EAGER mode** – loads everything like stock BedWars1058, but from the library and staggered (`stagger-ticks`) so the main thread never freezes.
* Runtime copies are made in the server root only while an arena runs (marked with `.maploader-runtime`) and deleted afterwards. Only copies the plugin created are ever deleted.
* Existing worlds in the root are moved into the library on first use (`storage.import-from-root`) or all at once with `/bwloader migrate confirm`.

## Install
1. Put `BedWars1058-MapLoader-2.0.0.jar` in `plugins/` next to BedWars1058 (required).
2. Start once, edit `plugins/BedWars1058-MapLoader/config.yml` (or use commands), run `/bwloader migrate`, then `/bwloader migrate confirm`.

## Commands (permission `bw1058maploader.admin`, aliases `/bwl`)
| Command | Purpose |
|---|---|
| `/bwloader status` | pool state, library size |
| `/bwloader reload` | reload config and apply it live |
| `/bwloader config list\|get <key>\|set <key> <value>` | edit config in-game (comments preserved, validated, applied immediately) |
| `/bwloader scan` | rescan the library folder |
| `/bwloader activate <arena>` | wake a parked arena manually |
| `/bwloader migrate [confirm]` | move root worlds into the library |

Example: `/bwloader config set storage.path /srv/mc/shared_maps`, `/bwloader config set loading.group-overrides.Solo 4`.

## How `/bw setupArena` protects your work
* The map is copied from the library into the server root and edited there; the world is flushed to disk every 2 minutes.
* When the session ends (or the server stops) the world is unloaded and written back to the library: the new version is written and verified first, then swapped in. The previous version goes to `<library>/.backups/<arena>/` (newest `storage.backups-to-keep`, default 3).
* If the server crashes mid-session, the edited world stays in the server root marked `.maploader-setup`. On the next start it is saved into the library automatically (with a backup of the old version) and logged. It is never deleted before it is saved.
* A failed or cancelled session start can never overwrite the stored map. An arena of the same name waits until the save has finished. The plugin never deletes a folder in the server root that it did not create; if a same-named world of yours is there, the arena refuses to load and tells you to run `/bwloader migrate`.

## Compatibility
Spigot/Paper 1.8 – 26.x (compiled against 1.8.8 API, 1.13+ void generator loaded by reflection). Java 11+ runtime for the plugin itself (use whatever your server needs; 26.x needs Java 25).

## Build
`gradle shadowJar` (Kotlin 2.2.21, JDK 17+). BedWars1058 API resolves from `-PbedwarsApiJar=<jar>`, then `./libs/*.jar`, then the andrei1058 Maven repo. Output: `build/libs/BedWarsWorldLoader-1.0.0.jar`.

## Developer note
This is partially AI-generated, though I wrote most of the code myself. Regardless, use at your own risk.

## Licensing
This project is licensed under the MIT License. Check the [LICENSE](LICENSE) file for more information