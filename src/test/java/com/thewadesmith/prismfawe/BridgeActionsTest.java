package com.thewadesmith.prismfawe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import org.prism_mc.prism.api.services.modifications.ModificationResult;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.prism_mc.prism.api.actions.Action;
import org.prism_mc.prism.api.actions.ActionData;
import org.prism_mc.prism.api.actions.CustomData;
import org.prism_mc.prism.api.actions.types.ActionType;
import org.prism_mc.prism.api.actions.types.ActionTypeRegistry;
import org.prism_mc.prism.api.activities.Activity;
import org.prism_mc.prism.api.containers.PlayerContainer;
import org.prism_mc.prism.api.services.modifications.ModificationQueueMode;
import org.prism_mc.prism.api.services.modifications.ModificationRuleset;
import org.prism_mc.prism.api.services.recording.RecordingService;
import org.prism_mc.prism.paper.api.PrismPaperApi;
import org.prism_mc.prism.paper.api.activities.PaperActivity;

class BridgeActionsTest {
    private final ActionTypeRegistry registry = mock(ActionTypeRegistry.class);
    private final ActionType changeType = mock(ActionType.class);
    private final ActionType nativeBreak = mock(ActionType.class);
    private final ActionType nativePlace = mock(ActionType.class);
    private final ChangeOrigin player = new ChangeOrigin(UUID.randomUUID(), "world", UUID.randomUUID(), "Builder");
    private final List<ActionData> changeData = new ArrayList<>();

    private void setupRegistry() throws Exception {
        when(registry.actionType("fawe-change")).thenReturn(Optional.of(changeType));
        when(registry.actionType("block-break")).thenReturn(Optional.of(nativeBreak));
        when(registry.actionType("block-place")).thenReturn(Optional.of(nativePlace));
        when(changeType.createAction(any())).thenAnswer(call -> {
            ActionData data = call.getArgument(0);
            changeData.add(data);
            var action = mock(Action.class, withSettings().extraInterfaces(CustomData.class));
            when(action.type()).thenReturn(changeType);
            when(((CustomData) action).serializeCustomData()).thenReturn(data.customData());
            return action;
        });
    }

    @Test
    void replacementIsOneActivityWithBothSnapshotsAndOfflinePlayerIdentity() throws Exception {
        setupRegistry();
        var api = mock(PrismPaperApi.class);
        var recording = mock(RecordingService.class);
        var activities = new ArrayList<Activity>();
        when(api.actionTypeRegistry()).thenReturn(registry);
        when(api.recordingService()).thenReturn(recording);
        when(recording.addToQueue(any())).thenAnswer(call -> { activities.add(call.getArgument(0)); return true; });
        var recorder = new PrismRecorder(api);
        var stone = data("stone");
        var vine = data("vine");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBlockData("minecraft:stone")).thenReturn(stone);
            bukkit.when(() -> Bukkit.createBlockData("minecraft:vine")).thenReturn(vine);
            recorder.record(change(new BlockSnapshot("minecraft:stone", false, null),
                    new BlockSnapshot("minecraft:vine", false, null)));
        }
        assertEquals(1, activities.size(), "One record avoids Prism's break/place sorting ambiguity");
        assertEquals("stone", changeData.getFirst().replacedBlockName());
        assertEquals("vine", changeData.getFirst().blockName());
        assertEquals(12345, activities.getFirst().timestamp());
        assertEquals(-8, activities.getFirst().coordinate().intX());
        var cause = assertInstanceOf(PlayerContainer.class, activities.getFirst().cause().container());
        assertEquals(player.playerId(), cause.uuid());
        assertEquals("Builder", cause.name());
        assertEquals(1, recorder.accepted());
    }

    @Test
    void rollbackAndRestoreDelegateExactlyOneCompleteSnapshotIncludingNbt() throws Exception {
        setupRegistry();
        var rollback = mock(Action.class);
        var restore = mock(Action.class);
        when(nativeBreak.createAction(any())).thenReturn(rollback);
        when(nativePlace.createAction(any())).thenReturn(restore);
        when(rollback.applyRollback(any(), any(), any(), any())).thenReturn(mock(ModificationResult.class));
        when(restore.applyRestore(any(), any(), any(), any())).thenReturn(mock(ModificationResult.class));
        var actions = new BridgeActions(registry);
        var oldNbt = "{id:\"minecraft:chest\",Items:[],CustomName:'old chest'}";
        var newNbt = "{id:\"minecraft:chest\",Items:[],CustomName:'new chest'}";
        var chest = data("chest");
        var rules = ModificationRuleset.builder().applyPhysics(false).blockBlacklist(List.of("bedrock"))
                .entityBlacklist(List.of("wither")).maxPerTask(7).taskDelay(3).removeBlocks(List.of("fire"))
                .removeDrops(true).moveEntities(true).drainLava(true).build();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBlockData("minecraft:chest")).thenReturn(chest);
            var action = actions.create(change(new BlockSnapshot("minecraft:chest", false, oldNbt),
                    new BlockSnapshot("minecraft:chest", false, newNbt)));
            var activity = PaperActivity.builder().action(action).build();
            actions.applyRollback(rules, this, activity, ModificationQueueMode.COMPLETING);
            actions.applyRestore(rules, this, activity, ModificationQueueMode.COMPLETING);
            var oldData = org.mockito.ArgumentCaptor.forClass(ActionData.class);
            var newData = org.mockito.ArgumentCaptor.forClass(ActionData.class);
            verify(nativeBreak).createAction(oldData.capture());
            verify(nativePlace).createAction(newData.capture());
            assertTrue(oldData.getValue().customData().contains("old chest"));
            assertTrue(newData.getValue().customData().contains("new chest"));
            assertFalse(oldData.getValue().customData().contains("bridge-format"), "Envelope must never be applied to a live tile");
            var usedRules = org.mockito.ArgumentCaptor.forClass(ModificationRuleset.class);
            verify(rollback).applyRollback(usedRules.capture(), eq(this), eq(activity), eq(ModificationQueueMode.COMPLETING));
            assertTrue(usedRules.getValue().overwrite(), "NBT must bypass Prism's block-properties-only ALREADY_SET shortcut");
            assertSame(rules.blockBlacklist(), usedRules.getValue().blockBlacklist());
            assertSame(rules.entityBlacklist(), usedRules.getValue().entityBlacklist());
            assertSame(rules.removeBlocks(), usedRules.getValue().removeBlocks());
            assertEquals(7, usedRules.getValue().maxPerTask());
            assertEquals(3, usedRules.getValue().taskDelay());
            assertFalse(usedRules.getValue().applyPhysics());
            assertTrue(usedRules.getValue().moveEntities());
            assertTrue(usedRules.getValue().removeDrops());
            assertTrue(usedRules.getValue().drainLava());
            verify(restore).applyRestore(argThat(ModificationRuleset::overwrite), eq(this), eq(activity), eq(ModificationQueueMode.COMPLETING));
        }
    }

    @Test
    void prismaRejectionIsVisibleInCounters() throws Exception {
        setupRegistry();
        var api = mock(PrismPaperApi.class);
        var recording = mock(RecordingService.class);
        when(api.actionTypeRegistry()).thenReturn(registry);
        when(api.recordingService()).thenReturn(recording);
        var recorder = new PrismRecorder(api);
        var air = data("air");
        var stone = data("stone");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBlockData("minecraft:air")).thenReturn(air);
            bukkit.when(() -> Bukkit.createBlockData("minecraft:stone")).thenReturn(stone);
            recorder.record(change(new BlockSnapshot("minecraft:air", true, null),
                    new BlockSnapshot("minecraft:stone", false, null)));
        }
        assertEquals(0, recorder.accepted());
        assertEquals(1, recorder.notAccepted());
    }

    @Test
    void removalIsSearchableByRemovedMaterialRatherThanAir() throws Exception {
        setupRegistry();
        var actions = new BridgeActions(registry);
        var chest = data("chest");
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBlockData("minecraft:chest")).thenReturn(chest);
            actions.create(change(new BlockSnapshot("minecraft:chest", false, null),
                    new BlockSnapshot("minecraft:air", true, null)));
        }
        assertEquals("chest", changeData.getFirst().blockName());
        assertTrue(changeData.getFirst().customData().contains("minecraft:air"));
    }

    private BlockChange change(BlockSnapshot before, BlockSnapshot after) {
        return new BlockChange(player, -8, 64, 4, 12345, before, after);
    }

    @Test
    void exactTileReplacementRemovesKeysAbsentInTargetAndKeepsWorldCoordinates() {
        var current = mock(ReadWriteNBT.class);
        var target = mock(ReadWriteNBT.class);
        when(current.getKeys()).thenReturn(Set.of("CustomName", "Items", "id"));
        BridgeActions.replaceTileData(current, target, -5, 64, 31);
        verify(current).removeKey("CustomName");
        verify(current).removeKey("Items");
        verify(current).removeKey("id");
        verify(current).mergeCompound(target);
        verify(current).setInteger("x", -5);
        verify(current).setInteger("y", 64);
        verify(current).setInteger("z", 31);
    }

    @Test
    void bedHeadReversalIsExplicitlySkippedBeforeNativePartnerSynthesis() throws Exception {
        setupRegistry();
        var actions = new BridgeActions(registry);
        var head = mock(org.bukkit.block.data.type.Bed.class);
        when(head.getPart()).thenReturn(org.bukkit.block.data.type.Bed.Part.HEAD);
        var serialized = "{\"bridge-format\":1,before:{state:\"minecraft:red_bed[part=head]\",air:0b},after:{state:\"minecraft:air\",air:1b}}";
        var action = mock(Action.class, withSettings().extraInterfaces(CustomData.class));
        when(((CustomData) action).serializeCustomData()).thenReturn(serialized);
        var activity = PaperActivity.builder().action(action).build();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.createBlockData("minecraft:red_bed[part=head]")).thenReturn(head);
            var result = actions.applyRollback(mock(ModificationRuleset.class), this, activity, ModificationQueueMode.COMPLETING);
            assertEquals(org.prism_mc.prism.api.services.modifications.ModificationResultStatus.SKIPPED, result.status());
            verify(nativeBreak, never()).createAction(any());
        }
    }

    private static BlockData data(String name) {
        var material = mock(Material.class);
        when(material.getKey()).thenReturn(new NamespacedKey("minecraft", name));
        when(material.getBlockTranslationKey()).thenReturn("block.minecraft." + name);
        var data = mock(BlockData.class);
        when(data.getMaterial()).thenReturn(material);
        when(data.getAsString()).thenReturn("minecraft:" + name);
        return data;
    }
}
