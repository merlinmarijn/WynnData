package com.bombbellannouncer.subscription;

import com.bombbellannouncer.bomb.BombType;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public record ComboSubscription(List<BombType> bombTypes) implements SubscriptionTarget {
	public ComboSubscription {
		Objects.requireNonNull(bombTypes, "bombTypes");
		List<BombType> normalized = bombTypes.stream()
			.filter(Objects::nonNull)
			.distinct()
			.sorted(Comparator.comparingInt(Enum::ordinal))
			.toList();
		if (normalized.size() < 2) {
			throw new IllegalArgumentException("A combo needs at least 2 unique bomb types.");
		}
		bombTypes = List.copyOf(normalized);
	}

	@Override
	public String key() {
		return "combo:" + encoded();
	}

	@Override
	public String displayName() {
		return bombTypes.stream().map(BombType::displayName).reduce((left, right) -> left + " + " + right).orElse("");
	}

	public String encoded() {
		return bombTypes.stream().map(BombType::name).reduce((left, right) -> left + "," + right).orElse("");
	}
}
