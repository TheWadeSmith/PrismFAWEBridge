package com.thewadesmith.prismfawe;

record BlockChange(ChangeOrigin origin, int x, int y, int z, long timestamp,
                   BlockSnapshot before, BlockSnapshot after) {}
