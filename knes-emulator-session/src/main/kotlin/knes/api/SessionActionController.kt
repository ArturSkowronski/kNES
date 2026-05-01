package knes.api

import knes.debug.ActionController

class SessionActionController(
    private val session: EmulatorSession
) : ActionController {

    override fun readState(): Map<String, Int> {
        return session.getWatchedState()
    }

    override fun tap(button: String, count: Int, pressFrames: Int, gapFrames: Int) {
        val steps = (1..count).flatMap {
            listOf(
                StepRequest(buttons = listOf(button), frames = pressFrames),
                StepRequest(buttons = emptyList(), frames = gapFrames)
            )
        }
        executeSteps(steps)
    }

    override fun step(buttons: List<String>, frames: Int) {
        executeSteps(listOf(StepRequest(buttons = buttons, frames = frames)))
    }

    override fun waitFrames(frames: Int) {
        executeSteps(listOf(StepRequest(buttons = emptyList(), frames = frames)))
    }

    override fun screenshot(): String? {
        return try {
            session.getScreenBase64()
        } catch (_: Exception) {
            null
        }
    }

    private fun executeSteps(steps: List<StepRequest>) {
        if (session.shared) {
            val latch = session.controller.enqueueSteps(steps)
            latch.await()
        } else {
            for (step in steps) {
                session.controller.setButtons(step.buttons)
                session.advanceFrames(step.frames)
            }
            session.controller.releaseAll()
        }
    }
}
