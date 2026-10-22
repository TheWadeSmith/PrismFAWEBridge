package com.thewadesmith.prismfawe;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.sk89q.worldedit.world.block.BlockState;
import de.tr7zw.nbtapi.NBT;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.GZIPOutputStream;
import org.enginehub.linbus.stream.LinBinaryIO;
import org.enginehub.linbus.tree.LinRootEntry;

record BlockSnapshot(String blockData, boolean air, String nbt) {
    static BlockSnapshot capture(BlockState state, FaweCompoundTag tile) {
        return new BlockSnapshot(state.getAsString(), state.getMaterial().isAir(),
                tile == null ? null : nativeSnbt(tile));
    }

    private static String nativeSnbt(FaweCompoundTag tile) {
        // LinStringIO leaves empty and numeric-looking strings unquoted. Transfer
        // detached tags losslessly through binary NBT, then use Minecraft's writer.
        // No world or Bukkit state is accessed on FAWE's worker thread.
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(new GZIPOutputStream(bytes))) {
                LinBinaryIO.write(output, new LinRootEntry("", tile.linTag()));
            }
            return NBT.readNBT(new ByteArrayInputStream(bytes.toByteArray())).toString();
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not serialize FAWE tile snapshot", failure);
        }
    }
}
