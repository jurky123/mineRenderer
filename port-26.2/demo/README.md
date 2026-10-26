# Material and lighting demonstration data pack

These are the original functions from the validated Minecraft 26.2 demonstration
save. They build a grass/vegetation area, 12 material samples, glass before
contrasting walls, shallow/deep water, a roofed lamp gallery and two static mobs.
New players receive a small creative-mode inspection kit.

Use a **new disposable creative world with commands enabled**. Copy
`datapacks/remix_demo` into that world's `datapacks/` directory and run `/reload`.
After two seconds, setup runs once and teleports players to the entrance near
`8 99 -7`. The scene assumes an open area around Y=98; a superflat world works.
The layout occupies approximately X=-12..68 and Z=-10..48, with water below Y=98.

Setup replaces blocks in that area, clears connected players' inventories,
changes time/weather/game rules and gives the inspection kit. Do not load it in
an existing personal world. It uses storage to prevent rebuilding on every load
and a player tag to give the welcome kit only once per player.

The already installed local save is named `RTX Remix 材质与光照演示` and needs no
manual setup. This repository contains the data pack, rather than Minecraft
region binaries or player saves. SPBR is optional and installed separately.
