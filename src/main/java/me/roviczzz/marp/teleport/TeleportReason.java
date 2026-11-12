package me.roviczzz.marp.teleport;

/** The teleport actions, each with its own warmup and cooldown settings. */
public enum TeleportReason {
    HOME,
    WARP,
    BACK,
    TPA;

    public String configKey() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
