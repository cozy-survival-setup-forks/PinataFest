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
- Votes cast while a player is offline count straight away for the party and for their total, and
  their rewards are saved and paid out after they log in, with an optional limit
- Particle and sound when a player votes

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
| `/pinatafest summon [location]` | Start a party now | `pinatafest.admin` |
| `/pinatafest setspawn <name> [2d\|3d] [radius]` | Save a spawn point where you stand | `pinatafest.admin` |
| `/pinatafest pos1`, `/pinatafest pos2` | Pick the two corners of a zone | `pinatafest.admin` |
| `/pinatafest spawns` | List every spawn point | `pinatafest.admin` |
| `/pinatafest delspawn <name>` | Delete a saved spawn point | `pinatafest.admin` |
| `/pinatafest kill` | Remove every pinata and countdown | `pinatafest.admin` |
| `/pinatafest fake <player> [site]` | Send a test vote | `pinatafest.admin` |
| `/pinatafest reload` | Reload `config.yml` and `lang.yml` | `pinatafest.admin` |

`/pf` works as a short form of `/pinatafest`.

## Placeholders

- `%pinatafest_votes%` and `%pinatafest_queued%` - a player's total votes and waiting rewards
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
