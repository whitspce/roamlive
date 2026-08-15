package dev.whitespc.roam.obs

/**
 * Remembers that OBS was confirmed on its BRB scene. A connection loss cannot
 * prove that viewers have left BRB, so protection remains active until a live
 * connection confirms a different program scene.
 */
internal class ObsBrbPrivacyLatch {
    var active: Boolean = false
        private set

    fun update(
        protectionEnabled: Boolean,
        brbScene: String,
        connected: Boolean,
        currentScene: String?,
    ): Boolean {
        if (!protectionEnabled || brbScene.isBlank()) {
            active = false
            return active
        }
        if (connected && currentScene != null) {
            active = currentScene == brbScene
        }
        return active
    }
}
