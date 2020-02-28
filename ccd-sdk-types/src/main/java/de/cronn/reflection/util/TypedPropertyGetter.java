package de.cronn.reflection.util;

// Copied in here to avoid adding a compile time dependency.
@FunctionalInterface
public interface TypedPropertyGetter<T, V> {
  V get(T bean);
}

