gamerule minecraft:advance_time false
gamerule minecraft:advance_weather false
gamerule minecraft:spawn_mobs false
gamerule minecraft:random_tick_speed 0
time set 6000
weather clear
difficulty peaceful
fill -12 98 -10 68 98 48 smooth_stone
fill -12 97 -10 68 97 48 stone
fill -12 99 -10 14 116 48 air
fill 15 99 -10 41 116 48 air
fill 42 99 -10 68 116 48 air
fill -12 99 -10 68 99 -10 polished_andesite
fill -12 99 48 68 99 48 polished_andesite
fill -12 99 -10 -12 99 48 polished_andesite
fill 68 99 -10 68 99 48 polished_andesite
fill -4 99 3 9 99 12 dirt
fill -4 100 3 9 100 12 grass_block
fill -3 101 7 2 102 10 dirt
fill -3 103 7 2 103 10 grass_block
setblock 6 101 5 short_grass
setblock 7 101 7 fern
setblock 5 101 8 poppy
fill 6 101 10 8 103 12 oak_leaves[persistent=true]
fill 16 99 3 17 101 4 stone
fill 20 99 3 21 101 4 polished_granite
fill 24 99 3 25 101 4 bricks
fill 28 99 3 29 101 4 oak_planks
fill 32 99 3 33 101 4 iron_block
fill 36 99 3 37 101 4 gold_block
fill 16 99 9 17 101 10 copper_block
fill 20 99 9 21 101 10 oxidized_copper
fill 24 99 9 25 101 10 quartz_block
fill 28 99 9 29 101 10 slime_block
fill 32 99 9 33 101 10 honey_block
fill 36 99 9 37 101 10 amethyst_block
fill 44 99 4 46 102 4 glass
fill 44 99 8 46 102 8 black_concrete
fill 45 99 8 45 102 8 white_concrete
fill 49 99 4 51 102 4 white_stained_glass
fill 49 99 8 51 102 8 black_concrete
fill 50 99 8 50 102 8 white_concrete
fill 54 99 4 56 102 4 cyan_stained_glass
fill 54 99 8 56 102 8 black_concrete
fill 55 99 8 55 102 8 white_concrete
fill 59 99 4 61 102 4 glass_pane
fill 59 99 8 61 102 8 black_concrete
fill 60 99 8 60 102 8 white_concrete
fill 40 99 24 62 106 40 stone_bricks
fill 41 99 25 61 105 39 air
fill 41 98 25 61 98 39 oak_planks
fill 49 99 24 53 102 24 air
setblock 43 101 36 glowstone
setblock 48 101 36 sea_lantern
setblock 53 101 36 shroomlight
setblock 58 101 36 ochre_froglight
setblock 45 104 29 lantern[hanging=true]
setblock 57 104 29 soul_lantern[hanging=true]
fill -7 88 23 28 97 43 smooth_sandstone
fill -6 89 24 27 98 42 water
fill -6 89 24 27 96 28 sand
fill -6 89 29 27 94 32 sand
fill -6 89 33 27 89 42 sand
fill 8 89 35 13 89 39 quartz_block
setblock 8 99 -5 oak_sign[rotation=8]{is_waxed:true,front_text:{messages:[{"text":"RTX Remix 26.2"},{"text":"材质与光照演示"},{"text":"前方：草地 / 材质 / 玻璃"},{"text":"后方：深浅水 / 灯光屋"}],has_glowing_text:true}}
setblock 2 99 1 oak_sign[rotation=8]{is_waxed:true,front_text:{messages:[{"text":"01 草方块与植物"},{"text":"检查草皮边缘和透明面"},{"text":"可放置、破坏或移动观察"},{"text":"创造模式自由体验"}],has_glowing_text:true}}
setblock 25 99 1 oak_sign[rotation=8]{is_waxed:true,front_text:{messages:[{"text":"02 原版与 PBR 材质"},{"text":"金属、石材、木材"},{"text":"背包已提供玻璃和光源"},{"text":"材质包：SPBR"}],has_glowing_text:true}}
setblock 51 99 1 oak_sign[rotation=8]{is_waxed:true,front_text:{messages:[{"text":"03 玻璃与运动"},{"text":"背后的条纹便于观察"},{"text":"拿起背包中的玻璃"},{"text":"走动、转头检查残影"}],has_glowing_text:true}}
setblock 10 99 21 oak_sign[rotation=8]{is_waxed:true,front_text:{messages:[{"text":"04 浅水与深水"},{"text":"近岸保留底色"},{"text":"远端水深逐渐增加"},{"text":"可飞行或下水查看"}],has_glowing_text:true}}
setblock 51 99 22 oak_sign[rotation=8]{is_waxed:true,front_text:{messages:[{"text":"05 灯光屋"},{"text":"发光表面与墙面照明"},{"text":"萤石、海晶灯、菌光体"},{"text":"普通灯笼 / 灵魂灯笼"}],has_glowing_text:true}}
summon cow 33 99 17 {NoAI:true,PersistenceRequired:true,Invulnerable:true}
summon sheep 36 99 17 {NoAI:true,PersistenceRequired:true,Invulnerable:true,Color:6b}
gamemode creative @a
clear @a
give @a glass 64
give @a cyan_stained_glass 64
give @a grass_block 64
give @a lantern 64
give @a glowstone 64
give @a water_bucket
give @a gold_block 64
setworldspawn 8 99 -7
spawnpoint @a 8 99 -7
tp @a 8 99 -7 0 10
data modify storage remix_demo:state initialized set value 1b
say REMIX_DEMO_READY
