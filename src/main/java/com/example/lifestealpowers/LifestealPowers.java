package com.example.lifestealpowers;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseLootEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LifestealPowers extends JavaPlugin implements Listener {

    enum Power {
        // active (right-click Power Core)
        DASH("Dash", "Launch yourself forward", false, 8),
        LEAP("Leap", "Jump very high, then float down", false, 12),
        FIREBALL("Fireball", "Shoot a fireball", false, 6),
        LIGHTNING("Lightning", "Strike where you look", false, 20),
        FREEZE("Freeze", "Freeze everyone nearby", false, 25),
        HEAL("Heal", "Regeneration and full food", false, 30),
        TELEPORT("Blink", "Teleport to where you look", false, 10),
        SHOCKWAVE("Shockwave", "Blast nearby entities away", false, 15),
        INVISIBILITY("Vanish", "Become invisible for 10s", false, 40),
        SPEED_BURST("Speed Burst", "Speed III for 10s", false, 25),
        STRENGTH_SURGE("Strength Surge", "Strength II for 10s", false, 35),
        SHIELD("Shield", "Resistance IV + absorption for 8s", false, 40),
        BLINDING_FLASH("Blinding Flash", "Blind nearby players", false, 25),
        WITHER_TOUCH("Wither Touch", "Wither nearby enemies", false, 30),
        VOID_PULL("Void Pull", "Pull entities toward you", false, 20),
        ARROW_STORM("Arrow Storm", "Fire a volley of arrows", false, 20),
        TNT_THROW("TNT Throw", "Throw a primed TNT", false, 15),
        SWAP("Swap", "Swap places with nearest player", false, 30),
        // passive
        NIGHT_EYES("Night Eyes", "Permanent night vision", true, 0),
        WATER_LORD("Water Lord", "Water breathing + dolphin's grace", true, 0),
        FIRE_WALKER("Fire Walker", "Permanent fire resistance", true, 0),
        HASTE_MINER("Haste Miner", "Permanent Haste II", true, 0),
        FEATHER_FALL("Feather Fall", "No fall damage", true, 0),
        LUCKY_CHARM("Lucky Charm", "Permanent Luck II", true, 0),
        // bending powers: each one gives you its moves (cycle with sneak + right-click)
        FIREBENDER("Firebender", "Fire Blast + Flame Ring (fire immunity)", false, 0),
        WATERBENDER("Waterbender", "Tidal Wave + Healing Water (water breathing)", false, 0),
        EARTHBENDER("Earthbender", "Earth Spike + Stone Wall (extra toughness)", false, 0),
        AIRBENDER("Airbender", "Air Blast + Air Glide (no fall damage, faster)", false, 0),
        OMNI_BENDER("Omni Bender", "THE BEST: all four elements, boosted, plus Avatar State", false, 0),
        // bending moves (not powers on their own - they come from the bender powers)
        FIRE_BLAST("Fire Blast", "Shoot a stream of fire", false, 8),
        FLAME_RING("Flame Ring", "Burst of flames all around you", false, 15),
        TIDAL_WAVE("Tidal Wave", "A wave that crushes enemies in front of you", false, 12),
        WATER_HEAL("Healing Water", "Heal yourself and put out fires", false, 25),
        EARTH_SPIKE("Earth Spike", "Launch an enemy with a spike of earth", false, 10),
        STONE_WALL("Stone Wall", "Raise a temporary wall of stone", false, 20),
        AIR_BLAST("Air Blast", "A gust that knocks enemies away", false, 8),
        AIR_GLIDE("Air Glide", "Rocket upward and glide", false, 15),
        AVATAR_STATE("Avatar State", "Huge boost to everything (Omni Bender only)", false, 90),
        // dragon egg powers: only while holding the dragon egg, never handed out or stolen
        DRAGON_FLIGHT("Dragon Flight", "Fly freely (dragon egg holder)", true, 0),
        DRAGON_BREATH("Dragon's Breath", "Breathe a damaging dragon cloud (dragon egg holder)", false, 15);

        final String display, desc;
        final boolean passive;
        final int cooldown;

        Power(String display, String desc, boolean passive, int cooldown) {
            this.display = display; this.desc = desc;
            this.passive = passive; this.cooldown = cooldown;
        }

        boolean eggOnly() { return this == DRAGON_FLIGHT || this == DRAGON_BREATH; }
        boolean isElement() { return this == FIREBENDER || this == WATERBENDER || this == EARTHBENDER || this == AIRBENDER; }
        boolean isMove() { return ordinal() >= FIRE_BLAST.ordinal() && ordinal() <= AVATAR_STATE.ordinal(); }
        /** powers that are never handed out, stolen, withdrawn or crafted on their own */
        boolean internal() { return eggOnly() || isMove(); }

        /** powers that can be given out, stolen, withdrawn and crafted */
        static List<Power> pool() {
            List<Power> l = new ArrayList<>();
            for (Power p : values()) if (!p.internal() && !p.isElement()) l.add(p);
            return l;
        }
    }

    // which moves each bending power gives
    private static final Map<Power, List<Power>> MOVES = new EnumMap<>(Power.class);
    static {
        MOVES.put(Power.FIREBENDER, List.of(Power.FIRE_BLAST, Power.FLAME_RING));
        MOVES.put(Power.WATERBENDER, List.of(Power.TIDAL_WAVE, Power.WATER_HEAL));
        MOVES.put(Power.EARTHBENDER, List.of(Power.EARTH_SPIKE, Power.STONE_WALL));
        MOVES.put(Power.AIRBENDER, List.of(Power.AIR_BLAST, Power.AIR_GLIDE));
        MOVES.put(Power.OMNI_BENDER, List.of(Power.FIRE_BLAST, Power.FLAME_RING, Power.TIDAL_WAVE, Power.WATER_HEAL,
                Power.EARTH_SPIKE, Power.STONE_WALL, Power.AIR_BLAST, Power.AIR_GLIDE, Power.AVATAR_STATE));
    }

    private record BanEntry(String name, String reason, long until) {}

    // a player can hold several powers (by stealing), but each power has only one owner
    private final Map<UUID, LinkedHashSet<Power>> owned = new HashMap<>();
    private final Set<UUID> lost = new HashSet<>();
    private final Map<UUID, Integer> selected = new HashMap<>();       // selected move on the Power Core
    private final Map<UUID, Integer> selectedBend = new HashMap<>();   // selected move on the Bending Star
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Random rng = new Random();
    private File dataFile;
    private NamespacedKey itemKey, eggKey, heartKey, crystalKey, heartRecipeKey, crystalRecipeKey, bendKey;
    // powers that currently exist only as Power Crystal items (so nobody else is given them)
    private final Set<Power> crystals = new HashSet<>();
    private final Set<UUID> flyers = new HashSet<>();
    private final Map<UUID, BanEntry> bans = new ConcurrentHashMap<>();
    private volatile long graceUntil = 0;     // PvP grace period end (epoch ms), 0 = none
    private boolean maceCrafted = false;      // the one and only mace has been crafted   // players currently flying thanks to the dragon egg

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "power_core");
        eggKey = new NamespacedKey(this, "egg_bonus");
        heartKey = new NamespacedKey(this, "heart_item");
        crystalKey = new NamespacedKey(this, "power_crystal");
        bendKey = new NamespacedKey(this, "bending_star");
        heartRecipeKey = new NamespacedKey(this, "heart_recipe");
        crystalRecipeKey = new NamespacedKey(this, "crystal_recipe");
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        registerRecipes();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    // ---------- config helpers ----------
    private double d(Power pw, String key, double def) {
        return getConfig().getDouble("powers." + pw.name() + "." + key, def);
    }
    private int ticks(Power pw, String key, double defSeconds) { return (int) (d(pw, key, defSeconds) * 20); }
    private int amp(Power pw, String key, int defLevel) { return Math.min(255, Math.max(0, (int) Math.min(d(pw, key, defLevel), 100000) - 1)); }
    private boolean enabled(Power pw) { return getConfig().getBoolean("powers." + pw.name() + ".enabled", true); }
    private int cooldownOf(Power pw) { return getConfig().getInt("powers." + pw.name() + ".cooldown", pw.cooldown); }

    // ---------- helpers ----------
    private Set<Power> powersOf(UUID id) {
        Set<Power> s = owned.get(id);
        return s == null ? Collections.emptySet() : s;
    }

    /** bending moves for the Bending Star */
    private List<Power> bendMovesOf(Player pl) {
        List<Power> l = new ArrayList<>();
        for (Power p : powersOf(pl.getUniqueId())) {
            List<Power> mv = MOVES.get(p);
            if (mv != null && enabled(p)) for (Power m : mv) if (!l.contains(m)) l.add(m);
        }
        return l;
    }

    /** active powers for the Power Core (bending moves are NOT in here) */
    private List<Power> activesOf(Player pl) {
        List<Power> l = new ArrayList<>();
        for (Power p : powersOf(pl.getUniqueId())) {
            if (MOVES.get(p) != null) continue;   // bending moves live on the Bending Star
            if (!p.passive) l.add(p);
        }
        if (pl.getInventory().contains(Material.DRAGON_EGG) && enabled(Power.DRAGON_BREATH)) l.add(Power.DRAGON_BREATH);
        return l;
    }

    private Set<Power> takenPowers() {
        Set<Power> t = new HashSet<>();
        for (Set<Power> s : owned.values()) t.addAll(s);
        t.addAll(crystals);
        return t;
    }

    // ---------- storage ----------
    private void load() {
        getDataFolder().mkdirs();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        if (y.getConfigurationSection("powers") != null) {
            for (String k : y.getConfigurationSection("powers").getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    List<String> names = y.isList("powers." + k)
                            ? y.getStringList("powers." + k)
                            : List.of(String.valueOf(y.getString("powers." + k)));
                    LinkedHashSet<Power> set = new LinkedHashSet<>();
                    for (String n : names) {
                        try { set.add(Power.valueOf(n)); } catch (IllegalArgumentException ignored) {}
                    }
                    if (!set.isEmpty()) owned.put(id, set);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        for (String s : y.getStringList("lost")) {
            try { lost.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
        for (String s : y.getStringList("crystals")) {
            try { crystals.add(Power.valueOf(s)); } catch (IllegalArgumentException ignored) {}
        }
        graceUntil = y.getLong("grace-until", 0);
        maceCrafted = y.getBoolean("mace-crafted", false);
        ConfigurationSection bs = y.getConfigurationSection("bans");
        if (bs != null) {
            for (String k : bs.getKeys(false)) {
                try {
                    bans.put(UUID.fromString(k), new BanEntry(bs.getString(k + ".name", "?"),
                            bs.getString(k + ".reason", ""), bs.getLong(k + ".until", 0)));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        owned.forEach((u, set) -> {
            List<String> names = new ArrayList<>();
            for (Power p : set) names.add(p.name());
            y.set("powers." + u, names);
        });
        List<String> l = new ArrayList<>();
        lost.forEach(u -> l.add(u.toString()));
        y.set("lost", l);
        List<String> cl = new ArrayList<>();
        for (Power cp : crystals) cl.add(cp.name());
        y.set("crystals", cl);
        y.set("grace-until", graceUntil);
        y.set("mace-crafted", maceCrafted);
        bans.forEach((u, b) -> {
            y.set("bans." + u + ".name", b.name());
            y.set("bans." + u + ".reason", b.reason());
            y.set("bans." + u + ".until", b.until());
        });
        try { y.save(dataFile); } catch (IOException e) { getLogger().warning("Could not save data: " + e.getMessage()); }
    }

    // ---------- power core item ----------
    private ItemStack makeCore() {
        ItemStack s = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName("§dPower Core");
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    private boolean isCore(ItemStack s) {
        return s != null && s.getType() == Material.NETHER_STAR && s.hasItemMeta()
                && s.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    private void removeCores(Player p) {
        for (ItemStack s : p.getInventory().getContents()) if (isCore(s) || isBendStar(s)) p.getInventory().remove(s);
    }

    private void giveCore(Player p) {
        for (ItemStack s : p.getInventory().getContents()) if (isCore(s)) return;
        p.getInventory().addItem(makeCore());
    }

    // ---------- bending star (second star, only for bending moves) ----------
    private ItemStack makeBendStar() {
        ItemStack s = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName("§bBending Star");
        m.setLore(List.of("§7Right-click: use your selected bending move",
                          "§7Sneak + right-click: switch bending move"));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(bendKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    private boolean isBendStar(ItemStack s) {
        return s != null && s.getType() == Material.NETHER_STAR && s.hasItemMeta()
                && s.getItemMeta().getPersistentDataContainer().has(bendKey, PersistentDataType.BYTE);
    }

    private void giveBendStar(Player p) {
        for (ItemStack s : p.getInventory().getContents()) if (isBendStar(s)) return;
        giveOrDrop(p, makeBendStar());
    }

    /** Power Core for everybody with a power, plus the Bending Star for anybody who can bend */
    private void giveItems(Player p) {
        giveCore(p);
        for (Power x : powersOf(p.getUniqueId()))
            if (x.isElement() || x == Power.OMNI_BENDER) { giveBendStar(p); break; }
    }

    // ---------- heart + power crystal items ----------
    private ItemStack heartItem() {
        ItemStack s = new ItemStack(Material.RED_DYE);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName("§cHeart");
        m.setLore(List.of("§7Right-click to gain 1 heart"));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(heartKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    /** pw == null gives a random (mystery) crystal that grants a random free power */
    private ItemStack crystalItem(Power pw) {
        ItemStack s = new ItemStack(Material.AMETHYST_SHARD);
        ItemMeta m = s.getItemMeta();
        m.setDisplayName(pw == null ? "§bPower Crystal §7(Random)" : "§bPower Crystal: §e" + pw.display);
        m.setLore(List.of(pw == null ? "§7Right-click to gain a random unclaimed power"
                                     : "§7Right-click to gain: " + pw.desc));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(crystalKey, PersistentDataType.STRING, pw == null ? "RANDOM" : pw.name());
        s.setItemMeta(m);
        return s;
    }

    private boolean isHeart(ItemStack s) {
        return s != null && s.getType() == Material.RED_DYE && s.hasItemMeta()
                && s.getItemMeta().getPersistentDataContainer().has(heartKey, PersistentDataType.BYTE);
    }

    private String crystalOf(ItemStack s) {
        if (s == null || !s.hasItemMeta()) return null;
        return s.getItemMeta().getPersistentDataContainer().get(crystalKey, PersistentDataType.STRING);
    }

    private void giveOrDrop(Player p, ItemStack item) {
        p.getInventory().addItem(item).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private void consumeOne(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        if (h.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else h.setAmount(h.getAmount() - 1);
    }

    private void useHeart(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        if (a == null) return;
        double maxHp = getConfig().getDouble("hearts.max-hearts", 30) * 2;
        if (a.getBaseValue() >= maxHp) { p.sendMessage("§cYou are already at the max hearts."); return; }
        a.setBaseValue(Math.min(maxHp, a.getBaseValue() + 2));
        consumeOne(p);
        p.sendMessage("§aYou gained a heart!");
    }

    private void useCrystal(Player p, String what) {
        UUID id = p.getUniqueId();
        Power pw;
        if (what.equals("RANDOM")) {
            List<Power> free = Power.pool();
            free.removeAll(takenPowers());
            free.removeIf(x -> !enabled(x));
            if (free.isEmpty()) { p.sendMessage("§cThere are no unclaimed powers left."); return; }
            pw = free.get(rng.nextInt(free.size()));
        } else {
            try { pw = Power.valueOf(what); }
            catch (IllegalArgumentException ex) { p.sendMessage("§cThis crystal is broken."); return; }
            crystals.remove(pw);
            if (!pw.isElement()) for (Set<Power> set : owned.values()) set.remove(pw);   // keep one-of-each
            owned.values().removeIf(Set::isEmpty);
        }
        owned.computeIfAbsent(id, x -> new LinkedHashSet<>()).add(pw);
        lost.remove(id);
        consumeOne(p);
        save();
        giveItems(p);
        p.sendMessage("§6You gained the power: §e" + pw.display + " §7- " + pw.desc);
    }

    private void registerRecipes() {
        registerRecipe("heart", heartRecipeKey, heartItem());
        registerRecipe("power-crystal", crystalRecipeKey, crystalItem(null));
    }

    private void registerRecipe(String id, NamespacedKey key, ItemStack result) {
        Bukkit.removeRecipe(key);
        String base = "recipes." + id;
        if (!getConfig().getBoolean(base + ".enabled", true)) return;
        try {
            List<String> shape = getConfig().getStringList(base + ".shape");
            ConfigurationSection ing = getConfig().getConfigurationSection(base + ".ingredients");
            if (shape.isEmpty() || ing == null) {
                getLogger().warning("Recipe '" + id + "' is missing its shape or ingredients.");
                return;
            }
            ShapedRecipe r = new ShapedRecipe(key, result);
            r.shape(shape.toArray(new String[0]));
            for (String k : ing.getKeys(false)) {
                Material m = Material.matchMaterial(ing.getString(k, ""));
                if (m == null) throw new IllegalArgumentException("unknown material for '" + k + "'");
                r.setIngredient(k.charAt(0), m);
            }
            Bukkit.addRecipe(r);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Could not register recipe '" + id + "': " + ex.getMessage());
        }
    }

    // our special items can't be used as crafting ingredients (e.g. a Power Core as a plain nether star)
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        if (!(e.getRecipe() instanceof Keyed k)) return;
        if (!k.getKey().equals(heartRecipeKey) && !k.getKey().equals(crystalRecipeKey)) return;
        for (ItemStack s : e.getInventory().getMatrix())
            if (isCore(s) || isBendStar(s) || isHeart(s) || crystalOf(s) != null) { e.getInventory().setResult(null); return; }
    }

    // dropped crystals must never be destroyed or despawn (the power would be lost forever)
    @EventHandler
    public void onItemDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item it) {
            ItemStack s = it.getItemStack();
            if (isCore(s) || isBendStar(s) || isHeart(s) || crystalOf(s) != null || isMace(s)) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent e) {
        String c = crystalOf(e.getEntity().getItemStack());
        if (isMace(e.getEntity().getItemStack()) || (c != null && !c.equals("RANDOM"))) { e.setCancelled(true); e.getEntity().setTicksLived(1); }
    }

    // ---------- assigning ----------
    /** picks the bending element that the fewest players have, so everything stays balanced */
    private Power pickElement() {
        Map<Power, Integer> count = new EnumMap<>(Power.class);
        for (Power x : Power.values()) if (x.isElement() && enabled(x)) count.put(x, 0);
        if (count.isEmpty()) return null;
        for (Set<Power> set : owned.values()) for (Power x : set) if (count.containsKey(x)) count.merge(x, 1, Integer::sum);
        int min = Collections.min(count.values());
        List<Power> low = new ArrayList<>();
        for (var en : count.entrySet()) if (en.getValue() == min) low.add(en.getKey());
        return low.get(rng.nextInt(low.size()));
    }

    /** everybody gets one unique power AND one bending ability */
    private void assign(Player p) {
        UUID id = p.getUniqueId();
        boolean hasUnique = false, hasElement = false;
        for (Power x : powersOf(id)) { if (x.isElement()) hasElement = true; else hasUnique = true; }
        boolean changed = false;

        // 1) a unique power (only if they haven't lost theirs)
        if (!hasUnique && !lost.contains(id)) {
            List<Power> free = Power.pool();
            free.removeAll(takenPowers());
            free.removeIf(x -> !enabled(x));
            if (free.isEmpty()) {
                p.sendMessage("§cAll unique powers are taken.");
            } else {
                Power pw = free.get(rng.nextInt(free.size()));
                owned.computeIfAbsent(id, x -> new LinkedHashSet<>()).add(pw);
                changed = true;
                p.sendMessage("§6You got the power: §e" + pw.display + " §7- " + pw.desc);
            }
        }

        // 2) a bending ability (the Omni Bender already has all four)
        boolean omni = powersOf(id).contains(Power.OMNI_BENDER);
        if (!hasElement && !omni && getConfig().getBoolean("bending.give-everyone", true)) {
            Power el = pickElement();
            if (el != null) {
                owned.computeIfAbsent(id, x -> new LinkedHashSet<>()).add(el);
                changed = true;
                p.sendMessage("§bYou are a §e" + el.display + "§b! §7" + el.desc + " §bUse your Bending Star to bend.");
            }
        }

        if (changed) save();
        if (!powersOf(id).isEmpty()) giveItems(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { assign(e.getPlayer()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> assign(p));
    }

    // ---------- death, stealing powers, lifesteal ----------
    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        Player k = v.getKiller();
        UUID vid = v.getUniqueId();
        e.getDrops().removeIf(x -> isCore(x) || isBendStar(x));
        removeCores(v);

        // the victim loses ONE unique power (bending abilities are always kept)
        LinkedHashSet<Power> vs = owned.get(vid);
        List<Power> uniques = new ArrayList<>();
        if (vs != null) for (Power x : vs) if (!x.isElement()) uniques.add(x);
        if (!uniques.isEmpty()) {
            Power taken = uniques.get(rng.nextInt(uniques.size()));
            vs.remove(taken);
            boolean anyUnique = false;
            for (Power x : vs) if (!x.isElement()) anyUnique = true;
            if (!anyUnique) lost.add(vid);
            if (vs.isEmpty()) owned.remove(vid);
            selected.remove(vid);

            if (k != null && !k.equals(v)) {
                // the killer steals it (this also brings a powerless player back)
                owned.computeIfAbsent(k.getUniqueId(), x -> new LinkedHashSet<>()).add(taken);
                lost.remove(k.getUniqueId());
                giveItems(k);
                v.sendMessage("§c" + k.getName() + " stole your power: " + taken.display);
                k.sendMessage("§aYou stole the power: §e" + taken.display + " §7- " + taken.desc);
            } else {
                v.sendMessage("§cYou died and lost your power: " + taken.display);
            }
            save();
        }

        // lifesteal hearts
        if (k != null && !k.equals(v)) {
            AttributeInstance va = v.getAttribute(Attribute.MAX_HEALTH);
            AttributeInstance ka = k.getAttribute(Attribute.MAX_HEALTH);
            double step = getConfig().getDouble("hearts.hearts-per-kill", 1) * 2;
            double minHp = Math.max(1, getConfig().getDouble("hearts.min-hearts", 1)) * 2;   // can't lose the last heart
            double maxHp = getConfig().getDouble("hearts.max-hearts", 30) * 2;
            if (va != null && ka != null && va.getBaseValue() > minHp) {
                va.setBaseValue(Math.max(minHp, va.getBaseValue() - step));
                ka.setBaseValue(Math.min(maxHp, ka.getBaseValue() + step));
                v.sendMessage("§cYou lost a heart.");
                k.sendMessage("§aYou stole a heart!");
            }
        }
    }

    @EventHandler
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p
                && ((powersOf(p.getUniqueId()).contains(Power.FEATHER_FALL) && enabled(Power.FEATHER_FALL))
                || hasEl(powersOf(p.getUniqueId()), Power.AIRBENDER))) e.setCancelled(true);
    }

    // ---------- periodic: dragon egg bonus + passive powers ----------
    private void tick() {
        if (graceUntil > 0 && System.currentTimeMillis() >= graceUntil) {
            graceUntil = 0;
            save();
            Bukkit.broadcast(Component.text("§cThe grace period is over. PvP is now enabled!"));
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
            if (a != null) {
                boolean has = p.getInventory().contains(Material.DRAGON_EGG);
                AttributeModifier found = null;
                for (AttributeModifier m : a.getModifiers()) if (eggKey.equals(m.getKey())) found = m;
                if (has && found == null)
                    a.addModifier(new AttributeModifier(eggKey, getConfig().getDouble("hearts.dragon-egg-bonus", 20) * 2,
                            AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                else if (!has && found != null) a.removeModifier(found);
            }
            if (noNetherite()) stripNetheriteArmor(p);
            Set<Power> mine = powersOf(p.getUniqueId());
            if (hasEl(mine, Power.FIREBENDER)) eff(p, PotionEffectType.FIRE_RESISTANCE, 100, 0);
            if (hasEl(mine, Power.WATERBENDER)) { eff(p, PotionEffectType.WATER_BREATHING, 100, 0); eff(p, PotionEffectType.DOLPHINS_GRACE, 100, 0); }
            if (hasEl(mine, Power.EARTHBENDER)) eff(p, PotionEffectType.RESISTANCE, 100, 0);
            if (hasEl(mine, Power.AIRBENDER)) eff(p, PotionEffectType.SPEED, 100, 0);
            boolean flight = p.getInventory().contains(Material.DRAGON_EGG) && enabled(Power.DRAGON_FLIGHT);
            if (flight) {
                flyers.add(p.getUniqueId());
                p.setAllowFlight(true);
                p.setFlySpeed((float) Math.max(0.01, Math.min(1.0, d(Power.DRAGON_FLIGHT, "speed", 0.1))));
            } else if (flyers.remove(p.getUniqueId())) {
                GameMode gm = p.getGameMode();
                if (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) {
                    if (p.isFlying()) eff(p, PotionEffectType.SLOW_FALLING, 200, 0);   // soft landing
                    p.setFlying(false);
                    p.setAllowFlight(false);
                }
                p.setFlySpeed(0.1f);
            }
            for (Power pw : powersOf(p.getUniqueId())) {
                if (!pw.passive || !enabled(pw)) continue;
                switch (pw) {
                    case NIGHT_EYES -> eff(p, PotionEffectType.NIGHT_VISION, 400, 0);
                    case WATER_LORD -> { eff(p, PotionEffectType.WATER_BREATHING, 100, 0); eff(p, PotionEffectType.DOLPHINS_GRACE, 100, 0); }
                    case FIRE_WALKER -> eff(p, PotionEffectType.FIRE_RESISTANCE, 100, 0);
                    case HASTE_MINER -> eff(p, PotionEffectType.HASTE, 100, amp(pw, "level", 2));
                    case LUCKY_CHARM -> eff(p, PotionEffectType.LUCK, 100, amp(pw, "level", 2));
                    default -> {}
                }
            }
        }
    }

    private void eff(LivingEntity e, PotionEffectType t, int ticks, int amp) {
        e.addPotionEffect(new PotionEffect(t, ticks, amp, false, true));
    }

    // ---------- using active powers ----------
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (isHeart(held)) { e.setCancelled(true); useHeart(p); return; }
        String crystal = crystalOf(held);
        if (crystal != null) { e.setCancelled(true); useCrystal(p, crystal); return; }
        boolean bendStar = isBendStar(held);
        if (!bendStar && !isCore(held)) return;
        e.setCancelled(true);
        trigger(p, bendStar, p.isSneaking());
    }

    /** Uses (or, with cycle = true, switches) the selected power / bending move. Used by the Power Core and the Bending Star. */
    private void trigger(Player p, boolean bendStar, boolean cycle) {
        UUID id = p.getUniqueId();
        List<Power> actives = bendStar ? bendMovesOf(p) : activesOf(p);
        Map<UUID, Integer> sel = bendStar ? selectedBend : selected;
        if (actives.isEmpty()) {
            boolean anyUnique = false;
            for (Power x : powersOf(id)) if (!x.isElement() && x != Power.OMNI_BENDER) anyUnique = true;
            if (bendStar) p.sendMessage("§cYou have no bending ability.");
            else if (!anyUnique) p.sendMessage("§cYou have no power. Kill someone to steal one!");
            else p.sendMessage("§eYour powers are all passive and always active.");
            return;
        }

        int idx = sel.getOrDefault(id, 0) % actives.size();
        if (cycle) {
            idx = (idx + 1) % actives.size();
            sel.put(id, idx);
            p.sendMessage((bendStar ? "§bSelected move: §e" : "§6Selected power: §e") + actives.get(idx).display);
            return;
        }

        Power pw = actives.get(idx);
        if (!enabled(pw)) { p.sendMessage("§c" + pw.display + " is disabled on this server."); return; }
        String key = id + ":" + pw.name();
        long now = System.currentTimeMillis();
        long ready = cooldowns.getOrDefault(key, 0L);
        if (now < ready) {
            p.sendMessage("§c" + pw.display + " cooldown: " + ((ready - now) / 1000 + 1) + "s");
            return;
        }
        if (use(p, pw)) cooldowns.put(key, now + cooldownOf(pw) * 1000L);
    }

    private List<LivingEntity> nearby(Player p, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(r, r, r))
            if (e instanceof LivingEntity le && !e.equals(p) && !(graceActive() && le instanceof Player)) out.add(le);
        return out;
    }

    private boolean use(Player p, Power pw) {
        Vector dir = p.getLocation().getDirection();
        World w = p.getWorld();
        double bst = pw.isMove() ? bend(p) : 1.0;   // Omni Bender boost
        switch (pw) {
            case DASH -> p.setVelocity(dir.clone().multiply(d(pw, "strength", 2.2)).setY(d(pw, "lift", 0.4)));
            case LEAP -> {
                p.setVelocity(new Vector(0, d(pw, "height", 1.6), 0));
                eff(p, PotionEffectType.SLOW_FALLING, ticks(pw, "slow-fall-seconds", 5), 0);
            }
            case FIREBALL -> {
                Fireball f = p.launchProjectile(Fireball.class);
                f.setYield((float) d(pw, "explosion-power", 1));
            }
            case LIGHTNING -> {
                double range = d(pw, "range", 40);
                RayTraceResult r = p.rayTraceBlocks(range);
                Location l = r != null ? r.getHitPosition().toLocation(w)
                        : p.getEyeLocation().add(dir.clone().multiply(range));
                w.strikeLightning(l);
            }
            case FREEZE -> {
                for (LivingEntity e : nearby(p, d(pw, "radius", 8))) {
                    e.setFreezeTicks(ticks(pw, "duration", 5) * 2);
                    eff(e, PotionEffectType.SLOWNESS, ticks(pw, "duration", 5), amp(pw, "level", 5));
                }
            }
            case HEAL -> { eff(p, PotionEffectType.REGENERATION, ticks(pw, "duration", 10), amp(pw, "level", 3)); p.setFoodLevel(20); }
            case TELEPORT -> {
                RayTraceResult r = p.rayTraceBlocks(d(pw, "range", 30));
                if (r == null || r.getHitBlockFace() == null) { p.sendMessage("§cNothing to blink to."); return false; }
                Location l = r.getHitPosition().toLocation(w).add(r.getHitBlockFace().getDirection());
                l.setYaw(p.getLocation().getYaw()); l.setPitch(p.getLocation().getPitch());
                p.teleport(l);
            }
            case SHOCKWAVE -> {
                for (LivingEntity e : nearby(p, d(pw, "radius", 6))) {
                    Vector v = e.getLocation().toVector().subtract(p.getLocation().toVector());
                    if (v.lengthSquared() < 0.01) continue;
                    e.setVelocity(v.normalize().multiply(d(pw, "strength", 2)).setY(0.6));
                }
            }
            case INVISIBILITY -> eff(p, PotionEffectType.INVISIBILITY, ticks(pw, "duration", 10), 0);
            case SPEED_BURST -> eff(p, PotionEffectType.SPEED, ticks(pw, "duration", 10), amp(pw, "level", 3));
            case STRENGTH_SURGE -> eff(p, PotionEffectType.STRENGTH, ticks(pw, "duration", 10), amp(pw, "level", 2));
            case SHIELD -> {
                eff(p, PotionEffectType.RESISTANCE, ticks(pw, "duration", 8), amp(pw, "resistance-level", 4));
                eff(p, PotionEffectType.ABSORPTION, ticks(pw, "duration", 8), amp(pw, "absorption-level", 3));
            }
            case BLINDING_FLASH -> {
                for (LivingEntity e : nearby(p, d(pw, "radius", 10)))
                    if (e instanceof Player) eff(e, PotionEffectType.BLINDNESS, ticks(pw, "duration", 5), 0);
            }
            case WITHER_TOUCH -> {
                for (LivingEntity e : nearby(p, d(pw, "radius", 6)))
                    eff(e, PotionEffectType.WITHER, ticks(pw, "duration", 5), amp(pw, "level", 2));
            }
            case VOID_PULL -> {
                for (LivingEntity e : nearby(p, d(pw, "radius", 12))) {
                    Vector v = p.getLocation().toVector().subtract(e.getLocation().toVector());
                    if (v.lengthSquared() < 1) continue;
                    e.setVelocity(v.normalize().multiply(d(pw, "strength", 1.4)).setY(0.3));
                }
            }
            case ARROW_STORM -> {
                int n = (int) d(pw, "arrows", 12);
                for (int i = 0; i < n; i++) {
                    Arrow a = p.launchProjectile(Arrow.class);
                    Vector spread = new Vector(rng.nextDouble() - .5, rng.nextDouble() * .3, rng.nextDouble() - .5).multiply(.4);
                    a.setVelocity(dir.clone().add(spread).multiply(d(pw, "speed", 2.5)));
                }
            }
            case TNT_THROW -> {
                TNTPrimed t = w.spawn(p.getEyeLocation(), TNTPrimed.class);
                t.setVelocity(dir.clone().multiply(d(pw, "speed", 1.5)));
                t.setFuseTicks(ticks(pw, "fuse-seconds", 2));
            }
            case SWAP -> {
                double range = d(pw, "range", 25);
                Player best = null; double bd = range * range;
                for (Player o : w.getPlayers()) {
                    if (o.equals(p)) continue;
                    double dist = o.getLocation().distanceSquared(p.getLocation());
                    if (dist < bd) { bd = dist; best = o; }
                }
                if (best == null) { p.sendMessage("§cNo player within " + (int) range + " blocks."); return false; }
                Location a = p.getLocation(), b = best.getLocation();
                p.teleport(b); best.teleport(a);
            }
            case FIRE_BLAST -> {
                double dmg = d(pw, "damage", 6) * bst;
                int fire = ticks(pw, "fire-seconds", 5);
                Set<Entity> hit = new HashSet<>();
                for (Location l : path(p, d(pw, "range", 15) * bst)) {
                    w.spawnParticle(Particle.FLAME, l, 4, 0.15, 0.15, 0.15, 0.01);
                    for (Entity en : w.getNearbyEntities(l, 1, 1, 1))
                        if (en instanceof LivingEntity le && !le.equals(p) && !(graceActive() && le instanceof Player) && hit.add(le)) {
                            le.setFireTicks(fire);
                            le.damage(dmg, p);
                        }
                }
            }
            case FLAME_RING -> {
                double radius = d(pw, "radius", 6) * bst, dmg = d(pw, "damage", 4) * bst;
                int fire = ticks(pw, "fire-seconds", 5);
                for (int deg = 0; deg < 360; deg += 10) {
                    double rad = Math.toRadians(deg);
                    Location l = p.getLocation().add(Math.cos(rad) * radius, 0.3, Math.sin(rad) * radius);
                    w.spawnParticle(Particle.FLAME, l, 3, 0.1, 0.3, 0.1, 0.02);
                }
                for (LivingEntity e : nearby(p, radius)) {
                    if (e.getLocation().distance(p.getLocation()) > radius) continue;
                    e.setFireTicks(fire);
                    e.damage(dmg, p);
                    Vector v = e.getLocation().toVector().subtract(p.getLocation().toVector());
                    if (v.lengthSquared() > 0.01) e.setVelocity(v.normalize().multiply(0.8).setY(0.3));
                }
            }
            case TIDAL_WAVE -> {
                double range = d(pw, "range", 10) * bst, str = d(pw, "strength", 2) * bst, dmg = d(pw, "damage", 3) * bst;
                for (int i = 1; i <= (int) range; i++)
                    w.spawnParticle(Particle.SPLASH, p.getEyeLocation().add(dir.clone().multiply(i)), 25, 0.8, 0.5, 0.8, 0.1);
                for (LivingEntity e : cone(p, range, 0.6)) {
                    e.setVelocity(dir.clone().multiply(str).setY(0.4));
                    eff(e, PotionEffectType.SLOWNESS, ticks(pw, "duration", 4), amp(pw, "level", 3));
                    e.setFireTicks(0);
                    e.damage(dmg, p);
                }
            }
            case WATER_HEAL -> {
                AttributeInstance mh = p.getAttribute(Attribute.MAX_HEALTH);
                double max = mh != null ? mh.getValue() : 20;
                p.setHealth(Math.min(max, p.getHealth() + d(pw, "heal", 8) * bst));
                p.setFireTicks(0);
                eff(p, PotionEffectType.REGENERATION, ticks(pw, "duration", 8), amp(pw, "level", 2));
                w.spawnParticle(Particle.SPLASH, p.getLocation().add(0, 1, 0), 60, 0.6, 0.8, 0.6, 0.1);
            }
            case EARTH_SPIKE -> {
                double dmg = d(pw, "damage", 7) * bst, lift = d(pw, "lift", 1.2);
                LivingEntity target = null;
                for (Location l : path(p, d(pw, "range", 12) * bst)) {
                    w.spawnParticle(Particle.CRIT, l, 3, 0.1, 0.1, 0.1, 0.02);
                    if (target == null)
                        for (Entity en : w.getNearbyEntities(l, 1, 1, 1))
                            if (en instanceof LivingEntity le && !le.equals(p) && !(graceActive() && le instanceof Player)) { target = le; break; }
                }
                if (target == null) { p.sendMessage("§cNo target in range."); return false; }
                target.damage(dmg, p);
                target.setVelocity(new Vector(0, lift, 0));
                w.spawnParticle(Particle.CRIT, target.getLocation(), 40, 0.5, 1, 0.5, 0.2);
            }
            case STONE_WALL -> {
                Vector h = dir.clone().setY(0);
                if (h.lengthSquared() < 0.01) h = new Vector(0, 0, 1);
                h.normalize();
                Vector perp = new Vector(-h.getZ(), 0, h.getX());
                Location base = p.getLocation().add(h.clone().multiply(d(pw, "distance", 3)));
                int half = (int) d(pw, "half-width", 2), height = (int) d(pw, "height", 3);
                List<Block> placed = new ArrayList<>();
                for (int i = -half; i <= half; i++)
                    for (int j = 0; j < height; j++) {
                        Block blk = base.clone().add(perp.clone().multiply(i)).add(0, j, 0).getBlock();
                        if (blk.isEmpty()) { blk.setType(Material.STONE); placed.add(blk); }
                    }
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    for (Block pb : placed) if (pb.getType() == Material.STONE) pb.setType(Material.AIR);
                }, ticks(pw, "duration", 8));
            }
            case AIR_BLAST -> {
                double range = d(pw, "range", 12) * bst, str = d(pw, "strength", 3) * bst;
                for (int i = 1; i <= (int) range; i++)
                    w.spawnParticle(Particle.CLOUD, p.getEyeLocation().add(dir.clone().multiply(i)), 12, 0.7, 0.4, 0.7, 0.05);
                for (LivingEntity e : cone(p, range, 0.6)) e.setVelocity(dir.clone().multiply(str).setY(0.6));
            }
            case AIR_GLIDE -> {
                p.setVelocity(new Vector(0, d(pw, "lift", 1.0), 0).add(dir.clone().multiply(0.6)));
                eff(p, PotionEffectType.SLOW_FALLING, ticks(pw, "duration", 8), 0);
                eff(p, PotionEffectType.SPEED, ticks(pw, "duration", 8), amp(pw, "level", 2));
                w.spawnParticle(Particle.CLOUD, p.getLocation(), 40, 0.5, 0.2, 0.5, 0.1);
            }
            case AVATAR_STATE -> {
                int dur = ticks(pw, "duration", 15);
                int lv = amp(pw, "level", 3);
                eff(p, PotionEffectType.SPEED, dur, lv);
                eff(p, PotionEffectType.STRENGTH, dur, lv);
                eff(p, PotionEffectType.RESISTANCE, dur, Math.max(0, lv - 1));
                eff(p, PotionEffectType.REGENERATION, dur, 1);
                eff(p, PotionEffectType.FIRE_RESISTANCE, dur, 0);
                eff(p, PotionEffectType.GLOWING, dur, 0);
                double radius = d(pw, "radius", 8), dmg = d(pw, "damage", 6);
                for (int deg = 0; deg < 360; deg += 8) {
                    double rad = Math.toRadians(deg);
                    Location l = p.getLocation().add(Math.cos(rad) * radius, 0.5, Math.sin(rad) * radius);
                    w.spawnParticle(Particle.FLAME, l, 2, 0.1, 0.2, 0.1, 0.02);
                    w.spawnParticle(Particle.CLOUD, l, 2, 0.1, 0.2, 0.1, 0.02);
                }
                for (LivingEntity e : nearby(p, radius)) {
                    e.damage(dmg, p);
                    Vector v = e.getLocation().toVector().subtract(p.getLocation().toVector());
                    if (v.lengthSquared() > 0.01) e.setVelocity(v.normalize().multiply(1.5).setY(0.5));
                }
            }
            case DRAGON_BREATH -> {
                double range = d(pw, "range", 8), radius = d(pw, "radius", 4), dmg = d(pw, "damage", 2);
                int dur = ticks(pw, "duration", 8);
                RayTraceResult r = p.rayTraceBlocks(range);
                Location center = r != null ? r.getHitPosition().toLocation(w)
                        : p.getEyeLocation().add(dir.clone().multiply(range));
                AreaEffectCloud cloud = w.spawn(center, AreaEffectCloud.class);
                cloud.setColor(Color.fromRGB(0xA020F0));
                cloud.setRadius((float) radius);
                cloud.setRadiusPerTick(0f);
                cloud.setDuration(dur);
                // we do the damage ourselves so the owner is never hurt by their own cloud
                new BukkitRunnable() {
                    int t = 0;
                    @Override public void run() {
                        if (t >= dur || cloud.isDead()) { cancel(); return; }
                        for (Entity en : w.getNearbyEntities(center, radius, radius, radius))
                            if (en instanceof LivingEntity le && !le.equals(p) && !(graceActive() && le instanceof Player)) le.damage(dmg, p);
                        t += 10;
                    }
                }.runTaskTimer(this, 0L, 10L);
            }
            default -> { return false; }
        }
        return true;
    }

    // ---------- bending helpers ----------
    private boolean hasEl(Set<Power> mine, Power el) {
        return (mine.contains(el) && enabled(el)) || (mine.contains(Power.OMNI_BENDER) && enabled(Power.OMNI_BENDER));
    }

    private double bend(Player p) {
        boolean omni = powersOf(p.getUniqueId()).contains(Power.OMNI_BENDER) && enabled(Power.OMNI_BENDER);
        return omni ? d(Power.OMNI_BENDER, "boost", 1.5) : 1.0;
    }

    private List<Location> path(Player p, double range) {
        List<Location> pts = new ArrayList<>();
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        for (double t = 1; t <= range; t += 0.5) {
            Location l = eye.clone().add(dir.clone().multiply(t));
            if (l.getBlock().getType().isSolid()) break;
            pts.add(l);
        }
        return pts;
    }

    private List<LivingEntity> cone(Player p, double range, double minDot) {
        Vector dir = p.getLocation().getDirection().normalize();
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : nearby(p, range)) {
            Vector to = e.getLocation().add(0, 1, 0).toVector().subtract(p.getEyeLocation().toVector());
            if (to.lengthSquared() < 0.01 || to.length() > range) continue;
            if (to.clone().normalize().dot(dir) >= minDot) out.add(e);
        }
        return out;
    }

    // ---------- grace period ----------
    private boolean graceActive() { return graceUntil > System.currentTimeMillis(); }

    @EventHandler
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!graceActive() || !(e.getEntity() instanceof Player victim)) return;
        Entity damager = e.getDamager();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity sh) damager = sh;
        else if (damager instanceof TNTPrimed t && t.getSource() instanceof Entity src) damager = src;
        if (damager instanceof Player && !damager.equals(victim)) e.setCancelled(true);
    }

    // ---------- bans / timeouts ----------
    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        BanEntry b = bans.get(e.getUniqueId());
        if (b == null) return;
        long now = System.currentTimeMillis();
        if (b.until() > 0 && now >= b.until()) { bans.remove(e.getUniqueId()); return; }
        String msg = b.until() > 0
                ? "§cYou are timed out for another " + ((b.until() - now) / 60000 + 1) + " minute(s).\n§7Reason: " + b.reason()
                : "§cYou are banned.\n§7Reason: " + b.reason();
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, Component.text(msg));
    }

    // ---------- no end crystal / respawn anchor explosions ----------
    // End crystals can still be broken (so the dragon fight works) but their blast
    // does no damage to players and no block damage.
    @EventHandler
    public void onCrystalDamage(EntityDamageByEntityEvent e) {
        if (getConfig().getBoolean("explosions.disable-end-crystal", true)
                && e.getDamager() instanceof EnderCrystal && e.getEntity() instanceof Player) e.setCancelled(true);
    }

    @EventHandler
    public void onCrystalExplode(EntityExplodeEvent e) {
        if (getConfig().getBoolean("explosions.disable-end-crystal", true) && e.getEntity() instanceof EnderCrystal)
            e.blockList().clear();
    }

    // respawn anchors explode when used outside the Nether, so using them there is blocked
    @EventHandler
    public void onAnchor(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        if (e.getClickedBlock().getType() != Material.RESPAWN_ANCHOR) return;
        if (!getConfig().getBoolean("explosions.disable-respawn-anchor", true)) return;
        if (e.getClickedBlock().getWorld().getEnvironment() == World.Environment.NETHER) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND)
            e.getPlayer().sendMessage("§cRespawn anchor explosions are disabled, so anchors only work in the Nether.");
    }

    // ---------- only one mace ----------
    private boolean isMace(ItemStack s) { return s != null && s.getType() == Material.MACE; }
    private boolean maceLimit() { return getConfig().getBoolean("mace.only-one", true); }

    @EventHandler
    public void onPrepareMace(PrepareItemCraftEvent e) {
        if (maceLimit() && maceCrafted && isMace(e.getInventory().getResult())) e.getInventory().setResult(null);
    }

    @EventHandler
    public void onCraftMace(CraftItemEvent e) {
        if (!maceLimit() || !isMace(e.getRecipe().getResult())) return;
        if (maceCrafted) { e.setCancelled(true); return; }
        if (e.isShiftClick()) {
            e.setCancelled(true);
            e.getWhoClicked().sendMessage("§cClick the result normally to craft the mace (no shift-click).");
            return;
        }
        maceCrafted = true;
        save();
        Bukkit.broadcast(Component.text("§6The one and only Mace has been crafted by " + e.getWhoClicked().getName() + "!"));
    }

    // maces and netherite armor can't come from chests or vaults either
    private boolean bannedLoot(ItemStack s) {
        return s != null && ((maceLimit() && isMace(s)) || (noNetherite() && isNetheriteArmor(s.getType())));
    }

    @EventHandler
    public void onLoot(LootGenerateEvent e) { e.getLoot().removeIf(this::bannedLoot); }

    @EventHandler
    public void onVaultLoot(BlockDispenseLootEvent e) {
        List<ItemStack> l = new ArrayList<>(e.getDispensedLoot());
        l.removeIf(this::bannedLoot);
        e.setDispensedLoot(l);
    }

    // ---------- no netherite armor (netherite tools are fine) ----------
    private boolean noNetherite() { return getConfig().getBoolean("netherite.disable-armor", true); }

    private boolean isNetheriteArmor(Material m) {
        String n = m.name();
        return n.startsWith("NETHERITE_") && (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS"));
    }

    private Material downgrade(Material m) {
        return Material.valueOf("DIAMOND_" + m.name().substring("NETHERITE_".length()));
    }

    @EventHandler
    public void onSmith(PrepareSmithingEvent e) {
        ItemStack r = e.getResult();
        if (noNetherite() && r != null && isNetheriteArmor(r.getType())) e.setResult(null);
    }

    private void stripNetheriteArmor(Player p) {
        PlayerInventory inv = p.getInventory();
        int count = 0;
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < armor.length; i++)
            if (armor[i] != null && isNetheriteArmor(armor[i].getType())) { armor[i] = armor[i].withType(downgrade(armor[i].getType())); count++; }
        ItemStack[] store = inv.getStorageContents();
        for (int i = 0; i < store.length; i++)
            if (store[i] != null && isNetheriteArmor(store[i].getType())) { store[i] = store[i].withType(downgrade(store[i].getType())); count++; }
        ItemStack off = inv.getItemInOffHand();
        if (isNetheriteArmor(off.getType())) { inv.setItemInOffHand(off.withType(downgrade(off.getType()))); count++; }
        if (count > 0) {
            inv.setArmorContents(armor);
            inv.setStorageContents(store);
            giveOrDrop(p, new ItemStack(Material.NETHERITE_INGOT, count));
            p.sendMessage("§cNetherite armor is disabled. It was turned into diamond armor and you got your netherite back.");
        }
    }

    // ---------- /lifesteal (admin) ----------
    private OfflinePlayer find(String name) {
        Player on = Bukkit.getPlayerExact(name);
        return on != null ? on : Bukkit.getOfflinePlayerIfCached(name);
    }

    private String join(String[] a, int from) {
        return String.join(" ", Arrays.copyOfRange(a, from, a.length));
    }

    private boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("lifesteal.admin")) { s.sendMessage("§cAdmins only."); return true; }
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            s.sendMessage("§6--- /ls admin commands ---");
            s.sendMessage("§6/ls grace <minutes|off>  §7PvP grace period (no player damage)");
            s.sendMessage("§6/ls ban <player> [reason]  §7/ls unban <player>");
            s.sendMessage("§6/ls timeout <player> <minutes> [reason]  §7temporary ban");
            s.sendMessage("§6/ls kick <player> [reason]");
            s.sendMessage("§6/ls sethearts <player> <hearts>");
            s.sendMessage("§6/ls check <player>  §7activity, hearts and powers");
            s.sendMessage("§6/ls inv <player>  §7/ls ender <player>  §7open their inventory");
            s.sendMessage("§6/ls mace status|reset|lock");
            return true;
        }
        long now = System.currentTimeMillis();
        switch (a[0].toLowerCase()) {
            case "grace" -> {
                if (a.length < 2) {
                    s.sendMessage(graceActive() ? "§aGrace period: " + ((graceUntil - now) / 60000 + 1) + " minute(s) left."
                                                : "§cNo grace period is active.");
                    return true;
                }
                if (a[1].equalsIgnoreCase("off")) {
                    graceUntil = 0; save();
                    Bukkit.broadcast(Component.text("§cThe grace period has ended. PvP is enabled!"));
                    return true;
                }
                double mins;
                try { mins = Double.parseDouble(a[1]); } catch (NumberFormatException ex) { return false; }
                if (mins <= 0) return false;
                graceUntil = now + (long) (mins * 60000);
                save();
                Bukkit.broadcast(Component.text("§aGrace period started! PvP is disabled for " + a[1] + " minute(s)."));
            }
            case "ban", "timeout" -> {
                boolean timed = a[0].equalsIgnoreCase("timeout");
                if (a.length < (timed ? 3 : 2)) return false;
                OfflinePlayer t = find(a[1]);
                if (t == null) { s.sendMessage("§cUnknown player (they must have joined before)."); return true; }
                long until = 0;
                int reasonFrom = 2;
                if (timed) {
                    double mins;
                    try { mins = Double.parseDouble(a[2]); } catch (NumberFormatException ex) { return false; }
                    if (mins <= 0) return false;
                    until = now + (long) (mins * 60000);
                    reasonFrom = 3;
                }
                String reason = a.length > reasonFrom ? join(a, reasonFrom) : (timed ? "Timed out by an admin" : "Banned by an admin");
                bans.put(t.getUniqueId(), new BanEntry(String.valueOf(t.getName()), reason, until));
                save();
                Player on = t.getPlayer();
                if (on != null) on.kick(Component.text(timed ? "§cYou were timed out.\n§7Reason: " + reason : "§cYou were banned.\n§7Reason: " + reason));
                s.sendMessage("§a" + t.getName() + (timed ? " timed out for " + a[2] + " minute(s)." : " banned."));
            }
            case "unban" -> {
                if (a.length < 2) return false;
                UUID found = null;
                for (var en : bans.entrySet()) if (en.getValue().name().equalsIgnoreCase(a[1])) found = en.getKey();
                if (found == null) { OfflinePlayer t = find(a[1]); if (t != null && bans.containsKey(t.getUniqueId())) found = t.getUniqueId(); }
                if (found == null) { s.sendMessage("§cThat player isn't banned."); return true; }
                bans.remove(found);
                save();
                s.sendMessage("§aUnbanned " + a[1] + ".");
            }
            case "kick" -> {
                if (a.length < 2) return false;
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cPlayer not online."); return true; }
                t.kick(Component.text("§cYou were kicked.\n§7Reason: " + (a.length > 2 ? join(a, 2) : "Kicked by an admin")));
                s.sendMessage("§aKicked " + t.getName() + ".");
            }
            case "sethearts" -> {
                if (a.length < 3) return false;
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cPlayer not online."); return true; }
                double hearts;
                try { hearts = Double.parseDouble(a[2]); } catch (NumberFormatException ex) { return false; }
                hearts = Math.max(1, Math.min(500, hearts));
                AttributeInstance at = t.getAttribute(Attribute.MAX_HEALTH);
                if (at == null) return true;
                at.setBaseValue(hearts * 2);
                t.setHealth(Math.min(t.getHealth(), at.getValue()));
                s.sendMessage("§a" + t.getName() + " now has " + hearts + " hearts.");
                t.sendMessage("§eAn admin set your hearts to " + hearts + ".");
            }
            case "check" -> {
                if (a.length < 2) return false;
                OfflinePlayer t = find(a[1]);
                if (t == null) { s.sendMessage("§cUnknown player (they must have joined before)."); return true; }
                s.sendMessage("§6--- " + t.getName() + " ---");
                s.sendMessage("§eStatus: " + (t.isOnline() ? "§aonline" : "§coffline"));
                long seen = t.isOnline() ? now : t.getLastSeen();
                s.sendMessage("§eLast seen: §f" + (t.isOnline() ? "right now" : seen == 0 ? "never" : new Date(seen).toString()));
                try {
                    double hours = t.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20.0 / 3600.0;
                    s.sendMessage("§ePlaytime: §f" + String.format("%.1f", hours) + " hours   §eKills: §f"
                            + t.getStatistic(Statistic.PLAYER_KILLS) + "   §eDeaths: §f" + t.getStatistic(Statistic.DEATHS));
                } catch (Exception ex) { s.sendMessage("§ePlaytime: §funknown"); }
                Player on = t.getPlayer();
                if (on != null) {
                    AttributeInstance at = on.getAttribute(Attribute.MAX_HEALTH);
                    s.sendMessage("§eHearts: §f" + (at != null ? at.getBaseValue() / 2 : "?") + "   §eHealth now: §f" + String.format("%.1f", on.getHealth() / 2));
                    Location l = on.getLocation();
                    s.sendMessage("§eLocation: §f" + l.getWorld().getName() + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ()
                            + "   §eMode: §f" + on.getGameMode());
                    s.sendMessage("§eHolding dragon egg: §f" + on.getInventory().contains(Material.DRAGON_EGG));
                }
                Set<Power> ps = powersOf(t.getUniqueId());
                StringBuilder sb = new StringBuilder();
                for (Power pw : ps) sb.append(pw.name()).append(" ");
                s.sendMessage("§ePowers: §f" + (ps.isEmpty() ? "none" : sb.toString().trim()));
                BanEntry b = bans.get(t.getUniqueId());
                if (b != null) s.sendMessage("§cBanned/timed out: " + b.reason());
            }
            case "inv", "ender" -> {
                if (a.length < 2) return false;
                if (!(s instanceof Player viewer)) { s.sendMessage("§cOnly players can open inventories."); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cPlayer not online."); return true; }
                viewer.openInventory(a[0].equalsIgnoreCase("inv") ? t.getInventory() : t.getEnderChest());
            }
            case "mace" -> {
                String sub = a.length > 1 ? a[1].toLowerCase() : "status";
                if (sub.equals("reset")) { maceCrafted = false; save(); s.sendMessage("§aThe mace can be crafted again (once)."); }
                else if (sub.equals("lock")) { maceCrafted = true; save(); s.sendMessage("§aThe mace is now locked (nobody can craft one)."); }
                else s.sendMessage("§eMace crafted already: §f" + maceCrafted);
            }
            default -> { return false; }
        }
        return true;
    }

    // ---------- tab completion (the suggestions that pop up as you type) ----------
    private List<String> onlineNames() {
        List<String> n = new ArrayList<>();
        for (Player pl : Bukkit.getOnlinePlayers()) n.add(pl.getName());
        return n;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        String cn = c.getName().toLowerCase();
        List<String> out = new ArrayList<>();
        if (cn.equals("lifesteal")) {
            if (!s.hasPermission("lifesteal.admin")) return out;
            if (a.length == 1) {
                out.addAll(List.of("grace", "ban", "unban", "timeout", "kick", "sethearts", "check", "inv", "ender", "mace"));
            } else if (a.length == 2) {
                switch (a[0].toLowerCase()) {
                    case "grace" -> out.addAll(List.of("off", "10", "30", "60"));
                    case "mace" -> out.addAll(List.of("status", "reset", "lock"));
                    case "unban" -> { for (BanEntry b : bans.values()) out.add(b.name()); }
                    case "ban", "timeout", "kick", "sethearts", "check", "inv", "ender" -> out.addAll(onlineNames());
                    default -> {}
                }
            } else if (a.length == 3) {
                switch (a[0].toLowerCase()) {
                    case "timeout" -> out.addAll(List.of("10", "60", "1440"));
                    case "sethearts" -> out.addAll(List.of("1", "10", "20", "30"));
                    default -> {}
                }
            }
        } else if (cn.equals("powers")) {
            if (a.length == 1) {
                out.addAll(List.of("list"));
                if (s.isOp()) out.addAll(List.of("reload", "give", "reset", "revive", "item"));
            } else if (s.isOp() && a.length == 2 && List.of("give", "reset", "revive", "item").contains(a[0].toLowerCase())) {
                out.addAll(onlineNames());
            } else if (s.isOp() && a.length == 3) {
                if (a[0].equalsIgnoreCase("give")) for (Power pw : Power.pool()) out.add(pw.name());
                else if (a[0].equalsIgnoreCase("item")) out.addAll(List.of("heart", "crystal", "bend"));
            }
        } else if (cn.equals("withdrawpower")) {
            if (a.length == 1 && s instanceof Player pl) for (Power pw : powersOf(pl.getUniqueId())) out.add(pw.name());
        } else if (cn.equals("withdrawheart")) {
            if (a.length == 1) out.addAll(List.of("1", "2", "5", "10"));
        }
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }

    // ---------- /powers ----------
    private boolean withdrawHeart(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) return true;
        if (!getConfig().getBoolean("withdraw.hearts", true)) { p.sendMessage("§cWithdrawing hearts is disabled."); return true; }
        int n = 1;
        if (a.length > 0) {
            try { n = Integer.parseInt(a[0]); } catch (NumberFormatException ex) { return false; }
            if (n < 1) return false;
        }
        AttributeInstance at = p.getAttribute(Attribute.MAX_HEALTH);
        if (at == null) return true;
        double minHp = Math.max(1, getConfig().getDouble("hearts.min-hearts", 1)) * 2;   // can't withdraw your last heart
        int can = (int) ((at.getBaseValue() - minHp) / 2);
        if (can < 1) { p.sendMessage("§cYou can't withdraw your last heart."); return true; }
        n = Math.min(Math.min(n, can), 64);
        at.setBaseValue(at.getBaseValue() - n * 2);
        ItemStack h = heartItem();
        h.setAmount(n);
        giveOrDrop(p, h);
        p.sendMessage("§aYou withdrew " + n + " heart" + (n == 1 ? "" : "s") + ".");
        return true;
    }

    private boolean withdrawPower(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) return true;
        if (!getConfig().getBoolean("withdraw.powers", true)) { p.sendMessage("§cWithdrawing powers is disabled."); return true; }
        UUID id = p.getUniqueId();
        Set<Power> ps = owned.get(id);
        if (ps == null || ps.isEmpty()) { p.sendMessage("§cYou have no power to withdraw."); return true; }
        if (a.length < 1) {
            StringBuilder sb = new StringBuilder();
            for (Power pw : ps) sb.append(pw.name()).append(" ");
            p.sendMessage("§6Usage: /withdrawpower <power>  §7Your powers: " + sb);
            return true;
        }
        Power pw;
        try { pw = Power.valueOf(a[0].toUpperCase()); }
        catch (IllegalArgumentException ex) { p.sendMessage("§cUnknown power. Use /powers to see yours."); return true; }
        if (pw.isElement()) { p.sendMessage("§cBending abilities can't be withdrawn."); return true; }
        if (!ps.remove(pw)) { p.sendMessage("§cYou don't own that power."); return true; }
        boolean anyUnique = false;
        for (Power x : ps) if (!x.isElement()) anyUnique = true;
        if (!anyUnique) lost.add(id);
        if (ps.isEmpty()) owned.remove(id);
        selected.remove(id);
        crystals.add(pw);
        save();
        giveOrDrop(p, crystalItem(pw));
        p.sendMessage("§aYou withdrew " + pw.display + " into a Power Crystal.");
        return true;
    }


    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        String cn = c.getName().toLowerCase();
        if (cn.equals("withdrawheart")) return withdrawHeart(s, a);
        if (cn.equals("withdrawpower")) return withdrawPower(s, a);
        if (cn.equals("lifesteal")) return admin(s, a);
        if (a.length == 0) {
            if (!(s instanceof Player p)) return true;
            Set<Power> ps = powersOf(p.getUniqueId());
            boolean egg = p.getInventory().contains(Material.DRAGON_EGG);
            if (ps.isEmpty() && !egg) { p.sendMessage("§cYou have no power. Kill someone to steal one!"); return true; }
            for (Power pw : ps) p.sendMessage("§6" + pw.display + " §7- " + pw.desc + (pw.passive ? " §8(passive)" : ""));
            if (egg) p.sendMessage("§5Dragon egg powers: §dDragon Flight §7(passive) §dand Dragon's Breath §7(active) - while you hold the egg");
            p.sendMessage("§7Power Core: right-click to use a power, sneak + right-click to switch.");
            p.sendMessage("§bBending Star: right-click to use a bending move, sneak + right-click to switch.");
            return true;
        }
        if (a[0].equalsIgnoreCase("list")) {
            for (Power pw : Power.values()) {
                if (pw.isMove()) continue;
                String holder = pw.eggOnly() ? "dragon egg holder" : pw.isElement() ? "shared - everyone gets one" : "free";
                for (var en : owned.entrySet()) if (!pw.isElement() && en.getValue().contains(pw)) {
                    String n = Bukkit.getOfflinePlayer(en.getKey()).getName();
                    holder = n == null ? en.getKey().toString() : n;
                }
                s.sendMessage("§e" + pw.name() + " §7(" + pw.display + ") - " + holder);
            }
            return true;
        }
        if (!s.isOp()) { s.sendMessage("§cAdmins only."); return true; }
        if (a[0].equalsIgnoreCase("reload")) { reloadConfig(); registerRecipes(); s.sendMessage("§aConfig reloaded."); return true; }
        if (a.length < 2) return false;
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { s.sendMessage("§cPlayer not online."); return true; }
        UUID tid = t.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "reset" -> { owned.remove(tid); selected.remove(tid); removeCores(t); save(); s.sendMessage("§aReset."); }
            case "revive" -> { lost.remove(tid); save(); assign(t); s.sendMessage("§aRevived."); }
            case "give" -> {
                if (a.length < 3) return false;
                try {
                    Power pw = Power.valueOf(a[2].toUpperCase());
                    if (pw.internal()) { s.sendMessage("§cThat one can't be given directly (dragon egg powers come from the egg, moves come from bender powers)."); return true; }
                    if (!pw.isElement()) for (Set<Power> set : owned.values()) set.remove(pw);   // keep one-of-each
                    owned.values().removeIf(Set::isEmpty);
                    owned.computeIfAbsent(tid, x -> new LinkedHashSet<>()).add(pw);
                    crystals.remove(pw);
                    lost.remove(tid);
                    save(); giveItems(t);
                    s.sendMessage("§aGiven " + pw.display + ".");
                } catch (IllegalArgumentException ex) { s.sendMessage("§cUnknown power. Use /powers list"); }
            }
            case "item" -> {
                if (a.length < 3) return false;
                if (a[2].equalsIgnoreCase("heart")) giveOrDrop(t, heartItem());
                else if (a[2].equalsIgnoreCase("crystal")) giveOrDrop(t, crystalItem(null));
                else if (a[2].equalsIgnoreCase("bend")) giveOrDrop(t, makeBendStar());
                else return false;
                s.sendMessage("§aGiven.");
            }
            default -> { return false; }
        }
        return true;
    }
}
