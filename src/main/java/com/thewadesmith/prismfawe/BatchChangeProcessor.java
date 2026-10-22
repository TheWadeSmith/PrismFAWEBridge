package com.thewadesmith.prismfawe;

import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import java.util.HashSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import org.enginehub.linbus.tree.LinCompoundTag;

/** Reads committed chunk snapshots; never changes FAWE's extent or chunk buffers. */
final class BatchChangeProcessor implements IBatchProcessor {
    private final ChangeOrigin origin;
    private final PendingChanges pending;
    private final Map<IChunkSet, List<BlockChange>> inFlight = Collections.synchronizedMap(new IdentityHashMap<>());

    BatchChangeProcessor(ChangeOrigin origin, PendingChanges pending) {
        this.origin = origin;
        this.pending = pending;
    }

    @Override
    public IChunkSet processSet(IChunk chunk, IChunkGet get, IChunkSet set) {
        // Capture before FAWE mutates live chunks. Its post-commit copy omits some
        // tile preimages (notably beacons and NBT-only edits).
        var changes = captureChanges(chunk, get, set);
        if (!changes.isEmpty()) {
            inFlight.put(set, changes);
        }
        return set;
    }

    @Override
    public void postProcess(IChunk chunk, IChunkGet get, IChunkSet set) {
        var changes = inFlight.remove(set);
        if (changes != null) {
            changes.forEach(pending::offer);
        }
    }

    private List<BlockChange> captureChanges(IChunk chunk, IChunkGet get, IChunkSet set) {
        var changes = new ArrayList<BlockChange>();
        if (get == null || set == null) {
            return changes;
        }
        long timestamp = System.currentTimeMillis();
        Set<Integer> visitedTiles = set.tiles().isEmpty() ? null : new HashSet<>();
        for (int layer = set.getMinSectionPosition(); layer <= set.getMaxSectionPosition(); layer++) {
            char[] blocks = set.loadIfPresent(layer);
            if (blocks == null) {
                continue;
            }
            for (int i = 0; i < blocks.length; i++) {
                if (blocks[i] == BlockTypesCache.ReservedIDs.__RESERVED__) {
                    continue;
                }
                int x = i & 15;
                int y = (layer << 4) + (i >> 8);
                int z = (i >> 4) & 15;
                capture(chunk, get, set, x, y, z, timestamp, changes);
                if (visitedTiles != null) {
                    visitedTiles.add(pack(x, y, z));
                }
            }
        }
        // NBT-only writes may have no changed block section at all.
        if (visitedTiles != null) {
            for (var position : set.tiles().keySet()) {
                int x = position.x() & 15;
                int z = position.z() & 15;
                if (!visitedTiles.contains(pack(x, position.y(), z))) {
                    capture(chunk, get, set, x, position.y(), z, timestamp, changes);
                }
            }
        }
        return changes;
    }

    private void capture(IChunk chunk, IChunkGet get, IChunkSet set, int x, int y, int z, long timestamp, List<BlockChange> changes) {
        var beforeState = get.getBlock(x, y, z);
        // Reading a missing SET section via getBlock would allocate one and mutate
        // FAWE's batch. A tile-only write keeps its original block properties.
        var section = set.loadIfPresent(y >> 4);
        int index = ((y & 15) << 8) | (z << 4) | x;
        var afterState = section == null || section[index] == BlockTypesCache.ReservedIDs.__RESERVED__
                ? beforeState : set.getBlock(x, y, z);
        var before = BlockSnapshot.capture(beforeState, get.tile(x, y, z));
        var afterTile = set.tile(x, y, z);
        if (afterTile == null && afterState.getMaterial().isTile()) {
            afterTile = afterState.getMaterial().defaultTile();
            if (afterTile == null) {
                afterTile = FaweCompoundTag.of(LinCompoundTag.builder().build());
            }
        }
        var after = BlockSnapshot.capture(afterState, afterTile);
        if (!before.equals(after) && !(before.air() && after.air())) {
            changes.add(new BlockChange(origin, (chunk.getX() << 4) + x, y,
                    (chunk.getZ() << 4) + z, timestamp, before, after));
        }
    }

    private static int pack(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    @Override
    public Extent construct(Extent child) {
        // WNA/direct-world sessions have no committed chunk callback. Do not silently
        // substitute an unsafe extent or change FAWE's placement/region semantics.
        pending.unsupportedSession();
        return child;
    }

    @Override
    public ProcessorScope getScope() { return ProcessorScope.READING_BLOCKS; }
}
