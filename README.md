# SkStorage

## SkStorage is a specialised Skript variable storage plugin.

CSV is notorious for its lack of append, slow reads, and slow writes. Sometimes you need more information that it provides, sometimes you get a vague "Cannot write to variables at sufficient speed." That ends now.
SkStorage is a middle ground between the beautifully complex Skript-DB, and the decadent PatPeter SQLibrary plugin.
From options like Simple Mode, to complexity like using BigDecimal (a first of its kind in Skript), it's safe to say you and your variables will be safe, written at sufficient speed, and won't change your workflow.

## Why choose SkStorage over basic Skript variables?
The variable system that ships with Skript is "good enough," but not perfect. As mentioned above, Skript uses the .CSV format as its primary storage layer, this is bad for a few reasons:
CSV has no database protections, if your CSV file is mid-write when your server crashes, that's it, your data is messed up forever. CSV has to rewrite the whole file when adding one variable. This is a big one! For large databases, having to rewrite the whole thing just to add one line is walking to Mexico on foot to buy a taco.

## How do I know SkStorage isn't just as bad?
Because SkStorage doesn't use CSV. SkStorage stores variables in SQLite with write-ahead logging (WAL). The same crash safe, transactional engine behind browsers, phones, and aircraft. A crash mid-write doesn't corrupt your data, SQLite rolls back to the last consistent state. Adding one variable writes one row, not the whole file.

## It also stops doing the thing that makes big CSV setups drag:
Instead of one giant file every variable fights over, you route variables into separate databases, per-player data sharded into per-UUID files, global data in its own table, so a write touches a small file, not a 500MB monster.

## Honest caveat
SkStorage is new. It's built on a storage engine that is decades-proven, but the plugin around it is young. Test it on a copy of your data before you trust it with production. The /skstorage migrate and dry-run tools exist exactly so you can.
