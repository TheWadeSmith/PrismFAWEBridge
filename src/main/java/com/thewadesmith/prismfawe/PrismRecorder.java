package com.thewadesmith.prismfawe;

import org.bukkit.Bukkit;
import org.prism_mc.prism.api.activities.Cause;
import org.prism_mc.prism.api.containers.PlayerContainer;
import org.prism_mc.prism.paper.api.PrismPaperApi;
import org.prism_mc.prism.paper.api.activities.PaperActivity;

/** Uses only the published Prism API, on Paper's main thread. */
final class PrismRecorder {
    private final PrismPaperApi prism;
    private final BridgeActions actions;
    private long accepted;
    private long notAccepted;

    PrismRecorder(PrismPaperApi prism) {
        this.prism = prism;
        actions = new BridgeActions(prism.actionTypeRegistry());
    }

    void record(BlockChange change) {
        var origin = change.origin();
        Object cause = origin.actorName();
        if (origin.playerId() != null) {
            var player = Bukkit.getPlayer(origin.playerId());
            cause = player == null ? new Cause(new PlayerContainer(origin.actorName(), origin.playerId())) : player;
        }
        var activity = PaperActivity.builder()
                .action(actions.create(change))
                .world(origin.worldId(), origin.worldName())
                .coordinate(change.x(), change.y(), change.z())
                .timestamp(change.timestamp())
                .cause(cause)
                .build();
        if (prism.recordingService().addToQueue(activity)) {
            accepted++;
        } else {
            notAccepted++;
        }
    }

    long accepted() { return accepted; }
    long notAccepted() { return notAccepted; }
}
