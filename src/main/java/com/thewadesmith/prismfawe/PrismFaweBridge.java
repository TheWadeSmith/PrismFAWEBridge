package com.thewadesmith.prismfawe;

import com.sk89q.worldedit.WorldEdit;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.prism_mc.prism.paper.api.PrismPaperApi;

public final class PrismFaweBridge extends JavaPlugin {
    private PendingChanges pending;
    private FaweListener listener;
    private PrismRecorder recorder;
    private int changesPerTick;
    private long errors;
    private long reportedDropped;
    private long reportedUnsupported;

    @Override
    public void onEnable() {
        try {
            var prism = getServer().getServicesManager().load(PrismPaperApi.class);
            if (prism == null) {
                throw new IllegalStateException("Prism 4.4+ must be enabled and publish its Paper API service.");
            }
            saveDefaultConfig();
            int capacity = getConfig().getInt("max-pending-changes", 100000);
            changesPerTick = getConfig().getInt("changes-per-tick", 1000);
            if (capacity < 1 || capacity > 1000000 || changesPerTick < 1 || changesPerTick > 100000) {
                throw new IllegalArgumentException("Invalid queue settings: capacity must be 1..1000000; changes-per-tick 1..100000.");
            }
            pending = new PendingChanges(capacity, () -> getLogger().severe(
                    "A FAWE edit completed after bridge shutdown. Its late changes cannot be recorded. Avoid reload/disable during edits."));
            recorder = new PrismRecorder(prism);
            listener = new FaweListener(pending);
            WorldEdit.getInstance().getEventBus().register(listener);
            getServer().getScheduler().runTaskTimer(this, () -> drain(changesPerTick), 1L, 1L);
            getServer().getScheduler().runTaskTimer(this, this::reportProblems, 100L, 100L);
            getLogger().info("FAWE queued block logging enabled through Prism's public API (fawe-change).");
        } catch (RuntimeException | LinkageError failure) {
            getLogger().log(Level.SEVERE, "Bridge could not start. Check Prism/FAWE/API compatibility.", failure);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            WorldEdit.getInstance().getEventBus().unregister(listener);
        }
        if (pending != null && recorder != null) {
            pending.close();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (pending.size() > 0 && System.nanoTime() < deadline) {
                drain(Math.min(changesPerTick, pending.size()));
            }
            reportProblems();
            if (pending.size() > 0) {
                getLogger().severe("Shutdown deadline reached: " + pending.size() + " block changes remain unrecorded.");
            }
        }
    }

    private void drain(int limit) {
        for (int i = 0; i < limit; i++) {
            var change = pending.poll();
            if (change == null) {
                break;
            }
            try {
                recorder.record(change);
            } catch (RuntimeException | LinkageError failure) {
                if (errors++ == 0) {
                    getLogger().log(Level.SEVERE, "Could not record a FAWE change. /prismfawe reports the failure count.", failure);
                }
            }
        }
    }

    private void reportProblems() {
        long dropped = pending.dropped();
        if (dropped != reportedDropped) {
            getLogger().severe("Bridge queue overflow: " + (dropped - reportedDropped)
                    + " block changes lost since last report (" + dropped + " total). Check /prismfawe and queue settings.");
            reportedDropped = dropped;
        }
        long unsupported = pending.unsupported();
        if (unsupported != reportedUnsupported) {
            getLogger().warning("Skipped " + (unsupported - reportedUnsupported)
                    + " non-queued FAWE sessions. WNA/direct-world mode is not supported; use FAWE's queued placement.");
            reportedUnsupported = unsupported;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("prismfawe.admin")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }
        if (pending == null || recorder == null) {
            sender.sendMessage("Prism FAWE bridge is unavailable; check the server log.");
            return true;
        }
        sender.sendMessage("Prism FAWE: pending=" + pending.size() + ", accepted activities=" + recorder.accepted()
                + ", not accepted=" + recorder.notAccepted() + ", overflow=" + pending.dropped()
                + ", errors=" + errors + ", non-queued sessions=" + pending.unsupported());
        sender.sendMessage("Not accepted includes Prism filters and Prism's own full queue. Accepted means queued, not yet saved to disk.");
        return true;
    }
}
