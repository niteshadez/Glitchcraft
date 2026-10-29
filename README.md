# GlitchCraft

**Release:** v1.0.5

GlitchCraft is a Paper plugin for Minecraft 1.21.11 that creates a staged, configurable glitch-troll experience. It uses Java 21, Paper APIs, and Adventure components. Many visual block effects are client-side only so they do not alter saved terrain.

## Requirements

- Minecraft/Paper 1.21.11
- Java 21
- Gradle 9.7

## Build and install

Build the plugin jar from the project root:

```text
gradle clean build
```

Copy `build/libs/GlitchCraft-1.0.5.jar` to the server's `plugins` directory and start or restart the Paper server. The plugin creates its configuration under `plugins/GlitchCraft/`.

## Features

### Staged progression

Progression is tracked per real player and registered mock player:

| Stage | Effects |
|---|---|
| 0 | Normal |
| 1 | Random single-block visual swaps and occasional ghost-drop visuals |
| 2 | Chest mimics with a per-player trigger cooldown |
| 3 | Doppelgänger encounters and inventory heists |
| 4 | Sprint rubberbanding, hotbar hijacks, chat obfuscation, and gravity distortions |
| 5 | Apocalypse visuals and the corruption climax |

Automatic progression uses staggered individual timers and a configurable global stage cap. `/gc start` assigns online non-exempt and mock players randomized 1–2 hour entry delays. Player stages and escalation deadlines persist in `plugins/GlitchCraft/data/players.yml`.

### Visual and encounter systems

- Stage 1 substitutes a single block in the affected player's view with a curated set of vanilla dirt, grass/path, sand, gravel, stone, log/wood, ore, and leaf blocks. The actual world block is restored visually after a short time or interaction.
- Stage 2 may turn a chest into a fleeing mimic.
- Stage 3 uses Paper mannequins for doppelgängers. Heists transfer inventory contents to chest storage at the copied player's base (or the mock test location), including overflow storage. The gift sign's note is client-only and private to its intended viewer.
- Stage 4 applies movement, hotbar, chat, and gravity anomalies.
- Stage 5 creates fake block-decay visuals, sky-clone visuals, and a timed finale. Fake block changes are sent to clients rather than written into world terrain.
- Exempt names/UUIDs configured in `exempt-players` are excluded from player-targeted effects.
- An administrator inventory provides player stage controls, global progression controls, and Start/Restart/Stop buttons.
- Local mock-player and test commands support solo development/testing.

## Commands

All `/gc` commands require the `glitchcraft.admin` permission. The permission defaults to operators.

| Command | Description |
|---|---|
| `/gc gui` | Open the 54-slot GlitchCraft admin panel. |
| `/gc <player>` | Open the player control GUI for an online player. |
| `/gc debug [player]` | Open Stalker diagnostics. |
| `/gc setstage <player> <1-5>` | Set an online non-exempt player's stage. |
| `/gc setglobal <1-5>` | Set the global stage cap. |
| `/gc progression cap <0-5>` | Set the global stage cap (alternate form). |
| `/gc toggleauto` | Toggle automatic progression. |
| `/gc start` | Enable automatic progression and assign fresh 1–2 hour entry delays. |
| `/gc stop` | Halt module loops and clear temporary effects. |
| `/gc restart` | Reset saved progression and restart with fresh entry delays. |
| `/gc trigger <player> <module>` | Trigger a player event. Module values include `blockglitch`, `glitch`, `doppelganger`, `stalker`, `inverted`, `anomaly`, `apocalypse`, and `quantum`. |
| `/gc apocalypse start` | Start the active apocalypse visual phase. |
| `/gc apocalypse trigger` | Trigger the apocalypse climax immediately. |
| `/gc apocalypse stop` | Stop the apocalypse phase and restore temporary state. |
| `/gc mock add <name>` | Register a mock player and look up its skin profile. |
| `/gc mock remove <name>` | Remove a mock player. |
| `/gc mock list` | List registered mock players and stages. |
| `/gc test countdown` | Set registered participants to Stage 4 and start the five-minute countdown. |
| `/gc test doppelganger <targetRealPlayer> <frameMockPlayer>` | Run a mock-frame heist test against an online player. |

`/gc mock add` requires a resolvable Mojang profile/skin. Mock players are local testing profiles, not real server connections.

## Configuration and saved data

Edit `plugins/GlitchCraft/config.yml`:

```yaml
exempt-players:
  - Notch

auto-progression:
  enabled: true

progression:
  min-stage-delay-seconds: 300
  max-stage-delay-seconds: 720
  max-global-stage: 5
```

Player progression, next-escalation timestamps, and mock-player profile/base data are stored in `plugins/GlitchCraft/data/players.yml`.

## Planned / not yet implemented

These are follow-up items, not features of v1.0.5:

- Improve doppelgänger pathing so mannequins can jump while chasing or fleeing.
- Add `/gc help` with a command and permission reference.
- Add a convenient Admin GUI keybind/shortcut. A true client-side keybind may require client-side support; the current GUI is opened with `/gc gui`.
- Refine Stage 5 presentation so affected players do not see the corruption notices, while preserving the observer/exemption behavior.
- Audit and tune module sounds, pitches, and volumes.
- Add a debug section to the Admin GUI for testing purposes.

## Testing

Testing in progress.
