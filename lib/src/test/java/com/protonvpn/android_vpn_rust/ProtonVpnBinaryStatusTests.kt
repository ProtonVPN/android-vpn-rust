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
import uniffi.proton_vpn_binary_status.Logical
import uniffi.proton_vpn_binary_status.StatusReference
import uniffi.proton_vpn_binary_status.computeLoadsUniffi
import java.util.Base64


class ProtonVpnBinaryStatusTests {

    private val dummyLocation = Location(0f, 0f)
    private val statusFile = Base64.getUrlDecoder().decode("AQAAAANLAADAPw==")
    private val server = Logical(
        statusReference = StatusReference(index = 0u, penalty = 0.5, cost = 0u),
        exitLocation = dummyLocation,
        exitCountry = "CH",
        features = 4u,
        physicalServers = listOf(
            PhysicalServer(lat = 0f, long = 0f)
        )
    )

    @Test
    fun `compute_loads returns results`() {
        val newLoads = computeLoadsUniffi(listOf(server), statusFile, dummyLocation, "DE")
        assertEquals(listOf(Load(isEnabled = true, isVisible = true, load = 75u, score = 2.5)), newLoads)
    }

    @Test(expected = Exception.ParserException::class)
    fun `compute_loads throws exception for invalid input`() {
        val invalidStatusFile = byteArrayOf(1, 0) // Too short.
        computeLoadsUniffi(listOf(server), invalidStatusFile, dummyLocation, "DE")
    }

    @Test
    fun `compute_loads returns hidden state for unknown servers`() {
        val unknownServer = server.copy(server.statusReference.copy(index = 100u))
        val newLoads = computeLoadsUniffi(listOf(unknownServer), statusFile, dummyLocation, "DE")
        // Check only isVisible and isEnabled, other values are the result of the scoring algorithm.
        assertEquals(listOf(false), newLoads.map { it.isVisible })
        assertEquals(listOf(false), newLoads.map { it.isEnabled })
    }
}
