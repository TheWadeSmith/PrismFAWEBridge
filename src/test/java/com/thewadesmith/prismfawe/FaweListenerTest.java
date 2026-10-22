package com.thewadesmith.prismfawe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fastasyncworldedit.core.extent.processor.ExtentBatchProcessorHolder;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.world.World;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;

class FaweListenerTest {
    @Test
    void eventAttachesBothNativeHooksWithoutWrappingTheQueue() {
        var pending = new PendingChanges(10);
        var listener = new FaweListener(pending);
        var queue = mock(ExtentBatchProcessorHolder.class, withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS));
        var world = mock(World.class);
        when(world.getName()).thenReturn("world");
        var bukkitWorld = mock(org.bukkit.World.class);
        var event = new EditSessionEvent(world, null, -1, EditSession.Stage.BEFORE_CHANGE);
        event.setExtent(queue);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(bukkitWorld);
            listener.onEditSession(event);
        }
        assertSame(queue, event.getExtent());
        assertInstanceOf(BatchChangeProcessor.class, queue.getProcessor());
        assertSame(queue.getProcessor(), queue.getPostProcessor());
    }

    @Test
    void cancelledSessionsAreNotObserved() {
        var pending = new PendingChanges(10);
        var extent = mock(com.sk89q.worldedit.extent.Extent.class);
        var event = new EditSessionEvent(mock(World.class), null, -1, EditSession.Stage.BEFORE_CHANGE);
        event.setExtent(extent);
        event.setCancelled(true);
        new FaweListener(pending).onEditSession(event);
        verifyNoInteractions(extent);
    }
}
