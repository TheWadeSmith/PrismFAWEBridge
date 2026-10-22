package com.thewadesmith.prismfawe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import de.tr7zw.nbtapi.NBT;
import de.tr7zw.nbtapi.iface.ReadWriteNBT;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import org.enginehub.linbus.stream.LinBinaryIO;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinRootEntry;
import org.enginehub.linbus.tree.LinStringTag;
import org.enginehub.linbus.tree.LinTagType;
import org.junit.jupiter.api.Test;

class BlockSnapshotTest {
    @Test
    void tileStringsReachTheNativeWriterWithoutLossOrTypeCoercion() throws Exception {
        var lines = LinListTag.builder(LinTagType.stringTag()).add(LinStringTag.of("Before"))
                .add(LinStringTag.of("")).add(LinStringTag.of("123"))
                .add(LinStringTag.of("true")).build();
        var tag = LinCompoundTag.builder().put("front_text", LinCompoundTag.builder().put("messages", lines).build()).build();
        var state = mock(BlockState.class);
        var material = mock(BlockMaterial.class);
        when(state.getAsString()).thenReturn("minecraft:oak_sign");
        when(state.getMaterial()).thenReturn(material);
        var nativeNbt = mock(ReadWriteNBT.class);
        var expected = "{front_text:{messages:[\"Before\",\"\",\"123\",\"true\"]}}";
        when(nativeNbt.toString()).thenReturn(expected);
        try (var nbt = mockStatic(NBT.class)) {
            nbt.when(() -> NBT.readNBT(any(InputStream.class))).thenAnswer(call -> {
                try (var input = new DataInputStream(new GZIPInputStream(call.getArgument(0, InputStream.class)))) {
                    assertEquals(new LinRootEntry("", tag), LinBinaryIO.readUsing(input, LinRootEntry::readFrom));
                }
                return nativeNbt;
            });
            assertEquals(expected, BlockSnapshot.capture(state, FaweCompoundTag.of(tag)).nbt());
            nbt.verify(() -> NBT.readNBT(any(InputStream.class)));
        }
    }
}
