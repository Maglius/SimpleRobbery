# SimpleRobbery - Paper plugin [AI Made]

A standalone Paper 26.2 Java 25 robbery plugin. **No resource pack required.**

## Features
- Area selection wand, configurable vaults and opening/cooldown schedules.
- Editable GUI layout (`menus.yml`) for robbery settings, guard types, equipment and rewards.
- Player/skeleton/zombie and other guards; settings for health, chase range, respawn and equipment.
- Configurable escape-to-claim, money and NBT-preserving special items.
- `message.yml`, `discord.yml`, stats and optional PlaceholderAPI integration.

## Building from source

Requires Java 25 and Gradle (the repository includes the Gradle wrapper for Unix/macOS).

```sh
./gradlew clean build
```

Alternatively, run `gradle clean build` if Gradle is installed on your machine.
The resulting plugin JAR is generated in `build/libs/`. This repository does not include the compiled JAR or operating-system-specific build launchers.

## New menu features
- Standard `&` colors and hex in `&#RRGGBB` / `<#RRGGBB>` format for item names, lore, titles and plugin messages.
- Any configurable GUI item using `material: PLAYER_HEAD` may add `head-owner: Notch` or `head-texture: <texture>`.
- `head-texture` accepts a Minecraft texture URL, 32-128 character texture hash, or Base64 Mojang texture JSON (with a textures.minecraft.net URL).
- Background icons and guard-selection choices also support custom heads.
- The default layout uses the slot order requested, with black glass panes, chainmail guard equipment and arrow/barrier navigation.

Example custom head:
```yaml
info:
  slot: 4
  material: PLAYER_HEAD
  head-owner: Notch
  name: '&#39C5BBInformation'
  lore:
  - '&7Your robbery name: {name}'
```

Example texture-based head:
```yaml
info:
  slot: 4
  material: PLAYER_HEAD
  head-texture: 'https://textures.minecraft.net/texture/<valid-texture-hash>'
  name: '<#FFAA22>Information'
```

## Migration from older versions
Plugin name, data directory and PlaceholderAPI identifier have changed. The **new data folder is `plugins/SimpleRobbery/`**. The folder used by any earlier differently-named build is not automatically migrated. If you want to preserve old robbery regions and payouts, back up the original folder first, then copy `robberies.yml`, `stats.yml` and any desired values in `discord.yml` to the new folder. Avoid overwriting `menus.yml` if you want the new default layout. The new stats placeholders use `%simplerobbery_...%`. An existing selection wand may need to be reissued with `/robbery wand` because its namespaced key changed. Restart the test server after replacing the JAR, and do not run two versions concurrently.

**Warning:** This is updated source. It has not been compiled against or tested on a live Paper 26.2 server here. Check Gradle output, then test commands, menus, rewards and PlaceholderAPI on a backup server before production.
