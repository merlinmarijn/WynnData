# WynnData

Fabric mod workspace targeting Minecraft `1.21.4`, plus a self-hosted relay that owns the Discord bot token and dashboard.

## Modules

- `:` Fabric client mod
- `:protocol` shared relay/client DTOs
- `:relay` self-hosted Discord relay service

## Requirements

- Java Development Kit `21`

## Build and Run

Client:

```bash
./gradlew runClient
./gradlew build
```

Relay:

```bash
./gradlew :relay:run
./gradlew :relay:installDist
```

Relay with Docker:

```bash
docker compose -f relay/compose.yaml up --build -d
```

On Windows Command Prompt:

```bat
gradlew.bat runClient
gradlew.bat build
gradlew.bat :relay:run
```

## Relay Setup

1. Copy [relay/.env.example](/mnt/c/Users/Marijn/Desktop/Projects/bombbell-announcer/relay/.env.example) to `relay/.env`.
2. Fill in:
   - `BASE_URL`
   - `DISCORD_BOT_TOKEN`
   - `DISCORD_APPLICATION_ID`
   - `DISCORD_PUBLIC_KEY`
3. Start the relay with `./gradlew :relay:run`.
   Or with Docker:
   - `docker compose -f relay/compose.yaml up --build -d`
4. Point your Discord interactions endpoint at:
   - `<BASE_URL>/discord/interactions`
5. Invite the bot with permissions:
   - `View Channel`
   - `Send Messages`
   - `Embed Links`
   - `Read Message History`
6. In Discord:
   - run `/bombbell setup channel:<channel>` once as an administrator
   - run `/bombbell enroll` as a contributor to get a private setup bundle
   - optional dashboard admin commands:
     - `/bombbell type list`
     - `/bombbell type enable bomb:<type>`
     - `/bombbell type disable bomb:<type>`
     - `/bombbell type move bomb:<type> position:<n>`
     - `/bombbell combo add name:<name> bombs:<csv> sort:<sort>`
     - `/bombbell combo edit name:<name> bombs:<csv>? sort:<sort>?`
     - `/bombbell combo remove name:<name>`
     - `/bombbell combo move name:<name> position:<n>`
     - `/bombbell combo list`

## Mod Setup

On first launch, the mod creates:

```text
config/wynndata.json
```

Open `Mods -> WynnData -> Configure`, paste the setup bundle from `/bombbell enroll`, and click `Connect`.

The mod stores only:

- `relayBaseUrl`
- `projectId`
- `contributorToken`
- `linkedDiscordUser`
- `dashboardName`

It does not store the Discord bot token.

## Docker

The relay includes a container build at [relay/Dockerfile](/mnt/c/Users/Marijn/Desktop/Projects/bombbell-announcer/relay/Dockerfile) and a ready-to-run Compose stack at [relay/compose.yaml](/mnt/c/Users/Marijn/Desktop/Projects/bombbell-announcer/relay/compose.yaml).

Quick start:

```bash
cp relay/.env.example relay/.env
# edit relay/.env first
docker compose -f relay/compose.yaml up --build -d
docker compose -f relay/compose.yaml logs -f
```

Notes:

- the relay listens on container port `8080`
- SQLite data is persisted in the named Docker volume `relay-data`
- the Compose file forces `SQLITE_PATH=/data/relay.db` so the database lives in the mounted volume
- stop it with `docker compose -f relay/compose.yaml down`
- remove the persistent database too with `docker compose -f relay/compose.yaml down -v`
