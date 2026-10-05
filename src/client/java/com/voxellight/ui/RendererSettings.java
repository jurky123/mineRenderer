package com.voxellight.ui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Settings use the same validated actions as commands; successful command edits update the UI too. */
public final class RendererSettings {
    public record Entry(String key,List<String> choices,String range) {
        public String label(){return key.replace('_',' ');}
        public String category(){
            if(key.startsWith("rt_")||key.equals("radiance_cache"))return "RTX";
            if(key.startsWith("water")||key.equals("underwater")||key.equals("caustics")||key.equals("rain_ripples"))return "Water";
            if(key.contains("cloud")||key.contains("atmosphere")||key.contains("volum")||key.equals("sky")||key.equals("forward_scatter"))return "Environment";
            if(key.contains("debug")||Set.of("status","profile","export","scene").contains(key))return "Diagnostics";
            return "Rendering";
        }
    }
    private static final com.voxellight.config.RendererPreferences preferences=new com.voxellight.config.RendererPreferences();
    private static final List<Entry> entries=new ArrayList<>();
    private static CommandDispatcher<FabricClientCommandSource> dispatcher;
    private static boolean replay,restoring;
    private static final java.nio.file.Path file=FabricLoader.getInstance().getConfigDir().resolve("voxellight/settings.json");
    private static String error="";
    public static List<Entry> entries(){return List.copyOf(entries);}
    public static String value(String key){return preferences.value(key);}
    public static String error(){return error;}
    public static void initialize(){
        try{preferences.load(file);}
        catch(Exception e){error="Could not load settings: "+e.getMessage();}
        ScreenEvents.AFTER_INIT.register((client,screen,width,height)->{
            if(screen instanceof PauseScreen||screen instanceof net.minecraft.client.gui.screens.options.OptionsScreen)Screens.getWidgets(screen).add(Button.builder(Component.literal("VoxelLight"),button->client.setScreenAndShow(new RendererSettingsScreen(screen))).bounds(width-108,8,100,20).build());
        });
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->replay=true);
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(replay&&dispatcher!=null&&client.level!=null&&client.player!=null&&client.getConnection()!=null){
                replay=false;restoring=true;
                try{for(var entry:preferences.snapshot().entrySet())apply(entry.getKey(),entry.getValue());}
                finally{restoring=false;}
            }
        });
    }
    public static void register(CommandDispatcher<FabricClientCommandSource> commands,CommandNode<FabricClientCommandSource> root){
        dispatcher=commands;entries.clear();
        for(var child:root.getChildren()){
            if(child.getName().equals("settings"))continue;
            var choices=new ArrayList<String>();String range="";
            for(var option:child.getChildren()){
                if(option instanceof ArgumentCommandNode<?,?> argument){
                    if(argument.getType() instanceof FloatArgumentType number)range=number.getMinimum()+" .. "+number.getMaximum();
                    if(argument.getType() instanceof IntegerArgumentType number)range=number.getMinimum()+" .. "+number.getMaximum();
                }else if(option.getCommand()!=null)choices.add(option.getName());
            }
            if(!choices.isEmpty()||!range.isEmpty()||child.getCommand()!=null)entries.add(new Entry(child.getName(),List.copyOf(choices),range));
        }
        entries.add(new Entry("rt_accumulate spp",List.of(),"4 .. 4096"));
        entries.sort(Comparator.comparing(Entry::key));
        commands.setConsumer((context,success,result)->{
            if(!success||result<=0)return;
            if(preferences.record(context.getInput(),!restoring))save();
        });
    }
    private static void save(){try{preferences.save(file);}catch(Exception e){error="Could not save settings: "+e.getMessage();}}
    public static boolean apply(String key,String value){
        var client=Minecraft.getInstance();
        if(dispatcher==null||client.getConnection()==null){error="Join a world before changing rendering settings.";return false;}
        error="";
        try{
            int result=dispatcher.execute("voxellight "+key+(value.isBlank()?"":" "+value),(FabricClientCommandSource)client.getConnection().getSuggestionsProvider());
            if(result<=0){error="This option is unavailable; inspect chat/status.";return false;}
            return true;
        }catch(Exception e){error=Objects.toString(e.getMessage(),"Invalid setting value");return false;}
    }
}
