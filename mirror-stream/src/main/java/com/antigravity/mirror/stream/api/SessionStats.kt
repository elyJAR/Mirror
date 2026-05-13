package com.antigravity.mirror.stream.api

/**
 * Real-time performance metrics for an active mirror session.
 * 
 * Requirements: tasks.md §6.4
 */
data class SessionStats(
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val queueDepth: Int = 0,
    val droppedFrames: Int = 0
)
