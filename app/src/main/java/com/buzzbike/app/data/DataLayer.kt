@file:Suppress("unused")

package com.buzzbike.app.data

/**
 * Package marker — slice 2+ will add Room entities/DAOs, repositories,
 * and mappers here.
 *
 * Dependency rule (AGENTS.md §3): `data` is the ONLY layer that may know
 * about Room, JSON, or network. UI and domain must not import anything
 * from this package's infrastructure types.
 */
internal object DataLayer
