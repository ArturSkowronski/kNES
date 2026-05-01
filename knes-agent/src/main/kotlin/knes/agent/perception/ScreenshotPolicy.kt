package knes.agent.perception

class ScreenshotPolicy {
    fun shouldAttach(previous: FfPhase?, current: FfPhase): Boolean {
        if (previous == null) return true
        return previous::class != current::class
    }
}
