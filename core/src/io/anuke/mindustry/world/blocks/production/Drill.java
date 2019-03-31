package io.anuke.mindustry.world.blocks.production;

import io.anuke.arc.Core;
import io.anuke.arc.collection.Array;
import io.anuke.arc.collection.ObjectIntMap;
import io.anuke.arc.util.Strings;
import io.anuke.mindustry.entities.Effects;
import io.anuke.mindustry.entities.Effects.Effect;
import io.anuke.arc.graphics.Blending;
import io.anuke.arc.graphics.Color;
import io.anuke.arc.graphics.g2d.Draw;
import io.anuke.arc.graphics.g2d.TextureRegion;
import io.anuke.arc.math.Mathf;
import io.anuke.arc.util.Time;
import io.anuke.mindustry.content.Fx;
import io.anuke.mindustry.content.Liquids;
import io.anuke.mindustry.entities.type.TileEntity;
import io.anuke.mindustry.graphics.Layer;
import io.anuke.mindustry.graphics.Pal;
import io.anuke.mindustry.type.Item;
import io.anuke.mindustry.type.ItemType;
import io.anuke.mindustry.ui.Bar;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Tile;
import io.anuke.mindustry.world.consumers.ConsumeLiquid;
import io.anuke.mindustry.world.meta.BlockGroup;
import io.anuke.mindustry.world.meta.BlockStat;
import io.anuke.mindustry.world.meta.StatUnit;

import static io.anuke.mindustry.Vars.content;

public class Drill extends Block{
    protected final static float hardnessDrillMultiplier = 50f;
    protected final int timerDump = timers++;

    protected final ObjectIntMap<Item> oreCount = new ObjectIntMap<>();
    protected final Array<Item> itemArray = new Array<>();

    /**Maximum tier of blocks this drill can mine.*/
    protected int tier;
    /**Base time to drill one ore, in frames.*/
    protected float drillTime = 300;
    /**Whether the liquid is required to drill. If false, then it will be used as a speed booster.*/
    protected boolean liquidRequired = false;
    /**How many times faster the drill will progress when boosted by liquid.*/
    protected float liquidBoostIntensity = 1.6f;
    /**Speed at which the drill speeds up.*/
    protected float warmupSpeed = 0.02f;

    /**Whether to draw the item this drill is mining.*/
    protected boolean drawMineItem = false;
    /**Effect played when an item is produced. This is colored.*/
    protected Effect drillEffect = Fx.mine;
    /**Speed the drill bit rotates at.*/
    protected float rotateSpeed = 2f;
    /**Effect randomly played while drilling.*/
    protected Effect updateEffect = Fx.pulverizeSmall;
    /**Chance the update effect will appear.*/
    protected float updateEffectChance = 0.02f;

    protected boolean drawRim = false;

    protected Color heatColor = Color.valueOf("ff5512");
    protected TextureRegion rimRegion;
    protected TextureRegion rotatorRegion;
    protected TextureRegion topRegion;

    public Drill(String name){
        super(name);
        update = true;
        solid = true;
        layer = Layer.overlay;
        group = BlockGroup.drills;
        hasLiquids = true;
        liquidCapacity = 5f;
        hasItems = true;

        consumes.liquid(Liquids.water, 0.05f).optional(true);
    }

    @Override
    public void setBars(){
        super.setBars();

        bars.add("drillspeed", e -> {
            DrillEntity entity = (DrillEntity)e;

            return new Bar(() -> Core.bundle.format("blocks.outputspeed", Strings.fixed(entity.lastDrillSpeed * 60 * entity.timeScale, 2)), () -> Pal.ammo, () -> entity.warmup);
        });
    }

    @Override
    public void load(){
        super.load();
        rimRegion = Core.atlas.find(name + "-rim");
        rotatorRegion = Core.atlas.find(name + "-rotator");
        topRegion = Core.atlas.find(name + "-top");
    }

    @Override
    public void draw(Tile tile){
        float s = 0.3f;
        float ts = 0.6f;

        DrillEntity entity = tile.entity();

        Draw.rect(region, tile.drawx(), tile.drawy());

        if(drawRim){
            Draw.color(heatColor);
            Draw.alpha(entity.warmup * ts * (1f - s + Mathf.absin(Time.time(), 3f, s)));
            Draw.blend(Blending.additive);
            Draw.rect(rimRegion, tile.drawx(), tile.drawy());
            Draw.blend();
            Draw.color();
        }

        Draw.rect(rotatorRegion, tile.drawx(), tile.drawy(), entity.drillTime * rotateSpeed);

        Draw.rect(topRegion, tile.drawx(), tile.drawy());

        if(entity.dominantItem != null && drawMineItem){
            Draw.color(entity.dominantItem.color);
            Draw.rect("blank", tile.drawx(), tile.drawy(), 2f, 2f);
            Draw.color();
        }
    }

    @Override
    public TextureRegion[] generateIcons(){
        return new TextureRegion[]{Core.atlas.find(name), Core.atlas.find(name + "-rotator"), Core.atlas.find(name + "-top")};
    }

    @Override
    public boolean canProduce(Tile tile){
        return tile.entity.items.total() < itemCapacity;
    }

    @Override
    public void setStats(){
        super.setStats();

        stats.add(BlockStat.drillTier, table -> {
            Array<Item> list = new Array<>();

            for(Item item : content.items()){
                if(tier >= item.hardness && Core.atlas.has(item.name + "1")){
                    list.add(item);
                }
            }

            for(int i = 0; i < list.size; i++){
                Item item = list.get(i);

                table.addImage(item.name + "1").size(8 * 3).padRight(2).padLeft(2).padTop(3).padBottom(3);
                table.add(item.localizedName());
                if(i != list.size - 1){
                    table.add("/").padLeft(5).padRight(5);
                }
            }
        });

        stats.add(BlockStat.drillSpeed, 60f / drillTime * size * size, StatUnit.itemsSecond);
    }

    @Override
    public void update(Tile tile){
        DrillEntity entity = tile.entity();

        if(entity.dominantItem == null){
            oreCount.clear();
            itemArray.clear();

            for(Tile other : tile.getLinkedTiles(tempTiles)){
                if(isValid(other)){
                    oreCount.getAndIncrement(getDrop(other), 0, 1);
                }
            }

            for(Item item : oreCount.keys()){
                itemArray.add(item);
            }

            itemArray.sort((item1, item2) -> Integer.compare(oreCount.get(item1, 0), oreCount.get(item2, 0)));
            itemArray.sort((item1, item2) -> Boolean.compare(item1.type == ItemType.material, item2.type == ItemType.material));

            if(itemArray.size == 0){
                return;
            }

            entity.dominantItem = itemArray.peek();
            entity.dominantItems = oreCount.get(itemArray.peek(), 0);
        }

        float totalHardness = entity.dominantItems * entity.dominantItem.hardness;

        if(entity.timer.get(timerDump, 15)){
            tryDump(tile);
        }

        entity.drillTime += entity.warmup * entity.delta();

        if(entity.items.total() < itemCapacity && entity.dominantItems > 0 && entity.cons.valid()){

            float speed = 1f;

            if(entity.consumed(ConsumeLiquid.class) && !liquidRequired){
                speed = liquidBoostIntensity;
            }
            if(hasPower){
                speed *= entity.power.satisfaction; // Drill slower when not at full power
            }

            entity.lastDrillSpeed = (speed * entity.dominantItems * entity.warmup) / (drillTime + hardnessDrillMultiplier * Math.max(totalHardness, 1f) / entity.dominantItems);
            entity.warmup = Mathf.lerpDelta(entity.warmup, speed, warmupSpeed);
            entity.progress += entity.delta()
            * entity.dominantItems * speed * entity.warmup;

            if(Mathf.chance(Time.delta() * updateEffectChance * entity.warmup))
                Effects.effect(updateEffect, entity.x + Mathf.range(size * 2f), entity.y + Mathf.range(size * 2f));
        }else{
            entity.lastDrillSpeed = 0f;
            entity.warmup = Mathf.lerpDelta(entity.warmup, 0f, warmupSpeed);
            return;
        }

        if(entity.dominantItems > 0 && entity.progress >= drillTime + hardnessDrillMultiplier * Math.max(totalHardness, 1f) / entity.dominantItems
                && tile.entity.items.total() < itemCapacity){

            offloadNear(tile, entity.dominantItem);

            useContent(tile, entity.dominantItem);

            entity.index++;
            entity.progress = 0f;

            Effects.effect(drillEffect, entity.dominantItem.color,
                    entity.x + Mathf.range(size), entity.y + Mathf.range(size));
        }
    }

    @Override
    public boolean canPlaceOn(Tile tile){
        if(isMultiblock()){
            for(Tile other : tile.getLinkedTilesAs(this, tempTiles)){
                if(isValid(other)){
                    return true;
                }
            }
            return false;
        }else{
            return isValid(tile);
        }
    }

    @Override
    public TileEntity newEntity(){
        return new DrillEntity();
    }

    public Item getDrop(Tile tile){
        return tile.drop();
    }

    public boolean isValid(Tile tile){
        if(tile == null) return false;
        Item drops = tile.drop();
        return drops != null && drops.hardness <= tier;
    }

    public static class DrillEntity extends TileEntity{
        float progress;
        int index;
        float warmup;
        float drillTime;
        float lastDrillSpeed;

        int dominantItems;
        Item dominantItem;
    }

}
