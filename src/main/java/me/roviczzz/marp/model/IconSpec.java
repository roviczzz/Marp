package me.roviczzz.marp.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * A menu icon: a durable material fallback plus optional serialized item bytes.
 *
 * <p>The material is always present and is what renders when the bytes are
 * missing, too large, corrupt, or from an incompatible server version.
 */
public record IconSpec(String material, byte[] itemBytes) {

    public IconSpec {
        if (material == null || material.isBlank()) {
            throw new IllegalArgumentException("material is required");
        }
        itemBytes = itemBytes == null ? null : itemBytes.clone();
    }

    public static IconSpec material(String material) {
        return new IconSpec(material, null);
    }

    @Override
    public byte[] itemBytes() {
        return itemBytes == null ? null : itemBytes.clone();
    }

    public boolean hasItemBytes() {
        return itemBytes != null && itemBytes.length > 0;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IconSpec that)) {
            return false;
        }
        return material.equals(that.material) && Arrays.equals(itemBytes, that.itemBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(material) + Arrays.hashCode(itemBytes);
    }

    @Override
    public String toString() {
        return "IconSpec[material=" + material + ", itemBytes=" + (itemBytes == null ? 0 : itemBytes.length) + "]";
    }
}
