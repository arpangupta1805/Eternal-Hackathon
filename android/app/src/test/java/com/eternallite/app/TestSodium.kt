package com.eternallite.app

import com.eternallite.app.crypto.LazySodiumOps
import com.eternallite.app.crypto.Sodium
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

/** Desktop libsodium for JVM tests. Same LazySodiumOps code the app runs on the phone. */
val testSodium: Sodium by lazy { LazySodiumOps(LazySodiumJava(SodiumJava())) }
