package ru.example.threexthree;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public class MineListener implements Listener {

    private final JavaPlugin plugin;
    private final NamespacedKey pickaxeKey;
    private final NamespacedKey radiusKey;

    private static final int DEFAULT_RADIUS = 3;

    // защёлка: пока true, наши собственные искусственные BlockBreakEvent
    // не должны заново запускать всю логику ломания области
    private boolean processing = false;

    // ниже — значения, подгружаемые из config.yml через reload()
    private List<Material> blacklist = new ArrayList<>();
    private boolean breakSoundEnabled = true;
    private Sound breakSound = Sound.ENTITY_ITEM_BREAK;
    private float breakSoundVolume = 1.0f;
    private float breakSoundPitch = 1.0f;

    // регионы: список паттернов имён (поддерживается * как wildcard в конце)
    private List<String> allowedRegionPatterns = new ArrayList<>();
    private String regionBlockedMessage = "&c&lНельзя! Тут регион!";

    public MineListener(JavaPlugin plugin, NamespacedKey pickaxeKey, NamespacedKey radiusKey) {
        this.plugin = plugin;
        this.pickaxeKey = pickaxeKey;
        this.radiusKey = radiusKey;
    }

    /**
     * Перечитывает настройки из config.yml. Вызывается при включении плагина
     * и при выполнении команды /3x3reload.
     */
    public void reload(FileConfiguration config) {
        List<String> names = config.getStringList("blacklisted-blocks");
        List<Material> parsed = new ArrayList<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                plugin.getLogger().warning("Неизвестный материал в blacklisted-blocks: " + name);
                continue;
            }
            parsed.add(material);
        }
        this.blacklist = parsed;

        this.breakSoundEnabled = config.getBoolean("break-sound.enabled", true);
        String soundName = config.getString("break-sound.sound", "ENTITY_ITEM_BREAK");
        try {
            this.breakSound = Sound.valueOf(soundName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Неизвестный звук в break-sound.sound: " + soundName + ", использую ENTITY_ITEM_BREAK");
            this.breakSound = Sound.ENTITY_ITEM_BREAK;
        }
        this.breakSoundVolume = (float) config.getDouble("break-sound.volume", 1.0);
        this.breakSoundPitch  = (float) config.getDouble("break-sound.pitch",  1.0);

        this.allowedRegionPatterns = config.getStringList("region.allowed-regions");
        this.regionBlockedMessage  = config.getString("region.region-blocked-message", "&c&lНельзя! Тут регион!");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (processing) return;

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        ItemMeta meta = tool.getItemMeta();

        if (meta == null || !meta.getPersistentDataContainer().has(pickaxeKey, PersistentDataType.BYTE)) {
            return;
        }

        processing = true;
        try {
            handleAreaBreak(event, player, tool, meta);
        } finally {
            processing = false;
        }
    }

    private void handleAreaBreak(BlockBreakEvent event, Player player, ItemStack tool, ItemMeta meta) {
        int radius = meta.getPersistentDataContainer().getOrDefault(radiusKey, PersistentDataType.INTEGER, DEFAULT_RADIUS);
        int half = radius / 2;

        Block center = event.getBlock();
        BlockFace face = getTargetFace(player, center);

        List<Block> toBreak = new ArrayList<>();
        for (int a = -half; a <= half; a++) {
            for (int b = -half; b <= half; b++) {
                if (a == 0 && b == 0) continue;
                Block target = offsetBlock(center, face, a, b);
                if (!target.equals(center)) {
                    toBreak.add(target);
                }
            }
        }

        boolean regionMessageSent = false;

        for (Block block : toBreak) {
            ItemStack currentTool = player.getInventory().getItemInMainHand();
            if (currentTool.getType() == Material.AIR) break;

            // Проверяем регионы WorldGuard напрямую, без фейкового BlockBreakEvent
            BlockRegionResult regionResult = checkRegion(block, player);
            if (regionResult == BlockRegionResult.BLOCKED) {
                if (!regionMessageSent) {
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', regionBlockedMessage));
                    regionMessageSent = true;
                }
                continue; // пропускаем блок, не ломаем
            }

            breakExtra(block, player, currentTool);
        }
    }

    /**
     * Проверяет, можно ли ломать блок с точки зрения регионов WorldGuard.
     *
     * Возвращает:
     *   ALLOWED  — регион разрешён конфигом, игрок owner/member, или WorldGuard не установлен
     *   BLOCKED  — блок находится в чужом регионе, которого нет в allowed-regions
     */
    private BlockRegionResult checkRegion(Block block, Player player) {
        // Если WorldGuard не установлен — разрешаем всё
        if (!isWorldGuardEnabled()) return BlockRegionResult.ALLOWED;

        // Если конфиг содержит "*" — разрешаем всё
        if (allowedRegionPatterns.contains("*")) return BlockRegionResult.ALLOWED;

        try {
            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionManager manager = container.get(BukkitAdapter.adapt(block.getWorld()));
            if (manager == null) return BlockRegionResult.ALLOWED;

            com.sk89q.worldedit.math.BlockVector3 pos =
                    com.sk89q.worldedit.math.BlockVector3.at(block.getX(), block.getY(), block.getZ());

            // UUID игрока для проверки owner/member
            com.sk89q.worldguard.LocalPlayer wgPlayer =
                    WorldGuard.getInstance().getPlatform().getMatcher().getPlayer(BukkitAdapter.adapt(player));

            // Получаем все регионы, в которых находится блок (кроме __global__)
            java.util.List<ProtectedRegion> regions = new java.util.ArrayList<>();
            for (ProtectedRegion region : manager.getApplicableRegions(pos)) {
                if (!region.getId().equals("__global__")) {
                    regions.add(region);
                }
            }

            // Блок вне каких-либо регионов (не считая __global__) — разрешаем
            if (regions.isEmpty()) return BlockRegionResult.ALLOWED;

            for (ProtectedRegion region : regions) {
                // Разрешён по белому списку паттернов
                if (matchesAnyPattern(region.getId())) return BlockRegionResult.ALLOWED;

                // Игрок является владельцем или участником этого региона
                if (region.isOwner(wgPlayer) || region.isMember(wgPlayer)) return BlockRegionResult.ALLOWED;
            }

            // Ни один регион не разрешён — блокируем
            return BlockRegionResult.BLOCKED;

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при проверке региона WorldGuard: " + e.getMessage());
            return BlockRegionResult.ALLOWED;
        }
    }

    /**
     * Проверяет, совпадает ли имя региона с одним из паттернов в конфиге.
     * Поддерживается wildcard * только в конце: "mine_*" совпадёт с "mine_world", "mine_2" и т.п.
     */
    private boolean matchesAnyPattern(String regionId) {
        for (String pattern : allowedRegionPatterns) {
            if (pattern.equals("*")) return true;
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (regionId.startsWith(prefix)) return true;
            } else {
                if (regionId.equalsIgnoreCase(pattern)) return true;
            }
        }
        return false;
    }

    private boolean isWorldGuardEnabled() {
        return Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }

    private enum BlockRegionResult {
        ALLOWED, BLOCKED
    }

    private BlockFace getTargetFace(Player player, Block center) {
        RayTraceResult result = player.rayTraceBlocks(6.0);
        if (result != null && result.getHitBlock() != null
                && result.getHitBlock().equals(center)
                && result.getHitBlockFace() != null) {
            return result.getHitBlockFace();
        }

        Vector dir = player.getEyeLocation().getDirection();
        double ax = Math.abs(dir.getX());
        double ay = Math.abs(dir.getY());
        double az = Math.abs(dir.getZ());

        if (ax >= ay && ax >= az) {
            return dir.getX() > 0 ? BlockFace.WEST : BlockFace.EAST;
        } else if (ay >= ax && ay >= az) {
            return dir.getY() > 0 ? BlockFace.DOWN : BlockFace.UP;
        } else {
            return dir.getZ() > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
        }
    }

    private Block offsetBlock(Block center, BlockFace face, int a, int b) {
        switch (face) {
            case UP:
            case DOWN:
                return center.getRelative(a, 0, b);
            case NORTH:
            case SOUTH:
                return center.getRelative(a, b, 0);
            case EAST:
            case WEST:
            default:
                return center.getRelative(0, b, a);
        }
    }

    private void breakExtra(Block block, Player player, ItemStack tool) {
        Material type = block.getType();

        if (type.isAir()) return;
        if (blacklist.contains(type)) return;
        if (type.getHardness() < 0) return;
        if (block.getState() instanceof Container) return;

        BlockBreakEvent fakeEvent = new BlockBreakEvent(block, player);
        Bukkit.getPluginManager().callEvent(fakeEvent);
        if (fakeEvent.isCancelled()) return;

        if (fakeEvent.isDropItems()) {
            block.breakNaturally(tool);
        } else {
            block.setType(Material.AIR);
        }

        damageTool(player, tool);
    }

    private void damageTool(Player player, ItemStack tool) {
        ItemMeta meta = tool.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return;

        int unbreakingLevel = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        double chanceToDamage = 1.0 / (unbreakingLevel + 1);
        if (Math.random() > chanceToDamage) return;

        int newDamage = damageable.getDamage() + 1;
        int maxDurability = tool.getType().getMaxDurability();

        if (newDamage >= maxDurability) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            if (breakSoundEnabled) {
                player.getWorld().playSound(player.getLocation(), breakSound, breakSoundVolume, breakSoundPitch);
            }
            return;
        }

        damageable.setDamage(newDamage);
        tool.setItemMeta((ItemMeta) damageable);
    }
}
