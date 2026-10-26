package io.github.rudicilibrasi.pianotrainer

/**
 * Creates the app's long-lived objects (repositories, audio services, …) once at start-up and
 * hands them to screens. Kept as plain constructor wiring; it can move to a DI library such as
 * Koin if it grows unwieldy.
 */
class AppContainer
