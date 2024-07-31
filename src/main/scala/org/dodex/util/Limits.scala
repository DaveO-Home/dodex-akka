package org.dodex.util

import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config

object Limits {
    val conf: Config = ConfigFactory.load()
    val connectLimit: Int = 10
    val hourLimit: Int = 360
    val dayLimit: Int = 288
    var interval: Int = 10000
}
