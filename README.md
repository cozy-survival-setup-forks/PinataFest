# PinataFest

Vote rewards for Paper 1.21.11. It receives votes from Votifier, NuVotifier or VotifierPlus, runs
the reward commands you set up, holds votes for players who are offline, and keeps a running total
for vote milestones.

## Features

- Receives votes from Votifier, NuVotifier and VotifierPlus, with no vote plugin needed to build it
- Reward commands per vote, each with its own chance, permission, list of vote sites, and options
  to stop rolling, pick one random command, skip vanished players and skip offline votes
- Milestones for a total number of votes, or for every N votes
- Offline votes are queued, saved to disk and paid out after the player logs in
- Particle and sound when a player votes
- `/vote` shows clickable vote links
- Reminders for players who have not voted recently
- PlaceholderAPI support
- Messages are MiniMessage in `lang.yml`, as chat, action bar or both, with an optional sound

## Commands

| Command | What it does | Permission |
| --- | --- | --- |
| `/vote` | Show the vote links | `pinatafest.vote` (everyone) |
| `/pinatafest votes` | Your own vote count | `pinatafest.votes` (everyone) |
| `/pinatafest votes <player>` | Someone else's vote count | `pinatafest.admin` |
| `/pinatafest fake <player> [site]` | Send a test vote | `pinatafest.admin` |
| `/pinatafest reload` | Reload `config.yml` and `lang.yml` | `pinatafest.admin` |

`pinatafest.noreminder` stops a player getting vote reminders.

## Placeholders

- `%pinatafest_votes%` - the player's total votes
- `%pinatafest_queued%` - votes waiting to be paid out

## Reward commands

In every command `%player%` is the voter, `%service%` is the site they voted on and `%votes%` is
their total after this vote. Commands run from the console.

## Building

Needs Java 21.

```
./gradlew build
```

The jar ends up in `build/libs`. To try it on a local server:

```
./gradlew runServer
```
