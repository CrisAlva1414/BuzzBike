package com.buzzbike.app.domain.wear

/**
 * Maximum length of a bike name.
 *
 * Shared by the garage form and [com.buzzbike.app.data.repository.RoomBikeRepository]
 * so the repository enforces the same bound even if a caller bypasses the UI.
 */
const val MAX_GARAGE_NAME_LENGTH: Int = 40

/** Maximum length of a component label, kept equal to [MAX_GARAGE_NAME_LENGTH]. */
const val MAX_GARAGE_COMPONENT_LABEL_LENGTH: Int = MAX_GARAGE_NAME_LENGTH

/** Maximum length of a garage note, enforced by both the form and the repository. */
const val MAX_GARAGE_NOTES_LENGTH: Int = 500
