# SkStorage

Skript variable storage that doesn't suck.

## why does this exist

The closest compatible plugin to this is PatPeter's SQLibrary for SQLite. The last release was 2013. It uses rollback journal mode (no WAL), one giant `variables.db` for everyone, stores `pd::<UUID>::eco.credits` as a 60 byte text key on every single row, and runs your user supplied regex on every write.

I needed a server that could handle more than a combined total of one variable. So this exists.

## what it does

Variables get sorted into one of three SQLite databases by name.

If the name matches a glob you put in `config.yml` under `persistent.patterns` (think leaderboards, credits, balances) it goes in `persistent.db`. That file uses dict encoded UUIDs and keys, so a million leaderboard rows ends up roughly a third the size it would otherwise.

If the name contains a UUID anywhere in it, it goes to `player/<first two hex>/<uuid>.db`. The file is opened when the player joins and closed when they quit. Open file handles roughly equal online players, not total players ever.

If the name starts with `sf::` it goes to `server.db` for global state.

Anything else is not persisted. It sits in Skript's RAM for the session and dies on restart. This catches typos that the old setup would happily save forever to nowhere useful.

WAL is on. Six other PRAGMAs are set. There's a flush task that commits every 200ms, so writes batch into transactions instead of fsyncing on every single set. Worst case data loss on a hard crash is 200 milliseconds.

## extras

There's a `playerdata.db` that tracks uuid, name, first join, last join, last quit, sessions, playtime, last world, optionally a hashed IP. None of this requires script involvement. Read it with `playerdata "field" of player` from Skript, or `/skstorage who <name>` from console. The hashed IP thing is off by default, see the config comment.

The migration tool reads a SQLibrary `variables21` table and routes every row into the new scopes. Dry run first, then `--i-have-backed-up`. It's single threaded because trying to multithread SQLite migration is how you get corruption. A million rows takes maybe ten minutes on a cheap VPS.

`/skstorage reset-season` wipes server.db and the player folder while leaving persistent.db and playerdata.db alone. It uses a confirmation token so you don't fat finger it.

## requirements

Paper 1.21, Skript 2.15.0, Java 21. Older Skripts might work but I haven't tested them

## install

Drop the jar in `plugins/`. Restart once so the config writes. Then copy `example-skript-databases.yml` from this repo into your Skript `config.sk` under `databases:`, replacing whatever's there (especially anything SQLibrary typed). Restart again.

If you have existing variables in `plugins/Skript/variables.db`:

```
/skstorage migrate sqlibrary ./plugins/Skript/variables.db --dry-run
/skstorage migrate sqlibrary ./plugins/Skript/variables.db --i-have-backed-up
```

Yes you have to actually back it up. The flag isn't an honor system, it's there so I have plausible deniability when you lose data.

## storage on disk

```
plugins/SkStorage/
  config.yml
  persistent.db
  playerdata.db
  server.db
  secret.key
  player/ab/abcd1234-....db
```

`secret.key` is the HMAC key for IP hashing. Don't commit it.

## commands

`/skstorage stats` shows disk and memory usage plus write counts per scope.

`/skstorage who <player>` looks up tracked metadata. Partial name match works.

`/skstorage reload` re reads `config.yml`. Doesn't touch Skript's databases section because that requires a full restart to take effect.

`/skstorage migrate sqlibrary <path>` is covered above.

`/skstorage reset-season` is the seasonal wipe.

`/skstorage debug` and `/skstorage flatline` exist for when something is weird and you want to inspect what's actually loaded.

## config

Most of it is self explanatory in the file. The two things people actually care about:

`persistent.patterns` is your allowlist. Add globs for anything cross player queryable. If you forget to add `credits::*` here, `loop {credits::*}` will not work for offline players. There's a `preload_players: true` option that fixes that retroactively at the cost of slower boot (I could load 600k variables in 9 seconds.)

`server.legacy_fallthrough: true` makes the server scope catch every unrouted variable, which is what you want during a migration from a codebase that didn't use `sf::` prefixes. Leave it false otherwise so you can find the typos.

## limitations I know about

The `playerdata "field" of player` expression sometimes doesn't register on Skript 2.15.0. Skript closes its registration window via a delayed task and we can lose the race. Auto tracking still works, you just can't read it from Skript. `/skstorage who` always works. PlaceholderAPI bridge is on the list.

CSV migrator is a stub. This will release in upcoming updates.

## before you trust this on a real server

Take a backup of your prod variables.db. Migrate it into a test instance. Verify a handful of variables read back the same values.

I tested this on my own server with a couple hundred million rows. It's pretty robust.
