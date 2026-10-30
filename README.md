# BedWars1058-MapLoader

Fixes slow startups and freezes on BedWars1058 servers with hundreds of arena worlds in the server root.

* **Map library** – all BedWars maps live in ONE folder (default `bedwars_worlds/`, set `storage.path`). A map is a world folder or a `.zip`.
* **Just-in-time loading** (`loading.mode: LAZY`) – arenas that are not needed are *parked* (no world loaded) but stay **listed**: BedWars1058's arena selector GUI, `/bw join random` and group joins show them as "waiting". When a player picks one they get a "preparing" message, the world is copied from the library and loaded, and they join automatically. Arenas nobody uses are unloaded again after `loading.idle-unload-seconds` (default 300) and become parked/listed again. `warm-arenas-per-group` (default 0) keeps some arenas permanently loaded, but is ignored while `list-parked-arenas` is on (every arena is joinable anyway). Every world load is logged with its reason (`Loading arena 'X' (...)`).
* **EAGER mode** – loads everything like stock BedWars1058, but from the library and staggered (`stagger-ticks`) so the main thread never freezes.
* Runtime copies are made in the server root only while an arena runs (marked with `.maploader-runtime`) and deleted afterwards. Only copies the plugin created are ever deleted.
* Existing worlds in the root are moved into the library on first use (`storage.import-from-root`) or all at once with `/bwloader migrate confirm`.

## Install
1. Put `BedWars1058-MapLoader-3.5.1.jar` in `plugins/` next to BedWars1058 (required).
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
| `/bwloader restore <arena\|all> [move] [confirm]` | put maps back into the server root as normal world folders (for stock BedWars1058). Copies by default; `move` removes them from the library. Never overwrites existing folders |
| `/bwloader sleep [idle\|all\|<arena>] [force]` | put loaded arenas back to sleep right now: world unloaded, copy deleted from the server root, arena stays listed and wakes up on join. Only idle arenas unless `force` (which removes players first). LAZY mode only |
| `/bwloader disable <arena\|all> [confirm]` | unload arena(s) (players are removed, worlds unloaded and copies deleted) so their maps can be moved/restored while the server runs; `enable <arena\|all>` brings them back. Disabled arenas also stay off until a restart |
| `/bwloader activate <arena\|all>` | load parked arena(s) now |

Example: `/bwloader config set storage.path /srv/mc/shared_maps`, `/bwloader config set loading.group-overrides.Solo 4`.

## How `/bw setupArena` protects your work
* The map is copied from the library into the server root and edited there; the world is flushed to disk every 2 minutes.
* When the session ends (or the server stops) the world is unloaded and written back to the library: the new version is written and verified first, then swapped in. The previous version goes to `<library>/.backups/<arena>/` (newest `storage.backups-to-keep`, default 3).
* If the server crashes mid-session, the edited world stays in the server root marked `.maploader-setup`. On the next start it is saved into the library automatically (with a backup of the old version) and logged. It is never deleted before it is saved.
* A failed or cancelled session start can never overwrite the stored map. An arena of the same name waits until the save has finished. The plugin never deletes a folder in the server root that it did not create; if a same-named world of yours is there, the arena refuses to load and tells you to run `/bwloader migrate`.

## Compatibility
Spigot/Paper 1.8 – 26.x (compiled against 1.8.8 API, 1.13+ void generator loaded by reflection). Java 11+ runtime for the plugin itself (use whatever your server needs; 26.x needs Java 25).

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.