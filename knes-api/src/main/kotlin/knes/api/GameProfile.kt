package knes.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import knes.debug.GameProfile as DebugProfile

/**
 * Serializable wrapper for REST API responses.
 * Delegates to knes.debug.GameProfile for the actual profile registry.
 */
@Serializable
data class ApiAddressEntry(
    val address: String,
    val description: String = ""
)

@Serializable
data class ApiGameProfile(
    val name: String,
    val id: String,
    val description: String = "",
    val addresses: Map<String, ApiAddressEntry>
) {
    fun toDebugProfile(): DebugProfile {
        return DebugProfile(
            name = name,
            id = id,
            description = description,
            addresses = addresses.mapValues { (_, entry) ->
                knes.debug.AddressEntry(
                    entry.address.removePrefix("0x").removePrefix("0X").toInt(16),
                    entry.description
                )
            }
        )
    }

    companion object {
        fun fromDebugProfile(p: DebugProfile): ApiGameProfile {
            return ApiGameProfile(
                name = p.name,
                id = p.id,
                description = p.description,
                addresses = p.addresses.mapValues { (_, entry) ->
                    ApiAddressEntry("0x${entry.address.toString(16).padStart(4, '0')}", entry.description)
                }
            )
        }
    }
}
