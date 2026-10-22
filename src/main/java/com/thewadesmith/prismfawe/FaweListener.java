package com.thewadesmith.prismfawe;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import org.bukkit.Bukkit;

final class FaweListener {
    private final PendingChanges pending;

    FaweListener(PendingChanges pending) { this.pending = pending; }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE || event.isCancelled() || event.getWorld() == null) {
            return;
        }
        var world = Bukkit.getWorld(event.getWorld().getName());
        if (world == null) {
            return;
        }
        var actor = event.getActor();
        var origin = new ChangeOrigin(world.getUID(), world.getName(),
                actor instanceof Player player ? player.getUniqueId() : null,
                actor == null ? "FAWE" : actor.getName());
        // This public API appends to FAWE's existing post-processors and keeps the
        // optimized queue extent, including its history, lighting and packet path.
        var processor = new BatchChangeProcessor(origin, pending);
        event.setExtent(event.getExtent().addProcessor(processor).addPostProcessor(processor));
    }
}
