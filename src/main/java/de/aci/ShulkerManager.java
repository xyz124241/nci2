package de.aci;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.bukkit.NamespacedKey;

import java.io.*;
import java.util.Map;
import java.util.HashMap;

/** Persistent virtual shulker storage with configurable 1-6 rows. */
public final class ShulkerManager implements Listener {
    private final AciPlugin plugin;
    private final Map<String, Integer> openAt = new HashMap<>();
    private final NamespacedKey marker, rowsKey, pickupKey, dataKey, itemIdKey, originalItemKey;
    public ShulkerManager(AciPlugin plugin){this.plugin=plugin;marker=new NamespacedKey(plugin,"aci_shulker");rowsKey=new NamespacedKey(plugin,"shulker_rows");pickupKey=new NamespacedKey(plugin,"shulker_auto_pickup");dataKey=new NamespacedKey(plugin,"shulker_contents");itemIdKey=new NamespacedKey(plugin,"id");originalItemKey=new NamespacedKey(plugin,"shulker_original_item");}

    @EventHandler public void onPlace(BlockPlaceEvent e){
        ItemStack item=e.getItemInHand();if(!item.hasItemMeta())return;
        String id=item.getItemMeta().getPersistentDataContainer().get(itemIdKey,PersistentDataType.STRING);CustomItem ci=plugin.items().get(id);
        if(ci==null||!ItemManager.isShulkerMaterial(ci.material))return;
        Block block=e.getBlockPlaced();if(!(block.getState() instanceof TileState tile))return;
        PersistentDataContainer from=item.getItemMeta().getPersistentDataContainer(),to=tile.getPersistentDataContainer();
        int rows=from.getOrDefault(rowsKey,PersistentDataType.INTEGER,ci.shulkerRows);
        byte pickup=from.getOrDefault(pickupKey,PersistentDataType.BYTE,(byte)(ci.shulkerAutoPickup?1:0));
        to.set(marker,PersistentDataType.BYTE,(byte)1);to.set(rowsKey,PersistentDataType.INTEGER,Math.max(1,Math.min(6,rows)));to.set(pickupKey,PersistentDataType.BYTE,pickup);
        to.set(itemIdKey,PersistentDataType.STRING,id);
        // Keep the exact placed item so custom display name, dye/color and other meta survive break/place.
        to.set(originalItemKey,PersistentDataType.BYTE_ARRAY,serializeItem(item));
        byte[] stored=from.get(dataKey,PersistentDataType.BYTE_ARRAY);
        // Read vanilla ShulkerBox contents from BlockStateMeta as a fallback. This is
        // the format understood by client-side Shulker Box Tooltip mods.
        if (stored == null && item.getItemMeta() instanceof BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof ShulkerBox shulkerState) {
            stored = serialize(shulkerState.getInventory().getContents());
        }
        to.set(dataKey,PersistentDataType.BYTE_ARRAY,stored==null?serialize(new ItemStack[Math.max(1,Math.min(6,rows))*9]):stored);
        tile.update(true,false);
    }

    @EventHandler public void onInteract(PlayerInteractEvent e){
        if(e.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND||e.getAction()!=Action.RIGHT_CLICK_BLOCK||e.getClickedBlock()==null)return;
        Block block=e.getClickedBlock();if(!(block.getState() instanceof TileState tile))return;
        PersistentDataContainer pdc=tile.getPersistentDataContainer();if(!pdc.has(marker,PersistentDataType.BYTE))return;
        e.setCancelled(true);
        if (openAt.getOrDefault(key(block.getLocation()), 0) > 0) {
            e.getPlayer().sendMessage("§cDiese Custom-Shulker ist bereits geöffnet.");
            return;
        }
        int rows=Math.max(1,Math.min(6,pdc.getOrDefault(rowsKey,PersistentDataType.INTEGER,3)));
        VirtualHolder holder=new VirtualHolder(block.getLocation(),rows);byte[] bytes=pdc.get(dataKey,PersistentDataType.BYTE_ARRAY);ItemStack[] contents=deserialize(bytes,rows*9);
        holder.inventory.setContents(contents);
        openAt.merge(key(block.getLocation()), 1, Integer::sum);
        e.getPlayer().openInventory(holder.inventory);
    }

    @EventHandler public void onClose(InventoryCloseEvent e){
        if(!(e.getInventory().getHolder(false) instanceof VirtualHolder holder))return;
        openAt.computeIfPresent(key(holder.location), (k,v) -> v <= 1 ? null : v - 1);
        Block block=holder.location.getBlock();if(!(block.getState() instanceof TileState tile))return;
        PersistentDataContainer pdc=tile.getPersistentDataContainer();if(!pdc.has(marker,PersistentDataType.BYTE))return;
        pdc.set(dataKey,PersistentDataType.BYTE_ARRAY,serialize(holder.inventory.getContents()));tile.update(true,false);
    }

    @EventHandler public void onBreak(BlockBreakEvent e){
        Block block=e.getBlock();if(!(block.getState() instanceof TileState tile))return;
        PersistentDataContainer pdc=tile.getPersistentDataContainer();if(!pdc.has(marker,PersistentDataType.BYTE))return;
        if (openAt.getOrDefault(key(block.getLocation()), 0) > 0) { e.setCancelled(true); e.getPlayer().sendMessage("§cSchließe zuerst die Custom-Shulker."); return; }
        e.setCancelled(true);e.setDropItems(false);e.setExpToDrop(0);
        int rows=Math.max(1,Math.min(6,pdc.getOrDefault(rowsKey,PersistentDataType.INTEGER,3)));
        boolean pickup=pdc.getOrDefault(pickupKey,PersistentDataType.BYTE,(byte)1)==1;
        byte[] data=pdc.get(dataKey,PersistentDataType.BYTE_ARRAY);
        // Restore the exact item that was placed instead of recreating the default ACI item.
        // Recreating via ItemManager would reset a player's custom name and color.
        ItemStack drop=deserializeItem(pdc.get(originalItemKey,PersistentDataType.BYTE_ARRAY));
        String id=pdc.get(itemIdKey,PersistentDataType.STRING);
        if(drop==null){
            drop=new ItemStack(block.getType());
            CustomItem ci=plugin.items().get(id);
            if(ci!=null)drop=plugin.items().create(ci,1);
        }
        drop.setAmount(1);
        ItemMeta meta=drop.getItemMeta();PersistentDataContainer itemPdc=meta.getPersistentDataContainer();
        itemPdc.set(marker,PersistentDataType.BYTE,(byte)1);itemPdc.set(rowsKey,PersistentDataType.INTEGER,rows);itemPdc.set(pickupKey,PersistentDataType.BYTE,(byte)(pickup?1:0));
        byte[] contents = data==null?serialize(new ItemStack[rows*9]):data;
        itemPdc.set(dataKey,PersistentDataType.BYTE_ARRAY,contents);
        if(id!=null)itemPdc.set(itemIdKey,PersistentDataType.STRING,id);
        drop.setItemMeta(meta);

        // Also write the contents into the vanilla ShulkerBox block-state data.
        // Shulker Box Tooltip (client mod) reads this standard item data, not ACI's PDC.
        if (drop.getItemMeta() instanceof BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof ShulkerBox shulkerState) {
            ItemStack[] tooltipContents = deserialize(contents, 27);
            shulkerState.getInventory().setContents(tooltipContents);
            blockMeta.setBlockState(shulkerState);
            drop.setItemMeta(blockMeta);
        }
        block.setType(Material.AIR,false);
        if(pickup){Map<Integer,ItemStack> rest=e.getPlayer().getInventory().addItem(drop);rest.values().forEach(r->block.getWorld().dropItemNaturally(block.getLocation(),r));}
        else block.getWorld().dropItemNaturally(block.getLocation(),drop);
    }


    @EventHandler public void onVirtualInventoryClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof VirtualHolder)) return;
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        if ((cursor != null && ItemManager.isShulkerMaterial(cursor.getType()))
                || (current != null && ItemManager.isShulkerMaterial(current.getType()))) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) p.sendMessage("§cShulker-Boxen können nicht ineinander gelagert werden.");
        }
    }


    @EventHandler public void onVirtualInventoryDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof VirtualHolder)) return;
        ItemStack cursor = e.getOldCursor();
        if (cursor != null && ItemManager.isShulkerMaterial(cursor.getType())) e.setCancelled(true);
    }


    /** Persist open virtual inventories before the plugin is disabled or reloaded. */
    public void saveAndCloseOpenInventories() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player.getOpenInventory().getTopInventory().getHolder(false) instanceof VirtualHolder holder)) continue;
            Block block = holder.location.getBlock();
            if (block.getState() instanceof TileState tile) {
                PersistentDataContainer pdc = tile.getPersistentDataContainer();
                if (pdc.has(marker, PersistentDataType.BYTE)) {
                    pdc.set(dataKey, PersistentDataType.BYTE_ARRAY, serialize(holder.inventory.getContents()));
                    tile.update(true, false);
                }
            }
            player.closeInventory();
        }
        openAt.clear();
    }

    private static String key(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private static byte[] serialize(ItemStack[] items){
        try{ByteArrayOutputStream b=new ByteArrayOutputStream();try(BukkitObjectOutputStream o=new BukkitObjectOutputStream(b)){o.writeObject(items);}return b.toByteArray();}
        catch(IOException ex){return new byte[0];}
    }
    private static byte[] serializeItem(ItemStack item){
        try{ByteArrayOutputStream b=new ByteArrayOutputStream();try(BukkitObjectOutputStream o=new BukkitObjectOutputStream(b)){o.writeObject(item.clone());}return b.toByteArray();}
        catch(IOException ex){return new byte[0];}
    }
    private static ItemStack deserializeItem(byte[] bytes){
        if(bytes==null||bytes.length==0)return null;
        try(BukkitObjectInputStream in=new BukkitObjectInputStream(new ByteArrayInputStream(bytes))){Object obj=in.readObject();if(obj instanceof ItemStack item)return item.clone();}
        catch(Exception ignored){}
        return null;
    }
    private static ItemStack[] deserialize(byte[] bytes,int size){
        if(bytes==null||bytes.length==0)return new ItemStack[size];
        try(BukkitObjectInputStream in=new BukkitObjectInputStream(new ByteArrayInputStream(bytes))){Object obj=in.readObject();if(obj instanceof ItemStack[] a){ItemStack[] out=new ItemStack[size];System.arraycopy(a,0,out,0,Math.min(size,a.length));return out;}}
        catch(Exception ignored){}return new ItemStack[size];
    }
    private static final class VirtualHolder implements InventoryHolder{
        private final Location location;private final Inventory inventory;
        private VirtualHolder(Location location,int rows){this.location=location.clone();this.inventory=Bukkit.createInventory(this,rows*9,"ACI Shulker");}
        @Override public Inventory getInventory(){return inventory;}
    }
}
