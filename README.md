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
