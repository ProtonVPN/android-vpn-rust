/*
 * Copyright (c) 2025. Proton AG
 *
 * This file is part of ProtonVPN.
 *
 * ProtonVPN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonVPN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonVPN.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.protonvpn.android_vpn_rust

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.proton_vpn_binary_status.Exception
import uniffi.proton_vpn_binary_status.Load
import uniffi.proton_vpn_binary_status.Location
import uniffi.proton_vpn_binary_status.PhysicalServer
import uniffi.proton_vpn_binary_status.Server
import uniffi.proton_vpn_binary_status.Status
import uniffi.proton_vpn_binary_status.UserLocation
import uniffi.proton_vpn_binary_status.computeLoadsUniffi
import java.util.Base64


class ProtonVpnBinaryStatusTests {

    private val statusFile = Base64.getUrlDecoder().decode("AQAAAANLAADAPw==")
    private val server = Server(
        status = Status(index = 0u, penalty = 0.5f, cost = 0u),
        exitLocation = Location(lat = 0.0f, long = 0.0f),
        exitCountry = "CH",
        physicalServers = listOf(
            PhysicalServer()
        )
    )
    private val userLocation = UserLocation(Location(lat = 0.0f, long = 0.0f), country = "DE")

    @Test
    fun `compute_loads returns results`() {
        val newLoads = computeLoadsUniffi(userLocation, listOf(server), statusFile)
        assertEquals(listOf(Load("", status = 3u, load = 75u, score = 2.5f)), newLoads)
    }

    @Test(expected = Exception.ServerIndexOutOfRange::class)
    fun `compute_loads throws exception for invalid input`() {
        val invalidServer = server.copy(server.status.copy(index = 100u))
        val newLoads = computeLoadsUniffi(userLocation, listOf(invalidServer), statusFile)
        assertEquals(listOf(Load("", status = 3u, load = 75u, score = 2.5f)), newLoads)
    }
}
