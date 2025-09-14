package com.fakeauctions;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;

public class FakeAuctions extends JavaPlugin {
    private BukkitTask task;
    private Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("FakeAuctions enabling...");

        // Schedule repeating task
        int intervalMinutes = getConfig().getInt("interval", 10);
        long ticks = intervalMinutes * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimer(this, this::createFakeAuction, 20L, Math.max(1, ticks));
        getLogger().info("Scheduled fake-auction task every " + intervalMinutes + " minutes.");
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        getLogger().info("FakeAuctions disabled.");
    }

    private void createFakeAuction() {
        try {
            Plugin ah = getServer().getPluginManager().getPlugin("AuctionHouse");
            if (ah == null || !ah.isEnabled()) {
                getLogger().warning("AuctionHouse plugin not found or not enabled. Skipping creating fake auction.");
                return;
            }

            // Pick category
            List<String> categories = getConfig().getStringList("categories-order");
            if (categories.isEmpty()) {
                categories = Arrays.asList("ores", "armor", "tools");
            }

            String chosenCategory = categories.get(random.nextInt(categories.size()));

            ItemStack item = buildRandomItemForCategory(chosenCategory);
            if (item == null) {
                getLogger().warning("No item created for category: " + chosenCategory);
                return;
            }

            double price = getRandomPrice();
            int amount = getConfig().getInt("default-count", 1);

            // Try to create auction using AuctionHouse plugin instance via reflection
            boolean success = tryCreateAuctionViaApi(ah, item, price, amount);
            if (!success) {
                getLogger().warning("Failed to create auction via AuctionHouse API reflection; attempting console command fallback.");
                String cmd = "ah list " + price + " " + amount;
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                getLogger().info("Dispatched console command '" + cmd + "' (result=" + dispatched + ")");
            }
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Error while creating fake auction", ex);
        }
    }

    private ItemStack buildRandomItemForCategory(String category) {
        ConfigurationSectionWrapper cfg = new ConfigurationSectionWrapper(getConfig());
        switch (category.toLowerCase()) {
            case "ores":
                List<String> ores = cfg.getStringList("ores");
                if (ores.isEmpty()) return null;
                String ore = ores.get(random.nextInt(ores.size()));
                Material m = safeMaterial(ore);
                if (m == null) return null;
                ItemStack oreItem = new ItemStack(m, 1);
                return oreItem;
            case "armor":
                List<String> armor = cfg.getStringList("armor");
                if (armor.isEmpty()) return null;
                String arm = armor.get(random.nextInt(armor.size()));
                Material am = safeMaterial(arm);
                if (am == null) return null;
                ItemStack armorItem = new ItemStack(am, 1);
                if (cfg.getBoolean("random-enchants", true) && random.nextBoolean()) applyRandomEnchants(armorItem);
                return armorItem;
            case "tools":
                List<String> tools = cfg.getStringList("tools");
                if (tools.isEmpty()) return null;
                String tool = tools.get(random.nextInt(tools.size()));
                Material tm = safeMaterial(tool);
                if (tm == null) return null;
                ItemStack toolItem = new ItemStack(tm, 1);
                if (cfg.getBoolean("random-enchants", true) && random.nextBoolean()) applyRandomEnchants(toolItem);
                return toolItem;
            default:
                return null;
        }
    }

    private Material safeMaterial(String name) {
        try {
            return Material.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Unknown material in config: " + name);
            return null;
        }
    }

    private double getRandomPrice() {
        double min = getConfig().getDouble("price-range.min", 50.0);
        double max = getConfig().getDouble("price-range.max", 500.0);
        if (max < min) max = min + 1;
        double val = min + random.nextDouble() * (max - min);
        return Math.round(val * 100.0) / 100.0;
    }

    private void applyRandomEnchants(ItemStack item) {
        List<String> possible = getConfig().getStringList("possible-enchants");
        if (possible.isEmpty()) {
            possible = Arrays.asList("DAMAGE_ALL","UNBREAKING","MENDING","EFFICIENCY","PROTECTION_ENVIRONMENTAL","THORNS");
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        int enchantsToApply = 1 + random.nextInt(3);
        for (int i = 0; i < enchantsToApply; i++) {
            String e = possible.get(random.nextInt(possible.size()));
            Enchantment ench = null;
            try {
                ench = Enchantment.getByName(e);
            } catch (Exception ignored) {}
            if (ench == null) continue;
            int maxLvl = Math.max(1, ench.getMaxLevel());
            int lvl = 1 + random.nextInt(maxLvl);
            meta.addEnchant(ench, lvl, true);
        }
        item.setItemMeta(meta);
    }

    private boolean tryCreateAuctionViaApi(Plugin ahPlugin, ItemStack item, double price, int amount) {
        try {
            Class<?> clazz = ahPlugin.getClass();
            Method[] methods = clazz.getMethods();
            for (Method m : methods) {
                String name = m.getName().toLowerCase(Locale.ROOT);
                if (!(name.contains("list") || name.contains("create") || name.contains("add"))) continue;
                Class<?>[] params = m.getParameterTypes();
                boolean hasItem = false, hasNumber = false;
                for (Class<?> p : params) {
                    if (p.getSimpleName().equals("ItemStack") || p.getName().equals("org.bukkit.inventory.ItemStack")) hasItem = true;
                    if (p.equals(double.class) || p.equals(float.class) || p.equals(Double.class) || p.equals(Integer.class) || p.equals(int.class)) hasNumber = true;
                }
                if (hasItem && hasNumber) {
                    Object[] args = new Object[params.length];
                    for (int i = 0; i < params.length; i++) {
                        Class<?> p = params[i];
                        if (p.getName().equals("org.bukkit.inventory.ItemStack")) args[i] = item;
                        else if (p.equals(double.class) || p.equals(Double.class)) args[i] = price;
                        else if (p.equals(int.class) || p.equals(Integer.class)) args[i] = amount;
                        else if (p.getSimpleName().equalsIgnoreCase("uuid")) args[i] = java.util.UUID.randomUUID();
                        else args[i] = null;
                    }
                    m.setAccessible(true);
                    try {
                        Object res = m.invoke(ahPlugin, args);
                        getLogger().info("Invoked AuctionHouse method " + m.getName() + " via reflection (result=" + res + ")");
                        return true;
                    } catch (IllegalArgumentException iae) {
                    }
                }
            }
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Reflection attempt to use AuctionHouse API failed", t);
        }
        return false;
    }

    private static class ConfigurationSectionWrapper {
        private final org.bukkit.configuration.file.FileConfiguration conf;
        ConfigurationSectionWrapper(org.bukkit.configuration.file.FileConfiguration conf) { this.conf = conf; }
        List<String> getStringList(String path) { return conf.getStringList(path); }
        boolean getBoolean(String path, boolean def) { return conf.getBoolean(path, def); }
    }
}
