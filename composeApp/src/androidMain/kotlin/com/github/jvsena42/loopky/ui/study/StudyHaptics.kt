package com.github.jvsena42.loopky.ui.study

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.github.jvsena42.loopky.presentation.study.StudyHaptic

/**
 * The shared vocabulary in Android's own constants, which the framework then renders on whatever
 * actuator the device has — and not at all when the user has turned haptics off.
 *
 * Android offers one "that did not work" pattern, so [StudyHaptic.Warning] and
 * [StudyHaptic.Failure] both land on `Reject`; the distinction between them is iOS's to make.
 */
internal fun StudyHaptic.feedbackType(): HapticFeedbackType = when (this) {
    StudyHaptic.Tick -> HapticFeedbackType.ContextClick
    StudyHaptic.Success -> HapticFeedbackType.Confirm
    StudyHaptic.Warning, StudyHaptic.Failure -> HapticFeedbackType.Reject
}
