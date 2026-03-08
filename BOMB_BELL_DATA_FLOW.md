# Bomb Bell Data Flow

This document explains, from start to finish, how Wynntils gets bomb bell data and turns it into the list shown in the Bomb Bell overlay.

The goal is to make it easy to reproduce the same behavior in a different mod and export the data to another system.

## What the overlay actually shows

The Bomb Bell overlay does not fetch data by itself.

It renders a list of active `BombInfo` objects from `Models.Bomb`:

- Overlay entry point: `common/src/main/java/com/wynntils/overlays/BombBellOverlay.java`
- Model that owns the data: `common/src/main/java/com/wynntils/models/worlds/BombModel.java`
- Bomb record type: `common/src/main/java/com/wynntils/models/worlds/type/BombInfo.java`
- Bomb source from boss bars: `common/src/main/java/com/wynntils/models/worlds/bossbars/InfoBar.java`
- Boss bar packet matcher: `common/src/main/java/com/wynntils/handlers/bossbar/BossBarHandler.java`

The overlay only does this:

1. Ask `Models.Bomb` for the current active bombs.
2. Apply local display filters like grouping, sort order, and which bomb types are enabled.
3. Convert each bomb into a display string.
4. Render the text.

If you want to export bomb bell data in another project, the real work is in reproducing `BombModel`, not the overlay.

## The two real data sources

Wynntils builds the bomb list from two in-game sources:

1. Chat messages announcing bombs on any server.
2. The boss bar shown on the current server for bombs that are active where the player is standing.

That distinction matters:

- Chat gives you cross-server bomb bell announcements.
- Boss bar gives you authoritative current-server bomb state and remaining time updates.

If you only listen to chat, you can still build a useful export, but you will miss some local corrections and local bomb state.

## End-to-end flow

The full flow is:

1. Minecraft receives a chat message or boss bar update packet.
2. Wynntils matches that message against bomb-specific patterns.
3. The matched data is converted into a `BombInfo` object.
4. `BombInfo` is stored in an in-memory active bomb map keyed by `(server, bomb type)`.
5. Expired entries are removed when they time out or when the game announces expiration.
6. The overlay reads that active set every tick.
7. The text shown to the user is produced from the `BombInfo`.

For a different mod, you can replace step 6 with:

- write to JSON
- send through a socket
- expose via HTTP
- push to a local file
- publish to Discord, a database, or another process

## Core data model

Wynntils stores each active bomb as:

```java
public record BombInfo(String user, BombType bomb, String server, long startTime, float length)
```

Meaning:

- `user`: player name who threw the bomb, if known
- `bomb`: normalized bomb type enum
- `server`: server/world name like `WC1`, `EU052`, etc.
- `startTime`: local system time in milliseconds when the entry was created
- `length`: duration in minutes, stored as a float

This is enough to compute:

- whether the bomb is still active
- how much time remains
- how to display it

## Supported bomb types

Wynntils normalizes strings into this enum:

```java
COMBAT_XP("Combat XP", "Combat Experience", 20),
DUNGEON("Dungeon", List.of("Dungeon", "Free Dungeon Entry"), 10),
LOOT("Loot", "Loot", 20),
PROFESSION_SPEED("Profession Speed", "Profession Speed", 10),
PROFESSION_XP("Profession XP", "Profession Experience", 20),
LOOT_CHEST("Loot Chest", List.of("Loot Chest", "More Chest Loot"), 20);
```

Important detail:

- Chat and boss bar text are not always identical to the UI label.
- You should normalize all parsed strings into your own enum exactly once.

## Source 1: cross-server bomb bell chat

### What is parsed

`BombModel.onChat(...)` listens for chat messages and matches bomb bell announcements with this regex:

```java
^\\u00A7#fddd5cff(?:\\uE01E\\uE002|\\uE001) (?<user>.+) has thrown an? \\u00A7#f3e6b2ff(?<bomb>.+) Bomb\\u00A7#fddd5cff ( ?)on \\u00A7#f3e6b2ff\\u00A7n(?<server>.+)$
```

Named groups:

- `user`
- `bomb`
- `server`

This is the message that tells Wynntils about bombs on other worlds.

### What happens after a match

After matching the chat line, Wynntils:

1. Normalizes `bomb` into a `BombType`.
2. Creates a `BombInfo` using:
   - parsed `user`
   - parsed `server`
   - `startTime = System.currentTimeMillis()`
   - `length = bombType.getActiveMinutes()`
3. Inserts it into the active bomb container.

The code path is:

- `BombModel.onChat(...)`
- `BombModel.addBombFromChat(...)`

### Why this works

Bomb bell chat gives the exact server where the bomb was thrown.

That makes it the only direct source for cross-server bomb discovery in this code path.

## Source 2: current-server boss bar

### Why the boss bar is also used

Chat tells you that a bomb exists.

The boss bar tells you that a bomb is active on your current world right now and gives a countdown in the bar title.

Wynntils uses `InfoBar`, which is registered with the boss bar handler when `BombModel` is constructed.

### How boss bars are matched

`BossBarHandler` keeps a list of known `TrackedBar` instances.

When Minecraft receives boss bar packets:

1. The handler checks each registered regex against the boss bar name.
2. If a pattern matches, the handler calls `trackedBar.onUpdateName(matcher)`.
3. For the info bar, that means `InfoBar.onUpdateName(...)`.

### Bomb boss bar regex

`InfoBar` uses this regex for bomb bars:

```java
\\u00A7#a0c84bff(?:Double )?(?<bomb>.+) from \\u00A7#ffd750ff(?<user>.+)\\u00A7#a0c84bff \\u00A77\\[\\u00A7f(?<length>\\d+)(?<unit>m|s)\\u00A77\\]
```

Named groups:

- `bomb`
- `user`
- `length`
- `unit`

### How the duration is derived

Boss bar time is parsed as:

- minutes: `length + 0.5f`
- seconds: `length / 60f`

The extra `0.5f` for minutes is intentional.
The comment says minute info is rounded, so half a minute is used as an offset to approximate the true remaining time.

### What server is used

Boss bar entries are stored with:

- `server = Models.WorldState.getCurrentWorldName()`

So boss bars only tell you about the world you are currently on.

### What happens after a match

`InfoBar.onUpdateName(...)` creates a `BombInfo` and calls:

```java
Models.Bomb.addBombInfoFromInfoBar(bombInfo);
```

That method:

1. Stores the bomb in `CURRENT_SERVER_BOMBS` keyed by bomb type.
2. Adds it to the global active bomb container if not already present.
3. Posts a local `BombEvent`.

## How active bombs are stored

There are two important containers in `BombModel`.

### 1. `CURRENT_SERVER_BOMBS`

Type:

```java
Map<BombType, BombInfo>
```

Purpose:

- Tracks active bombs on the current world only.
- Used for local current-server state.
- Cleared when the world changes.

### 2. `BOMBS`

This is the long-lived active bomb container used by the overlay.

It stores:

```java
Map<BombKey, BombInfo>
```

Where:

```java
BombKey(String server, BombType type)
```

Purpose:

- Holds the set of active bombs across all known servers.
- De-duplicates entries by `(server, bomb type)`.

Important behavior:

- A chat-discovered bomb for `WC1 + Combat XP` replaces the existing entry for `WC1 + Combat XP`.
- A boss-bar-discovered bomb for the current world does not create duplicates.

## Expiry and cleanup

There are two ways entries disappear.

### 1. Passive timeout

Each `BombInfo` has:

```java
isActive() -> System.currentTimeMillis() < startTime + getLength()
```

Before returning the bomb set, `BombModel.getBombBells()` calls `removeOldTimers()`, which removes inactive entries.

This means the list self-cleans even if no explicit expiration message arrives.

### 2. Explicit expiration chat

`BombModel.onChat(...)` also matches expiration messages:

```java
^\\u00A7#a0c84bff(?:\\uE014\\uE002|\\uE001) \\u00A7#ffd750ff.+\\u00A7#a0c84bff (?<bomb>.+) Bomb has expired!.*$
```

When this matches:

1. The bomb type is normalized.
2. The current-world entry for that bomb type is removed from `CURRENT_SERVER_BOMBS`.
3. The corresponding `(server, bomb type)` entry is removed from the active container.

This is only useful when the game announces expiration for the bomb currently affecting your world.

## How the overlay consumes the data

The overlay calls:

```java
Models.Bomb.getBombBellStream(group, sortOrder, maxPerGroup, alwaysShowCurrentWorld)
```

Then it:

1. Filters out disabled bomb types.
2. Converts each `BombInfo` into a string.
3. Renders the text.

The overlay itself does not know where the data came from.

## Sorting and grouping rules

`BombModel.getBombBellStream(...)` supports two modes.

### Ungrouped

- Sort all active bombs together.
- Limit to `maxPerGroup`.

### Grouped

- Group bombs by `BombType`.
- Sort each group by remaining time.
- Return up to `maxPerGroup` entries per bomb type.

There is also an `alwaysShowCurrentWorld` option:

- if enabled
- and the current world has a bomb of that type
- and that bomb would normally be excluded by the limit
- Wynntils replaces the lowest-priority entry in that group with the current-world entry

This is display logic only.
It does not affect how the bomb data is collected.

## How display text is generated

`BombInfo.asString()` creates output like:

```text
Combat XP on WC32 (16m 35s)
```

It also colors the server differently if it matches the current world.

For exporting data, you should not export the rendered string as your canonical format.

Export structured fields instead:

- user
- bomb type
- server
- start time
- length
- remaining milliseconds
- active status
- source if you add one

## Reproducing this in another project

If you want a different mod to export bomb data, copy the architecture, not the exact UI code.

### Minimum architecture

Implement these parts:

1. A normalized `BombType` enum.
2. A `BombInfo` record or class.
3. A chat listener that parses bomb bell announcements.
4. A boss bar listener that parses current-world bomb bars.
5. An active bomb store keyed by `(server, bomb type)`.
6. Timeout cleanup logic.
7. An exporter that publishes the active set.

### Strong recommendation

Track the source that created each bomb entry.

Wynntils currently does not store source inside `BombInfo`, but your project should.

Suggested enum:

```java
public enum BombSource {
    CHAT_BELL,
    LOCAL_BOSSBAR
}
```

Then export it with each record.

That makes debugging much easier.

## Portable implementation plan

Use this flow in your own mod.

### Step 1: define the normalized types

Create:

- `BombType`
- `BombInfo`
- `BombKey`

Suggested `BombInfo` fields:

```java
public record BombInfo(
    String user,
    BombType bomb,
    String server,
    long startTimeMillis,
    float lengthMinutes,
    BombSource source
) {}
```

### Step 2: hook chat messages

Register a chat listener in your target mod loader.

When a line arrives:

1. strip formatting only if your matcher requires it
2. test the bomb bell regex
3. if matched, normalize the bomb type
4. create `BombInfo`
5. store it under `(server, bomb type)`
6. trigger export

Also match the local expiration regex and remove the current-world entry when it appears.

### Step 3: hook boss bar updates

Register a boss bar listener or packet hook for the target mod loader.

When a boss bar title changes:

1. test the bomb boss bar regex
2. if matched, normalize the bomb type
3. convert the displayed time into minutes
4. use the current world name as the server
5. insert or refresh the entry under `(server, bomb type)`
6. trigger export

### Step 4: keep a single source of truth

Use one active map:

```java
Map<BombKey, BombInfo> activeBombs
```

Never let the overlay, exporter, or UI own state independently.

Everything should read from the same active bomb store.

### Step 5: clean up periodically

Before every read or export:

1. remove expired entries
2. then export the remaining set

Do not rely only on explicit expiration messages.

### Step 6: export structured data

Recommended JSON shape:

```json
{
  "timestamp": 1710000000000,
  "bombs": [
    {
      "user": "ExamplePlayer",
      "bombType": "COMBAT_XP",
      "server": "WC1",
      "startTimeMillis": 1710000000000,
      "lengthMinutes": 20.0,
      "remainingMillis": 1132456,
      "active": true,
      "source": "CHAT_BELL"
    }
  ]
}
```

This is much better than exporting a pre-rendered string.

## Reference pseudo-code

```java
public final class BombTracker {
    private final Map<BombKey, BombInfo> activeBombs = new ConcurrentHashMap<>();

    public void onChatMessage(String rawMessage) {
        Matcher bell = BOMB_BELL_PATTERN.matcher(rawMessage);
        if (bell.matches()) {
            BombType bombType = BombType.fromString(bell.group("bomb"));
            if (bombType == null) return;

            BombInfo info = new BombInfo(
                bell.group("user"),
                bombType,
                bell.group("server").trim(),
                System.currentTimeMillis(),
                bombType.getActiveMinutes(),
                BombSource.CHAT_BELL
            );

            activeBombs.put(new BombKey(info.server(), info.bomb()), info);
            export();
            return;
        }

        Matcher expired = BOMB_EXPIRED_PATTERN.matcher(rawMessage);
        if (expired.matches()) {
            BombType bombType = BombType.fromString(expired.group("bomb"));
            if (bombType == null) return;

            String currentWorld = getCurrentWorldName();
            activeBombs.remove(new BombKey(currentWorld, bombType));
            export();
        }
    }

    public void onBossBarTitle(String rawTitle) {
        Matcher bombBar = BOMB_INFO_PATTERN.matcher(rawTitle);
        if (!bombBar.matches()) return;

        BombType bombType = BombType.fromString(bombBar.group("bomb"));
        if (bombType == null) return;

        float length = Float.parseFloat(bombBar.group("length"));
        if (bombBar.group("unit").equals("m")) {
            length += 0.5f;
        } else {
            length /= 60f;
        }

        BombInfo info = new BombInfo(
            bombBar.group("user"),
            bombType,
            getCurrentWorldName(),
            System.currentTimeMillis(),
            length,
            BombSource.LOCAL_BOSSBAR
        );

        activeBombs.put(new BombKey(info.server(), info.bomb()), info);
        export();
    }

    public List<BombInfo> getActiveBombs() {
        long now = System.currentTimeMillis();
        activeBombs.entrySet().removeIf(entry -> !isActive(entry.getValue(), now));
        return activeBombs.values().stream().toList();
    }

    private boolean isActive(BombInfo info, long now) {
        long lengthMillis = (long) (info.lengthMinutes() * 60000L);
        return now < info.startTimeMillis() + lengthMillis;
    }
}
```

## Important edge cases

### Local bomb throw chat is incomplete

Wynntils also parses a local "bomb thrown" chat line, but a comment in the code notes that the user name is sent on the following chat line and is not currently used.

If you reproduce that logic, either:

- ignore the user for local-only chat events
- or buffer the next line and stitch the data together

### Time is approximate

For chat-discovered bombs, the duration is assumed from the bomb type default.

For boss-bar-discovered bombs, the duration is derived from the displayed countdown and is therefore a better current-world approximation.

### World names are required

To export correct per-server data, you need a reliable `getCurrentWorldName()` source.

Without that, boss bar data cannot be assigned to the correct world.

### Duplicate announcements happen

Do not append every observation to a list.

Use a keyed map so updates refresh the same logical bomb entry.

## What to export

If your end goal is another application or service, export this, not the overlay text:

- bomb type
- server
- user
- source
- start time
- duration
- remaining time
- active flag
- last updated time

Suggested addition:

- `observedAtMillis`

That lets downstream systems know when your mod last confirmed the entry.

## Exact source files to read if you want to verify behavior

- `common/src/main/java/com/wynntils/overlays/BombBellOverlay.java`
- `common/src/main/java/com/wynntils/models/worlds/BombModel.java`
- `common/src/main/java/com/wynntils/models/worlds/bossbars/InfoBar.java`
- `common/src/main/java/com/wynntils/handlers/bossbar/BossBarHandler.java`
- `common/src/main/java/com/wynntils/models/worlds/type/BombInfo.java`
- `common/src/main/java/com/wynntils/models/worlds/type/BombType.java`

## Practical summary

To reproduce bomb bell tracking in another project:

1. Parse bomb bell chat to discover bombs on any server.
2. Parse the current-world bomb boss bar to confirm and refresh local bombs.
3. Normalize bomb names into a fixed enum.
4. Store active bombs in a map keyed by `(server, bomb type)`.
5. Remove entries when expired or when explicit expiration chat arrives.
6. Export structured data from that active map.

That is the complete path Wynntils uses, minus the rendering layer.
