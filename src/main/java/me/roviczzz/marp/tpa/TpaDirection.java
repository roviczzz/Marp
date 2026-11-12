package me.roviczzz.marp.tpa;

/** Direction of a player-to-player teleport request. */
public enum TpaDirection {
    /** Requester moves to target ({@code /tpa}). */
    TPA,
    /** Target moves to requester ({@code /tpahere}). */
    TPA_HERE
}
