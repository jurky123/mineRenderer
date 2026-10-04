package com.voxellight.ui;

import com.voxellight.VoxelLightClient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import java.util.List;

/** Vanilla widgets, searchable pages and explicit values; no dependency on server UI services. */
public final class RendererSettingsScreen extends Screen {
    private final Screen parent;
    private String category="Rendering",query="";
    private int page;
    private int statusTicks;private String status="";
    private List<RendererSettings.Entry> visible=List.of();
    public RendererSettingsScreen(Screen parent){super(Component.literal("VoxelLight settings"));this.parent=parent;}
    private Button button(String label,int x,int y,int w,Runnable action){return addRenderableWidget(Button.builder(Component.literal(label),b->action.run()).bounds(x,y,w,20).build());}
    @Override protected void init(){
        int left=Math.max(8,(width-460)/2), span=Math.min(460,width-16);
        String[] groups={"Rendering","RTX","Environment","Water","Diagnostics"};
        for(int i=0;i<groups.length;i++){String group=groups[i];button(group,left+i*span/5,32,span/5-2,()->{category=group;page=0;rebuildWidgets();});}
        var search=addRenderableWidget(new EditBox(font,left,58,span,20,Component.literal("Search settings")));
        search.setMaxLength(80);search.setValue(query);search.setHint(Component.literal("Search settings"));
        search.setResponder(value->{query=value;page=0;rebuildWidgets();});
        visible=RendererSettings.entries().stream().filter(e->e.category().equals(category)&&e.label().contains(query.toLowerCase(java.util.Locale.ROOT))).toList();
        int rows=Math.max(1,(height-160)/24),pages=Math.max(1,(visible.size()+rows-1)/rows);page=Math.min(page,pages-1);
        for(int row=0;row<rows&&page*rows+row<visible.size();row++){
            var entry=visible.get(page*rows+row);int y=86+row*24;
            String current=RendererSettings.value(entry.key());
            if(!entry.range().isEmpty()){
                button(entry.label(),left,y,span/2-4,()->{}).active=false;
                var field=addRenderableWidget(new EditBox(font,left+span/2,y,span/3-8,20,Component.literal(entry.label())));
                field.setValue(current);field.setHint(Component.literal(entry.range()));field.setMaxLength(32);
                button("Apply",left+span*5/6,y,span/6,()->RendererSettings.apply(entry.key(),field.getValue()));
            }else if(entry.choices().isEmpty())button(entry.label(),left,y,span,()->RendererSettings.apply(entry.key(),""));
            else {
                var b=button(entry.label()+": "+(current.isEmpty()?"Default — choose":current),left,y,span,()->minecraft.setScreenAndShow(new ChoiceScreen(this,entry)));
                b.setTooltip(Tooltip.create(Component.literal("Choose a value. Default means no saved override; commands and UI share the same settings.")));
            }
        }
        button("Previous",left,height-54,90,()->{page=Math.max(0,page-1);rebuildWidgets();});
        button("Next",left+94,height-54,90,()->{page=Math.min(pages-1,page+1);rebuildWidgets();});
        button("Done",left+span-100,height-54,100,this::onClose);
        if(!query.isEmpty())setInitialFocus(search);
    }
    private static final class ChoiceScreen extends Screen {
        private final RendererSettingsScreen parent;
        private final RendererSettings.Entry entry;
        private int page;
        ChoiceScreen(RendererSettingsScreen parent,RendererSettings.Entry entry){super(Component.literal(entry.label()));this.parent=parent;this.entry=entry;}
        @Override protected void init(){
            int rows=Math.max(1,(height-100)/24),pages=(entry.choices().size()+rows-1)/rows,left=Math.max(8,(width-300)/2),span=Math.min(300,width-16);
            for(int row=0;row<rows&&page*rows+row<entry.choices().size();row++){
                String value=entry.choices().get(page*rows+row);
                addRenderableWidget(Button.builder(Component.literal(value.replace('_',' ')),b->{if(RendererSettings.apply(entry.key(),value))minecraft.setScreenAndShow(parent);}).bounds(left,40+row*24,span,20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Previous"),b->{page=Math.max(0,page-1);rebuildWidgets();}).bounds(left,height-40,90,20).build());
            addRenderableWidget(Button.builder(Component.literal("Next"),b->{page=Math.min(pages-1,page+1);rebuildWidgets();}).bounds(left+94,height-40,90,20).build());
            addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(left+span-90,height-40,90,20).build());
        }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics,int x,int y,float partial){super.extractRenderState(graphics,x,y,partial);graphics.centeredText(font,title,width/2,15,0xffffffff);if(!RendererSettings.error().isEmpty())graphics.centeredText(font,font.plainSubstrByWidth(RendererSettings.error(),width-16),width/2,height-64,0xffff7777);}
        @Override public boolean isPauseScreen(){return false;}
        @Override public void onClose(){minecraft.setScreenAndShow(parent);}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){
        super.extractRenderState(graphics,mouseX,mouseY,partial);
        graphics.centeredText(font,title,width/2,12,0xffffffff);
        if(status.isEmpty())status=VoxelLightClient.probe().status();
        java.util.regex.Matcher progress=java.util.regex.Pattern.compile("rtReferenceSamples=([0-9]+).*?rtReference=(true|false), rtReferenceTargetSpp=([0-9]+)").matcher(status);
        String line=progress.find()?"Reference: "+(progress.group(2).equals("true")?progress.group(1)+" / "+progress.group(3)+" spp":"off"):"RTX inactive — select RTX Quality or Reference on";
        if(status.contains("unsupported backend"))line="RTX requires Vulkan; change Minecraft graphics backend and restart.";
        var failure=java.util.regex.Pattern.compile("rt=(RTX unavailable[^,]*)").matcher(status);if(failure.find())line=font.plainSubstrByWidth(failure.group(1),width-16);
        graphics.centeredText(font,RendererSettings.error().isEmpty()?line:font.plainSubstrByWidth(RendererSettings.error(),width-16),width/2,height-28,RendererSettings.error().isEmpty()?0xffcccccc:0xffff7777);
    }
    @Override public void tick(){super.tick();if(++statusTicks%20==0)status=VoxelLightClient.probe().status();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){minecraft.setScreenAndShow(parent);}
}
