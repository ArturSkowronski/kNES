package knes.agent.perception

class ScreenshotPolicy {
    fun shouldAttach(previous: FfPhase?, current: FfPhase): Boolean {
        if (previous == null) return true
        if (previous::class != current::class) return true
        // V4 hybrid C: when stuck inside an interior, the advisor needs the
        // current frame to give cardinal-direction guidance — the decoder
        // baseline is unreliable on towns (~13%) and visual context is the
        // only signal that resolves the ambiguity.
        return current is FfPhase.Indoors
    }
}
