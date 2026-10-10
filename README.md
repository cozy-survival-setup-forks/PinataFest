# PinataFest

Every vote counts towards a pinata party. Votes arrive from Votifier, NuVotifier or VotifierPlus,
pay out reward commands, and after enough of them a llama pinata appears that players have to
chase down and hit before it gets away. For Paper 1.21.11.

## The pinata party

1. Every vote adds one to the party counter, whether the voter is online or not.
2. When the counter reaches `votes_needed` a countdown starts and a pinata appears at one of your
   locations.
3. The pinata runs away from the nearest player, glows in changing colours, wears a changing
   carpet and has a boss bar. Every hit counts, and every hit rolls the hit rewards.
4. After a hit it may pull a trick: teleport, knock nearby players back, shoot into the air, turn
   into a baby, or give nearby players speed.
5. The last hit breaks it. Everyone who hit it gets the die rewards, the last hitter gets the
   last-hit rewards, and fireworks go off.

Health scales with the players online, hits have a cooldown per player, and hitting can require a
permission, certain items or a recent vote.

## Votes and rewards

- Reward commands per vote, each with a chance, permission, vote-site filter and options to stop
  rolling, pick one random command, run once, and skip vanished or offline voters
- Votes are also counted per month, reset by themselves on the first of the month at midnight in `votes.monthly.timezone` (New York by default). The counts are kept in `votes.db`
- Votes cast while a player is offline count straight away for the party and for their total, and
  their rewards are saved and paid out after they log in, with an optional limit
- Votes, totals and the rewards waiting for offline players are kept in `votes.db` (SQLite). An older `votes.yml` is brought in once at the first start, in one step and checked (players, vote totals, monthly totals and waiting rewards must match), and only then renamed to `votes.yml.migrated`. If it cannot be read the plugin stays off and the file is left untouched'+nl+'- Paying out waiting rewards is written to a record first. If the server stops in the middle, those rewards are not paid a second time: they are listed in `/pinatafest doctor` and the console, and after you have checked them `/pinatafest doctor resolve <id>` clears the entry'+nl+'- If `spawns.yml` cannot be read, the file is kept as `spawns.yml.broken-<time>` and never overwritten
- Particle and sound when a player votes
- A chat message for everyone when a player votes, `VOTING ▶ Steve has voted for the server [/vote]`, with a clickable `[/vote]`.
  Votes that arrive together, one per voting site, are joined into `has voted 3 times` (`votes.announce_window_ticks`).
  Both messages are `vote_broadcast` and `vote_broadcast_multiple` in `lang.yml`

## Spawn points

There is no limit on spawn points, across as many worlds as you like. Each one is either

- **fixed**: one exact spot,
- **area**: a random point inside a rectangle, on the highest block there, or
- **zone**: a random point anywhere inside a box, height included.

Stand where you want it and run `/pinatafest setspawn <name>` for a fixed spot. Add `2d` or `3d`
and a radius for an area or a zone around you, for example `/pinatafest setspawn field 2d 30`. Or
set two corners with `/pinatafest pos1` and `/pinatafest pos2` and run `setspawn <name> 2d` or
`setspawn <name> 3d`. Saved points go into `spawns.yml`; `/pinatafest spawns` lists them and
`/pinatafest delspawn <name>` removes one. Spawn points can also be written by hand under
`pinata.locations` in `config.yml`.

## Hiding other players

Players can choose to hide everyone else while a pinata is out, which keeps crowded parties
playable on weaker computers. `/pinatavisibility` (or `/pinatafest visibility`) takes `hide`,
`show`, `toggle` and `status`. The choice is saved per player, only visibility changes made by
this plugin are undone by it, and a title thanks everyone when the last pinata is gone.

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/pinatafest votes` | Your own vote count | `pinatafest.votes` (everyone) |
| `/pinatafest progress` | Votes so far towards the next pinata | `pinatafest.votes` (everyone) |
| `/pinatavisibility [hide\|show\|toggle\|status]` | Hide other players during parties | `pinatafest.visibility` (everyone) |
| `/pinatafest votes <player>` | Someone else's vote count | `pinatafest.admin` |
| `/pinatafest monthly` | Your votes this month | `pinatafest.votes` (everyone) |
| `/pinatafest monthly get <player>` | Someone else's votes this month | `pinatafest.admin` |
| `/pinatafest monthly set/add <player> <amount>` | Change a player's monthly votes | `pinatafest.admin` |
| `/pinatafest monthly reset <player or global>` | Reset one player or everyone | `pinatafest.admin` |
| `/pinatafest summon [location]` | Start a party now | `pinatafest.admin` |
| `/pinatafest setspawn <name> [2d\|3d] [radius]` | Save a spawn point where you stand | `pinatafest.admin` |
| `/pinatafest pos1`, `/pinatafest pos2` | Pick the two corners of a zone | `pinatafest.admin` |
| `/pinatafest spawns` | List every spawn point | `pinatafest.admin` |
| `/pinatafest delspawn <name>` | Delete a saved spawn point | `pinatafest.admin` |
| `/pinatafest kill` | Remove every pinata and countdown | `pinatafest.admin` |
| `/pinatafest status` | Pinatas out, countdowns running, votes towards the next party | `pinatafest.admin` |
| `/pinatafest counter <amount>` | Set the votes counted towards the next party | `pinatafest.admin` |
| `/pinatafest queue <player>` | How many vote rewards wait for an offline player | `pinatafest.admin` |
| `/pinatafest queue clear <player>` | Throw those waiting rewards away | `pinatafest.admin` |
| `/pinatafest fake <player> [site]` | Send a test vote | `pinatafest.admin` |
| `/pinatafest reload` | Reload `config.yml` and `lang.yml`. A file with a mistake is skipped and the old settings stay | `pinatafest.admin` |

`/pf` works as a short form of `/pinatafest`.

## Placeholders

- `%pinatafest_votes%` and `%pinatafest_queued%` - a player's total votes and waiting rewards
- `%pinatafest_votes_monthly%` - votes this month. `%pinatafest_votes_needed_N%` - votes still needed to reach N this month, 0 once reached
- `%pinatafest_counter%` and `%pinatafest_left%` - votes counted and votes still needed for the next pinata
- `%pinatafest_visibility%` - `hidden` or `visible` for the player

## Configuration

`config.yml` holds the party, pinata and reward settings, `lang.yml` holds every message. In
reward commands `%player%` is the player, `%service%` the vote site and `%votes%` their total.

## Building

Needs Java 21.

```
./gradlew build
```

The jar ends up in `build/libs`. To try it on a local server:

```
./gradlew runServer
```

## Keeping your files safe

- `config.yml` and `lang.yml` start with a `config-version` / `lang-version` number. After an update, new settings are added to your files with their comments, and nothing you changed is touched. The old file is kept next to it as `<name>.<date>.bak` (the newest 5). A setting is only removed when the changelog says so.
- A value with a mistake (a negative time, an item that does not exist, text where a number belongs) is named in the console by file and key. On a reload, the settings in use stay as they were.
- Files are written to a temporary file and moved into place, with the previous version kept as `.bak`. A file that cannot be read is restored from its `.bak`, and the unreadable one is kept as `.broken-<time>`.
- A file or database that was made by a newer version of the plugin is left alone and a warning is logged.
- `votes.db` is a SQLite database in WAL mode. It is checked when the plugin starts, a copy is made on a schedule (`backup.interval-hours`, `backup.keep` in `config.yml`, in the `backups` folder) and every copy is opened and checked before older ones are removed. A damaged database is replaced by the newest copy that checks out, or, where nothing may be lost, the plugin stays off and the file is left untouched.
- The backup is a consistent snapshot, not a copy of the open file.
- `/pinatafest doctor` shows the health of the files, versions, last backup and recent save failures (no player data). `/pinatafest backup now` makes a checked backup right away. Both need the admin permission.

## Telemetry

On startup PinataFest sends a small anonymous beacon (plugin name/version, server software/version,
online/max player counts, and a random ID with no player data) so we know which versions are in
use. Turn it off with `metrics.enabled: false` in `config.yml`. The random ID is kept as `server-id` in `votes.db` (older versions kept it in a `.server-id` file, which is moved over unchanged). The address and the interval are fixed in the plugin and are not settings.

## License

See `LICENSE`: free to run on your own servers, not for redistribution or resale.
