package org.dodex.util

import com.typesafe.config.ConfigFactory

object Limits {
    val conf = ConfigFactory.load()
    val connectLimit: Int = 10
    val hourLimit: Int = 360
    val dayLimit: Int = 288
    val interval: Int = 10000
}
