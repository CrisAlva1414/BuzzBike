package com.buzzbike.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class — Hilt root.
 *
 * BuzzBike es local-first (AGENTS.md §7): no hay backend propio, ni cuentas,
 * ni analytics. Este objeto solo inicializa el grafo de DI y el ciclo de vida
 * de la app.
 */
@HiltAndroidApp
class BuzzBikeApplication : Application()
