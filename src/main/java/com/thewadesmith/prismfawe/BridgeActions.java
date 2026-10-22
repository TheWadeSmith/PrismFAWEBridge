package com.thewadesmith.prismfawe;

import de.tr7zw.nbtapi.NBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import java.util.HashSet;
import org.enginehub.linbus.format.snbt.LinStringIO;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinTagType;
import org.bukkit.Bukkit;
import org.bukkit.block.TileState;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.prism_mc.prism.api.actions.Action;
import org.prism_mc.prism.api.actions.ActionData;
import org.prism_mc.prism.api.actions.CustomData;
import org.prism_mc.prism.api.actions.types.ActionResultType;
import org.prism_mc.prism.api.actions.types.ActionType;
import org.prism_mc.prism.api.actions.types.ActionTypeRegistry;
import org.prism_mc.prism.api.activities.Activity;
import org.prism_mc.prism.api.services.modifications.ModificationHandler;
import org.prism_mc.prism.api.services.modifications.ModificationQueueMode;
import org.prism_mc.prism.api.services.modifications.ModificationResult;
import org.prism_mc.prism.api.services.modifications.ModificationRuleset;
import org.prism_mc.prism.api.services.modifications.ModificationSkipReason;
import org.prism_mc.prism.api.services.modifications.ModificationResultStatus;

/** One reversible row per edit, with both snapshots in Prism's persisted custom data. */
final class BridgeActions implements ModificationHandler {
    private final ActionType changeType;
    private final ActionType nativeBreak;
    private final ActionType nativePlace;

    BridgeActions(ActionTypeRegistry registry) {
        nativeBreak = registry.actionType("block-break").orElseThrow();
        nativePlace = registry.actionType("block-place").orElseThrow();
        changeType = registry.actionType("fawe-change").orElseGet(() -> registry.registerBlockAction(
                "fawe-change", ActionResultType.CREATES, true, this, "changed with FAWE"));
        changeType.modificationHandler(this);
    }

    Action create(BlockChange change) {
        var envelope = LinCompoundTag.builder().putInt("bridge-format", 1)
                .put("before", write(change.before())).put("after", write(change.after())).build();
        var affected = change.after().air() ? change.before() : change.after();
        return build(changeType, data(affected, change.before(), LinStringIO.writeToString(envelope)));
    }

    private static LinCompoundTag write(BlockSnapshot snapshot) {
        var target = LinCompoundTag.builder().putString("state", snapshot.blockData())
                .putByte("air", (byte) (snapshot.air() ? 1 : 0));
        if (snapshot.nbt() != null) {
            target.putString("tile", snapshot.nbt());
        }
        return target.build();
    }

    private static BlockSnapshot read(Activity activity, String side) {
        if (!(activity.action() instanceof CustomData customData) || customData.serializeCustomData() == null) {
            throw new IllegalStateException("FAWE change has no snapshot data.");
        }
        var envelope = parse(customData.serializeCustomData());
        var snapshot = envelope.findTag(side, LinTagType.compoundTag());
        if (envelope.getTag("bridge-format", LinTagType.intTag()).value() != 1 || snapshot == null) {
            throw new IllegalStateException("Unsupported or incomplete FAWE snapshot format.");
        }
        var tile = snapshot.findTag("tile", LinTagType.stringTag());
        return new BlockSnapshot(snapshot.getTag("state", LinTagType.stringTag()).value(),
                snapshot.getTag("air", LinTagType.byteTag()).value() != 0,
                tile == null ? null : tile.value());
    }

    private static LinCompoundTag parse(String snbt) {
        return LinStringIO.readFromStringUsing(snbt, LinCompoundTag::readFrom);
    }

    private static Action build(ActionType type, ActionData data) {
        try {
            return type.createAction(data);
        } catch (Exception exception) {
            throw new IllegalStateException("Prism could not deserialize the block snapshot.", exception);
        }
    }

    static ActionData data(BlockSnapshot snapshot, BlockSnapshot replaced, String customData) {
        var blockData = Bukkit.createBlockData(snapshot.blockData());
        var material = blockData.getMaterial();
        var key = material.getKey();
        var oldData = replaced == null ? null : Bukkit.createBlockData(replaced.blockData());
        var oldMaterial = oldData == null ? null : oldData.getMaterial();
        return new ActionData(null, (short) 0, null,
                key.getNamespace(), key.getKey(), properties(blockData.getAsString()),
                oldMaterial == null ? null : oldMaterial.getKey().getNamespace(),
                oldMaterial == null ? null : oldMaterial.getKey().getKey(),
                oldData == null ? null : properties(oldData.getAsString()),
                null, customData, "", null, (short) (customData == null ? 0 : 1),
                material.getBlockTranslationKey(), oldMaterial == null ? null : oldMaterial.getBlockTranslationKey(),
                null, null);
    }

    private static String properties(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? "" : state.substring(bracket);
    }

    @Override
    public ModificationResult applyRollback(ModificationRuleset rules, Object owner, Activity activity, ModificationQueueMode mode) {
        var before = read(activity, "before");
        return modify(before, true, rules, owner, activity, mode);
    }

    @Override
    public ModificationResult applyRestore(ModificationRuleset rules, Object owner, Activity activity, ModificationQueueMode mode) {
        var after = read(activity, "after");
        return modify(after, false, rules, owner, activity, mode);
    }

    private ModificationResult modify(BlockSnapshot target, boolean rollback, ModificationRuleset rules,
                                      Object owner, Activity activity, ModificationQueueMode mode) {
        var targetData = Bukkit.createBlockData(target.blockData());
        if (targetData instanceof Bed || (targetData instanceof Bisected
                && !(targetData instanceof Stairs) && !(targetData instanceof TrapDoor))) {
            // Stock Prism synthesizes partners for event root snapshots. FAWE
            // logs each position, so synthesis could overwrite an unrecorded neighbor.
            return ModificationResult.builder().activity(activity).skipped()
                    .skipReason(ModificationSkipReason.NOT_IMPLEMENTED)
                    .target("FAWE multi-block snapshot: native partner synthesis is unsafe").build();
        }
        var nativeType = rollback ? nativeBreak : nativePlace;
        var action = build(nativeType, data(target, null, target.nbt()));
        var effectiveRules = nbtRules(rules, target);
        var result = rollback ? action.applyRollback(effectiveRules, owner, activity, mode)
                : action.applyRestore(effectiveRules, owner, activity, mode);
        if (target.nbt() != null && mode == ModificationQueueMode.COMPLETING
                && (result.status() == ModificationResultStatus.APPLIED || result.status() == ModificationResultStatus.PARTIAL)) {
            var world = Bukkit.getWorld(activity.worldUuid());
            var position = activity.coordinate();
            var block = world.getBlockAt(position.intX(), position.intY(), position.intZ());
            // A blacklist may have made the native action write air instead.
            if (block.getType() == targetData.getMaterial() && block.getState() instanceof TileState state) {
                var tile = NBT.parseNBT(target.nbt());
                NBT.modify(state, current -> { replaceTileData(current, tile, block.getX(), block.getY(), block.getZ()); });
            }
        }
        return result;
    }

    static void replaceTileData(ReadWriteNBT current, ReadWriteNBT target, int x, int y, int z) {
        // Native Prism recursively merges NBT. Clear every top-level key first so
        // absent nested fields/components are removed too, then apply the full snapshot.
        for (String key : new HashSet<>(current.getKeys())) {
            current.removeKey(key);
        }
        current.mergeCompound(target);
        current.setInteger("x", x);
        current.setInteger("y", y);
        current.setInteger("z", z);
    }

    static ModificationRuleset nbtRules(ModificationRuleset rules, BlockSnapshot target) {
        if (target.nbt() == null || rules.overwrite()) {
            return rules;
        }
        // Prism's "already set" shortcut compares block properties only. A tile
        // snapshot must also apply its contents/text even when those properties match.
        return ModificationRuleset.builder().applyPhysics(rules.applyPhysics())
                .blockBlacklist(rules.blockBlacklist()).drainLava(rules.drainLava())
                .entityBlacklist(rules.entityBlacklist()).maxPerTask(rules.maxPerTask())
                .moveEntities(rules.moveEntities()).removeBlocks(rules.removeBlocks())
                .removeDrops(rules.removeDrops()).overwrite(true).taskDelay(rules.taskDelay()).build();
    }
}
