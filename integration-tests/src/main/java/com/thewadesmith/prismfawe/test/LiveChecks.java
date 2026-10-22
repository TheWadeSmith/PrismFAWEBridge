package com.thewadesmith.prismfawe.test;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.prism_mc.prism.api.activities.Activity;
import org.prism_mc.prism.api.util.Coordinate;
import org.prism_mc.prism.paper.api.PrismPaperApi;
import org.prism_mc.prism.paper.api.activities.PaperActivityQuery;

/** Destructive checks restricted to a named disposable world and fixed coordinates. */
public final class LiveChecks extends JavaPlugin {
    private final List<String> results = new ArrayList<>();
    private PrismPaperApi prism;
    private org.bukkit.World world;
    private com.sk89q.worldedit.world.World weWorld;
    private long started;
    private volatile boolean running;

    @Override
    public void onEnable() {
        prism = getServer().getServicesManager().load(PrismPaperApi.class);
        getLogger().info("Run bridgecheck from the console to test the disposable bridge-test-world.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bridgecheck.run") || running) return true;
        world = Bukkit.getWorld("bridge-test-world");
        if (world == null || !Bukkit.getIp().equals("127.0.0.1")) {
            sender.sendMessage("Refusing: requires bridge-test-world and localhost-only server binding.");
            return true;
        }
        weWorld = BukkitAdapter.adapt(world);
        started = System.currentTimeMillis();
        running = true;
        getServer().getScheduler().runTaskAsynchronously(this, this::checks);
        return true;
    }

    private void checks() {
        results.clear();
        try {
            main(() -> { world.getChunkAt(-2, -2).load(); world.getChunkAt(-3, -2).load();
                for (int x = -40; x <= -16; x++) for (int z = -40; z <= -16; z++)
                    world.getBlockAt(x, 80, z).setType(Material.AIR, false);
                return null; });
            main(() -> { world.getBlockAt(-32, 80, -32).setType(Material.STONE, false); return null; });
            commandAt(-32, 80, -32, "/set diamond_block");
            waitMaterial(-32, 80, -32, Material.DIAMOND_BLOCK);
            var set = waitRow(-32, 80, -32, 1);
            check("FAWE //set stored one database row with negative coordinates", set.coordinate().intX() == -32);
            rollback(set);
            check("Prism rollback restores original stone", material(-32, 80, -32) == Material.STONE);
            restore(set);
            check("Prism restore reapplies diamond block", material(-32, 80, -32) == Material.DIAMOND_BLOCK);
            commandAt(-32, 80, -32, "/set diamond_block");
            Thread.sleep(1500);
            check("No-op //set adds no duplicate record", rows(-32, 80, -32).size() == 1);
            commandAt(-32, 80, -32, "/replace diamond_block gold_block");
            waitMaterial(-32, 80, -32, Material.GOLD_BLOCK);
            waitRow(-32, 80, -32, 2);
            check("FAWE //replace is logged", rows(-32, 80, -32).size() == 2);
            main(() -> { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "/undo"); return null; });
            waitMaterial(-32, 80, -32, Material.DIAMOND_BLOCK);
            waitRow(-32, 80, -32, 3);
            check("FAWE //undo is logged", rows(-32, 80, -32).size() == 3);
            main(() -> { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "/redo"); return null; });
            waitMaterial(-32, 80, -32, Material.GOLD_BLOCK);
            waitRow(-32, 80, -32, 4);
            check("FAWE //redo is logged", rows(-32, 80, -32).size() == 4);

            main(() -> {
                world.getBlockAt(-35, 80, -32).setType(Material.CHEST, false);
                var chest = (Chest) world.getBlockAt(-35, 80, -32).getState();
                chest.getBlockInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
                return null;
            });
            commandAt(-35, 80, -32, "/cut");
            waitMaterial(-35, 80, -32, Material.AIR);
            var chestCut = waitRow(-35, 80, -32, 1);
            rollback(chestCut);
            check("Cut chest rollback restores its three diamonds", main(() -> {
                var chest = (Chest) world.getBlockAt(-35, 80, -32).getState();
                var item = chest.getBlockInventory().getItem(0);
                return item != null && item.getType() == Material.DIAMOND && item.getAmount() == 3;
            }));
            restore(chestCut);
            check("Cut chest restore removes the chest again", material(-35, 80, -32) == Material.AIR);
            commandAt(-35, 80, -32, "/paste -o");
            waitMaterial(-35, 80, -32, Material.CHEST);
            var pasted = waitRow(-35, 80, -32, 2);
            check("FAWE //paste restores clipboard chest contents", main(() -> {
                var item = ((Chest) world.getBlockAt(-35, 80, -32).getState()).getBlockInventory().getItem(0);
                return item != null && item.getType() == Material.DIAMOND && item.getAmount() == 3;
            }));
            rollback(pasted);
            check("Prism rolls back the pasted chest", material(-35, 80, -32) == Material.AIR);
            restore(pasted);
            check("Prism restores the pasted chest and contents", main(() -> {
                var item = ((Chest) world.getBlockAt(-35, 80, -32).getState()).getBlockInventory().getItem(0);
                return item != null && item.getType() == Material.DIAMOND && item.getAmount() == 3;
            }));

            main(() -> {
                for (int x : new int[]{-38, -18}) world.getBlockAt(x, 80, -32).setType(Material.CHEST, false);
                var old = (Chest) world.getBlockAt(-38, 80, -32).getState();
                old.getBlockInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
                var fresh = (Chest) world.getBlockAt(-18, 80, -32).getState();
                fresh.customName(Component.text("New chest")); fresh.update(true, false);
                fresh.getBlockInventory().setItem(0, new ItemStack(Material.EMERALD, 5));
                return null;
            });
            edit(-38, 80, -32, main(() -> weWorld.getFullBlock(BlockVector3.at(-18, 80, -32))));
            var named = waitRow(-38, 80, -32, 1);
            rollback(named);
            check("Same-type chest rollback removes added name and restores inventory", main(() -> {
                var chest = (Chest) world.getBlockAt(-38, 80, -32).getState();
                var item = chest.getBlockInventory().getItem(0);
                return chest.customName() == null && item != null && item.getType() == Material.DIAMOND && item.getAmount() == 2;
            }));
            restore(named);
            check("Same-type chest restore restores name and new contents", main(() -> {
                var chest = (Chest) world.getBlockAt(-38, 80, -32).getState();
                var item = chest.getBlockInventory().getItem(0);
                return chest.customName() != null
                        && PlainTextComponentSerializer.plainText().serialize(chest.customName()).equals("New chest")
                        && item != null && item.getType() == Material.EMERALD && item.getAmount() == 5;
            }));

            main(() -> { sign(-36, "Before"); sign(-20, "After"); return null; });
            edit(-36, 80, -35, main(() -> weWorld.getFullBlock(BlockVector3.at(-20, 80, -35))));
            var sign = waitRow(-36, 80, -35, 1);
            rollback(sign);
            check("Same-state sign rollback preserves original, numeric, boolean-like and empty text",
                    main(() -> signText(-36).equals(List.of("Before", "123", "true", ""))));
            restore(sign);
            check("Same-state sign restore preserves changed, numeric, boolean-like and empty text",
                    main(() -> signText(-36).equals(List.of("After", "123", "true", ""))));
            check("Bridge remained enabled", main(() -> Bukkit.getPluginManager().isPluginEnabled("PrismFAWEBridge")));
            getLogger().info("LIVE CHECKS COMPLETE: " + results.size() + " PASS");
        } catch (Throwable failure) {
            results.add("FAIL " + failure);
            getLogger().log(Level.SEVERE, "LIVE CHECKS FAILED", failure);
        } finally {
            running = false;
            try {
                Files.createDirectories(getDataFolder().toPath());
                Files.write(getDataFolder().toPath().resolve("results.txt"), results);
            } catch (Exception failure) { getLogger().log(Level.SEVERE, "Could not save results", failure); }
        }
    }

    private void commandAt(int x, int y, int z, String command) throws Exception {
        main(() -> {
            var actor = BukkitAdapter.adapt(Bukkit.getConsoleSender());
            var session = WorldEdit.getInstance().getSessionManager().get(actor);
            session.setWorldOverride(weWorld);
            var position = BlockVector3.at(x, y, z);
            session.setRegionSelector(weWorld, new CuboidRegionSelector(weWorld, position, position));
            session.setPlaceAtPos1(true);
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) throw new IllegalStateException("Command failed: " + command);
            return null;
        });
    }

    private void edit(int x, int y, int z, BaseBlock block) throws Exception {
        var actor = main(() -> BukkitAdapter.adapt(Bukkit.getConsoleSender()));
        try (var session = WorldEdit.getInstance().newEditSessionBuilder().world(weWorld).actor(actor).build()) {
            session.setBlock(BlockVector3.at(x, y, z), block);
        }
    }

    private List<Activity> rows(int x, int y, int z) throws Exception {
        var point = new Coordinate(x, y, z);
        return prism.lookup(PaperActivityQuery.builder().actionTypeKey("fawe-change").worldUuid(world.getUID())
                .minCoordinate(point).maxCoordinate(point).after(started / 1000 - 1).grouped(false).limit(100).build()).get(10, TimeUnit.SECONDS);
    }

    private Activity waitRow(int x, int y, int z, int count) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            var found = rows(x, y, z);
            if (found.size() >= count) return found.getFirst();
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + count + " recorded changes at " + x + "," + y + "," + z);
    }

    private void rollback(Activity activity) throws Exception {
        var query = PaperActivityQuery.builder().actionTypeKey("fawe-change")
                .activityIds(List.of(((Number) activity.primaryKey()).intValue())).worldUuid(world.getUID()).rollback().build();
        var result = prism.rollback(Bukkit.getConsoleSender(), query).get(15, TimeUnit.SECONDS);
        if (result.applied() != 1) throw new AssertionError("Rollback applied=" + result.applied());
    }

    private void restore(Activity activity) throws Exception {
        var query = PaperActivityQuery.builder().actionTypeKey("fawe-change")
                .activityIds(List.of(((Number) activity.primaryKey()).intValue())).worldUuid(world.getUID()).restore().build();
        var result = prism.restore(Bukkit.getConsoleSender(), query).get(15, TimeUnit.SECONDS);
        if (result.applied() != 1) throw new AssertionError("Restore applied=" + result.applied());
    }

    private void sign(int x, String text) {
        world.getBlockAt(x, 79, -35).setType(Material.STONE, false);
        world.getBlockAt(x, 80, -35).setType(Material.OAK_SIGN, false);
        var sign = (Sign) world.getBlockAt(x, 80, -35).getState();
        sign.getSide(Side.FRONT).line(0, Component.text(text));
        sign.getSide(Side.FRONT).line(1, Component.text("123"));
        sign.getSide(Side.FRONT).line(2, Component.text("true"));
        sign.update(true, false);
    }

    private List<String> signText(int x) {
        var sign = (Sign) world.getBlockAt(x, 80, -35).getState();
        return sign.getSide(Side.FRONT).lines().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    private Material material(int x, int y, int z) throws Exception { return main(() -> world.getBlockAt(x, y, z).getType()); }
    private void waitMaterial(int x, int y, int z, Material expected) throws Exception {
        for (int i = 0; i < 100; i++) { if (material(x, y, z) == expected) return; Thread.sleep(100); }
        throw new AssertionError("Expected " + expected + " at " + x + "," + y + "," + z);
    }
    private <T> T main(Supplier<T> work) throws Exception {
        var future = new CompletableFuture<T>();
        Bukkit.getScheduler().runTask(this, () -> { try { future.complete(work.get()); }
            catch (Throwable failure) { future.completeExceptionally(failure); } });
        return future.get(10, TimeUnit.SECONDS);
    }
    private void check(String description, boolean value) {
        if (!value) throw new AssertionError(description);
        results.add("PASS " + description); getLogger().info("PASS " + description);
    }
}
