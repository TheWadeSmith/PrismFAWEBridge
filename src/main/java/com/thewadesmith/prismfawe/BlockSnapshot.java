package com.thewadesmith.prismfawe;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.sk89q.worldedit.world.block.BlockState;
import org.enginehub.linbus.format.snbt.LinStringIO;

record BlockSnapshot(String blockData, boolean air, String nbt) {
    static BlockSnapshot capture(BlockState state, FaweCompoundTag tile) {
        return new BlockSnapshot(state.getAsString(), state.getMaterial().isAir(),
                tile == null ? null : LinStringIO.writeToString(tile.linTag()));
    }
}
