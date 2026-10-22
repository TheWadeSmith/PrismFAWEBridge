package com.thewadesmith.prismfawe;

import java.util.UUID;

record ChangeOrigin(UUID worldId, String worldName, UUID playerId, String actorName) {}
