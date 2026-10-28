package dev.undo.core.connector;

import java.util.List;
import java.util.Optional;

public record EffectPage(List<ProviderEffect> effects, Optional<String> nextCursor) {}
