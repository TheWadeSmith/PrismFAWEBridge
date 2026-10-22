package com.thewadesmith.prismfawe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fastasyncworldedit.core.extent.processor.ExtentBatchProcessorHolder;
import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import java.util.UUID;
import java.util.Map;
import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.sk89q.worldedit.math.BlockVector3;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.junit.jupiter.api.Test;

class BatchChangeProcessorTest {
    private final PendingChanges pending = new PendingChanges(10);
    private final ChangeOrigin origin = new ChangeOrigin(UUID.randomUUID(), "world", UUID.randomUUID(), "Builder");
    private final BatchChangeProcessor processor = new BatchChangeProcessor(origin, pending);

    @Test
    void nativeHookPreservesExtentAndOtherProcessors() {
        var queue = mock(ExtentBatchProcessorHolder.class, withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
        var existing = mock(IBatchProcessor.class, CALLS_REAL_METHODS);
        when(existing.getScope()).thenReturn(ProcessorScope.READING_BLOCKS);
        queue.addPostProcessor(existing);
        queue.addProcessor(processor);
        assertSame(queue, queue.addPostProcessor(processor));
        var chunk = mock(IChunk.class);
        var get = mock(IChunkGet.class);
        var set = mock(IChunkSet.class);
        queue.postProcess(chunk, get, set);
        verify(existing).postProcess(chunk, get, set);
    }

    @Test
    void preProcessingNeverChangesOrLogsBlocks() {
        var set = mock(IChunkSet.class);
        assertSame(set, processor.processSet(mock(IChunk.class), mock(IChunkGet.class), set));
        assertEquals(0, pending.size());
    }

    @Test
    void capturesCommittedChangesAtNegativeChunkAndSectionCoordinates() {
        var chunk = mock(IChunk.class);
        when(chunk.getX()).thenReturn(-2);
        when(chunk.getZ()).thenReturn(-3);
        var get = mock(IChunkGet.class);
        var set = mock(IChunkSet.class);
        when(set.getMinSectionPosition()).thenReturn(-4);
        when(set.getMaxSectionPosition()).thenReturn(-4);
        var blocks = new char[4096];
        blocks[(5 << 8) | (7 << 4) | 3] = 42;
        when(set.loadIfPresent(-4)).thenReturn(blocks);
        doReturn(state("minecraft:stone", false)).when(get).getBlock(3, -59, 7);
        doReturn(state("minecraft:dirt", false)).when(set).getBlock(3, -59, 7);

        processor.processSet(chunk, get, set);
        assertEquals(0, pending.size(), "Preimages must not be published before commit");
        processor.postProcess(chunk, get, set);

        var change = pending.poll();
        assertNotNull(change);
        assertEquals(-29, change.x());
        assertEquals(-59, change.y());
        assertEquals(-41, change.z());
        assertEquals("minecraft:stone", change.before().blockData());
        assertEquals("minecraft:dirt", change.after().blockData());
        assertEquals(origin, change.origin());
        assertEquals(0, pending.size());
    }

    @Test
    void ignoresUnchangedAndReservedEntries() {
        var set = mock(IChunkSet.class);
        when(set.loadIfPresent(0)).thenReturn(new char[]{0, 8});
        var get = mock(IChunkGet.class);
        doReturn(state("minecraft:stone", false)).when(get).getBlock(1, 0, 0);
        doReturn(state("minecraft:stone", false)).when(set).getBlock(1, 0, 0);
        var chunk = mock(IChunk.class);
        processor.processSet(chunk, get, set);
        processor.postProcess(chunk, get, set);
        assertEquals(0, pending.size());
        verify(get, never()).getBlock(0, 0, 0);
    }

    @Test
    void queueOverflowIsCountedWithoutBlockingFawe() {
        var queue = new PendingChanges(1);
        var change = new BlockChange(origin, 1, 2, 3, 123, new BlockSnapshot("minecraft:stone", false, null),
                new BlockSnapshot("minecraft:air", true, null));
        assertTrue(queue.offer(change));
        assertFalse(queue.offer(change));
        assertEquals(1, queue.dropped());
        assertEquals(change, queue.poll());
    }

    @Test
    void capturesTileOnlyBeforeCommitWithoutReadingSparsePostCommitCopy() {
        var chunk = mock(IChunk.class);
        when(chunk.getX()).thenReturn(-2);
        when(chunk.getZ()).thenReturn(3);
        var original = mock(IChunkGet.class);
        var postCopy = mock(IChunkGet.class);
        var set = mock(IChunkSet.class);
        var oldNbt = FaweCompoundTag.of(LinCompoundTag.builder().putString("CustomName", "old chest").build());
        var newNbt = FaweCompoundTag.of(LinCompoundTag.builder().putString("CustomName", "new chest").build());
        when(set.tiles()).thenReturn(Map.of(BlockVector3.at(2, 64, 3), newNbt));
        doReturn(state("minecraft:chest", false)).when(original).getBlock(2, 64, 3);
        when(original.tile(2, 64, 3)).thenReturn(oldNbt);
        when(set.tile(2, 64, 3)).thenReturn(newNbt);

        processor.processSet(chunk, original, set);
        assertEquals(0, pending.size());
        processor.postProcess(chunk, postCopy, set);

        var change = pending.poll();
        assertNotNull(change);
        assertTrue(change.before().nbt().contains("old chest"));
        assertTrue(change.after().nbt().contains("new chest"));
        assertEquals(-30, change.x());
        assertEquals(51, change.z());
        verifyNoInteractions(postCopy);
        verify(set, never()).getBlock(2, 64, 3);
        processor.postProcess(chunk, postCopy, set);
        assertEquals(0, pending.size(), "Commit callback must publish each snapshot once");
    }

    @Test
    void closingQueueRejectsLateCallbacksAndStillAllowsDrainage() {
        var warnings = new java.util.concurrent.atomic.AtomicInteger();
        var queue = new PendingChanges(2, warnings::incrementAndGet);
        var change = new BlockChange(origin, 1, 2, 3, 123, new BlockSnapshot("minecraft:stone", false, null),
                new BlockSnapshot("minecraft:air", true, null));
        assertTrue(queue.offer(change));
        queue.close();
        assertFalse(queue.offer(change));
        assertFalse(queue.offer(change));
        assertSame(change, queue.poll());
        assertEquals(2, queue.late());
        assertEquals(1, warnings.get());
    }

    private static BlockState state(String data, boolean air) {
        var state = mock(BlockState.class);
        var material = mock(BlockMaterial.class);
        when(state.getAsString()).thenReturn(data);
        when(state.getOrdinal()).thenReturn(8);
        when(state.getMaterial()).thenReturn(material);
        when(material.isAir()).thenReturn(air);
        return state;
    }
}
