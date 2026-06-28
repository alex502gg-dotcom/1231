package ru.example.threexthree;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ThreeXThreePlugin extends JavaPlugin implements CommandExecutor {

    // ключ "это наша кирка"
    private NamespacedKey pickaxeKey;
    // ключ "радиус области добычи" (3, 5, 9 или другое значение из конфига)
    private NamespacedKey radiusKey;

    private MineListener listener;

    // допустимые радиусы — подгружаются из config.yml, по умолчанию 3/5/9
    private List<Integer> allowedRadii = List.of(3, 5, 9);

    // соответствие названия материала из команды реальному предмету
    private static final Map<String, Material> MATERIAL_MAP = new LinkedHashMap<>();

    static {
        MATERIAL_MAP.put("wooden", Material.WOODEN_PICKAXE);
        MATERIAL_MAP.put("stone", Material.STONE_PICKAXE);
        MATERIAL_MAP.put("iron", Material.IRON_PICKAXE);
        MATERIAL_MAP.put("golden", Material.GOLDEN_PICKAXE);
        MATERIAL_MAP.put("diamond", Material.DIAMOND_PICKAXE);
        MATERIAL_MAP.put("netherite", Material.NETHERITE_PICKAXE);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig(); // создаёт plugins/ThreeXThreePickaxe/config.yml при первом запуске

        pickaxeKey = new NamespacedKey(this, "three_x_three_pickaxe");
        radiusKey = new NamespacedKey(this, "three_x_three_radius");

        listener = new MineListener(this, pickaxeKey, radiusKey);
        listener.reload(getConfig());
        reloadAllowedRadii();

        getServer().getPluginManager().registerEvents(listener, this);

        var giveCmd = getCommand("3x3");
        if (giveCmd != null) {
            giveCmd.setExecutor(this);
        }
        var reloadCmd = getCommand("3x3reload");
        if (reloadCmd != null) {
            reloadCmd.setExecutor(this);
        }

        getLogger().info("ThreeXThreePickaxe включён!");
    }

    private void reloadAllowedRadii() {
        List<Integer> radii = getConfig().getIntegerList("allowed-radii");
        if (radii.isEmpty()) {
            radii = new ArrayList<>(List.of(3, 5, 9));
        }
        this.allowedRadii = radii;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("3x3reload")) {
            return handleReload(sender);
        }
        return handleGive(sender, args);
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("threexthree.reload")) {
            sender.sendMessage(Component.text("У вас нет прав на эту команду.", NamedTextColor.RED));
            return true;
        }

        reloadConfig();
        listener.reload(getConfig());
        reloadAllowedRadii();

        sender.sendMessage(Component.text("Конфиг ThreeXThreePickaxe перезагружен.", NamedTextColor.GREEN));
        return true;
    }

    private boolean handleGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(usageMessage());
            return true;
        }

        if (!sender.hasPermission("threexthree.give")) {
            sender.sendMessage(Component.text("У вас нет прав на эту команду.", NamedTextColor.RED));
            return true;
        }

        String materialKey = args[0].toLowerCase();
        Material material = MATERIAL_MAP.get(materialKey);
        if (material == null) {
            sender.sendMessage(Component.text("Некорректный материал: " + args[0], NamedTextColor.RED));
            sender.sendMessage(usageMessage());
            return true;
        }

        Integer radius;
        try {
            radius = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            radius = null;
        }
        if (radius == null || !allowedRadii.contains(radius)) {
            sender.sendMessage(Component.text(
                    "Некорректный радиус: " + args[1] + " (доступно: " + allowedRadii + ")", NamedTextColor.RED));
            sender.sendMessage(usageMessage());
            return true;
        }

        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Игрок \"" + args[2] + "\" не найден или не онлайн.", NamedTextColor.RED));
                return true;
            }
            boolean givingToSelf = sender instanceof Player p && p.getUniqueId().equals(target.getUniqueId());
            if (!givingToSelf && !sender.hasPermission("threexthree.give.others")) {
                sender.sendMessage(Component.text("У вас нет прав выдавать кирку другим игрокам.", NamedTextColor.RED));
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Из консоли укажите игрока: /3x3 <материал> <радиус> <игрок>", NamedTextColor.RED));
                return true;
            }
            target = (Player) sender;
        }

        ItemStack pickaxe = createPickaxe(material, radius);
        target.getInventory().addItem(pickaxe);

        sender.sendMessage(Component.text(
                "Кирка " + radius + "x" + radius + " выдана игроку " + target.getName() + "!",
                NamedTextColor.GREEN));

        if (!sender.equals(target)) {
            target.sendMessage(Component.text(
                    "Вам выдали кирку " + radius + "x" + radius + "!", NamedTextColor.GREEN));
        }

        return true;
    }

    private Component usageMessage() {
        return Component.text(
                "Использование: /3x3 <wooden|stone|iron|golden|diamond|netherite> <" +
                        String.join("|", allowedRadii.stream().map(String::valueOf).toList()) + "> [игрок]",
                NamedTextColor.GRAY);
    }

    private ItemStack createPickaxe(Material material, int radius) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(
                Component.text("Кирка " + radius + "x" + radius, NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false)
        );
        meta.lore(List.of(
                Component.text("Ломает блоки областью " + radius + "x" + radius, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));

        meta.getPersistentDataContainer().set(pickaxeKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(radiusKey, PersistentDataType.INTEGER, radius);

        item.setItemMeta(meta);
        return item;
    }
}
