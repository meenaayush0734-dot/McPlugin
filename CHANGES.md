# SurvivalPlus 2.2.0 - changes

## Homes
- Personal home limit: 2 -> 3 (`max-homes`).
- Removed "24 ghante me 3 baar /home" limit.
- `/home` ab 10 minute me 1 baar (`home-cooldown-minutes: 10`). Bypass permission me cooldown nahi.
- Team homes: `/team sethome <name>` (owner/admin), members `/home <name>` se jaa sakte hain.

## Teams (new)
- `/createteam <name>` - team banao, tum owner ho (3-12 chars: letters, numbers, _)
- `/team help` - sab team commands
- `/team info [team]`
- `/team invite <player>` + `/team accept` (60 sec me valid)  - owner/admin
- `/team kick <player>`, `/team leave`, `/team disband confirm` (owner)
- `/team color <color>` - owner ya admin
- `/team admin <player>` - sirf owner, admin toggle
- `/team enderchest` (alias `/team ec`) - team ka shared enderchest, saare members dekh/rakh sakte hain
- `/team sethome <name>`, `/team delhome <name>` - owner/admin
- `/team ally <team>` - toggle, owner/admin (dono teams me mutual)
- Naam ke saamne `[TeamName]` team color me, bold me (chat, tab list, nametag)

## Discord
- Chat me "discord" likhne par bot `https://discord.gg/ccYUmyTPsc` bhejta hai.
- `/discordlink` - link dikhata hai (sab)
- `/discordlink <https://...>` - link change, sirf OP

## Data
- Purane data.yml homes wahi format me hain (homes.<uuid>.<name>.*), load ho jaate hain.
- `uses.<uuid>` (purana 24h limit data) ab use nahi hota.
