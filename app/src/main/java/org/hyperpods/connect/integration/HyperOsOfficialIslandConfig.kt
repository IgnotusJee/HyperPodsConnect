package org.hyperpods.connect.integration

import org.hyperpods.connect.config.ConfigManager

/** ROM-only identity required by HyperOS' built-in island type gate. */
object HyperOsOfficialIslandConfig {
    val presentationTypeId: String
        get() = ConfigManager.hyperOsPresentationTypeId()

    val supportDescriptor: String
        get() = "$presentationTypeId,000000000000000010000000"
}
